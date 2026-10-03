package com.makiplayer.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URL;

public class MainActivity extends Activity {
    private static final String OFFICIAL_PAGE =
            "https://www.bilibili.com/video/BV1B3BHYbErF/";

    private LinearLayout homePanel;
    private LinearLayout browserPanel;
    private FrameLayout webContainer;
    private WebView webView;
    private WebChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        homePanel = findViewById(R.id.homePanel);
        browserPanel = findViewById(R.id.browserPanel);
        webContainer = findViewById(R.id.webContainer);

        Button openButton = findViewById(R.id.openButton);
        Button backButton = findViewById(R.id.backButton);

        setupWebView();

        openButton.setOnClickListener(v -> openOfficialPage());
        backButton.setOnClickListener(v -> showHome());
    }

    private void setupWebView() {
        webView = new WebView(this);
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return !isAllowed(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAllowed(url);
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webContainer.removeAllViews();
                webContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                webContainer.removeAllViews();
                webContainer.addView(webView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customViewCallback = null;
            }
        };
        webView.setWebChromeClient(chromeClient);
    }

    private boolean isAllowed(String url) {
        try {
            String host = new URL(url).getHost();
            return host.equals("bilibili.com")
                    || host.endsWith(".bilibili.com")
                    || host.equals("hdslb.com")
                    || host.endsWith(".hdslb.com");
        } catch (Exception e) {
            return false;
        }
    }

    private void openOfficialPage() {
        homePanel.setVisibility(View.GONE);
        browserPanel.setVisibility(View.VISIBLE);
        webView.loadUrl(OFFICIAL_PAGE);
    }

    private void showHome() {
        if (customView != null) chromeClient.onHideCustomView();
        webView.stopLoading();
        webView.loadUrl("about:blank");
        browserPanel.setVisibility(View.GONE);
        homePanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (browserPanel.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (browserPanel.getVisibility() == View.VISIBLE) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
