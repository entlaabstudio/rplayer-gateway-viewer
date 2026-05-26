package com.technotramp.unexpectedtracks;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GatewayProxyServer implements Closeable {
    private static final String LOG_TAG = "RPlayerProxy";
    private static final String GATEWAY_ORIGIN = "https://ipfs.io";
    private static final String ROOT_PATH = "/ipfs/bafybeiewkxwysf4jlnhbxs7pd4junvkrrais76qm3qgkpn3en4b2lcqxwm/index.htm";
    private static final int BUFFER_SIZE = 32 * 1024;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    int start() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "RPlayerProxyAccept");
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    String viewerUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + ROOT_PATH;
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(serverSocket);
        executorService.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executorService.execute(() -> handleSocket(socket));
            } catch (IOException exception) {
                if (running) {
                    Log.w(LOG_TAG, "Proxy nemohl přijmout spojení.", exception);
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        try {
            HttpRequest request = readRequest(socket);

            if (request == null) {
                return;
            }

            Log.i(LOG_TAG, request.method + " " + request.path);

            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                writePlainResponse(socket, 405, "Method Not Allowed", "Metoda neni podporovana.");
                return;
            }

            if (!request.path.startsWith("/ipfs/")) {
                writePlainResponse(socket, 404, "Not Found", "Proxy obsluhuje pouze IPFS cestu RPlayeru.");
                return;
            }

            proxyRequest(socket, request);
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Proxy pozadavek selhal.", exception);
            try {
                writePlainResponse(socket, 502, "Bad Gateway", "Proxy nedokazala nacist obsah z ipfs.io.");
            } catch (IOException responseException) {
                Log.w(LOG_TAG, "Proxy nedokazala odeslat chybovou odpoved.", responseException);
            }
        } finally {
            closeQuietly(socket);
        }
    }

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

    private void proxyRequest(Socket socket, HttpRequest request) throws IOException {
        URL gatewayUrl = new URL(GATEWAY_ORIGIN + request.path);
        HttpURLConnection connection = (HttpURLConnection) gatewayUrl.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(request.method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "RPlayer Gateway Viewer/0.1.0");

        String range = request.headers.get("range");
        if (range != null && !range.isEmpty()) {
            connection.setRequestProperty("Range", range);
        }

        int statusCode = connection.getResponseCode();
        String statusText = connection.getResponseMessage();
        InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

        if (responseStream == null) {
            responseStream = new ByteArrayInputStream(new byte[0]);
        }

        writeGatewayResponse(socket, request, connection, statusCode, statusText, responseStream);
        connection.disconnect();
    }

    private void writeGatewayResponse(
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
        writeHeader(outputStream, "Content-Type", contentType);
        writeHeader(outputStream, "Access-Control-Allow-Origin", "*");
        writeHeader(outputStream, "Accept-Ranges", "bytes");
        writeHeader(outputStream, "Cache-Control", "no-store");
        writeHeader(outputStream, "Connection", "close");

        String contentLength = connection.getHeaderField("Content-Length");
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
            copyStream(responseStream, outputStream);
        }

        outputStream.flush();
        closeQuietly(responseStream);
    }

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

    private static void writeHeader(BufferedOutputStream outputStream, String name, String value) throws IOException {
        outputStream.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void copyStream(InputStream inputStream, BufferedOutputStream outputStream) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;

        while ((read = bufferedInputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
    }

    private static String safeStatusText(String statusText) {
        if (statusText == null || statusText.trim().isEmpty()) {
            return "OK";
        }

        return statusText;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers;

        private HttpRequest(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = normalizePath(path);
            this.headers = headers;
        }

        private static String normalizePath(String rawPath) {
            if (rawPath.startsWith("http://127.0.0.1")) {
                int pathStart = rawPath.indexOf('/', "http://127.0.0.1".length());
                return pathStart >= 0 ? rawPath.substring(pathStart) : "/";
            }

            return rawPath;
        }
    }
}
