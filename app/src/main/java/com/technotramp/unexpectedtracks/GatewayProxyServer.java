package com.technotramp.unexpectedtracks;

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
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    private static final String ROOT_PATH = "/ipfs/bafybeiewkxwysf4jlnhbxs7pd4junvkrrais76qm3qgkpn3en4b2lcqxwm/index.htm";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long SLOW_REQUEST_LOG_THRESHOLD_MS = 5000;
    private static final String CACHE_CONTROL_VALUE = "public, max-age=31536000, immutable";

    private final ExecutorService executorService = Executors.newCachedThreadPool();
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
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + ROOT_PATH;
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

            if (!request.path.startsWith("/ipfs/")) {
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
        connection.setRequestProperty("User-Agent", "RPlayer Gateway Viewer/0.1.0");

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

        if (!"HEAD".equals(request.method) && shouldInjectHtmlBridge(request, statusCode, contentType)) {
            byte[] originalHtmlBody = readStreamBytes(responseStream);
            storeCompleteResponse(request, originalHtmlBody, statusCode, contentType, connection.getHeaderField("Last-Modified"), connection.getHeaderField("ETag"));
            String html = new String(originalHtmlBody, StandardCharsets.UTF_8);
            injectedResponseBody = injectHtmlBridge(html).getBytes(StandardCharsets.UTF_8);
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
        String lastModified = connection.getHeaderField("Last-Modified");
        String etag = connection.getHeaderField("ETag");

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

        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));

        if (!"HEAD".equals(request.method)) {
            long bytesWritten = copyGatewayBody(request, responseStream, outputStream, statusCode, contentType, lastModified, etag, injectedResponseBody);
            outputStream.flush();
            closeQuietly(responseStream);
            return bytesWritten;
        }

        outputStream.flush();
        closeQuietly(responseStream);
        return 0;
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
        if (!isCompleteCacheRequest(request)) {
            return false;
        }

        CacheEntry cacheEntry = cacheEntryFor(request.path);
        CacheMetadata metadata = CacheMetadata.load(cacheEntry.metaFile);

        if (!cacheEntry.dataFile.isFile() || metadata == null) {
            Log.i(LOG_TAG, "Persistent cache miss: " + request.path);
            return false;
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

        if (shouldInjectHtmlBridge(request, 200, metadata.contentType)) {
            body = injectHtmlBridge(new String(readStreamBytes(inputStream), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
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

        outputStream.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        copyStream(inputStream, outputStream);
        outputStream.flush();
        closeQuietly(inputStream);
    }

    /**
     * Copies a gateway body to WebView and stores complete cacheable responses on disk.
     *
     * @param request parsed WebView request
     * @param inputStream gateway response body
     * @param outputStream target WebView response stream
     * @param statusCode gateway HTTP status code
     * @param contentType resolved MIME type sent to WebView
     * @param lastModified gateway Last-Modified header, if any
     * @param etag gateway ETag header, if any
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
        String lastModified,
        String etag,
        byte[] injectedBody
    ) throws IOException {
        if (!isCompleteCacheRequest(request) || statusCode != 200) {
            return copyStream(inputStream, outputStream);
        }

        CacheEntry cacheEntry = cacheEntryFor(request.path);
        File tempFile = new File(cacheDirectory, cacheEntry.key + ".tmp");
        long bytesWritten;

        if (injectedBody != null) {
            return copyStream(inputStream, outputStream);
        }

        bytesWritten = copyStreamToOutputAndFile(inputStream, outputStream, tempFile);
        finishCacheWrite(cacheEntry, tempFile, contentType, lastModified, etag, bytesWritten);
        return bytesWritten;
    }

    /**
     * Stores a complete in-memory response body when HTML injection already consumed it.
     *
     * @param request parsed WebView request
     * @param body original gateway response body before viewer bridge injection
     * @param statusCode gateway HTTP status code
     * @param contentType resolved MIME type sent to WebView
     * @param lastModified gateway Last-Modified header, if any
     * @param etag gateway ETag header, if any
     */
    private void storeCompleteResponse(HttpRequest request, byte[] body, int statusCode, String contentType, String lastModified, String etag) {
        if (!isCompleteCacheRequest(request) || statusCode != 200) {
            return;
        }

        try {
            CacheEntry cacheEntry = cacheEntryFor(request.path);
            File tempFile = new File(cacheDirectory, cacheEntry.key + ".tmp");

            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                outputStream.write(body);
            }

            finishCacheWrite(cacheEntry, tempFile, contentType, lastModified, etag, body.length);
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
    private long copyStreamToOutputAndFile(InputStream inputStream, BufferedOutputStream outputStream, File file) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[BUFFER_SIZE];
        long totalBytes = 0;
        int read;

        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            while ((read = bufferedInputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
                fileOutputStream.write(buffer, 0, read);
                totalBytes += read;
            }
        }

        return totalBytes;
    }

    /**
     * Promotes a temporary cache body and writes its metadata.
     *
     * @param cacheEntry target cache files
     * @param tempFile temporary body file
     * @param contentType resolved MIME type sent to WebView
     * @param lastModified gateway Last-Modified header, if any
     * @param etag gateway ETag header, if any
     * @param contentLength complete response body length
     * @throws IOException when cache files cannot be written
     */
    private void finishCacheWrite(CacheEntry cacheEntry, File tempFile, String contentType, String lastModified, String etag, long contentLength) throws IOException {
        if (cacheEntry.dataFile.exists() && !cacheEntry.dataFile.delete()) {
            Log.w(LOG_TAG, "Persistent cache old file could not be replaced: " + cacheEntry.dataFile.getAbsolutePath());
        }

        if (!tempFile.renameTo(cacheEntry.dataFile)) {
            if (!tempFile.delete()) {
                Log.w(LOG_TAG, "Persistent cache temporary file could not be removed: " + tempFile.getAbsolutePath());
            }
            return;
        }

        CacheMetadata metadata = new CacheMetadata(contentType, lastModified, etag, contentLength);
        metadata.save(cacheEntry.metaFile);
        Log.i(LOG_TAG, "Persistent cache stored: " + cacheEntry.dataFile.getName() + ", bytes=" + contentLength);
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
        outputStream.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
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
     * Decides whether the request is safe for the simple complete-response cache.
     *
     * @param request parsed WebView request
     * @return true when the request can be cached only as a complete response
     */
    private static boolean isCompleteCacheRequest(HttpRequest request) {
        return "GET".equals(request.method) && !request.headers.containsKey("range");
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
     * Creates a lowercase SHA-256 cache key.
     *
     * @param value source value to hash
     * @return hexadecimal SHA-256 string
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);

            for (byte item : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }

            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
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
     * Minimal metadata needed to replay a complete cached HTTP response.
     */
    private static final class CacheMetadata {
        private final String contentType;
        private final String lastModified;
        private final String etag;
        private final long contentLength;

        /**
         * Creates immutable cache response metadata.
         *
         * @param contentType MIME type sent to WebView
         * @param lastModified Last-Modified header value, if any
         * @param etag ETag header value, if any
         * @param contentLength complete cached body length
         */
        private CacheMetadata(String contentType, String lastModified, String etag, long contentLength) {
            this.contentType = contentType == null || contentType.trim().isEmpty() ? "application/octet-stream" : contentType;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.etag = etag == null ? "" : etag;
            this.contentLength = contentLength;
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
                String contentType = reader.readLine();
                String lastModified = reader.readLine();
                String etag = reader.readLine();
                String length = reader.readLine();
                return new CacheMetadata(contentType, lastModified, etag, Long.parseLong(length));
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
                String text = contentType + "\n" + lastModified + "\n" + etag + "\n" + contentLength + "\n";
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
