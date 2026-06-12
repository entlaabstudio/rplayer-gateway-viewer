package com.technotramp.unexpectedtracks;

import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small localhost HTTP proxy used as a controlled bridge between WebView and ipfs.io.
 *
 * <p>The proxy serves only IPFS paths, forwards requests to the configured gateway,
 * and rewrites selected response headers so Android WebView renders RPlayer assets
 * instead of treating them as forced downloads.</p>
 */
final class GatewayProxyServer implements Closeable {
    private static final String LOG_TAG = "RPlayerProxy";
    private static final String GATEWAY_ORIGIN = "https://ipfs.io";
    private static final String IPFS_CID = BuildConfig.IPFS_CID;
    private static final String ENTRY_FILE = "index.htm";
    private static final String IPFS_ROOT_PATH = "/ipfs/" + IPFS_CID + "/";
    private static final String ROOT_PATH = IPFS_ROOT_PATH + ENTRY_FILE;
    private static final String RPLAYER_SCRIPT_PATH = IPFS_ROOT_PATH + "src/js/rplayer.js";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long SLOW_REQUEST_LOG_THRESHOLD_MS = 5000;
    private static final long CACHE_REPAIR_RETRY_DELAY_MS = 5000;
    private static final int CACHE_REPAIR_MAX_ATTEMPTS = 6;
    private static final String CACHE_CONTROL_VALUE = "public, max-age=31536000, immutable";

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Set<String> cacheRepairPaths = ConcurrentHashMap.newKeySet();
    private final String htmlBridgeScript;
    private final File cacheDirectory;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    /**
     * Creates a proxy with a script injected into HTML responses before RPlayer runs.
     *
     * @param htmlBridgeScript JavaScript bridge source inserted into proxied HTML documents
     * @param cacheDirectory persistent directory for complete immutable proxy responses
     */
    GatewayProxyServer(String htmlBridgeScript, File cacheDirectory) {
        this.htmlBridgeScript = htmlBridgeScript == null ? "" : htmlBridgeScript;
        this.cacheDirectory = cacheDirectory;
    }

    /**
     * Starts the proxy on a random localhost port.
     *
     * @return the selected local TCP port
     * @throws IOException when the local server socket cannot be opened
     */
    int start() throws IOException {
        ensureCacheDirectory();
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "RPlayerProxyAccept");
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    /**
     * Builds the local URL loaded by the WebView for the fixed album entry point.
     *
     * @return local proxy URL for the configured RPlayer index file
     */
    String viewerUrl() {
        return localOrigin() + ROOT_PATH;
    }

    /**
     * Builds the local album root URL accepted by native loaders.
     *
     * @return local proxy URL prefix for the configured album root
     */
    String albumRootUrl() {
        return localOrigin() + IPFS_ROOT_PATH;
    }

    /**
     * Builds the local origin selected for this proxy instance.
     *
     * @return local HTTP origin with the selected random port
     */
    private String localOrigin() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /**
     * Stops accepting new proxy connections and shuts down worker threads.
     */
    @Override
    public void close() {
        running = false;
        closeQuietly(serverSocket);
        executorService.shutdownNow();
    }

    /**
     * Accepts local WebView connections and dispatches each socket to a worker thread.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executorService.execute(() -> handleSocket(socket));
            } catch (IOException exception) {
                if (running) {
                    Log.w(LOG_TAG, "Proxy could not accept a connection.", exception);
                }
            }
        }
    }

    /**
     * Handles one raw HTTP request from WebView and routes it through the proxy policy.
     *
     * @param socket accepted localhost socket from Android WebView
     */
    private void handleSocket(Socket socket) {
        try {
            HttpRequest request = readRequest(socket);

            if (request == null) {
                return;
            }

            Log.i(LOG_TAG, "Local request: " + request.method + " " + request.path + rangeLogSuffix(request));

            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                writePlainResponse(socket, 405, "Method Not Allowed", "The method is not supported.");
                return;
            }

            if (isCacheStateRequest(request)) {
                writeCacheStateResponse(socket, request);
                return;
            }

            if (!isSafeAlbumPath(request.path)) {
                writePlainResponse(socket, 404, "Not Found", "The proxy only serves the RPlayer IPFS path.");
                return;
            }

            if (serveFromCacheIfPossible(socket, request)) {
                return;
            }

            proxyRequest(socket, request);
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Proxy request failed.", exception);
            try {
                writePlainResponse(socket, 502, "Bad Gateway", "The proxy could not load content from ipfs.io.");
            } catch (IOException responseException) {
                Log.w(LOG_TAG, "The proxy could not send an error response.", responseException);
            }
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Parses the minimal HTTP request data needed by this proxy.
     *
     * @param socket source socket with the WebView request
     * @return parsed request, or null when the request line is empty or invalid
     * @throws IOException when the socket stream cannot be read
     */
    private HttpRequest readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();

        if (requestLine == null || requestLine.trim().isEmpty()) {
            return null;
        }

        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                headers.put(name, value);
            }
        }

        return new HttpRequest(requestParts[0], requestParts[1], headers);
    }

    /**
     * Forwards an allowed request to ipfs.io and streams the gateway response back.
     *
     * @param socket target socket connected to WebView
     * @param request parsed request to forward
     * @throws IOException when the gateway request or local response fails
     */
    private void proxyRequest(Socket socket, HttpRequest request) throws IOException {
        URL gatewayUrl = new URL(GATEWAY_ORIGIN + request.path);
        HttpURLConnection connection = (HttpURLConnection) gatewayUrl.openConnection();
        long requestStartedAt = System.currentTimeMillis();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(request.method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "RPlayer Gateway Viewer/" + BuildConfig.VERSION_NAME);

        String range = request.headers.get("range");
        if (range != null && !range.isEmpty()) {
            connection.setRequestProperty("Range", range);
        }

        Log.i(LOG_TAG, "Gateway request started: " + request.method + " " + gatewayUrl + rangeLogSuffix(request));

        int statusCode = connection.getResponseCode();
        String statusText = connection.getResponseMessage();
        InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

        if (responseStream == null) {
            responseStream = new ByteArrayInputStream(new byte[0]);
        }

        try {
            long bytesWritten = writeGatewayResponse(socket, request, connection, statusCode, statusText, responseStream);
            logGatewayRequestFinished(request, connection, statusCode, statusText, bytesWritten, requestStartedAt);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Writes the proxied HTTP response and replaces unsafe or unreliable headers.
     *
     * <p>The MIME type is resolved from the requested path first, because IPFS gateways
     * may return headers that make WebView download assets instead of rendering them.</p>
     *
     * @param socket target socket connected to WebView
     * @param request original local request
     * @param connection open gateway connection
     * @param statusCode gateway HTTP status code
     * @param statusText gateway HTTP status message
     * @param responseStream gateway response body stream
     * @throws IOException when the response cannot be written
     */
    private long writeGatewayResponse(
        Socket socket,
        HttpRequest request,
        HttpURLConnection connection,
        int statusCode,
        String statusText,
        InputStream responseStream
    ) throws IOException {
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write(("HTTP/1.1 " + statusCode + " " + safeStatusText(statusText) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));

        String contentType = MimeTypes.fromPath(request.path, connection.getContentType());
        byte[] injectedResponseBody = null;

        if (!"HEAD".equals(request.method) && shouldTransformTextResponse(request, statusCode, contentType)) {
            byte[] originalBody = readStreamBytes(responseStream);
            storeCompleteResponse(request, originalBody, statusCode, contentType, cacheHeadersFrom(connection));
            injectedResponseBody = transformTextResponse(request, originalBody, contentType);
            responseStream = new ByteArrayInputStream(injectedResponseBody);
        }

        writeHeader(outputStream, "Content-Type", contentType);
        writeHeader(outputStream, "Access-Control-Allow-Origin", "*");
        writeHeader(outputStream, "Accept-Ranges", "bytes");
        writeHeader(outputStream, "Cache-Control", CACHE_CONTROL_VALUE);
        writeHeader(outputStream, "Connection", "close");

        String contentLength = injectedResponseBody == null
            ? connection.getHeaderField("Content-Length")
            : String.valueOf(injectedResponseBody.length);
        String contentRange = connection.getHeaderField("Content-Range");
        CacheResponseHeaders cacheHeaders = cacheHeadersFrom(connection);
        String lastModified = cacheHeaders.lastModified;
        String etag = cacheHeaders.etag;

        if (contentLength != null) {
            writeHeader(outputStream, "Content-Length", contentLength);
        }

        if (contentRange != null) {
            writeHeader(outputStream, "Content-Range", contentRange);
        }

        if (lastModified != null) {
            writeHeader(outputStream, "Last-Modified", lastModified);
        }

        if (etag != null) {
            writeHeader(outputStream, "ETag", etag);
        }

        if (!cacheHeaders.ipfsPath.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Path", cacheHeaders.ipfsPath);
        }

        if (!cacheHeaders.ipfsRoots.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Roots", cacheHeaders.ipfsRoots);
        }

        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));

        if (!"HEAD".equals(request.method)) {
            long bytesWritten = copyGatewayBody(request, responseStream, outputStream, statusCode, contentType, cacheHeaders, injectedResponseBody);
            outputStream.flush();
            closeQuietly(responseStream);
            return bytesWritten;
        }

        outputStream.flush();
        closeQuietly(responseStream);
        return 0;
    }

    /**
     * Checks whether a proxied text response needs a local viewer patch before WebView sees it.
     *
     * @param request parsed WebView request
     * @param statusCode gateway or cached response status
     * @param contentType resolved response MIME type
     * @return true when the response should be transformed
     */
    private boolean shouldTransformTextResponse(HttpRequest request, int statusCode, String contentType) {
        return shouldInjectHtmlBridge(request, statusCode, contentType) || shouldPatchRPlayerScript(request, statusCode);
    }

    /**
     * Applies the selected local text response patch.
     *
     * @param request parsed WebView request
     * @param body original UTF-8 response body
     * @param contentType resolved response MIME type
     * @return transformed UTF-8 response body
     */
    private byte[] transformTextResponse(HttpRequest request, byte[] body, String contentType) {
        String text = new String(body, StandardCharsets.UTF_8);

        if (shouldPatchRPlayerScript(request, 200)) {
            return patchRPlayerScript(text).getBytes(StandardCharsets.UTF_8);
        }

        return injectHtmlBridge(text).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Checks whether the main RPlayer module should expose its downloads instance to the wrapper.
     *
     * @param request parsed WebView request
     * @param statusCode gateway or cached response status
     * @return true when rplayer.js should be patched
     */
    private static boolean shouldPatchRPlayerScript(HttpRequest request, int statusCode) {
        return statusCode == 200 && RPLAYER_SCRIPT_PATH.equals(request.path);
    }

    /**
     * Exposes the RPlayer downloads module instance without modifying the immutable IPFS content.
     *
     * @param script original rplayer.js source
     * @return patched script source served only through the local proxy
     */
    private static String patchRPlayerScript(String script) {
        String original = "new RPlayerDownloads(RPObj,QrCode);";
        String patched = "window.RPlayerGatewayViewerDownloads = new RPlayerDownloads(RPObj,QrCode);";

        if (!script.contains(original)) {
            Log.w(LOG_TAG, "RPlayer downloads instance patch target was not found.");
            return script;
        }

        Log.i(LOG_TAG, "RPlayer downloads instance exposed to viewer bridge.");
        return script.replace(original, patched);
    }

    /**
     * Checks whether a local request asks for wrapper cache state JSON.
     *
     * @param request parsed WebView request
     * @return true for the internal cache state endpoint
     */
    private static boolean isCacheStateRequest(HttpRequest request) {
        return request.path.startsWith("/__rplayer_gateway/cache-state?");
    }

    /**
     * Writes current persistent cache state for one safe album path.
     *
     * @param socket target socket connected to WebView
     * @param request parsed WebView request
     * @throws IOException when the response cannot be written
     */
    private void writeCacheStateResponse(Socket socket, HttpRequest request) throws IOException {
        String checkedPath = queryParameter(request.path, "path");
        boolean complete = isCompleteCachedPath(checkedPath);
        byte[] body = ("{\"complete\":" + complete + "}").getBytes(StandardCharsets.UTF_8);

        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.ISO_8859_1));
        writeHeader(outputStream, "Content-Type", "application/json; charset=utf-8");
        writeHeader(outputStream, "Cache-Control", "no-store");
        writeHeader(outputStream, "Connection", "close");
        writeHeader(outputStream, "Content-Length", String.valueOf(body.length));
        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(body);
        outputStream.flush();
    }

    /**
     * Checks whether a safe album path has a complete valid persistent cache entry.
     *
     * @param path local IPFS path to check
     * @return true when the cached file exists and passes local validation
     */
    private boolean isCompleteCachedPath(String path) {
        if (!isSafeAlbumPath(path)) {
            return false;
        }

        CacheEntry cacheEntry = cacheEntryFor(path);
        CacheMetadata metadata = CacheMetadata.load(cacheEntry.metaFile);
        return cacheEntry.dataFile.isFile() && metadata != null && isCacheEntryValid(cacheEntry, metadata);
    }

    /**
     * Reads one URL query parameter from a local endpoint path.
     *
     * @param requestPath endpoint path with query string
     * @param name query parameter name
     * @return decoded parameter value, or an empty string when missing
     */
    private static String queryParameter(String requestPath, String name) {
        int queryStart = requestPath.indexOf('?');
        if (queryStart < 0 || queryStart >= requestPath.length() - 1) {
            return "";
        }

        String prefix = name + "=";
        String[] pairs = requestPath.substring(queryStart + 1).split("&");
        for (String pair : pairs) {
            if (pair.startsWith(prefix)) {
                try {
                    return URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8.name());
                } catch (IllegalArgumentException | java.io.UnsupportedEncodingException exception) {
                    return "";
                }
            }
        }

        return "";
    }

    /**
     * Checks whether a local request path is inside the fixed album root and safe to proxy.
     *
     * Path is expected to be the normalized local request path.
     * Returns true when the path belongs to the configured album and contains no traversal markers.
     */
    private static boolean isSafeAlbumPath(String path) {
        if (path == null || !path.startsWith(IPFS_ROOT_PATH)) {
            return false;
        }

        String lowerPath = path.toLowerCase(Locale.ROOT);
        return !lowerPath.contains("..")
            && !lowerPath.contains("%2e")
            && !lowerPath.contains("%5c")
            && !lowerPath.contains("\\");
    }

    /**
     * Serves a complete cached response when this request can safely skip ipfs.io.
     *
     * @param socket target socket connected to WebView
     * @param request parsed WebView request
     * @return true when the response was written from persistent cache
     * @throws IOException when the cached response cannot be written
     */
    private boolean serveFromCacheIfPossible(Socket socket, HttpRequest request) throws IOException {
        if (!isCacheLookupRequest(request)) {
            return false;
        }

        CacheEntry cacheEntry = cacheEntryFor(request.path);
        CacheMetadata metadata = CacheMetadata.load(cacheEntry.metaFile);

        if (!cacheEntry.dataFile.isFile() || metadata == null) {
            Log.i(LOG_TAG, "Persistent cache miss: " + request.path);
            if (isAlbumAudioRangeRequest(request)) {
                scheduleCacheRepair(request.path, "audio range cache miss");
            }
            return false;
        }

        if (!isCacheEntryValid(cacheEntry, metadata)) {
            Log.w(LOG_TAG, "Persistent cache invalid, refetching: " + request.path);
            deleteCacheEntry(cacheEntry);
            scheduleCacheRepair(request.path, "invalid cache hit");
            return false;
        }

        if (isRangeRequest(request)) {
            RangeRequest rangeRequest = parseRangeRequest(request.headers.get("range"), cacheEntry.dataFile.length());
            if (rangeRequest == null) {
                writeRangeNotSatisfiableResponse(socket, cacheEntry.dataFile.length());
                return true;
            }

            Log.i(LOG_TAG, "Persistent cache range hit: " + request.path + rangeLogSuffix(request));
            writeCachedRangeResponse(socket, cacheEntry.dataFile, metadata, rangeRequest);
            return true;
        }

        Log.i(LOG_TAG, "Persistent cache hit: " + request.path);
        writeCachedResponse(socket, request, cacheEntry.dataFile, metadata);
        return true;
    }

    /**
     * Writes a full cached response and injects the viewer bridge into HTML on demand.
     *
     * @param socket target socket connected to WebView
     * @param request parsed WebView request
     * @param dataFile cached original response body
     * @param metadata cached response metadata
     * @throws IOException when the cached response cannot be read or written
     */
    private void writeCachedResponse(Socket socket, HttpRequest request, File dataFile, CacheMetadata metadata) throws IOException {
        InputStream inputStream = new FileInputStream(dataFile);
        byte[] body = null;
        long contentLength = dataFile.length();

        if (shouldTransformTextResponse(request, 200, metadata.contentType)) {
            body = transformTextResponse(request, readStreamBytes(inputStream), metadata.contentType);
            closeQuietly(inputStream);
            inputStream = new ByteArrayInputStream(body);
            contentLength = body.length;
        }

        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.ISO_8859_1));
        writeHeader(outputStream, "Content-Type", metadata.contentType);
        writeHeader(outputStream, "Access-Control-Allow-Origin", "*");
        writeHeader(outputStream, "Accept-Ranges", "bytes");
        writeHeader(outputStream, "Cache-Control", CACHE_CONTROL_VALUE);
        writeHeader(outputStream, "Connection", "close");
        writeHeader(outputStream, "Content-Length", String.valueOf(contentLength));

        if (!metadata.lastModified.isEmpty()) {
            writeHeader(outputStream, "Last-Modified", metadata.lastModified);
        }

        if (!metadata.etag.isEmpty()) {
            writeHeader(outputStream, "ETag", metadata.etag);
        }

        if (!metadata.ipfsPath.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Path", metadata.ipfsPath);
        }

        if (!metadata.ipfsRoots.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Roots", metadata.ipfsRoots);
        }

        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        copyStream(inputStream, outputStream);
        outputStream.flush();
        closeQuietly(inputStream);
    }

    /**
     * Writes one byte range from a complete cached response.
     *
     * @param socket target socket connected to WebView
     * @param dataFile cached original response body
     * @param metadata cached response metadata
     * @param rangeRequest requested byte range resolved against the cached file length
     * @throws IOException when the cached response cannot be read or written
     */
    private void writeCachedRangeResponse(Socket socket, File dataFile, CacheMetadata metadata, RangeRequest rangeRequest) throws IOException {
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write("HTTP/1.1 206 Partial Content\r\n".getBytes(StandardCharsets.ISO_8859_1));
        writeHeader(outputStream, "Content-Type", metadata.contentType);
        writeHeader(outputStream, "Access-Control-Allow-Origin", "*");
        writeHeader(outputStream, "Accept-Ranges", "bytes");
        writeHeader(outputStream, "Cache-Control", CACHE_CONTROL_VALUE);
        writeHeader(outputStream, "Connection", "close");
        writeHeader(outputStream, "Content-Length", String.valueOf(rangeRequest.length()));
        writeHeader(
            outputStream,
            "Content-Range",
            "bytes " + rangeRequest.start + "-" + rangeRequest.end + "/" + rangeRequest.totalLength
        );

        if (!metadata.lastModified.isEmpty()) {
            writeHeader(outputStream, "Last-Modified", metadata.lastModified);
        }

        if (!metadata.etag.isEmpty()) {
            writeHeader(outputStream, "ETag", metadata.etag);
        }

        if (!metadata.ipfsPath.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Path", metadata.ipfsPath);
        }

        if (!metadata.ipfsRoots.isEmpty()) {
            writeHeader(outputStream, "X-Ipfs-Roots", metadata.ipfsRoots);
        }

        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        copyFileRange(dataFile, outputStream, rangeRequest.start, rangeRequest.length());
        outputStream.flush();
    }

    /**
     * Writes an HTTP 416 response for an unsatisfiable cached range request.
     *
     * @param socket target socket connected to WebView
     * @param totalLength complete cached file length
     * @throws IOException when the response cannot be written
     */
    private void writeRangeNotSatisfiableResponse(Socket socket, long totalLength) throws IOException {
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write("HTTP/1.1 416 Range Not Satisfiable\r\n".getBytes(StandardCharsets.ISO_8859_1));
        writeHeader(outputStream, "Content-Range", "bytes */" + totalLength);
        writeHeader(outputStream, "Content-Length", "0");
        writeHeader(outputStream, "Connection", "close");
        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        outputStream.flush();
    }

    /**
     * Copies a gateway body to WebView and stores complete cacheable responses on disk.
     *
     * @param request parsed WebView request
     * @param inputStream gateway response body
     * @param outputStream target WebView response stream
     * @param statusCode gateway HTTP status code
     * @param contentType resolved MIME type sent to WebView
     * @param cacheHeaders gateway headers useful for persistent cache validation
     * @param injectedBody HTML body already modified for bridge injection, if any
     * @return number of bytes written to WebView
     * @throws IOException when streaming fails
     */
    private long copyGatewayBody(
        HttpRequest request,
        InputStream inputStream,
        BufferedOutputStream outputStream,
        int statusCode,
        String contentType,
        CacheResponseHeaders cacheHeaders,
        byte[] injectedBody
    ) throws IOException {
        if (!isCompleteCacheRequest(request) || statusCode != 200) {
            return copyStream(inputStream, outputStream);
        }

        CacheEntry cacheEntry = cacheEntryFor(request.path);
        File tempFile = temporaryCacheFile(cacheEntry, "download");
        long bytesWritten;

        if (injectedBody != null) {
            return copyStream(inputStream, outputStream);
        }

        CopyResult copyResult = copyStreamToOutputAndFile(inputStream, outputStream, tempFile);
        finishCacheWrite(cacheEntry, tempFile, contentType, cacheHeaders, copyResult, true);
        return copyResult.bytesCopied;
    }

    /**
     * Stores a complete in-memory response body when HTML injection already consumed it.
     *
     * @param request parsed WebView request
     * @param body original gateway response body before viewer bridge injection
     * @param statusCode gateway HTTP status code
     * @param contentType resolved MIME type sent to WebView
     * @param cacheHeaders gateway headers useful for persistent cache validation
     */
    private void storeCompleteResponse(HttpRequest request, byte[] body, int statusCode, String contentType, CacheResponseHeaders cacheHeaders) {
        if (!isCompleteCacheRequest(request) || statusCode != 200) {
            return;
        }

        try {
            CacheEntry cacheEntry = cacheEntryFor(request.path);
            File tempFile = temporaryCacheFile(cacheEntry, "download");

            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                outputStream.write(body);
            }

            finishCacheWrite(cacheEntry, tempFile, contentType, cacheHeaders, new CopyResult(body.length, sha256(body)), true);
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Persistent cache could not store response: " + request.path, exception);
        }
    }

    /**
     * Copies a stream to WebView and a temporary cache file at the same time.
     *
     * @param inputStream gateway response body
     * @param outputStream target WebView response stream
     * @param file cache temporary file
     * @return number of bytes copied
     * @throws IOException when reading or writing fails
     */
    private CopyResult copyStreamToOutputAndFile(InputStream inputStream, BufferedOutputStream outputStream, File file) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[BUFFER_SIZE];
        long totalBytes = 0;
        int read;

        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            MessageDigest digest = newSha256Digest();

            while ((read = bufferedInputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
                fileOutputStream.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                totalBytes += read;
            }

            return new CopyResult(totalBytes, hex(digest.digest()));
        }
    }

    /**
     * Promotes a validated temporary cache body and writes its metadata.
     *
     * @param cacheEntry target cache files
     * @param tempFile temporary body file
     * @param contentType resolved MIME type sent to WebView
     * @param cacheHeaders gateway headers useful for persistent cache validation
     * @param copyResult copied body length and local SHA-256
     * @param repairOnFailure true when invalid candidates should start a background repair
     * @throws IOException when cache files cannot be written
     */
    private void finishCacheWrite(
        CacheEntry cacheEntry,
        File tempFile,
        String contentType,
        CacheResponseHeaders cacheHeaders,
        CopyResult copyResult,
        boolean repairOnFailure
    ) throws IOException {
        CacheMetadata metadata = new CacheMetadata(contentType, cacheHeaders, copyResult.bytesCopied, copyResult.sha256);

        if (!isCacheCandidateValid(tempFile, metadata)) {
            Log.w(LOG_TAG, "Persistent cache candidate rejected: " + cacheEntry.key + ", bytes=" + copyResult.bytesCopied);
            deleteFileQuietly(tempFile);
            if (repairOnFailure) {
                scheduleCacheRepair(cacheHeaders.ipfsPath, "invalid downloaded candidate");
            }
            return;
        }

        if (cacheEntry.dataFile.exists() && !cacheEntry.dataFile.delete()) {
            Log.w(LOG_TAG, "Persistent cache old file could not be replaced: " + cacheEntry.dataFile.getAbsolutePath());
        }

        if (!tempFile.renameTo(cacheEntry.dataFile)) {
            deleteFileQuietly(tempFile);
            return;
        }

        metadata.save(cacheEntry.metaFile);
        Log.i(
            LOG_TAG,
            "Persistent cache stored: "
                + cacheEntry.dataFile.getName()
                + ", bytes=" + copyResult.bytesCopied
                + ", etag=" + metadata.etag
        );
    }

    /**
     * Checks whether a downloaded cache candidate is complete and locally usable.
     *
     * @param dataFile temporary body file
     * @param metadata metadata collected from the gateway and local copy
     * @return true when the candidate can be promoted into persistent cache
     */
    private boolean isCacheCandidateValid(File dataFile, CacheMetadata metadata) {
        if (!dataFile.isFile() || metadata.actualContentLength != dataFile.length()) {
            return false;
        }

        if (metadata.expectedContentLength >= 0 && metadata.expectedContentLength != dataFile.length()) {
            return false;
        }

        if (metadata.sha256.isEmpty() || !hasUsableIpfsIdentity(metadata)) {
            return false;
        }

        return !isImageContent(metadata.contentType) || canDecodeImage(dataFile);
    }

    /**
     * Checks whether a stored cache entry still matches its local metadata.
     *
     * @param cacheEntry cache files to validate
     * @param metadata metadata loaded from disk
     * @return true when the cached response can be served without refetching
     */
    private boolean isCacheEntryValid(CacheEntry cacheEntry, CacheMetadata metadata) {
        if (!isCacheCandidateValid(cacheEntry.dataFile, metadata)) {
            return false;
        }

        try {
            return metadata.sha256.equals(sha256(cacheEntry.dataFile));
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Persistent cache SHA-256 check failed: " + cacheEntry.dataFile.getName(), exception);
            return false;
        }
    }

    /**
     * Schedules a conservative background retry for a stale cache entry.
     *
     * @param ipfsPath gateway IPFS path to refetch
     * @param reason diagnostic reason written to Android logs
     */
    private void scheduleCacheRepair(String ipfsPath, String reason) {
        if (ipfsPath == null || ipfsPath.trim().isEmpty() || !isSafeAlbumPath(ipfsPath)) {
            return;
        }

        if (!cacheRepairPaths.add(ipfsPath)) {
            return;
        }

        executorService.execute(() -> {
            try {
                for (int attempt = 1; running && attempt <= CACHE_REPAIR_MAX_ATTEMPTS; attempt++) {
                    sleepBeforeRepairAttempt(attempt);
                    if (!running) {
                        return;
                    }

                    Log.i(LOG_TAG, "Persistent cache repair attempt " + attempt + ": " + ipfsPath + ", reason=" + reason);
                    if (repairCacheEntry(ipfsPath)) {
                        Log.i(LOG_TAG, "Persistent cache repair finished: " + ipfsPath);
                        return;
                    }
                }

                Log.w(LOG_TAG, "Persistent cache repair gave up: " + ipfsPath);
            } finally {
                cacheRepairPaths.remove(ipfsPath);
            }
        });
    }

    /**
     * Waits before retrying a stale cache entry so gateway traffic stays modest.
     */
    private static void sleepBeforeRepairAttempt(int attempt) {
        try {
            Thread.sleep(CACHE_REPAIR_RETRY_DELAY_MS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Downloads one complete response for cache repair without writing to WebView.
     *
     * @param ipfsPath safe IPFS path under the configured album root
     * @return true when the cache entry was repaired
     */
    private boolean repairCacheEntry(String ipfsPath) {
        HttpURLConnection connection = null;
        InputStream responseStream = null;
        File tempFile = null;

        try {
            URL gatewayUrl = new URL(GATEWAY_ORIGIN + ipfsPath);
            connection = (HttpURLConnection) gatewayUrl.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "RPlayer Gateway Viewer/" + BuildConfig.VERSION_NAME);

            if (connection.getResponseCode() != 200) {
                return false;
            }

            responseStream = connection.getInputStream();
            CacheEntry cacheEntry = cacheEntryFor(ipfsPath);
            tempFile = temporaryCacheFile(cacheEntry, "repair");
            CopyResult copyResult = copyStreamToFile(responseStream, tempFile);
            finishCacheWrite(cacheEntry, tempFile, MimeTypes.fromPath(ipfsPath, connection.getContentType()), cacheHeadersFrom(connection), copyResult, false);
            return cacheEntry.dataFile.isFile();
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Persistent cache repair failed: " + ipfsPath, exception);
            deleteFileQuietly(tempFile);
            return false;
        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Copies a stream to a file while calculating local SHA-256.
     */
    private CopyResult copyStreamToFile(InputStream inputStream, File file) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[BUFFER_SIZE];
        long totalBytes = 0;
        int read;

        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            MessageDigest digest = newSha256Digest();

            while ((read = bufferedInputStream.read(buffer)) >= 0) {
                fileOutputStream.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                totalBytes += read;
            }

            return new CopyResult(totalBytes, hex(digest.digest()));
        }
    }

    /**
     * Reads cache-related response headers from one gateway response.
     */
    private static CacheResponseHeaders cacheHeadersFrom(HttpURLConnection connection) {
        return new CacheResponseHeaders(
            connection.getHeaderField("Last-Modified"),
            connection.getHeaderField("ETag"),
            connection.getHeaderField("X-Ipfs-Path"),
            connection.getHeaderField("X-Ipfs-Roots"),
            parseLongHeader(connection.getHeaderField("Content-Length"))
        );
    }

    /**
     * Checks whether gateway metadata contains a consistent expected IPFS identity.
     */
    private static boolean hasUsableIpfsIdentity(CacheMetadata metadata) {
        String etagCid = normalizeGatewayCid(metadata.etag);
        String rootsCid = lastIpfsRoot(metadata.ipfsRoots);

        if (etagCid.isEmpty()) {
            return !rootsCid.isEmpty();
        }

        return rootsCid.isEmpty() || etagCid.equals(rootsCid);
    }

    /**
     * Normalizes a gateway CID value stored in ETag-like headers.
     */
    private static String normalizeGatewayCid(String value) {
        String normalized = safeHeaderValue(value);

        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }

        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    /**
     * Returns the final CID from X-Ipfs-Roots, which represents the requested asset.
     */
    private static String lastIpfsRoot(String ipfsRoots) {
        String safeRoots = safeHeaderValue(ipfsRoots);
        if (safeRoots.isEmpty()) {
            return "";
        }

        String[] roots = safeRoots.split(",");
        return roots.length == 0 ? "" : roots[roots.length - 1].trim();
    }

    /**
     * Returns true when the MIME type should be decoded as an Android image.
     */
    private static boolean isImageContent(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    /**
     * Checks image headers without allocating the whole bitmap pixel buffer.
     */
    private static boolean canDecodeImage(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    /**
     * Removes both body and metadata for a broken cache object.
     */
    private static void deleteCacheEntry(CacheEntry cacheEntry) {
        deleteFileQuietly(cacheEntry.dataFile);
        deleteFileQuietly(cacheEntry.metaFile);
    }

    /**
     * Removes one file without throwing during cache cleanup.
     */
    private static void deleteFileQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(LOG_TAG, "Persistent cache file could not be removed: " + file.getAbsolutePath());
        }
    }

    /**
     * Writes one completed gateway request into Android logs for boot and cache diagnostics.
     */
    private void logGatewayRequestFinished(
        HttpRequest request,
        HttpURLConnection connection,
        int statusCode,
        String statusText,
        long bytesWritten,
        long requestStartedAt
    ) {
        long durationMs = System.currentTimeMillis() - requestStartedAt;
        String levelPrefix = durationMs >= SLOW_REQUEST_LOG_THRESHOLD_MS ? "Slow gateway response" : "Gateway response";

        Log.i(
            LOG_TAG,
            levelPrefix
                + ": " + statusCode + " " + safeStatusText(statusText)
                + ", " + bytesWritten + " bytes"
                + ", " + durationMs + " ms"
                + ", contentType=" + MimeTypes.fromPath(request.path, connection.getContentType())
                + ", cacheControl=" + headerOrDash(connection, "Cache-Control")
                + ", etag=" + headerOrDash(connection, "ETag")
                + ", lastModified=" + headerOrDash(connection, "Last-Modified")
                + ", path=" + request.path
                + rangeLogSuffix(request)
        );
    }

    /**
     * Decides whether the response is an HTML document where early bridge injection is useful.
     */
    private boolean shouldInjectHtmlBridge(HttpRequest request, int statusCode, String contentType) {
        if (statusCode != 200 || htmlBridgeScript.trim().isEmpty()) {
            return false;
        }

        String lowerPath = request.path.toLowerCase(Locale.ROOT);
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        return lowerPath.endsWith(".htm")
            || lowerPath.endsWith(".html")
            || lowerContentType.contains("text/html");
    }

    /**
     * Inserts the bridge at the beginning of the HTML head when possible.
     */
    private String injectHtmlBridge(String html) {
        String bridgeTag = "<script type=\"text/javascript\">\n" + htmlBridgeScript + "\n</script>\n";
        String lowerHtml = html.toLowerCase(Locale.ROOT);
        int headEnd = lowerHtml.indexOf("</head>");

        if (headEnd >= 0) {
            return html.substring(0, headEnd) + bridgeTag + html.substring(headEnd);
        }

        return bridgeTag + html;
    }

    /**
     * Reads a small text-like response into memory for controlled HTML injection.
     */
    private static byte[] readStreamBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;

        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }

        return outputStream.toByteArray();
    }

    /**
     * Writes a small local plain-text HTTP response for proxy policy errors.
     *
     * @param socket target socket connected to WebView
     * @param statusCode HTTP status code to send
     * @param statusText HTTP status text to send
     * @param body response body text
     * @throws IOException when the response cannot be written
     */
    private void writePlainResponse(Socket socket, int statusCode, String statusText, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write(("HTTP/1.1 " + statusCode + " " + statusText + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        writeHeader(outputStream, "Content-Type", "text/plain; charset=utf-8");
        writeHeader(outputStream, "Content-Length", String.valueOf(bodyBytes.length));
        writeHeader(outputStream, "Connection", "close");
        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(bodyBytes);
        outputStream.flush();
    }

    /**
     * Writes one HTTP header line using the wire encoding expected by HTTP/1.1.
     */
    private static void writeHeader(BufferedOutputStream outputStream, String name, String value) throws IOException {
        outputStream.write((name + ": " + safeHeaderValue(value) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Removes characters that could break one HTTP header or cache metadata line.
     */
    private static String safeHeaderValue(String value) {
        if (value == null) {
            return "";
        }

        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * Copies the response body without buffering the whole file in memory.
     */
    private static long copyStream(InputStream inputStream, BufferedOutputStream outputStream) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        long totalBytes = 0;

        while ((read = bufferedInputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
            totalBytes += read;
        }

        return totalBytes;
    }

    /**
     * Decides whether the request can consult the persistent cache.
     *
     * @param request parsed WebView request
     * @return true when a complete cached file could answer the request
     */
    private static boolean isCacheLookupRequest(HttpRequest request) {
        return "GET".equals(request.method);
    }

    /**
     * Decides whether the request is safe for the simple complete-response cache.
     *
     * @param request parsed WebView request
     * @return true when the request can be cached only as a complete response
     */
    private static boolean isCompleteCacheRequest(HttpRequest request) {
        return "GET".equals(request.method) && !isRangeRequest(request);
    }

    /**
     * Checks whether WebView requested a byte range.
     *
     * @param request parsed WebView request
     * @return true when a Range header is present
     */
    private static boolean isRangeRequest(HttpRequest request) {
        String range = request.headers.get("range");
        return range != null && !range.trim().isEmpty();
    }

    /**
     * Checks whether a Range request targets the main album audio class of files.
     *
     * @param request parsed WebView request
     * @return true when the request should trigger complete background audio caching
     */
    private static boolean isAlbumAudioRangeRequest(HttpRequest request) {
        String lowerPath = request.path.toLowerCase(Locale.ROOT);
        return isRangeRequest(request)
            && (lowerPath.endsWith(".m4a")
                || lowerPath.endsWith(".mp3")
                || lowerPath.endsWith(".aac")
                || lowerPath.endsWith(".ogg")
                || lowerPath.endsWith(".flac")
                || lowerPath.endsWith(".wav"));
    }

    /**
     * Parses one HTTP byte range against a complete local file length.
     *
     * @param rangeHeader raw Range header value
     * @param totalLength complete cached file length
     * @return parsed range, or null when the request is invalid or unsatisfiable
     */
    private static RangeRequest parseRangeRequest(String rangeHeader, long totalLength) {
        if (rangeHeader == null || totalLength <= 0) {
            return null;
        }

        String range = rangeHeader.trim().toLowerCase(Locale.ROOT);
        if (!range.startsWith("bytes=") || range.indexOf(',') >= 0) {
            return null;
        }

        String value = range.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0) {
            return null;
        }

        try {
            String startText = value.substring(0, separator).trim();
            String endText = value.substring(separator + 1).trim();
            long start;
            long end;

            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    return null;
                }

                start = Math.max(0, totalLength - suffixLength);
                end = totalLength - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? totalLength - 1 : Long.parseLong(endText);
            }

            if (start < 0 || end < start || start >= totalLength) {
                return null;
            }

            return new RangeRequest(start, Math.min(end, totalLength - 1), totalLength);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Ensures the persistent cache directory exists before the proxy accepts requests.
     *
     * @throws IOException when the directory cannot be created
     */
    private void ensureCacheDirectory() throws IOException {
        if (cacheDirectory == null) {
            throw new IOException("Persistent cache directory is not configured.");
        }

        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Persistent cache directory could not be created: " + cacheDirectory.getAbsolutePath());
        }
    }

    /**
     * Builds deterministic cache filenames for one immutable IPFS path.
     *
     * @param path requested IPFS path
     * @return cache entry files for body and metadata
     */
    private CacheEntry cacheEntryFor(String path) {
        String key = sha256(path);
        return new CacheEntry(key, new File(cacheDirectory, key + ".data"), new File(cacheDirectory, key + ".meta"));
    }

    /**
     * Builds a unique temporary file name for one cache write candidate.
     *
     * @param cacheEntry target cache entry
     * @param purpose short diagnostic purpose for the temporary file name
     * @return unique temporary file inside the persistent cache directory
     */
    private File temporaryCacheFile(CacheEntry cacheEntry, String purpose) {
        String uniqueName = cacheEntry.key
            + "."
            + safeTemporaryNamePart(purpose)
            + "."
            + Thread.currentThread().getId()
            + "."
            + System.nanoTime()
            + ".tmp";
        return new File(cacheDirectory, uniqueName);
    }

    /**
     * Keeps temporary cache file names boring and filesystem-safe.
     */
    private static String safeTemporaryNamePart(String value) {
        String safeValue = value == null ? "cache" : value.toLowerCase(Locale.ROOT);
        return safeValue.replaceAll("[^a-z0-9_-]", "_");
    }

    /**
     * Copies a selected byte range from one file to the response stream.
     *
     * @param file source file
     * @param outputStream target response stream
     * @param start first byte offset to copy
     * @param length number of bytes to copy
     * @throws IOException when file reading or response writing fails
     */
    private static void copyFileRange(File file, BufferedOutputStream outputStream, long start, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            randomAccessFile.seek(start);
            while (remaining > 0) {
                int read = randomAccessFile.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    return;
                }

                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /**
     * Creates a lowercase SHA-256 cache key.
     *
     * @param value source value to hash
     * @return hexadecimal SHA-256 string
     */
    private static String sha256(String value) {
        MessageDigest digest = newSha256Digest();
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Calculates lowercase SHA-256 for a byte array.
     */
    private static String sha256(byte[] value) {
        MessageDigest digest = newSha256Digest();
        return hex(digest.digest(value));
    }

    /**
     * Calculates lowercase SHA-256 for one local file.
     */
    private static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;

        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }

        return hex(digest.digest());
    }

    /**
     * Creates a SHA-256 digest or fails loudly on broken Android runtimes.
     */
    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    /**
     * Converts binary hash bytes into lowercase hexadecimal text.
     */
    private static String hex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);

        for (byte item : hash) {
            builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }

        return builder.toString();
    }

    /**
     * Parses a positive HTTP length header.
     */
    private static long parseLongHeader(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Returns a valid HTTP status text when the gateway omits the reason phrase.
     */
    private static String safeStatusText(String statusText) {
        if (statusText == null || statusText.trim().isEmpty()) {
            return "OK";
        }

        return statusText;
    }

    /**
     * Returns a readable request Range suffix for diagnostics.
     */
    private static String rangeLogSuffix(HttpRequest request) {
        String range = request.headers.get("range");
        if (range == null || range.trim().isEmpty()) {
            return "";
        }

        return ", range=" + range;
    }

    /**
     * Reads a gateway response header for logs without producing null text.
     */
    private static String headerOrDash(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    /**
     * Closes a resource during cleanup without replacing the original proxy error.
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Files backing one complete persistent cache object.
     */
    private static final class CacheEntry {
        private final String key;
        private final File dataFile;
        private final File metaFile;

        /**
         * Creates file references for a cache object.
         *
         * @param key deterministic cache key
         * @param dataFile cached body file
         * @param metaFile cached metadata file
         */
        private CacheEntry(String key, File dataFile, File metaFile) {
            this.key = key;
            this.dataFile = dataFile;
            this.metaFile = metaFile;
        }
    }

    /**
     * One satisfiable byte range inside a complete cached file.
     */
    private static final class RangeRequest {
        private final long start;
        private final long end;
        private final long totalLength;

        /**
         * Creates one byte range resolved against a complete cached file.
         *
         * @param start first requested byte offset
         * @param end last requested byte offset
         * @param totalLength complete cached file length
         */
        private RangeRequest(long start, long end, long totalLength) {
            this.start = start;
            this.end = end;
            this.totalLength = totalLength;
        }

        /**
         * Returns the number of bytes in this range.
         *
         * @return byte count to send
         */
        private long length() {
            return end - start + 1;
        }
    }

    /**
     * Metadata collected from one gateway response for cache validation.
     */
    private static final class CacheResponseHeaders {
        private final String lastModified;
        private final String etag;
        private final String ipfsPath;
        private final String ipfsRoots;
        private final long expectedContentLength;

        private CacheResponseHeaders(String lastModified, String etag, String ipfsPath, String ipfsRoots, long expectedContentLength) {
            this.lastModified = safeHeaderValue(lastModified);
            this.etag = safeHeaderValue(etag);
            this.ipfsPath = safeHeaderValue(ipfsPath);
            this.ipfsRoots = safeHeaderValue(ipfsRoots);
            this.expectedContentLength = expectedContentLength;
        }
    }

    /**
     * Local copy result used before a response is promoted into persistent cache.
     */
    private static final class CopyResult {
        private final long bytesCopied;
        private final String sha256;

        private CopyResult(long bytesCopied, String sha256) {
            this.bytesCopied = bytesCopied;
            this.sha256 = safeHeaderValue(sha256);
        }
    }

    /**
     * Metadata needed to validate and replay a complete cached HTTP response.
     */
    private static final class CacheMetadata {
        private final String contentType;
        private final String lastModified;
        private final String etag;
        private final String ipfsPath;
        private final String ipfsRoots;
        private final long expectedContentLength;
        private final long actualContentLength;
        private final String sha256;

        /**
         * Creates immutable cache response metadata.
         *
         * @param contentType MIME type sent to WebView
         * @param cacheHeaders gateway headers useful for persistent cache validation
         * @param actualContentLength actual cached body length
         * @param sha256 local SHA-256 of cached body bytes
         */
        private CacheMetadata(String contentType, CacheResponseHeaders cacheHeaders, long actualContentLength, String sha256) {
            String safeContentType = safeHeaderValue(contentType);
            this.contentType = safeContentType.isEmpty() ? "application/octet-stream" : safeContentType;
            this.lastModified = cacheHeaders.lastModified;
            this.etag = cacheHeaders.etag;
            this.ipfsPath = cacheHeaders.ipfsPath;
            this.ipfsRoots = cacheHeaders.ipfsRoots;
            this.expectedContentLength = cacheHeaders.expectedContentLength;
            this.actualContentLength = actualContentLength;
            this.sha256 = safeHeaderValue(sha256);
        }

        /**
         * Creates immutable cache response metadata loaded from disk.
         */
        private CacheMetadata(
            String contentType,
            String lastModified,
            String etag,
            String ipfsPath,
            String ipfsRoots,
            long expectedContentLength,
            long actualContentLength,
            String sha256
        ) {
            String safeContentType = safeHeaderValue(contentType);
            this.contentType = safeContentType.isEmpty() ? "application/octet-stream" : safeContentType;
            this.lastModified = safeHeaderValue(lastModified);
            this.etag = safeHeaderValue(etag);
            this.ipfsPath = safeHeaderValue(ipfsPath);
            this.ipfsRoots = safeHeaderValue(ipfsRoots);
            this.expectedContentLength = expectedContentLength;
            this.actualContentLength = actualContentLength;
            this.sha256 = safeHeaderValue(sha256);
        }

        /**
         * Loads metadata from disk.
         *
         * @param file metadata file
         * @return metadata, or null when the file is missing or invalid
         */
        private static CacheMetadata load(File file) {
            if (!file.isFile()) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String version = reader.readLine();
                if (!"cache-v2".equals(version)) {
                    return null;
                }

                String contentType = reader.readLine();
                String lastModified = reader.readLine();
                String etag = reader.readLine();
                String ipfsPath = reader.readLine();
                String ipfsRoots = reader.readLine();
                long expectedContentLength = Long.parseLong(reader.readLine());
                long actualContentLength = Long.parseLong(reader.readLine());
                String sha256 = reader.readLine();
                return new CacheMetadata(
                    contentType,
                    lastModified,
                    etag,
                    ipfsPath,
                    ipfsRoots,
                    expectedContentLength,
                    actualContentLength,
                    sha256
                );
            } catch (IOException | RuntimeException exception) {
                return null;
            }
        }

        /**
         * Saves metadata to disk next to the cached body.
         *
         * @param file metadata file
         * @throws IOException when metadata cannot be written
         */
        private void save(File file) throws IOException {
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                String text = "cache-v2\n"
                    + contentType + "\n"
                    + lastModified + "\n"
                    + etag + "\n"
                    + ipfsPath + "\n"
                    + ipfsRoots + "\n"
                    + expectedContentLength + "\n"
                    + actualContentLength + "\n"
                    + sha256 + "\n";
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Minimal immutable representation of the HTTP request fields used by the proxy.
     */
    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers;

        private HttpRequest(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = normalizePath(path);
            this.headers = headers;
        }

        /**
         * Converts absolute localhost request targets to origin-form paths.
         *
         * <p>Most WebView requests already use paths such as /ipfs/..., but the proxy
         * also accepts absolute localhost URLs defensively.</p>
         */
        private static String normalizePath(String rawPath) {
            if (rawPath.startsWith("http://127.0.0.1")) {
                int pathStart = rawPath.indexOf('/', "http://127.0.0.1".length());
                return pathStart >= 0 ? rawPath.substring(pathStart) : "/";
            }

            return rawPath;
        }
    }
}
