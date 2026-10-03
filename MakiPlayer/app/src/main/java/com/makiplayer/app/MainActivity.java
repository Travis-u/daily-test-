package com.makiplayer.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String SEED_BVID = "BV1B3BHYbErF";
    private static final String API_URL =
            "https://api.bilibili.com/x/web-interface/view?bvid=" + SEED_BVID;
    private static final String PREFS = "maki_player";
    private static final String CACHE_KEY = "lecture_cache";

    private LinearLayout listPanel;
    private LinearLayout videoList;
    private LinearLayout playerPanel;
    private TextView statusText;
    private TextView playingTitle;
    private FrameLayout webContainer;

    private WebView webView;
    private WebChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final List<Lecture> lectures = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listPanel = findViewById(R.id.listPanel);
        videoList = findViewById(R.id.videoList);
        playerPanel = findViewById(R.id.playerPanel);
        statusText = findViewById(R.id.statusText);
        playingTitle = findViewById(R.id.playingTitle);
        webContainer = findViewById(R.id.webContainer);

        Button refreshButton = findViewById(R.id.refreshButton);
        Button backButton = findViewById(R.id.backButton);

        setupWebView();

        refreshButton.setOnClickListener(v -> fetchLectures(true));
        backButton.setOnClickListener(v -> showList());

        String cached = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(CACHE_KEY, null);
        if (cached != null && loadCached(cached)) {
            renderLectures("● 已载入缓存 · 正在自动同步…");
            fetchLectures(false);
        } else {
            fetchLectures(true);
        }
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return host == null || !host.equals("player.bilibili.com");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    String host = new URL(url).getHost();
                    return !host.equals("player.bilibili.com");
                } catch (Exception e) {
                    return true;
                }
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
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        };
        webView.setWebChromeClient(chromeClient);
    }

    private void fetchLectures(boolean showLoading) {
        if (showLoading) {
            statusText.setText("● 正在自动同步完整目录…");
        }

        final int oldCount = lectures.size();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                conn.setRequestProperty("Referer",
                        "https://www.bilibili.com/video/" + SEED_BVID + "/");
                conn.setRequestProperty("Accept", "application/json,text/plain,*/*");

                int code = conn.getResponseCode();
                InputStream in = code >= 200 && code < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                String body = readAll(in);

                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                JSONObject root = new JSONObject(body);
                if (root.optInt("code", -1) != 0) {
                    throw new Exception("Bilibili API " + root.optInt("code"));
                }

                JSONArray normalized = extractSeasonEpisodes(root);
                if (normalized.length() == 0) {
                    throw new Exception("没有读取到合集条目");
                }

                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString(CACHE_KEY, normalized.toString()).apply();

                runOnUiThread(() -> {
                    loadCached(normalized.toString());
                    int delta = lectures.size() - oldCount;
                    String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(new Date());
                    String status;
                    if (oldCount > 0 && delta > 0) {
                        status = "● 自动更新 +" + delta + " · 共 " + lectures.size()
                                + " 个 · " + time;
                    } else {
                        status = "● 已自动同步 · 共 " + lectures.size()
                                + " 个 · " + time;
                    }
                    renderLectures(status);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (lectures.isEmpty()) {
                        statusText.setText("● 同步失败 · 点“立即同步”重试");
                        Toast.makeText(this,
                                "读取 B 站合集失败：" + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        statusText.setText("● 离线使用缓存 · 下次启动会自动再同步");
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private JSONArray extractSeasonEpisodes(JSONObject root) throws Exception {
        JSONObject data = root.getJSONObject("data");
        JSONObject season = data.optJSONObject("ugc_season");
        if (season == null) throw new Exception("这个视频没有返回合集信息");

        JSONArray out = new JSONArray();
        JSONArray sections = season.optJSONArray("sections");
        Set<String> seen = new HashSet<>();

        if (sections != null) {
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.optJSONObject(i);
                if (section == null) continue;
                JSONArray episodes = section.optJSONArray("episodes");
                if (episodes == null) continue;

                for (int j = 0; j < episodes.length(); j++) {
                    JSONObject ep = episodes.optJSONObject(j);
                    if (ep == null) continue;

                    String bvid = ep.optString("bvid", "");
                    String title = ep.optString("title", "");

                    JSONObject arc = ep.optJSONObject("arc");
                    if (arc != null) {
                        if (bvid.isEmpty()) bvid = arc.optString("bvid", "");
                        if (title.isEmpty()) title = arc.optString("title", "");
                    }

                    if (bvid.isEmpty() || title.isEmpty() || seen.contains(bvid)) continue;

                    seen.add(bvid);
                    JSONObject one = new JSONObject();
                    one.put("title", title);
                    one.put("bvid", bvid);
                    out.put(one);
                }
            }
        }
        return out;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private boolean loadCached(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<Lecture> temp = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) continue;
                String title = one.optString("title", "");
                String bvid = one.optString("bvid", "");
                if (!title.isEmpty() && !bvid.isEmpty()) {
                    temp.add(new Lecture(title, bvid));
                }
            }
            if (temp.isEmpty()) return false;
            lectures.clear();
            lectures.addAll(temp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void renderLectures(String status) {
        videoList.removeAllViews();

        for (int i = 0; i < lectures.size(); i++) {
            Lecture lecture = lectures.get(i);

            TextView item = new TextView(this);
            item.setText(String.format(Locale.CHINA, "%02d   %s", i + 1, lecture.title));
            item.setTextColor(0xFFEAF2F8);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(16), dp(14), dp(16));
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true);
            item.setBackgroundResource(outValue.resourceId);

            item.setOnClickListener(v -> playLecture(lecture));
            videoList.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            View divider = new View(this);
            divider.setBackgroundColor(0xFF172331);
            videoList.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }

        statusText.setText(status);
    }

    private void playLecture(Lecture lecture) {
        playingTitle.setText(lecture.title);
        listPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);

        String url = "https://player.bilibili.com/player.html?bvid="
                + lecture.bvid + "&danmaku=0&autoplay=0&high_quality=1";
        webView.loadUrl(url);
    }

    private void showList() {
        if (customView != null) chromeClient.onHideCustomView();
        webView.stopLoading();
        webView.loadUrl("about:blank");
        playerPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
        } else if (playerPanel.getVisibility() == View.VISIBLE) {
            showList();
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

    private static class Lecture {
        final String title;
        final String bvid;

        Lecture(String title, String bvid) {
            this.title = title;
            this.bvid = bvid;
        }
    }
}
