package com.technotramp.unexpectedtracks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;
import android.view.View;
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
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main Android entry point for the single-album viewer.
 *
 * <p>The activity starts the local gateway proxy, configures a locked-down
 * WebView, and loads the fixed RPlayer album through the proxy URL.</p>
 */
public final class MainActivity extends Activity {
    private static final String LOG_TAG = "RPlayerViewer";
    private static final int CREATE_DOWNLOAD_REQUEST_CODE = 1001;
    private static final int CREATE_NATIVE_ZIP_REQUEST_CODE = 1002;
    private static final int CREATE_NATIVE_FOLDER_REQUEST_CODE = 1003;
    private static final int MEDIA_NOTIFICATION_ID = 2001;
    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final long VIEWER_RETRY_DELAY_MS = 5000;
    private static final long DIRECT_RPLAYER_STARTUP_TIMEOUT_MS = 15000;
    private static final long NATIVE_ZIP_RETRY_DELAY_MS = 5000;
    private static final String PREFERENCES_NAME = "rplayer_gateway_viewer";
    private static final String PREF_RPLAYER_INITIALIZED_ONCE = "rplayer_initialized_once";
    private static final String MEDIA_NOTIFICATION_CHANNEL_ID = "music_player";
    private static final String DEFAULT_MEDIA_TITLE = BuildConfig.DEFAULT_MEDIA_TITLE;
    private static final String DEFAULT_MEDIA_ARTIST = BuildConfig.DEFAULT_MEDIA_ARTIST;
    private static final String DEFAULT_MEDIA_ALBUM = BuildConfig.DEFAULT_MEDIA_ALBUM;
    private static final String ACTION_MEDIA_PREVIOUS = BuildConfig.APPLICATION_ID + ".action.MEDIA_PREVIOUS";
    private static final String ACTION_MEDIA_PLAY_PAUSE = BuildConfig.APPLICATION_ID + ".action.MEDIA_PLAY_PAUSE";
    private static final String ACTION_MEDIA_NEXT = BuildConfig.APPLICATION_ID + ".action.MEDIA_NEXT";
    private static final String EXTRA_MEDIA_ACTION_TOKEN = BuildConfig.APPLICATION_ID + ".extra.MEDIA_ACTION_TOKEN";
    private static final int MEDIA_PREVIOUS_REQUEST_CODE = 2002;
    private static final int MEDIA_PLAY_PAUSE_REQUEST_CODE = 2003;
    private static final int MEDIA_NEXT_REQUEST_CODE = 2004;
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
    private PendingNativeZip pendingNativeZip;
    private PendingNativeFolder pendingNativeFolder;
    private MediaSession mediaSession;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService nativeZipExecutor = Executors.newSingleThreadExecutor();
    private final String mediaActionToken = UUID.randomUUID().toString();
    private String downloadBridgeScriptSource;
    private String mediaSessionBridgeScriptSource;
    private String displayModeBridgeScriptSource;
    private boolean fullscreenDisplayMode;
    private String currentMediaTitle = DEFAULT_MEDIA_TITLE;
    private String currentMediaArtist = DEFAULT_MEDIA_ARTIST;
    private String currentMediaAlbum = DEFAULT_MEDIA_ALBUM;
    private String currentMediaArtworkUrl = "";
    private String localProxyAlbumRootUrl = "";
    private Bitmap currentMediaArtwork;
    private long currentMediaPositionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    private long currentMediaDurationMs = -1;
    private int currentPlaybackState = PlaybackState.STATE_NONE;
    private boolean playbackForegroundServiceActive;
    private boolean mainFrameLoadFailed;
    private boolean viewerRetryPending;
    private boolean directRPlayerStartupActive;
    private Toast missingExternalAppToast;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable directRPlayerStartupFallbackRunnable = new Runnable() {
        /**
         * Falls back to the boot loader when direct RPlayer startup does not initialize in time.
         */
        @Override
        public void run() {
            fallbackToBootLoaderAfterDirectStartupTimeout();
        }
    };

    private final Runnable viewerRetryRunnable = new Runnable() {
        /**
         * Retries the current viewer load after a retryable startup failure.
         */
        @Override
        public void run() {
            viewerRetryPending = false;

            if (webView == null) {
                return;
            }

            Log.i(LOG_TAG, "Retrying viewer load after temporary gateway failure.");
            webView.reload();
        }
    };

    /**
     * Initializes the activity, starts the local proxy, and loads the viewer URL.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSafeDisplayArea();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createLayout();
        createMediaNotificationChannel();
        createMediaSession();

        handleMediaControlIntent(getIntent());

        try {
            proxyServer = new GatewayProxyServer(mediaSessionBridgeScript(), new File(getFilesDir(), "rplayer-ipfs-cache"));
            proxyServer.start();
            localProxyAlbumRootUrl = proxyServer.albumRootUrl();
            configureWebView();
            loadInitialViewerUrl();
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Proxy server could not be started.", exception);
            showError("RPlayer Gateway Viewer could not start the local proxy.");
        }
    }

    /**
     * Handles media control intents delivered to an already running Activity.
     *
     * @param intent intent received from Android system UI
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMediaControlIntent(intent);
    }

    /**
     * Handles the Android save dialog result for a generated RPlayer ZIP file.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CREATE_NATIVE_ZIP_REQUEST_CODE) {
            handleNativeZipDestinationResult(resultCode, data);
            return;
        }

        if (requestCode == CREATE_NATIVE_FOLDER_REQUEST_CODE) {
            handleNativeFolderDestinationResult(resultCode, data);
            return;
        }

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
     * Sends the viewer to the background when Android Back is pressed.
     *
     * <p>This mirrors the Home button behavior so playback can continue and the
     * activity is not destroyed accidentally.</p>
     */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    /**
     * Releases WebView, proxy, and temporary download resources when the activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(viewerRetryRunnable);
        closeActiveDownloadSession();
        cancelMissingExternalAppToast();
        stopPlaybackForegroundService();
        cancelMediaNotification();

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

        artworkExecutor.shutdownNow();
        nativeZipExecutor.shutdownNow();

        super.onDestroy();
    }

    /**
     * Keeps the viewer inside the system safe area on devices with cutouts or rounded corners.
     */
    private void configureSafeDisplayArea() {
        fullscreenDisplayMode = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0xff000000);
        getWindow().setNavigationBarColor(0xff000000);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setDisplayCutoutMode(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
    }

    /**
     * Lets the viewer use the whole display after the user taps RPlayer fullscreen.
     */
    private void configureFullscreenDisplayArea() {
        fullscreenDisplayMode = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(0xff000000);
        getWindow().setNavigationBarColor(0xff000000);
        setDisplayCutoutMode(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    /**
     * Applies display cutout handling when the Android version supports it.
     *
     * @param cutoutMode Android display cutout mode
     */
    private void setDisplayCutoutMode(int cutoutMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.layoutInDisplayCutoutMode = cutoutMode;
        getWindow().setAttributes(layoutParams);
    }

    /**
     * Toggles between the safe default display mode and user-requested fullscreen.
     */
    private void toggleDisplayMode() {
        if (fullscreenDisplayMode) {
            configureSafeDisplayArea();
            return;
        }

        configureFullscreenDisplayArea();
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

        webView.addJavascriptInterface(new BootBridge(), "RPlayerGatewayBootNative");
        webView.addJavascriptInterface(new DownloadBridge(), "RPlayerGatewayDownloads");
        webView.addJavascriptInterface(new MediaSessionBridge(), "RPlayerGatewayMediaSessionNative");
        webView.addJavascriptInterface(new DisplayModeBridge(), "RPlayerGatewayDisplayModeNative");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            /**
             * Keeps album navigation inside WebView and opens user-selected web links externally.
             *
             * @param view WebView receiving the navigation request.
             * @param request requested navigation target.
             * @return true when the app handled or blocked the navigation.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();

                if (isLocalProxyAlbumUri(uri)) {
                    return false;
                }

                if ((isInternalWebViewUri(uri) || isLocalProxyInternalUri(uri)) && !request.isForMainFrame()) {
                    return false;
                }

                if (!request.isForMainFrame()) {
                    Log.w(LOG_TAG, "Blocked external subresource request: " + uri);
                    return true;
                }

                return openExternalWebLink(uri);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();

                if (isLocalProxyAlbumUri(uri)
                    || ((isInternalWebViewUri(uri) || isLocalProxyInternalUri(uri)) && !request.isForMainFrame())
                ) {
                    return super.shouldInterceptRequest(view, request);
                }

                Log.w(
                    LOG_TAG,
                    "Blocked external WebView request: "
                        + uri
                        + ", mainFrame="
                        + request.isForMainFrame()
                );
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

                if (isRetryableViewerFailure(request)) {
                    scheduleViewerRetry("WebView resource error: " + error.getDescription());
                }
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

                if (isRetryableViewerFailure(request) && errorResponse.getStatusCode() >= 500) {
                    scheduleViewerRetry("WebView HTTP error: " + errorResponse.getStatusCode());
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(LOG_TAG, "WebView page started: " + url);

                if (isLocalProxyAlbumUri(Uri.parse(url)) && !viewerRetryPending) {
                    mainFrameLoadFailed = false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(LOG_TAG, "WebView page finished: " + url);

                if (isLocalProxyAlbumUri(Uri.parse(url)) && !mainFrameLoadFailed) {
                    cancelViewerRetry();
                }

                if (mainFrameLoadFailed) {
                    return;
                }

                injectDownloadBridge();
                injectMediaSessionBridge();
                injectDisplayModeBridge();
            }
        });
    }

    /**
     * Checks whether a URI belongs to WebView's internal schemes.
     *
     * @param uri URI requested by WebView.
     * @return true for internal data, about, and blob resources.
     */
    private boolean isInternalWebViewUri(Uri uri) {
        String scheme = uri.getScheme();
        return "data".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme);
    }

    /**
     * Loads either the full boot loader or the direct RPlayer document after a previous success.
     */
    private void loadInitialViewerUrl() {
        if (wasRPlayerInitializedOnce()) {
            directRPlayerStartupActive = true;
            mainHandler.postDelayed(directRPlayerStartupFallbackRunnable, DIRECT_RPLAYER_STARTUP_TIMEOUT_MS);
            Log.i(LOG_TAG, "Starting RPlayer directly after previous successful initialization.");
            webView.loadUrl(proxyServer.rplayerUrl());
            return;
        }

        directRPlayerStartupActive = false;
        Log.i(LOG_TAG, "Starting RPlayer through boot loader.");
        webView.loadUrl(proxyServer.viewerUrl());
    }

    /**
     * Checks whether RPlayer has already initialized successfully with current app data.
     *
     * @return true when the direct startup shortcut may be attempted
     */
    private boolean wasRPlayerInitializedOnce() {
        return viewerPreferences().getBoolean(PREF_RPLAYER_INITIALIZED_ONCE, false);
    }

    /**
     * Stores the successful RPlayer initialization shortcut flag.
     */
    private void markRPlayerInitializedOnce() {
        mainHandler.removeCallbacks(directRPlayerStartupFallbackRunnable);
        directRPlayerStartupActive = false;
        if (!wasRPlayerInitializedOnce()) {
            viewerPreferences().edit().putBoolean(PREF_RPLAYER_INITIALIZED_ONCE, true).apply();
            Log.i(LOG_TAG, "Direct RPlayer startup enabled after successful initialization.");
            return;
        }

        Log.i(LOG_TAG, "RPlayer direct startup confirmed.");
    }

    /**
     * Falls back to the boot loader when direct startup does not reach RPlayer initialization.
     */
    private void fallbackToBootLoaderAfterDirectStartupTimeout() {
        if (!directRPlayerStartupActive || webView == null || proxyServer == null) {
            return;
        }

        directRPlayerStartupActive = false;
        viewerPreferences().edit().remove(PREF_RPLAYER_INITIALIZED_ONCE).apply();
        Log.w(LOG_TAG, "Direct RPlayer startup timed out. Falling back to boot loader.");
        webView.loadUrl(proxyServer.viewerUrl());
    }

    /**
     * Gets app-private viewer preferences cleared with Android app data.
     *
     * @return SharedPreferences for startup behavior
     */
    private SharedPreferences viewerPreferences() {
        return getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
    }

    /**
     * Checks whether a URI points to the exact local proxy album root.
     *
     * @param uri URI requested by WebView.
     * @return true when the URI belongs to the current local proxy album URL.
     */
    private boolean isLocalProxyAlbumUri(Uri uri) {
        if (uri == null || localProxyAlbumRootUrl.isEmpty()) {
            return false;
        }

        String uriText = uri.toString();
        return uriText.equals(localProxyAlbumRootUrl.substring(0, localProxyAlbumRootUrl.length() - 1))
            || uriText.startsWith(localProxyAlbumRootUrl);
    }


    /**
     * Checks whether a URI points to a private local proxy helper endpoint.
     *
     * @param uri URI requested by WebView.
     * @return true when the URI belongs to the current local proxy helper namespace.
     */
    private boolean isLocalProxyInternalUri(Uri uri) {
        if (uri == null || localProxyAlbumRootUrl.isEmpty()) {
            return false;
        }

        String localProxyOrigin = localProxyAlbumRootUrl.substring(0, localProxyAlbumRootUrl.indexOf("/ipfs/"));
        return uri.toString().startsWith(localProxyOrigin + "/__rplayer_gateway/");
    }


    /**
     * Checks whether a failed WebView request is the main local viewer page.
     *
     * @param request failed WebView request.
     * @return true when the failure should keep retrying instead of becoming final.
     */
    private boolean isRetryableViewerFailure(WebResourceRequest request) {
        return request != null && request.isForMainFrame() && isLocalProxyAlbumUri(request.getUrl());
    }

    /**
     * Shows a waiting state and schedules a modest retry of the viewer page.
     *
     * @param reason diagnostic reason written to logcat.
     */
    private void scheduleViewerRetry(String reason) {
        mainFrameLoadFailed = true;
        errorView.setText("Waiting for IPFS gateway connection...\nThe viewer will continue automatically.");
        errorView.setVisibility(TextView.VISIBLE);

        if (viewerRetryPending) {
            return;
        }

        viewerRetryPending = true;
        Log.i(LOG_TAG, "Viewer load will retry: " + reason);
        mainHandler.postDelayed(viewerRetryRunnable, VIEWER_RETRY_DELAY_MS);
    }

    /**
     * Clears the waiting state after the viewer page loads successfully.
     */
    private void cancelViewerRetry() {
        viewerRetryPending = false;
        mainHandler.removeCallbacks(viewerRetryRunnable);
        errorView.setVisibility(TextView.GONE);
    }

    /**
     * Opens a user-selected web link in the Android system browser.
     *
     * @param uri external URI requested from the RPlayer page.
     * @return true because external navigation is handled outside WebView.
     */
    private boolean openExternalWebLink(Uri uri) {
        String scheme = uri.getScheme();
        Intent intent;

        if ("http".equals(scheme) || "https".equals(scheme)) {
            intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
        } else if ("mailto".equals(scheme)) {
            intent = new Intent(Intent.ACTION_SENDTO, uri);
        } else {
            Log.w(LOG_TAG, "Blocked external link with unsupported scheme: " + uri);
            return true;
        }

        try {
            startActivity(intent);
            Log.i(LOG_TAG, "Opened external link outside WebView: " + uri);
        } catch (ActivityNotFoundException exception) {
            Log.w(LOG_TAG, "No external app is available for link: " + uri, exception);
            showMissingExternalAppMessage(scheme);
        }

        return true;
    }

    /**
     * Tells the user when Android has no app for an allowed external link type.
     *
     * @param scheme URI scheme that could not be opened by Android.
     */
    private void showMissingExternalAppMessage(String scheme) {
        String message = "mailto".equals(scheme)
            ? "No e-mail app is available on this device."
            : "No app is available to open this link.";

        if (missingExternalAppToast != null) {
            missingExternalAppToast.cancel();
        }

        missingExternalAppToast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        missingExternalAppToast.show();
    }

    /**
     * Cancels a pending missing-app message when the Activity is going away.
     */
    private void cancelMissingExternalAppToast() {
        if (missingExternalAppToast == null) {
            return;
        }

        missingExternalAppToast.cancel();
        missingExternalAppToast = null;
    }

    /**
     * Dispatches media actions received from Android notification buttons.
     *
     * @param intent Android intent received by the Activity
     */
    private void handleMediaControlIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!isMediaNotificationAction(action)) {
            return;
        }

        if (!isTrustedMediaActionIntent(intent)) {
            Log.w(LOG_TAG, "Ignored untrusted media notification action: " + action);
            return;
        }

        if (ACTION_MEDIA_PREVIOUS.equals(action)) {
            Log.i(LOG_TAG, "Media notification action: previous");
            dispatchBrowserMediaAction("previoustrack");
            return;
        }

        if (ACTION_MEDIA_PLAY_PAUSE.equals(action)) {
            Log.i(LOG_TAG, "Media notification action: play/pause");
            dispatchPlayPauseMediaAction();
            return;
        }

        if (ACTION_MEDIA_NEXT.equals(action)) {
            Log.i(LOG_TAG, "Media notification action: next");
            dispatchBrowserMediaAction("nexttrack");
        }
    }

    /**
     * Checks whether an Activity intent is one of the internal notification actions.
     *
     * @param action intent action to inspect
     * @return true when the action belongs to the media notification controls
     */
    private boolean isMediaNotificationAction(String action) {
        return ACTION_MEDIA_PREVIOUS.equals(action)
            || ACTION_MEDIA_PLAY_PAUSE.equals(action)
            || ACTION_MEDIA_NEXT.equals(action);
    }

    /**
     * Verifies that a media action came from this process' immutable PendingIntent.
     *
     * @param intent Activity intent carrying a media notification action
     * @return true when the private process token matches
     */
    private boolean isTrustedMediaActionIntent(Intent intent) {
        return mediaActionToken.equals(intent.getStringExtra(EXTRA_MEDIA_ACTION_TOKEN));
    }

    /**
     * Sends a play or pause action based on the last known Android playback state.
     */
    private void dispatchPlayPauseMediaAction() {
        if (currentPlaybackState == PlaybackState.STATE_PLAYING) {
            dispatchBrowserMediaAction("pause");
            return;
        }

        dispatchBrowserMediaAction("play");
    }

    /**
     * Creates the native Android MediaSession used by OS-level media controls.
     */
    private void createMediaSession() {
        mediaSession = new MediaSession(this, "RPlayerGatewayViewer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(createMediaSessionActivityIntent());
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
                currentMediaPositionMs = position;
                updateNativePlaybackState(currentPlaybackState);
            }
        });
        updateNativePlaybackState("none");
        mediaSession.setActive(true);
    }

    /**
     * Creates the Android notification channel used for OS media controls.
     */
    private void createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            MEDIA_NOTIFICATION_CHANNEL_ID,
            "Music playback",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows RPlayer playback controls.");
        channel.setShowBadge(false);
        channel.setSound(null, null);

        NotificationManager notificationManager = notificationManager();
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Creates the Activity intent opened from Android system media controls.
     *
     * @return pending intent pointing back to this viewer Activity
     */
    private PendingIntent createMediaSessionActivityIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
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
     * Injects the JavaScript bridge that connects RPlayer fullscreen icons to Android display modes.
     */
    private void injectDisplayModeBridge() {
        try {
            webView.evaluateJavascript(displayModeBridgeScript(), null);
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Display mode bridge script could not be loaded.", exception);
        }
    }

    /**
     * Loads the Display Mode JavaScript bridge from Android assets.
     *
     * @return JavaScript source executed inside the WebView
     * @throws IOException when the asset cannot be read
     */
    private String displayModeBridgeScript() throws IOException {
        if (displayModeBridgeScriptSource == null) {
            displayModeBridgeScriptSource = readAssetText("display-mode-bridge.js");
        }

        return displayModeBridgeScriptSource;
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
     * Sends native ZIP progress back to the WebView download bridge.
     *
     * @param completedEntries number of finished ZIP entries
     * @param expectedEntries expected number of ZIP entries
     * @param currentEntry current ZIP entry path or empty text
     */
    private void dispatchNativeZipProgress(int completedEntries, int expectedEntries, String currentEntry) {
        if (webView == null) {
            return;
        }

        String script = "window.RPlayerGatewayNativeDownloadProgress && "
            + "window.RPlayerGatewayNativeDownloadProgress.update("
            + completedEntries
            + ","
            + expectedEntries
            + ","
            + jsString(currentEntry)
            + ");";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    /**
     * Updates Android's native MediaSession metadata.
     *
     * @param title current track title
     * @param artist album artist or composer
     * @param album album name
     * @param artworkUrl artwork URL resolved inside the WebView
     */
    private void updateNativeMetadata(String title, String artist, String album, String artworkUrl) {
        if (mediaSession == null) {
            return;
        }

        currentMediaTitle = mediaTextOrDefault(title, DEFAULT_MEDIA_TITLE);
        currentMediaArtist = mediaTextOrDefault(artist, DEFAULT_MEDIA_ARTIST);
        currentMediaAlbum = mediaTextOrDefault(album, DEFAULT_MEDIA_ALBUM);
        updateCurrentArtworkUrl(artworkUrl);
        applyNativeMediaMetadata();
        mediaSession.setActive(true);
        updateMediaNotification();
        loadArtworkAsync(currentMediaArtworkUrl);
    }

    /**
     * Applies the latest known metadata values to Android's native MediaSession.
     */
    private void applyNativeMediaMetadata() {
        if (mediaSession == null) {
            return;
        }

        MediaMetadata.Builder builder = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentMediaTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentMediaArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, currentMediaAlbum);

        if (currentMediaDurationMs > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, currentMediaDurationMs);
        }

        if (currentMediaArtwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, currentMediaArtwork);
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentMediaArtwork);
        }

        mediaSession.setMetadata(builder.build());
    }

    /**
     * Stores a new artwork URL and clears stale artwork when the track changes.
     *
     * @param artworkUrl artwork URL resolved inside the WebView
     */
    private void updateCurrentArtworkUrl(String artworkUrl) {
        String normalizedArtworkUrl = mediaTextOrDefault(artworkUrl, "");

        if (normalizedArtworkUrl.equals(currentMediaArtworkUrl)) {
            return;
        }

        currentMediaArtworkUrl = normalizedArtworkUrl;
        currentMediaArtwork = null;
    }

    /**
     * Loads track artwork from the local proxy without blocking the UI thread.
     *
     * @param artworkUrl artwork URL resolved inside the WebView
     */
    private void loadArtworkAsync(String artworkUrl) {
        if (!isLocalArtworkUrl(artworkUrl)) {
            return;
        }

        if (artworkExecutor.isShutdown()) {
            return;
        }

        artworkExecutor.execute(() -> {
            Bitmap bitmap = loadArtworkBitmap(artworkUrl);
            if (bitmap == null) {
                return;
            }

            runOnUiThread(() -> {
                if (!artworkUrl.equals(currentMediaArtworkUrl)) {
                    return;
                }

                currentMediaArtwork = bitmap;
                applyNativeMediaMetadata();
                updateMediaNotification();
                Log.i(LOG_TAG, "Media artwork updated: " + artworkUrl);
            });
        });
    }

    /**
     * Loads and decodes one artwork bitmap.
     *
     * @param artworkUrl local proxy artwork URL
     * @return decoded bitmap or null when loading fails
     */
    private Bitmap loadArtworkBitmap(String artworkUrl) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(artworkUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);

            try (InputStream inputStream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(inputStream);
            }
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Media artwork could not be loaded: " + artworkUrl, exception);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Checks whether artwork can be loaded through the local proxy.
     *
     * @param artworkUrl artwork URL resolved inside the WebView
     * @return true when the URL points to the local proxy
     */
    private boolean isLocalArtworkUrl(String artworkUrl) {
        return artworkUrl != null
            && isLocalProxyAlbumUrl(artworkUrl);
    }

    /**
     * Checks whether a source URL belongs to the current local proxy album root.
     *
     * @param sourceUrl source URL requested by a native helper
     * @return true when the URL points to the local proxy album root
     */
    private boolean isLocalProxyAlbumUrl(String sourceUrl) {
        return sourceUrl != null
            && !localProxyAlbumRootUrl.isEmpty()
            && sourceUrl.startsWith(localProxyAlbumRootUrl);
    }

    /**
     * Updates Android's native MediaSession playback state.
     *
     * @param state browser Media Session playback state
     */
    private void updateNativePlaybackState(String state) {
        updateNativePlaybackState(playbackStateFromWeb(state));
    }

    /**
     * Updates Android's native MediaSession playback state.
     *
     * @param playbackStateValue Android PlaybackState constant
     */
    private void updateNativePlaybackState(int playbackStateValue) {
        updateNativePlaybackState(playbackStateValue, true);
    }

    /**
     * Updates Android's native MediaSession playback state.
     *
     * @param playbackStateValue Android PlaybackState constant
     * @param refreshNotification true when the visible media notification should be rebuilt
     */
    private void updateNativePlaybackState(int playbackStateValue, boolean refreshNotification) {
        if (mediaSession == null) {
            return;
        }

        float playbackSpeed = playbackStateValue == PlaybackState.STATE_PLAYING ? 1.0f : 0.0f;
        PlaybackState playbackState = new PlaybackState.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(playbackStateValue, currentMediaPositionMs, playbackSpeed)
            .build();
        mediaSession.setPlaybackState(playbackState);
        currentPlaybackState = playbackState.getState();
        syncPlaybackForegroundService();

        if (refreshNotification) {
            updateMediaNotification();
        }
    }

    /**
     * Updates Android's native MediaSession progress for the current RPlayer track.
     *
     * @param positionMs current track position in milliseconds
     * @param durationMs current track duration in milliseconds
     */
    private void updateNativeProgress(long positionMs, long durationMs) {
        currentMediaPositionMs = Math.max(0, positionMs);
        currentMediaDurationMs = Math.max(0, durationMs);
        applyNativeMediaMetadata();
        updateNativePlaybackState(currentPlaybackState, false);
    }

    /**
     * Shows or updates the Android media notification linked to the MediaSession.
     */
    private void updateMediaNotification() {
        if (mediaSession == null) {
            return;
        }

        if (currentPlaybackState == PlaybackState.STATE_NONE) {
            cancelMediaNotification();
            return;
        }

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
            .setMediaSession(mediaSession.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2);

        Notification.Builder builder = notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentMediaTitle)
            .setContentText(currentMediaArtist)
            .setSubText(currentMediaAlbum)
            .setContentIntent(createMediaSessionActivityIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(currentPlaybackState == PlaybackState.STATE_PLAYING)
            .addAction(android.R.drawable.ic_media_previous, "Previous", createMediaActionIntent(ACTION_MEDIA_PREVIOUS, MEDIA_PREVIOUS_REQUEST_CODE))
            .addAction(playPauseIcon(), playPauseTitle(), createMediaActionIntent(ACTION_MEDIA_PLAY_PAUSE, MEDIA_PLAY_PAUSE_REQUEST_CODE))
            .addAction(android.R.drawable.ic_media_next, "Next", createMediaActionIntent(ACTION_MEDIA_NEXT, MEDIA_NEXT_REQUEST_CODE))
            .setStyle(mediaStyle);

        if (currentMediaArtwork != null) {
            builder.setLargeIcon(currentMediaArtwork);
        }

        NotificationManager notificationManager = notificationManager();
        if (notificationManager != null) {
            notificationManager.notify(MEDIA_NOTIFICATION_ID, builder.build());
            Log.i(LOG_TAG, "Media notification updated: " + currentMediaTitle + ", state=" + currentPlaybackState);
        }
    }

    /**
     * Creates a PendingIntent used by Android media notification actions.
     *
     * @param action internal action name handled by this Activity
     * @param requestCode stable request code for the action button
     * @return pending intent routed back to this Activity
     */
    private PendingIntent createMediaActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_MEDIA_ACTION_TOKEN, mediaActionToken);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /**
     * Returns the play/pause icon matching the current playback state.
     *
     * @return Android drawable resource ID
     */
    private int playPauseIcon() {
        if (currentPlaybackState == PlaybackState.STATE_PLAYING) {
            return android.R.drawable.ic_media_pause;
        }

        return android.R.drawable.ic_media_play;
    }

    /**
     * Returns the play/pause action title matching the current playback state.
     *
     * @return action title shown by Android system UI
     */
    private String playPauseTitle() {
        if (currentPlaybackState == PlaybackState.STATE_PLAYING) {
            return "Pause";
        }

        return "Play";
    }

    /**
     * Keeps the foreground playback service aligned with the current playback state.
     */
    private void syncPlaybackForegroundService() {
        if (currentPlaybackState == PlaybackState.STATE_NONE) {
            stopPlaybackForegroundService();
            return;
        }

        startPlaybackForegroundService();
    }

    /**
     * Starts the foreground playback service while RPlayer has an active media state.
     */
    private void startPlaybackForegroundService() {
        if (playbackForegroundServiceActive) {
            return;
        }

        playbackForegroundServiceActive = true;
        Intent intent = new Intent(this, PlaybackForegroundService.class);
        intent.setAction(PlaybackForegroundService.ACTION_START);

        if (usesForegroundServiceStartApi()) {
            startForegroundService(intent);
            return;
        }

        startService(intent);
    }

    /**
     * Checks whether Android requires the dedicated foreground service start API.
     *
     * Returns true when startForegroundService must be used.
     */
    private boolean usesForegroundServiceStartApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * Stops the foreground playback service when active playback ends.
     */
    private void stopPlaybackForegroundService() {
        if (!playbackForegroundServiceActive) {
            return;
        }

        playbackForegroundServiceActive = false;
        Intent intent = new Intent(this, PlaybackForegroundService.class);
        intent.setAction(PlaybackForegroundService.ACTION_STOP);
        startService(intent);
    }

    /**
     * Checks whether Android requires notification channels.
     *
     * Returns true when notifications must be assigned to a channel.
     */
    private boolean usesNotificationChannels() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * Creates a Notification.Builder compatible with the current Android version.
     *
     * @return notification builder for media playback
     */
    private Notification.Builder notificationBuilder() {
        if (usesNotificationChannels()) {
            return new Notification.Builder(this, MEDIA_NOTIFICATION_CHANNEL_ID);
        }

        return new Notification.Builder(this);
    }

    /**
     * Cancels the media notification when playback is no longer active.
     */
    private void cancelMediaNotification() {
        NotificationManager notificationManager = notificationManager();
        if (notificationManager != null) {
            notificationManager.cancel(MEDIA_NOTIFICATION_ID);
        }
    }

    /**
     * Returns Android's notification service.
     *
     * @return notification manager or null when the service is unavailable
     */
    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    /**
     * Normalizes metadata text before it is shown in Android system UI.
     *
     * @param value metadata value received from the browser
     * @param defaultValue fallback text used when the value is blank
     * @return non-empty metadata text
     */
    private static String mediaTextOrDefault(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value.trim();
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
        byte[] body = "External subresources are blocked by RPlayer Gateway Viewer.".getBytes(StandardCharsets.UTF_8);
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
     * Lets the user choose between a ZIP file and a physical folder export.
     *
     * @param downloadId JavaScript-generated identifier for the pending export session
     * @param zipFileName suggested ZIP filename
     * @param folderName suggested album folder name
     */
    private void openNativeExportChoiceDialog(String downloadId, String zipFileName, String folderName) {
        new AlertDialog.Builder(this)
            .setTitle("Export album")
            .setItems(new CharSequence[] {"Save ZIP file", "Save as folder"}, (dialog, which) -> {
                if (which == 0) {
                    openNativeZipSaveDialog(downloadId, zipFileName);
                    return;
                }

                openNativeFolderSaveDialog(downloadId, folderName);
            })
            .setOnCancelListener(dialog -> notifyNativeExportDestinationCanceled(downloadId))
            .show();
    }

    /**
     * Opens Android's system save dialog before the native ZIP is written.
     *
     * @param downloadId JavaScript-generated identifier for the pending ZIP session
     * @param fileName suggested ZIP filename
     */
    private void openNativeZipSaveDialog(String downloadId, String fileName) {
        pendingNativeZip = new PendingNativeZip(downloadId, sanitizeFileName(fileName));

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, pendingNativeZip.fileName);

        try {
            startActivityForResult(intent, CREATE_NATIVE_ZIP_REQUEST_CODE);
        } catch (ActivityNotFoundException exception) {
            Log.e(LOG_TAG, "Android native ZIP save dialog is not available.", exception);
            notifyNativeZipDestinationCanceled(downloadId);
            pendingNativeZip = null;
            showError("Android save dialog is not available.");
        }
    }

    /**
     * Starts native ZIP streaming after Android returns the selected output URI.
     *
     * @param resultCode Android activity result code
     * @param data Android activity result data containing the selected URI
     */
    private void handleNativeZipDestinationResult(int resultCode, Intent data) {
        PendingNativeZip pendingZip = pendingNativeZip;
        pendingNativeZip = null;

        if (pendingZip == null) {
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            notifyNativeZipDestinationCanceled(pendingZip.downloadId);
            return;
        }

        try {
            OutputStream outputStream = getContentResolver().openOutputStream(data.getData());
            if (outputStream == null) {
                throw new IOException("Android returned no output stream for the selected native ZIP destination.");
            }

            ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
            // RPlayer download packages already contain compressed media assets.
            zipOutputStream.setLevel(Deflater.NO_COMPRESSION);

            synchronized (this) {
                closeActiveDownloadSession();
                activeDownloadSession = new DownloadSession(
                    pendingZip.downloadId,
                    pendingZip.fileName,
                    "application/zip",
                    0,
                    null,
                    outputStream,
                    zipOutputStream
                );
            }

            Log.i(LOG_TAG, "Native ZIP direct download started: " + pendingZip.fileName);
            notifyNativeZipDestinationReady(pendingZip.downloadId);
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Native ZIP destination could not be opened.", exception);
            notifyNativeZipDestinationCanceled(pendingZip.downloadId);
            showError("RPlayer Gateway Viewer could not open the selected ZIP file.");
        }
    }

    /**
     * Opens Android's system folder picker before the native folder export is written.
     *
     * @param downloadId JavaScript-generated identifier for the pending folder session
     * @param folderName suggested album folder name
     */
    private void openNativeFolderSaveDialog(String downloadId, String folderName) {
        pendingNativeFolder = new PendingNativeFolder(downloadId, sanitizeFileName(folderName));

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, CREATE_NATIVE_FOLDER_REQUEST_CODE);
        } catch (ActivityNotFoundException exception) {
            Log.e(LOG_TAG, "Android folder picker is not available.", exception);
            notifyNativeFolderDestinationCanceled(downloadId);
            pendingNativeFolder = null;
            showError("Android folder picker is not available.");
        }
    }

    /**
     * Starts native folder export after Android returns the selected tree URI.
     *
     * @param resultCode Android activity result code
     * @param data Android activity result data containing the selected tree URI
     */
    private void handleNativeFolderDestinationResult(int resultCode, Intent data) {
        PendingNativeFolder pendingFolder = pendingNativeFolder;
        pendingNativeFolder = null;

        if (pendingFolder == null) {
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            notifyNativeFolderDestinationCanceled(pendingFolder.downloadId);
            return;
        }

        Uri treeUri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException exception) {
            Log.w(LOG_TAG, "Persistent folder permission could not be kept.", exception);
        }

        try {
            Uri rootFolderUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            );

            synchronized (this) {
                closeActiveDownloadSession();
                activeDownloadSession = new DownloadSession(
                    pendingFolder.downloadId,
                    pendingFolder.folderName,
                    "application/octet-stream",
                    0,
                    null,
                    null,
                    null,
                    rootFolderUri
                );
            }

            Log.i(LOG_TAG, "Native folder export destination selected for: " + pendingFolder.folderName);
            notifyNativeFolderDestinationReady(pendingFolder.downloadId);
        } catch (IllegalArgumentException exception) {
            Log.e(LOG_TAG, "Native folder destination could not be opened.", exception);
            notifyNativeFolderDestinationCanceled(pendingFolder.downloadId);
            showError("RPlayer Gateway Viewer could not open the selected folder.");
        }
    }

    /**
     * Notifies JavaScript that the native ZIP destination is ready for queued entries.
     *
     * @param downloadId JavaScript-generated identifier for the pending ZIP session
     */
    private void notifyNativeZipDestinationReady(String downloadId) {
        evaluateDownloadScript("window.RPlayerGatewayNativeZipDestinationReady && window.RPlayerGatewayNativeZipDestinationReady("
            + JSONObject.quote(downloadId)
            + ");");
    }

    /**
     * Notifies JavaScript that the native ZIP destination was canceled or failed.
     *
     * @param downloadId JavaScript-generated identifier for the pending ZIP session
     */
    private void notifyNativeZipDestinationCanceled(String downloadId) {
        notifyNativeExportDestinationCanceled(downloadId);
    }

    /**
     * Notifies JavaScript that the native folder destination is ready for queued entries.
     *
     * @param downloadId JavaScript-generated identifier for the pending folder session
     */
    private void notifyNativeFolderDestinationReady(String downloadId) {
        evaluateDownloadScript("window.RPlayerGatewayNativeFolderDestinationReady && window.RPlayerGatewayNativeFolderDestinationReady("
            + JSONObject.quote(downloadId)
            + ");");
    }

    /**
     * Notifies JavaScript that the native folder destination was canceled or failed.
     *
     * @param downloadId JavaScript-generated identifier for the pending folder session
     */
    private void notifyNativeFolderDestinationCanceled(String downloadId) {
        notifyNativeExportDestinationCanceled(downloadId);
    }

    /**
     * Notifies JavaScript that the native export destination was canceled or failed.
     *
     * @param downloadId JavaScript-generated identifier for the pending export session
     */
    private void notifyNativeExportDestinationCanceled(String downloadId) {
        evaluateDownloadScript("window.RPlayerGatewayNativeExportDestinationCanceled && window.RPlayerGatewayNativeExportDestinationCanceled("
            + JSONObject.quote(downloadId)
            + ");");
    }

    /**
     * Runs a small JavaScript callback in the WebView when it is still available.
     *
     * @param script JavaScript source to evaluate
     */
    private void evaluateDownloadScript(String script) {
        if (webView == null) {
            return;
        }

        webView.evaluateJavascript(script, null);
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

        if (activeDownloadSession.zipOutputStream != null) {
            closeQuietly(activeDownloadSession.zipOutputStream);
        } else {
            closeQuietly(activeDownloadSession.outputStream);
        }
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
            return BuildConfig.DEFAULT_DOWNLOAD_FILE_NAME;
        }

        String cleanName = fileName.trim().replace('"', '_').replaceAll("[\\\\/:*?<>|]", "_");
        if (cleanName.isEmpty()) {
            return BuildConfig.DEFAULT_DOWNLOAD_FILE_NAME;
        }

        return cleanName;
    }

    /**
     * Sanitizes a RPlayer ZIP entry path before it is written by ZipOutputStream.
     *
     * @param path ZIP entry path requested by JavaScript
     * @return safe relative ZIP entry path
     */
    private static String sanitizeZipEntryPath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("ZIP entry path is empty.");
        }

        String normalizedPath = path.replace('\\', '/').trim();
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        String[] parts = normalizedPath.split("/");
        StringBuilder cleanPath = new StringBuilder();
        for (String part : parts) {
            String cleanPart = part.trim().replaceAll("[\\p{Cntrl}]", "_");
            if (cleanPart.isEmpty() || ".".equals(cleanPart)) {
                continue;
            }
            if ("..".equals(cleanPart)) {
                throw new IllegalArgumentException("ZIP entry path contains parent traversal.");
            }
            if (cleanPath.length() > 0) {
                cleanPath.append('/');
            }
            cleanPath.append(cleanPart);
        }

        if (cleanPath.length() == 0) {
            throw new IllegalArgumentException("ZIP entry path is empty.");
        }

        return cleanPath.toString();
    }

    /**
     * JavaScript interface that lets RPlayer toggle Android display modes.
     */
    private final class DisplayModeBridge {
        /**
         * Toggles between the safe default display mode and immersive fullscreen.
         */
        @JavascriptInterface
        public void toggleFullscreen() {
            runOnUiThread(MainActivity.this::toggleDisplayMode);
        }

        /**
         * Receives diagnostic messages from the JavaScript bridge.
         *
         * @param message diagnostic message
         */
        @JavascriptInterface
        public void log(String message) {
            Log.i(LOG_TAG, "Display mode bridge: " + message);
        }
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
            Log.i(LOG_TAG, "Media metadata: " + title + " / " + artist + " / " + album + " / " + artworkUrl);
            runOnUiThread(() -> updateNativeMetadata(title, artist, album, artworkUrl));
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
         * Receives current track progress calculated from RPlayer's own seeker.
         *
         * @param positionMs current track position in milliseconds
         * @param durationMs current track duration in milliseconds
         */
        @JavascriptInterface
        public void updateProgress(long positionMs, long durationMs) {
            runOnUiThread(() -> updateNativeProgress(positionMs, durationMs));
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
     * Receives RPlayer boot lifecycle events from patched immutable JavaScript.
     */
    private final class BootBridge {
        /**
         * Marks that RPlayer reached its real initialization phase.
         */
        @JavascriptInterface
        public void markRPlayerInitialized() {
            mainHandler.post(() -> markRPlayerInitializedOnce());
        }
    }

    /**
     * JavaScript interface that receives generated ZIP data from the WebView in chunks.
     */
    private final class DownloadBridge {
        /**
         * Opens the Android export choice before native ZIP or folder streaming starts.
         *
         * @param downloadId JavaScript-generated identifier for this export build
         * @param zipFileName suggested output ZIP filename
         * @param folderName suggested output folder name
         */
        @JavascriptInterface
        public void requestNativeExportDestination(String downloadId, String zipFileName, String folderName) {
            runOnUiThread(() -> openNativeExportChoiceDialog(downloadId, zipFileName, folderName));
        }

        /**
         * Receives diagnostic messages from the JavaScript download bridge.
         *
         * @param message diagnostic message
         */
        @JavascriptInterface
        public void log(String message) {
            Log.i(LOG_TAG, "Download bridge: " + message);
        }

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
         * Starts a native ZIP file assembled from individual RPlayer entries.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         */
        @JavascriptInterface
        public synchronized void beginNativeZip(String downloadId) {
            if (isActiveNativeZip(downloadId)) {
                Log.i(LOG_TAG, "Native ZIP direct stream confirmed.");
                return;
            }

            closeActiveDownloadSession();

            try {
                File file = File.createTempFile("rplayer-native-zip-", ".tmp", getCacheDir());
                FileOutputStream outputStream = new FileOutputStream(file);
                ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
                // RPlayer download packages already contain compressed media assets.
                zipOutputStream.setLevel(Deflater.NO_COMPRESSION);

                activeDownloadSession = new DownloadSession(
                    downloadId,
                    BuildConfig.DEFAULT_DOWNLOAD_FILE_NAME,
                    "application/zip",
                    0,
                    file,
                    outputStream,
                    zipOutputStream
                );

                Log.i(LOG_TAG, "Native ZIP download started.");
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Native ZIP file could not be created.", exception);
                showDownloadError("RPlayer Gateway Viewer could not create a native ZIP file.");
            }
        }

        /**
         * Stores the expected number of queued native ZIP entries for progress reporting.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param expectedEntries number of entries planned by the WebView bridge
         */
        @JavascriptInterface
        public synchronized void setNativeZipExpectedEntries(String downloadId, int expectedEntries) {
            if (!isActiveNativeExport(downloadId)) {
                return;
            }

            activeDownloadSession.expectedZipEntries = Math.max(0, expectedEntries);
            activeDownloadSession.completedZipEntries = 0;
            reportNativeZipProgress(activeDownloadSession, "");
        }

        /**
         * Adds a UTF-8 text file entry to the active native ZIP.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param path ZIP entry path requested by RPlayer
         * @param text UTF-8 text content
         */
        @JavascriptInterface
        public void addNativeZipTextEntry(String downloadId, String path, String text) {
            submitNativeZipTask(downloadId, "Native ZIP text entry could not be written.", session -> {
                beginNativeZipEntryInternal(session, path);
                byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
                session.zipOutputStream.write(data);
                session.receivedBytes += data.length;
                finishNativeZipEntryInternal(session);
                markNativeZipEntryFinished(session, path);
            });
        }

        /**
         * Streams a local proxy resource directly into one native ZIP entry.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param path ZIP entry path requested by RPlayer
         * @param sourceUrl local proxy source URL to stream from
         */
        @JavascriptInterface
        public void addNativeZipSourceEntry(String downloadId, String path, String sourceUrl) {
            if (!isLocalProxyAlbumUrl(sourceUrl)) {
                Log.w(LOG_TAG, "Blocked native ZIP source outside album proxy: " + sourceUrl);
                failDownload(downloadId, "Native ZIP source is outside the album proxy.");
                return;
            }

            submitNativeZipTask(downloadId, "Native ZIP source entry could not be written.", session -> {
                NativeZipSourceFile sourceFile = nativeZipSourceFile(session, path, sourceUrl);

                try {
                    beginNativeZipStoredEntryInternal(session, path, sourceFile.file);

                    try (InputStream inputStream = new FileInputStream(sourceFile.file)) {
                        byte[] buffer = new byte[COPY_BUFFER_SIZE];
                        int read;
                        while ((read = inputStream.read(buffer)) >= 0) {
                            session.zipOutputStream.write(buffer, 0, read);
                            session.receivedBytes += read;
                        }
                    }

                    finishNativeZipEntryInternal(session);
                    markNativeZipEntryFinished(session, path);
                    Log.i(LOG_TAG, "Native ZIP source entry finished: " + path + " <- " + sourceUrl);
                } finally {
                    if (sourceFile.temporary) {
                        deleteTempFile(sourceFile.file);
                    }
                }
            });
        }

        /**
         * Streams an MP3 file into one native ZIP entry with RPlayer ID3 metadata.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param path ZIP entry path requested by RPlayer
         * @param sourceUrl local proxy MP3 source URL to stream from
         * @param metadataJson JSON-encoded track metadata and optional image URLs
         */
        @JavascriptInterface
        public void addNativeZipTaggedMp3Entry(String downloadId, String path, String sourceUrl, String metadataJson) {
            if (!isLocalProxyAlbumUrl(sourceUrl)) {
                Log.w(LOG_TAG, "Blocked native ZIP MP3 source outside album proxy: " + sourceUrl);
                failDownload(downloadId, "Native ZIP MP3 source is outside the album proxy.");
                return;
            }

            JSONObject metadata;
            try {
                metadata = new JSONObject(metadataJson == null ? "{}" : metadataJson);
            } catch (JSONException exception) {
                Log.e(LOG_TAG, "Native ZIP MP3 metadata could not be parsed.", exception);
                failDownload(downloadId, "Native ZIP MP3 metadata could not be parsed.");
                return;
            }

            String coverImageUrl = metadata.optString("coverImageUrl", "");
            String iconImageUrl = metadata.optString("iconImageUrl", "");
            if (!isBlank(coverImageUrl) && !isLocalProxyAlbumUrl(coverImageUrl)) {
                Log.w(LOG_TAG, "Blocked native ZIP MP3 cover outside album proxy: " + coverImageUrl);
                failDownload(downloadId, "Native ZIP MP3 cover is outside the album proxy.");
                return;
            }

            if (!isBlank(iconImageUrl) && !isLocalProxyAlbumUrl(iconImageUrl)) {
                Log.w(LOG_TAG, "Blocked native ZIP MP3 icon outside album proxy: " + iconImageUrl);
                failDownload(downloadId, "Native ZIP MP3 icon is outside the album proxy.");
                return;
            }

            submitNativeZipTask(downloadId, "Native ZIP tagged MP3 entry could not be written.", session -> {
                File sourceFile = downloadNativeZipSourceToTempFile(session, path, sourceUrl);

                try {
                    byte[] coverImage = readOptionalNativeZipBytes(session, coverImageUrl, "metadata cover image");
                    byte[] iconImage = readOptionalNativeZipBytes(session, iconImageUrl, "metadata icon image");
                    byte[] id3Tag = Id3TagWriter.buildTag(metadata, coverImage, iconImage);

                    beginNativeZipEntryInternal(session, path);
                    session.zipOutputStream.write(id3Tag);
                    session.receivedBytes += id3Tag.length;

                    try (InputStream inputStream = new FileInputStream(sourceFile)) {
                        byte[] buffer = new byte[COPY_BUFFER_SIZE];
                        session.receivedBytes += Id3TagWriter.copyMp3WithoutExistingTag(
                            inputStream,
                            session.zipOutputStream,
                            buffer
                        );
                    }

                    finishNativeZipEntryInternal(session);
                    markNativeZipEntryFinished(session, path);
                    Log.i(LOG_TAG, "Native ZIP tagged MP3 entry finished: " + path + " <- " + sourceUrl);
                } finally {
                    deleteTempFile(sourceFile);
                }
            });
        }

        /**
         * Starts a native folder export assembled from individual RPlayer entries.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         */
        @JavascriptInterface
        public synchronized void beginNativeFolder(String downloadId) {
            if (isActiveNativeFolder(downloadId)) {
                Log.i(LOG_TAG, "Native folder export confirmed.");
            }
        }

        /**
         * Adds a UTF-8 text file to the active native folder export.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         * @param path relative file path requested by RPlayer
         * @param text UTF-8 text content
         */
        @JavascriptInterface
        public void addNativeFolderTextEntry(String downloadId, String path, String text) {
            submitNativeFolderTask(downloadId, "Native folder text file could not be written.", session -> {
                byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
                writeNativeFolderBytes(session, path, data);
                markNativeZipEntryFinished(session, path);
            });
        }

        /**
         * Writes a local proxy resource to the active native folder export.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         * @param path relative file path requested by RPlayer
         * @param sourceUrl local proxy source URL to copy from
         */
        @JavascriptInterface
        public void addNativeFolderSourceEntry(String downloadId, String path, String sourceUrl) {
            if (!isLocalProxyAlbumUrl(sourceUrl)) {
                Log.w(LOG_TAG, "Blocked native folder source outside album proxy: " + sourceUrl);
                failDownload(downloadId, "Native folder source is outside the album proxy.");
                return;
            }

            submitNativeFolderTask(downloadId, "Native folder source file could not be written.", session -> {
                NativeZipSourceFile sourceFile = nativeZipSourceFile(session, path, sourceUrl);

                try {
                    writeNativeFolderFile(session, path, sourceFile.file);
                    markNativeZipEntryFinished(session, path);
                    Log.i(LOG_TAG, "Native folder source file finished: " + path + " <- " + sourceUrl);
                } finally {
                    if (sourceFile.temporary) {
                        deleteTempFile(sourceFile.file);
                    }
                }
            });
        }

        /**
         * Writes an MP3 file with RPlayer ID3 metadata to the active native folder export.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         * @param path relative file path requested by RPlayer
         * @param sourceUrl local proxy MP3 source URL to copy from
         * @param metadataJson JSON-encoded track metadata and optional image URLs
         */
        @JavascriptInterface
        public void addNativeFolderTaggedMp3Entry(String downloadId, String path, String sourceUrl, String metadataJson) {
            if (!isLocalProxyAlbumUrl(sourceUrl)) {
                Log.w(LOG_TAG, "Blocked native folder MP3 source outside album proxy: " + sourceUrl);
                failDownload(downloadId, "Native folder MP3 source is outside the album proxy.");
                return;
            }

            JSONObject metadata;
            try {
                metadata = new JSONObject(metadataJson == null ? "{}" : metadataJson);
            } catch (JSONException exception) {
                Log.e(LOG_TAG, "Native folder MP3 metadata could not be parsed.", exception);
                failDownload(downloadId, "Native folder MP3 metadata could not be parsed.");
                return;
            }

            String coverImageUrl = metadata.optString("coverImageUrl", "");
            String iconImageUrl = metadata.optString("iconImageUrl", "");
            if (!isBlank(coverImageUrl) && !isLocalProxyAlbumUrl(coverImageUrl)) {
                Log.w(LOG_TAG, "Blocked native folder MP3 cover outside album proxy: " + coverImageUrl);
                failDownload(downloadId, "Native folder MP3 cover is outside the album proxy.");
                return;
            }

            if (!isBlank(iconImageUrl) && !isLocalProxyAlbumUrl(iconImageUrl)) {
                Log.w(LOG_TAG, "Blocked native folder MP3 icon outside album proxy: " + iconImageUrl);
                failDownload(downloadId, "Native folder MP3 icon is outside the album proxy.");
                return;
            }

            submitNativeFolderTask(downloadId, "Native folder tagged MP3 file could not be written.", session -> {
                File sourceFile = downloadNativeZipSourceToTempFile(session, path, sourceUrl);

                try {
                    byte[] coverImage = readOptionalNativeZipBytes(session, coverImageUrl, "metadata cover image");
                    byte[] iconImage = readOptionalNativeZipBytes(session, iconImageUrl, "metadata icon image");
                    byte[] id3Tag = Id3TagWriter.buildTag(metadata, coverImage, iconImage);

                    try (OutputStream outputStream = openNativeFolderOutputStream(session, path)) {
                        outputStream.write(id3Tag);
                        session.receivedBytes += id3Tag.length;

                        try (InputStream inputStream = new FileInputStream(sourceFile)) {
                            byte[] buffer = new byte[COPY_BUFFER_SIZE];
                            session.receivedBytes += Id3TagWriter.copyMp3WithoutExistingTag(
                                inputStream,
                                outputStream,
                                buffer
                            );
                        }
                    }

                    markNativeZipEntryFinished(session, path);
                    Log.i(LOG_TAG, "Native folder tagged MP3 file finished: " + path + " <- " + sourceUrl);
                } finally {
                    deleteTempFile(sourceFile);
                }
            });
        }

        /**
         * Finalizes the native folder export.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         * @param folderName suggested folder name from RPlayer
         */
        @JavascriptInterface
        public void finishNativeFolder(String downloadId, String folderName) {
            submitNativeFolderTask(downloadId, "Native folder export could not be finalized.", session -> {
                synchronized (DownloadBridge.this) {
                    if (activeDownloadSession == session) {
                        activeDownloadSession = null;
                    }
                }

                Log.i(LOG_TAG, "Native folder export finished: " + sanitizeFileName(folderName)
                    + ", entries=" + session.zipEntryCount
                    + ", bytes=" + session.receivedBytes);
                reportNativeZipProgress(session, "");
            });
        }

        /**
         * Starts a binary file entry in the active native ZIP.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param path ZIP entry path requested by RPlayer
         */
        @JavascriptInterface
        public synchronized void beginNativeZipEntry(String downloadId, String path) {
            if (!isActiveNativeZip(downloadId)) {
                return;
            }

            try {
                beginNativeZipEntryInternal(path);
            } catch (IOException | IllegalArgumentException exception) {
                Log.e(LOG_TAG, "Native ZIP entry could not be started.", exception);
                failDownload(downloadId, "Native ZIP entry could not be started.");
            }
        }

        /**
         * Appends one Base64-encoded binary chunk to the current native ZIP entry.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param base64Chunk Base64-encoded binary payload
         */
        @JavascriptInterface
        public synchronized void appendNativeZipEntryChunk(String downloadId, String base64Chunk) {
            if (!isActiveNativeZip(downloadId) || activeDownloadSession.currentZipEntry == null) {
                return;
            }

            try {
                byte[] data = Base64.decode(base64Chunk, Base64.NO_WRAP);
                activeDownloadSession.zipOutputStream.write(data);
                activeDownloadSession.receivedBytes += data.length;
            } catch (IOException | IllegalArgumentException exception) {
                Log.e(LOG_TAG, "Native ZIP binary chunk could not be written.", exception);
                failDownload(downloadId, "Native ZIP binary chunk could not be written.");
            }
        }

        /**
         * Finishes the current binary file entry in the active native ZIP.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         */
        @JavascriptInterface
        public synchronized void finishNativeZipEntry(String downloadId) {
            if (!isActiveNativeZip(downloadId)) {
                return;
            }

            try {
                finishNativeZipEntryInternal();
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Native ZIP entry could not be finalized.", exception);
                failDownload(downloadId, "Native ZIP entry could not be finalized.");
            }
        }

        /**
         * Finalizes the native ZIP file written to the selected Android document.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param fileName suggested filename from RPlayer
         */
        @JavascriptInterface
        public void finishNativeZip(String downloadId, String fileName) {
            submitNativeZipTask(downloadId, "Native ZIP file could not be finalized.", session -> {
                if (session.currentZipEntry != null) {
                    session.zipOutputStream.closeEntry();
                    session.currentZipEntry = null;
                }
                session.zipOutputStream.finish();
                session.zipOutputStream.close();

                synchronized (DownloadBridge.this) {
                    if (activeDownloadSession == session) {
                        activeDownloadSession = null;
                    }
                }

                Log.i(LOG_TAG, "Native ZIP download finished: " + sanitizeFileName(fileName)
                    + ", entries=" + session.zipEntryCount
                    + ", bytes=" + session.receivedBytes);
                reportNativeZipProgress(session, "");
            });
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
            if (activeDownloadSession.zipOutputStream != null || activeDownloadSession.folderRootUri != null) {
                dispatchNativeZipProgress(
                    -1,
                    activeDownloadSession.expectedZipEntries,
                    message == null ? "Native ZIP download failed." : message
                );
            }
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
         * Checks whether a JavaScript callback belongs to the current native ZIP session.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return true when the callback belongs to an active native ZIP session
         */
        private boolean isActiveNativeZip(String downloadId) {
            return isActiveDownload(downloadId) && activeDownloadSession.zipOutputStream != null;
        }

        /**
         * Checks whether a JavaScript callback belongs to the current native folder session.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return true when the callback belongs to an active native folder session
         */
        private boolean isActiveNativeFolder(String downloadId) {
            return isActiveDownload(downloadId) && activeDownloadSession.folderRootUri != null;
        }

        /**
         * Checks whether a JavaScript callback belongs to any active native export session.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return true when the callback belongs to a native ZIP or folder export
         */
        private boolean isActiveNativeExport(String downloadId) {
            return isActiveNativeZip(downloadId) || isActiveNativeFolder(downloadId);
        }

        /**
         * Gets the active native ZIP session for a queued bridge call.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return active native ZIP session or null when the callback is stale
         */
        private synchronized DownloadSession activeNativeZipSession(String downloadId) {
            if (!isActiveNativeZip(downloadId)) {
                return null;
            }

            return activeDownloadSession;
        }

        /**
         * Gets the active native folder session for a queued bridge call.
         *
         * @param downloadId JavaScript-generated identifier to validate
         * @return active native folder session or null when the callback is stale
         */
        private synchronized DownloadSession activeNativeFolderSession(String downloadId) {
            if (!isActiveNativeFolder(downloadId)) {
                return null;
            }

            return activeDownloadSession;
        }

        /**
         * Queues one native ZIP write task outside the WebView JavaScript bridge thread.
         *
         * @param downloadId JavaScript-generated identifier for this ZIP build
         * @param failureMessage message used when the task fails
         * @param task ZIP write task using the active download session
         */
        private void submitNativeZipTask(String downloadId, String failureMessage, NativeZipTask task) {
            submitNativeExportTask(downloadId, failureMessage, activeNativeZipSession(downloadId), task);
        }

        /**
         * Queues one native folder write task outside the WebView JavaScript bridge thread.
         *
         * @param downloadId JavaScript-generated identifier for this folder export
         * @param failureMessage message used when the task fails
         * @param task folder write task using the active download session
         */
        private void submitNativeFolderTask(String downloadId, String failureMessage, NativeZipTask task) {
            submitNativeExportTask(downloadId, failureMessage, activeNativeFolderSession(downloadId), task);
        }

        /**
         * Queues one native export task outside the WebView JavaScript bridge thread.
         *
         * @param downloadId JavaScript-generated identifier for this export
         * @param failureMessage message used when the task fails
         * @param session active export session
         * @param task write task using the active download session
         */
        private void submitNativeExportTask(String downloadId, String failureMessage, DownloadSession session, NativeZipTask task) {
            if (session == null) {
                return;
            }

            try {
                nativeZipExecutor.execute(() -> {
                    if (!isSameActiveSession(session)) {
                        return;
                    }

                    try {
                        task.run(session);
                    } catch (IOException | IllegalArgumentException exception) {
                        Log.e(LOG_TAG, failureMessage, exception);
                        failDownload(downloadId, failureMessage);
                    }
                });
            } catch (RejectedExecutionException exception) {
                Log.e(LOG_TAG, failureMessage, exception);
                failDownload(downloadId, failureMessage);
            }
        }

        /**
         * Checks whether a queued native ZIP task still belongs to the active session.
         *
         * @param session queued task session
         * @return true when the task may still write into the ZIP file
         */
        private synchronized boolean isSameActiveSession(DownloadSession session) {
            return activeDownloadSession == session;
        }

        /**
         * Opens a sanitized file entry in the active native ZIP.
         *
         * @param path ZIP entry path requested by RPlayer
         * @throws IOException when the ZIP stream rejects the entry
         */
        private void beginNativeZipEntryInternal(String path) throws IOException {
            beginNativeZipEntryInternal(activeDownloadSession, path);
        }

        /**
         * Opens a sanitized file entry in the supplied native ZIP session.
         *
         * @param session active native ZIP session
         * @param path ZIP entry path requested by RPlayer
         * @throws IOException when the ZIP stream rejects the entry
         */
        private void beginNativeZipEntryInternal(DownloadSession session, String path) throws IOException {
            if (session.currentZipEntry != null) {
                throw new IOException("Previous native ZIP entry is still open.");
            }

            String cleanPath = sanitizeZipEntryPath(path);
            ZipEntry zipEntry = new ZipEntry(cleanPath);
            session.zipOutputStream.putNextEntry(zipEntry);
            session.currentZipEntry = cleanPath;
            session.zipEntryCount += 1;
            Log.i(LOG_TAG, "Native ZIP entry started: " + cleanPath);
        }

        /**
         * Opens a stored ZIP entry for an already materialized binary source file.
         *
         * @param session active native ZIP session
         * @param path ZIP entry path requested by RPlayer
         * @param sourceFile complete source file that will be copied into the entry
         * @throws IOException when the ZIP stream rejects the entry or the source cannot be read
         */
        private void beginNativeZipStoredEntryInternal(DownloadSession session, String path, File sourceFile) throws IOException {
            if (session.currentZipEntry != null) {
                throw new IOException("Previous native ZIP entry is still open.");
            }

            String cleanPath = sanitizeZipEntryPath(path);
            ZipEntry zipEntry = new ZipEntry(cleanPath);
            long size = sourceFile.length();
            zipEntry.setMethod(ZipEntry.STORED);
            zipEntry.setSize(size);
            zipEntry.setCompressedSize(size);
            zipEntry.setCrc(crc32(sourceFile));
            session.zipOutputStream.putNextEntry(zipEntry);
            session.currentZipEntry = cleanPath;
            session.zipEntryCount += 1;
            Log.i(LOG_TAG, "Native ZIP stored entry started: " + cleanPath + ", bytes=" + size);
        }

        /**
         * Calculates CRC-32 required by the ZIP stored entry format.
         *
         * @param file complete source file to describe in the ZIP central directory
         * @return unsigned CRC-32 value expected by ZipEntry
         * @throws IOException when the source file cannot be read
         */
        private long crc32(File file) throws IOException {
            CRC32 crc32 = new CRC32();
            try (InputStream inputStream = new FileInputStream(file)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    crc32.update(buffer, 0, read);
                }
            }

            return crc32.getValue();
        }

        /**
         * Closes the currently open file entry in the active native ZIP.
         *
         * @throws IOException when the ZIP stream cannot close the entry
         */
        private void finishNativeZipEntryInternal() throws IOException {
            finishNativeZipEntryInternal(activeDownloadSession);
        }

        /**
         * Closes the currently open file entry in the supplied native ZIP session.
         *
         * @param session active native ZIP session
         * @throws IOException when the ZIP stream cannot close the entry
         */
        private void finishNativeZipEntryInternal(DownloadSession session) throws IOException {
            if (session.currentZipEntry == null) {
                return;
            }

            session.zipOutputStream.closeEntry();
            session.currentZipEntry = null;
        }

        /**
         * Marks one queued native ZIP entry as complete and reports progress to JavaScript.
         *
         * @param session active native ZIP session
         * @param path ZIP entry path completed by the worker
         */
        private void markNativeZipEntryFinished(DownloadSession session, String path) {
            session.completedZipEntries += 1;
            reportNativeZipProgress(session, path);
        }

        /**
         * Reports current native ZIP entry progress to JavaScript.
         *
         * @param session active native ZIP session
         * @param currentEntry recently completed ZIP entry path
         */
        private void reportNativeZipProgress(DownloadSession session, String currentEntry) {
            dispatchNativeZipProgress(
                session.completedZipEntries,
                session.expectedZipEntries,
                currentEntry == null ? "" : currentEntry
            );
        }

        /**
         * Writes one byte array into the active native folder export.
         *
         * @param session active native folder session
         * @param path relative file path requested by RPlayer
         * @param data file bytes to write
         * @throws IOException when Android refuses the destination file
         */
        private void writeNativeFolderBytes(DownloadSession session, String path, byte[] data) throws IOException {
            try (OutputStream outputStream = openNativeFolderOutputStream(session, path)) {
                outputStream.write(data);
                session.receivedBytes += data.length;
            }
        }

        /**
         * Copies one complete file into the active native folder export.
         *
         * @param session active native folder session
         * @param path relative file path requested by RPlayer
         * @param sourceFile complete source file to copy
         * @throws IOException when Android refuses the destination file
         */
        private void writeNativeFolderFile(DownloadSession session, String path, File sourceFile) throws IOException {
            try (
                InputStream inputStream = new FileInputStream(sourceFile);
                OutputStream outputStream = openNativeFolderOutputStream(session, path)
            ) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read);
                    session.receivedBytes += read;
                }
            }
        }

        /**
         * Opens one output file in the selected native folder export tree.
         *
         * @param session active native folder session
         * @param path relative file path requested by RPlayer
         * @return writable stream to the selected Android document
         * @throws IOException when the file or parent folders cannot be created
         */
        private OutputStream openNativeFolderOutputStream(DownloadSession session, String path) throws IOException {
            String relativePath = relativeNativeFolderPath(session, path);
            int lastSlash = relativePath.lastIndexOf('/');
            String directoryPath = lastSlash < 0 ? "" : relativePath.substring(0, lastSlash);
            String fileName = lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
            if (fileName.trim().isEmpty()) {
                throw new IOException("Native folder file path is empty.");
            }

            Uri parentUri = nativeFolderDirectoryUri(session, directoryPath);
            Uri fileUri = DocumentsContract.createDocument(
                getContentResolver(),
                parentUri,
                MimeTypes.fromPath(fileName, null),
                fileName
            );
            if (fileUri == null) {
                throw new IOException("Android returned no file URI for: " + relativePath);
            }

            OutputStream outputStream = getContentResolver().openOutputStream(fileUri);
            if (outputStream == null) {
                throw new IOException("Android returned no output stream for: " + relativePath);
            }

            return outputStream;
        }

        /**
         * Resolves or creates one folder path in the selected native export tree.
         *
         * @param session active native folder session
         * @param directoryPath relative directory path inside the album folder
         * @return Android document URI for the resolved directory
         * @throws IOException when Android refuses to create a directory
         */
        private Uri nativeFolderDirectoryUri(DownloadSession session, String directoryPath) throws IOException {
            Uri cachedUri = session.folderUris.get(directoryPath);
            if (cachedUri != null) {
                return cachedUri;
            }

            Uri parentUri = session.folderRootUri;
            StringBuilder currentPath = new StringBuilder();
            for (String part : directoryPath.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }

                if (currentPath.length() > 0) {
                    currentPath.append('/');
                }
                currentPath.append(part);
                String key = currentPath.toString();
                Uri existingUri = session.folderUris.get(key);
                if (existingUri != null) {
                    parentUri = existingUri;
                    continue;
                }

                Uri createdUri = DocumentsContract.createDocument(
                    getContentResolver(),
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    part
                );
                if (createdUri == null) {
                    throw new IOException("Android returned no directory URI for: " + key);
                }

                session.folderUris.put(key, createdUri);
                parentUri = createdUri;
            }

            return parentUri;
        }

        /**
         * Keeps the ZIP-style export path unchanged inside the selected parent folder.
         *
         * @param session active native folder session
         * @param path path produced by the RPlayer export plan
         * @return path relative to the selected parent folder
         */
        private String relativeNativeFolderPath(DownloadSession session, String path) {
            return sanitizeZipEntryPath(path);
        }

        /**
         * Reads an optional local proxy resource fully for metadata embedding.
         *
         * @param sourceUrl local proxy URL or blank value
         * @return resource bytes, or null when no resource was requested
         * @throws IOException when the resource cannot be read
         */
        private byte[] readOptionalNativeZipBytes(DownloadSession session, String sourceUrl, String label) throws IOException {
            if (isBlank(sourceUrl)) {
                return null;
            }

            File sourceFile = downloadNativeZipSourceToTempFile(session, label, sourceUrl);

            try (InputStream inputStream = new FileInputStream(sourceFile)) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read);
                }

                return outputStream.toByteArray();
            } finally {
                deleteTempFile(sourceFile);
            }
        }

        /**
         * Downloads one source file completely before it is written into the ZIP.
         *
         * <p>This keeps the ZIP stream valid when the gateway or network disappears
         * in the middle of a source file.</p>
         *
         * @param session active native ZIP session
         * @param path ZIP entry path or diagnostic label
         * @param sourceUrl local proxy source URL
         * @return temporary file containing a complete source response
         * @throws IOException when the source cannot be downloaded or the session ends
         */
        private File downloadNativeZipSourceToTempFile(DownloadSession session, String path, String sourceUrl) throws IOException {
            int attempt = 1;

            while (true) {
                File sourceFile = File.createTempFile("rplayer-native-source-", ".tmp", getCacheDir());
                HttpURLConnection connection = null;

                try {
                    connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    int statusCode = connection.getResponseCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        IOException exception = new IOException(
                            "Native ZIP source returned HTTP " + statusCode + ": " + sourceUrl
                        );

                        if (!isRetryableNativeZipStatus(statusCode)) {
                            throw exception;
                        }

                        throw new RetryableNativeZipSourceException(exception);
                    }

                    try (
                        InputStream inputStream = connection.getInputStream();
                        OutputStream outputStream = new FileOutputStream(sourceFile)
                    ) {
                        byte[] buffer = new byte[COPY_BUFFER_SIZE];
                        int read;
                        while ((read = inputStream.read(buffer)) >= 0) {
                            outputStream.write(buffer, 0, read);
                        }
                    }

                    return sourceFile;
                } catch (RetryableNativeZipSourceException exception) {
                    deleteTempFile(sourceFile);
                    waitBeforeNativeZipRetry(session, path, sourceUrl, attempt, exception.getCause());
                    attempt += 1;
                } catch (IOException exception) {
                    deleteTempFile(sourceFile);

                    if (isStorageFailure(exception)) {
                        throw exception;
                    }

                    waitBeforeNativeZipRetry(session, path, sourceUrl, attempt, exception);
                    attempt += 1;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }

        /**
         * Finds a verified persistent source file before falling back to a temporary download.
         *
         * @param session active native ZIP session
         * @param path ZIP entry path or diagnostic label
         * @param sourceUrl local proxy source URL
         * @return source file and whether it should be deleted after the ZIP entry is written
         * @throws IOException when the source cannot be downloaded or the session ends
         */
        private NativeZipSourceFile nativeZipSourceFile(DownloadSession session, String path, String sourceUrl) throws IOException {
            File cachedFile = proxyServer == null ? null : proxyServer.cachedFileForLocalUrl(sourceUrl);
            if (cachedFile != null) {
                Log.i(LOG_TAG, "Native ZIP source uses persistent cache: " + path + " <- " + sourceUrl);
                return new NativeZipSourceFile(cachedFile, false);
            }

            return new NativeZipSourceFile(downloadNativeZipSourceToTempFile(session, path, sourceUrl), true);
        }

        /**
         * Checks whether an HTTP status is likely to be temporary during downloads.
         *
         * @param statusCode HTTP status returned by the local proxy
         * @return true when the source should be retried instead of failing the ZIP
         */
        private boolean isRetryableNativeZipStatus(int statusCode) {
            return statusCode == 408
                || statusCode == 429
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
        }

        /**
         * Checks whether a download failure is probably local storage exhaustion.
         *
         * @param exception failure raised while downloading one ZIP source
         * @return true when retrying would likely loop forever
         */
        private boolean isStorageFailure(IOException exception) {
            String message = exception.getMessage();
            if (message == null) {
                return false;
            }

            String lowerMessage = message.toLowerCase(java.util.Locale.ROOT);
            return lowerMessage.contains("no space") || lowerMessage.contains("enospc");
        }

        /**
         * Waits before retrying a temporarily unavailable ZIP source.
         *
         * @param session active native ZIP session
         * @param path ZIP entry path or diagnostic label
         * @param sourceUrl local proxy source URL
         * @param attempt retry attempt number
         * @param failure failure that triggered the retry
         * @throws IOException when the wait is interrupted or the session is gone
         */
        private void waitBeforeNativeZipRetry(
            DownloadSession session,
            String path,
            String sourceUrl,
            int attempt,
            Throwable failure
        ) throws IOException {
            if (!isSameActiveSession(session)) {
                throw new IOException("Native ZIP session is no longer active.");
            }

            String currentEntry = "Waiting for connection: " + path;
            Log.w(
                LOG_TAG,
                "Native ZIP source unavailable, retrying after wait. attempt="
                    + attempt
                    + ", path=" + path
                    + ", source=" + sourceUrl,
                failure
            );
            dispatchNativeZipProgress(session.completedZipEntries, session.expectedZipEntries, currentEntry);

            try {
                Thread.sleep(NATIVE_ZIP_RETRY_DELAY_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Native ZIP retry wait was interrupted.", exception);
            }

            if (!isSameActiveSession(session)) {
                throw new IOException("Native ZIP session is no longer active.");
            }
        }

        /**
         * Checks whether a string is null, empty, or only whitespace.
         *
         * @param value checked string
         * @return true when no meaningful value is present
         */
        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
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
     * Marker exception used when a ZIP source failure should be retried.
     */
    private static final class RetryableNativeZipSourceException extends IOException {
        private RetryableNativeZipSourceException(IOException cause) {
            super(cause);
        }
    }

    /**
     * Background operation writing one entry into the active native ZIP session.
     */
    private interface NativeZipTask {
        /**
         * Writes one queued native ZIP operation.
         *
         * @param session active native ZIP session
         * @throws IOException when the ZIP stream or source stream fails
         */
        void run(DownloadSession session) throws IOException;
    }

    /**
     * Describes one native ZIP source file and its ownership.
     */
    private final class NativeZipSourceFile {
        private final File file;
        private final boolean temporary;

        /**
         * Creates a source file descriptor for native ZIP writing.
         *
         * @param file complete file to copy into the ZIP entry
         * @param temporary true when the file must be deleted after use
         */
        private NativeZipSourceFile(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
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
        private final OutputStream outputStream;
        private final ZipOutputStream zipOutputStream;
        private final Uri folderRootUri;
        private final Map<String, Uri> folderUris = new HashMap<>();
        private int nextChunkIndex;
        private long receivedBytes;
        private int zipEntryCount;
        private String currentZipEntry;
        private int expectedZipEntries;
        private int completedZipEntries;

        private DownloadSession(
            String downloadId,
            String fileName,
            String mimeType,
            long totalBytes,
            File file,
            OutputStream outputStream
        ) {
            this(downloadId, fileName, mimeType, totalBytes, file, outputStream, null);
        }

        private DownloadSession(
            String downloadId,
            String fileName,
            String mimeType,
            long totalBytes,
            File file,
            OutputStream outputStream,
            ZipOutputStream zipOutputStream
        ) {
            this(downloadId, fileName, mimeType, totalBytes, file, outputStream, zipOutputStream, null);
        }

        private DownloadSession(
            String downloadId,
            String fileName,
            String mimeType,
            long totalBytes,
            File file,
            OutputStream outputStream,
            ZipOutputStream zipOutputStream,
            Uri folderRootUri
        ) {
            this.downloadId = downloadId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.totalBytes = totalBytes;
            this.file = file;
            this.outputStream = outputStream;
            this.zipOutputStream = zipOutputStream;
            this.folderRootUri = folderRootUri;
            if (folderRootUri != null) {
                this.folderUris.put("", folderRootUri);
            }
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

    /**
     * Native ZIP output destination waiting for Android's save dialog result.
     */
    private static final class PendingNativeZip {
        private final String downloadId;
        private final String fileName;

        private PendingNativeZip(String downloadId, String fileName) {
            this.downloadId = downloadId;
            this.fileName = fileName;
        }
    }

    /**
     * Native folder output destination waiting for Android's folder picker result.
     */
    private static final class PendingNativeFolder {
        private final String downloadId;
        private final String folderName;

        private PendingNativeFolder(String downloadId, String folderName) {
            this.downloadId = downloadId;
            this.folderName = folderName;
        }
    }
}
