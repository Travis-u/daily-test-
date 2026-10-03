package com.makiplayer.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String DEFAULT_BVID = "BV1DjdfYaELW";
    private EditText input;
    private FrameLayout webContainer;
    private WebView webView;
    private WebChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.input);
        webContainer = findViewById(R.id.webContainer);
        Button playButton = findViewById(R.id.playButton);
        Button defaultButton = findViewById(R.id.defaultButton);

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

        webView.setWebViewClient(new WebViewClient());
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
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        };
        webView.setWebChromeClient(chromeClient);

        playButton.setOnClickListener(v -> {
            String bvid = extractBvid(input.getText().toString());
            if (bvid == null) {
                Toast.makeText(this, "没识别到 BV 号", Toast.LENGTH_SHORT).show();
                return;
            }
            loadBvid(bvid);
        });

        defaultButton.setOnClickListener(v -> {
            input.setText(DEFAULT_BVID);
            loadBvid(DEFAULT_BVID);
        });
    }

    private void loadBvid(String bvid) {
        String url = "https://player.bilibili.com/player.html?bvid=" + bvid + "&danmaku=0&autoplay=0";
        webView.loadUrl(url);
    }

    private String extractBvid(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(text.trim());
        return m.find() ? m.group(1) : null;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
