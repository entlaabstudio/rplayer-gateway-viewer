package com.technotramp.unexpectedtracks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Main Android entry point for the single-album viewer.
 *
 * <p>The activity starts the local gateway proxy, configures a locked-down
 * WebView, and loads the fixed RPlayer album through the proxy URL.</p>
 */
public final class MainActivity extends Activity {
    private static final String LOG_TAG = "RPlayerViewer";
    private static final int CREATE_DOWNLOAD_REQUEST_CODE = 1001;
    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final long MEDIA_SESSION_ACTIONS = PlaybackState.ACTION_PLAY
        | PlaybackState.ACTION_PAUSE
        | PlaybackState.ACTION_PLAY_PAUSE
        | PlaybackState.ACTION_SKIP_TO_NEXT
        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
        | PlaybackState.ACTION_STOP
        | PlaybackState.ACTION_SEEK_TO
        | PlaybackState.ACTION_FAST_FORWARD
        | PlaybackState.ACTION_REWIND;

    private GatewayProxyServer proxyServer;
    private WebView webView;
    private TextView errorView;
    private DownloadSession activeDownloadSession;
    private PendingDownload pendingDownload;
    private MediaSession mediaSession;
    private String downloadBridgeScriptSource;
    private String mediaSessionBridgeScriptSource;

    /**
     * Initializes the activity, starts the local proxy, and loads the viewer URL.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createLayout();
        createMediaSession();

        try {
            proxyServer = new GatewayProxyServer(mediaSessionBridgeScript(), new File(getFilesDir(), "rplayer-ipfs-cache"));
            proxyServer.start();
            configureWebView();
            webView.loadUrl(proxyServer.viewerUrl());
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Proxy server could not be started.", exception);
            showError("RPlayer Gateway Viewer could not start the local proxy.");
        }
    }

    /**
     * Handles the Android save dialog result for a generated RPlayer ZIP file.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != CREATE_DOWNLOAD_REQUEST_CODE) {
            return;
        }

        PendingDownload download = pendingDownload;
        pendingDownload = null;

        if (download == null) {
            return;
        }

        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                copyDownloadToUri(download, data.getData());
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Generated ZIP could not be saved.", exception);
                showError("RPlayer Gateway Viewer could not save the generated ZIP file.");
            }
        }

        deleteTempFile(download.file);
    }

    /**
     * Releases WebView, proxy, and temporary download resources when the activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        closeActiveDownloadSession();

        if (pendingDownload != null) {
            deleteTempFile(pendingDownload.file);
            pendingDownload = null;
        }

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        if (webView != null) {
            webView.destroy();
        }

        if (proxyServer != null) {
            proxyServer.close();
        }

        super.onDestroy();
    }

    /**
     * Enables the WebView features required by RPlayer while keeping file and
     * content access disabled.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);

        webView.addJavascriptInterface(new DownloadBridge(), "RPlayerGatewayDownloads");
        webView.addJavascriptInterface(new MediaSessionBridge(), "RPlayerGatewayMediaSessionNative");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                String host = request.getUrl().getHost();
                boolean internalRequest = "data".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme);
                boolean localRequest = "127.0.0.1".equals(host) || "localhost".equals(host);

                if (internalRequest || localRequest) {
                    return super.shouldInterceptRequest(view, request);
                }

                // The prototype does not allow external navigation outside the local proxy yet.
                return blockedResponse();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.w(
                    LOG_TAG,
                    "WebView resource error: "
                        + error.getErrorCode()
                        + " " + error.getDescription()
                        + ", url=" + request.getUrl()
                        + ", mainFrame=" + request.isForMainFrame()
                );
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                Log.w(
                    LOG_TAG,
                    "WebView HTTP error: "
                        + errorResponse.getStatusCode()
                        + " " + errorResponse.getReasonPhrase()
                        + ", url=" + request.getUrl()
                        + ", mainFrame=" + request.isForMainFrame()
                );
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(LOG_TAG, "WebView page started: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(LOG_TAG, "WebView page finished: " + url);
                injectDownloadBridge();
                injectMediaSessionBridge();
            }
        });
    }

    /**
     * Creates the native Android MediaSession used by OS-level media controls.
     */
    private void createMediaSession() {
        mediaSession = new MediaSession(this, "RPlayerGatewayViewer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                dispatchBrowserMediaAction("play");
            }

            @Override
            public void onPause() {
                dispatchBrowserMediaAction("pause");
            }

            @Override
            public void onSkipToNext() {
                dispatchBrowserMediaAction("nexttrack");
            }

            @Override
            public void onSkipToPrevious() {
                dispatchBrowserMediaAction("previoustrack");
            }

            @Override
            public void onStop() {
                dispatchBrowserMediaAction("stop");
            }

            @Override
            public void onFastForward() {
                dispatchBrowserMediaAction("seekforward");
            }

            @Override
            public void onRewind() {
                dispatchBrowserMediaAction("seekbackward");
            }

            @Override
            public void onSeekTo(long position) {
                String detailsJson = "{\"seekTime\":" + (position / 1000.0d) + "}";
                dispatchBrowserMediaAction("seekto", detailsJson);
            }
        });
        updateNativePlaybackState("none");
        mediaSession.setActive(true);
    }

    /**
     * Injects the JavaScript hook that routes FileSaver-style Blob downloads to Android.
     */
    private void injectDownloadBridge() {
        try {
            webView.evaluateJavascript(downloadBridgeScript(), null);
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Download bridge script could not be loaded.", exception);
            showError("RPlayer Gateway Viewer could not load the download bridge.");
        }
    }

    /**
     * Loads the JavaScript bridge from Android assets and caches it for later page loads.
     *
     * @return JavaScript source executed inside the WebView
     * @throws IOException when the asset cannot be read
     */
    private String downloadBridgeScript() throws IOException {
        if (downloadBridgeScriptSource == null) {
            downloadBridgeScriptSource = readAssetText("download-bridge.js");
        }

        return downloadBridgeScriptSource;
    }

    /**
     * Reads a UTF-8 text asset bundled with the application.
     *
     * @param assetName asset filename relative to app/src/main/assets
     * @return complete asset text
     * @throws IOException when the asset cannot be opened or read
     */
    private String readAssetText(String assetName) throws IOException {
        try (
            InputStream inputStream = getAssets().open(assetName);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }

            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Injects the JavaScript bridge that mirrors browser Media Session data to Android.
     */
    private void injectMediaSessionBridge() {
        try {
            webView.evaluateJavascript(mediaSessionBridgeScript(), null);
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Media session bridge script could not be loaded.", exception);
        }
    }

    /**
     * Loads the Media Session JavaScript bridge from Android assets.
     *
     * @return JavaScript source executed inside the WebView
     * @throws IOException when the asset cannot be read
     */
    private String mediaSessionBridgeScript() throws IOException {
        if (mediaSessionBridgeScriptSource == null) {
            mediaSessionBridgeScriptSource = readAssetText("media-session-bridge.js");
        }

        return mediaSessionBridgeScriptSource;
    }

    /**
     * Sends an Android media action back to the browser Media Session handler.
     *
     * @param action browser Media Session action name
     */
    private void dispatchBrowserMediaAction(String action) {
        dispatchBrowserMediaAction(action, "{}");
    }

    /**
     * Sends an Android media action and detail object back to RPlayer.
     *
     * @param action browser Media Session action name
     * @param detailsJson JavaScript object literal with action details
     */
    private void dispatchBrowserMediaAction(String action, String detailsJson) {
        if (webView == null) {
            return;
        }

        String script = "window.RPlayerGatewayMediaSession && "
            + "window.RPlayerGatewayMediaSession.dispatchAction("
            + jsString(action)
            + ","
            + detailsJson
            + ");";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    /**
     * Updates Android's native MediaSession metadata.
     *
     * @param title current track title
     * @param artist album artist or composer
     * @param album album name
     */
    private void updateNativeMetadata(String title, String artist, String album) {
        if (mediaSession == null) {
            return;
        }

        MediaMetadata metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .build();
        mediaSession.setMetadata(metadata);
        mediaSession.setActive(true);
    }

    /**
     * Updates Android's native MediaSession playback state.
     *
     * @param state browser Media Session playback state
     */
    private void updateNativePlaybackState(String state) {
        if (mediaSession == null) {
            return;
        }

        PlaybackState playbackState = new PlaybackState.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(playbackStateFromWeb(state), PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build();
        mediaSession.setPlaybackState(playbackState);
    }

    /**
     * Converts browser playback state names to Android playback state constants.
     *
     * @param state browser Media Session playback state
     * @return Android PlaybackState constant
     */
    private static int playbackStateFromWeb(String state) {
        if ("playing".equals(state)) {
            return PlaybackState.STATE_PLAYING;
        }

        if ("paused".equals(state)) {
            return PlaybackState.STATE_PAUSED;
        }

        return PlaybackState.STATE_NONE;
    }

    /**
     * Escapes a Java string for single-quoted JavaScript string literals.
     *
     * @param value string to escape
     * @return single-quoted JavaScript string literal
     */
    private static String jsString(String value) {
        if (value == null) {
            return "''";
        }

        return "'" + value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            + "'";
    }

    /**
     * Creates the minimal view hierarchy: the WebView and a full-screen error view.
     */
    private void createLayout() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        errorView = new TextView(this);

        errorView.setTextColor(0xffffffff);
        errorView.setBackgroundColor(0xff000000);
        errorView.setTextSize(16);
        errorView.setPadding(24, 24, 24, 24);
        errorView.setVisibility(TextView.GONE);

        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(errorView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }

    /**
     * Displays a startup or download error above the WebView.
     */
    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(TextView.VISIBLE);
    }

    /**
     * Returns a small plain-text response for navigation blocked by the WebView policy.
     */
    private WebResourceResponse blockedResponse() {
        byte[] body = "External addresses are blocked in this prototype.".getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(
            "text/plain",
            "utf-8",
            new ByteArrayInputStream(body)
        );
    }

    /**
     * Opens Android's system save dialog for a completed temporary ZIP file.
     *
     * @param download completed temporary download waiting for a user-selected destination
     */
    private void openSaveDialog(PendingDownload download) {
        pendingDownload = download;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(download.mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, download.fileName);

        try {
            startActivityForResult(intent, CREATE_DOWNLOAD_REQUEST_CODE);
        } catch (ActivityNotFoundException exception) {
            Log.e(LOG_TAG, "Android save dialog is not available.", exception);
            showError("Android save dialog is not available.");
            pendingDownload = null;
            deleteTempFile(download.file);
        }
    }

    /**
     * Copies a temporary generated ZIP to the document URI selected by the user.
     *
     * @param download completed temporary download to copy
     * @param destinationUri Android document URI selected in the save dialog
     * @throws IOException when the temporary file cannot be copied
     */
    private void copyDownloadToUri(PendingDownload download, Uri destinationUri) throws IOException {
        try (
            FileInputStream inputStream = new FileInputStream(download.file);
            OutputStream outputStream = getContentResolver().openOutputStream(destinationUri)
        ) {
            if (outputStream == null) {
                throw new IOException("Android returned no output stream for the selected destination.");
            }

            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    /**
     * Closes the current JavaScript download session and removes its temporary file.
     */
    private synchronized void closeActiveDownloadSession() {
        if (activeDownloadSession == null) {
            return;
        }

        closeQuietly(activeDownloadSession.outputStream);
        deleteTempFile(activeDownloadSession.file);
        activeDownloadSession = null;
    }

    /**
     * Deletes a temporary file and logs a warning when Android keeps it locked.
     *
     * @param file temporary file to delete
     */
    private void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(LOG_TAG, "Temporary download file could not be deleted: " + file.getAbsolutePath());
        }
    }

    /**
     * Closes a resource during cleanup without hiding the original error path.
     *
     * @param closeable resource to close
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
     * Sanitizes a browser-supplied filename before it is shown in Android's save dialog.
     *
     * @param fileName filename received from RPlayer/FileSaver
     * @return safe filename for ACTION_CREATE_DOCUMENT
     */
    private static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "unexpected-tracks.zip";
        }

        String cleanName = fileName.trim().replace('"', '_').replaceAll("[\\\\/:*?<>|]", "_");
        if (cleanName.isEmpty()) {
            return "unexpected-tracks.zip";
        }

        return cleanName;
    }

    /**
     * JavaScript interface that mirrors browser Media Session state to Android.
     */
    private final class MediaSessionBridge {
        /**
         * Receives metadata assigned by RPlayer through navigator.mediaSession.metadata.
         *
         * @param title current track title
         * @param artist album artist or composer
         * @param album album name
         * @param artworkUrl first artwork URL reported by the browser Media Session API
         */
        @JavascriptInterface
        public void updateMetadata(String title, String artist, String album, String artworkUrl) {
            Log.i(LOG_TAG, "Media metadata: " + title + " / " + artist + " / " + album);
            runOnUiThread(() -> updateNativeMetadata(title, artist, album));
        }

        /**
         * Receives browser playback state changes observed in WebView.
         *
         * @param state browser Media Session playback state
         */
        @JavascriptInterface
        public void updatePlaybackState(String state) {
            Log.i(LOG_TAG, "Media playback state: " + state);
            runOnUiThread(() -> updateNativePlaybackState(state));
        }

        /**
         * Records that RPlayer registered a browser Media Session action handler.
         *
         * @param action browser Media Session action name
         */
        @JavascriptInterface
        public void registerAction(String action) {
            Log.i(LOG_TAG, "Media action registered: " + action);
        }

        /**
         * Receives diagnostic messages from the JavaScript bridge.
         *
         * @param message diagnostic message
         */
        @JavascriptInterface
        public void log(String message) {
            Log.i(LOG_TAG, "Media bridge: " + message);
        }
    }

    /**
     * JavaScript interface that receives generated ZIP data from the WebView in chunks.
     */
    private final class DownloadBridge {
        /**
         * Starts a new temporary download file for a Blob generated inside WebView.
         *
         * @param downloadId JavaScript-generated identifier for this download
         * @param fileName suggested filename from RPlayer/FileSaver
         * @param mimeType Blob MIME type
         * @param totalBytes expected byte size reported by Blob.size
         */
        @JavascriptInterface
        public synchronized void beginDownload(String downloadId, String fileName, String mimeType, long totalBytes) {
            closeActiveDownloadSession();

            try {
                File file = File.createTempFile("rplayer-download-", ".tmp", getCacheDir());
                FileOutputStream outputStream = new FileOutputStream(file);
                activeDownloadSession = new DownloadSession(
                    downloadId,
                    sanitizeFileName(fileName),
                    mimeType == null || mimeType.trim().isEmpty() ? "application/zip" : mimeType,
                    totalBytes,
                    file,
                    outputStream
                );
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Temporary download file could not be created.", exception);
                showDownloadError("RPlayer Gateway Viewer could not create a temporary download file.");
            }
        }

        /**
         * Appends one Base64-encoded Blob chunk to the active temporary file.
         *
         * @param downloadId JavaScript-generated identifier for this download
         * @param chunkIndex zero-based chunk index used to detect ordering errors
         * @param base64Chunk Base64-encoded chunk payload
         */
        @JavascriptInterface
        public synchronized void appendChunk(String downloadId, int chunkIndex, String base64Chunk) {
            if (!isActiveDownload(downloadId)) {
                return;
            }

            if (chunkIndex != activeDownloadSession.nextChunkIndex) {
                failDownload(downloadId, "Download chunks arrived out of order.");
                return;
            }

            try {
                byte[] data = Base64.decode(base64Chunk, Base64.NO_WRAP);
                activeDownloadSession.outputStream.write(data);
                activeDownloadSession.nextChunkIndex += 1;
                activeDownloadSession.receivedBytes += data.length;
            } catch (IOException | IllegalArgumentException exception) {
                Log.e(LOG_TAG, "Generated ZIP chunk could not be written.", exception);
                failDownload(downloadId, "Generated ZIP chunk could not be written.");
            }
        }

        /**
         * Finalizes the temporary file and opens Android's save dialog.
         *
         * @param downloadId JavaScript-generated identifier for this download
         */
        @JavascriptInterface
        public synchronized void finishDownload(String downloadId) {
            if (!isActiveDownload(downloadId)) {
                return;
            }

            DownloadSession session = activeDownloadSession;
            activeDownloadSession = null;

            try {
                session.outputStream.flush();
                session.outputStream.close();
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Generated ZIP file could not be finalized.", exception);
                deleteTempFile(session.file);
                showDownloadError("RPlayer Gateway Viewer could not finalize the generated ZIP file.");
                return;
            }

            PendingDownload download = new PendingDownload(session.fileName, session.mimeType, session.file);
            runOnUiThread(() -> openSaveDialog(download));
        }

        /**
         * Aborts the active temporary file after a JavaScript-side download failure.
         *
         * @param downloadId JavaScript-generated identifier for this download
         * @param message readable failure reason from the WebView
         */
        @JavascriptInterface
        public synchronized void failDownload(String downloadId, String message) {
            if (!isActiveDownload(downloadId)) {
                return;
            }

            Log.e(LOG_TAG, message == null ? "Generated ZIP download failed." : message);
            closeActiveDownloadSession();
            showDownloadError("RPlayer Gateway Viewer could not receive the generated ZIP file.");
        }

        /**
         * Checks whether a JavaScript callback belongs to the current download session.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return true when the callback belongs to the active download
         */
        private boolean isActiveDownload(String downloadId) {
            return activeDownloadSession != null && activeDownloadSession.downloadId.equals(downloadId);
        }

        /**
         * Shows a download error from the UI thread.
         *
         * @param message message displayed over the WebView
         */
        private void showDownloadError(String message) {
            runOnUiThread(() -> showError(message));
        }
    }

    /**
     * Mutable state for one in-progress Blob transfer from JavaScript to Android.
     */
    private static final class DownloadSession {
        private final String downloadId;
        private final String fileName;
        private final String mimeType;
        private final long totalBytes;
        private final File file;
        private final FileOutputStream outputStream;
        private int nextChunkIndex;
        private long receivedBytes;

        private DownloadSession(
            String downloadId,
            String fileName,
            String mimeType,
            long totalBytes,
            File file,
            FileOutputStream outputStream
        ) {
            this.downloadId = downloadId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.totalBytes = totalBytes;
            this.file = file;
            this.outputStream = outputStream;
        }
    }

    /**
     * Completed temporary download waiting for the Android save dialog result.
     */
    private static final class PendingDownload {
        private final String fileName;
        private final String mimeType;
        private final File file;

        private PendingDownload(String fileName, String mimeType, File file) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.file = file;
        }
    }
}
