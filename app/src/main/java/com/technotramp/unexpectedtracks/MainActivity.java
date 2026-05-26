package com.technotramp.unexpectedtracks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Main Android entry point for the single-album viewer.
 *
 * <p>The activity starts the local gateway proxy, configures a locked-down
 * WebView, and loads the fixed RPlayer album through the proxy URL.</p>
 */
public final class MainActivity extends Activity {
    private static final String LOG_TAG = "RPlayerViewer";

    private GatewayProxyServer proxyServer;
    private WebView webView;
    private TextView errorView;

    /**
     * Initializes the activity, starts the local proxy, and loads the viewer URL.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createLayout();

        try {
            proxyServer = new GatewayProxyServer();
            proxyServer.start();
            configureWebView();
            webView.loadUrl(proxyServer.viewerUrl());
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Proxy server could not be started.", exception);
            showError("RPlayer Gateway Viewer could not start the local proxy.");
        }
    }

    /**
     * Releases WebView and proxy resources when the activity is destroyed.
     */
    @Override
    protected void onDestroy() {
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
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);

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
        });
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
     * Displays a startup error when the local proxy cannot be created.
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
}
