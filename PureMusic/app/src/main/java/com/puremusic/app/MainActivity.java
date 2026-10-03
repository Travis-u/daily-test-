package com.puremusic.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String NETEASE_PACKAGE = "com.netease.cloudmusic";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessionManager;
    private MediaController controller;
    private MediaController.Callback controllerCallback;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener;

    private TextView statusText;
    private TextView titleText;
    private TextView artistText;
    private TextView currentTimeText;
    private TextView durationText;
    private TextView emptyHint;
    private ImageView coverView;
    private SeekBar seekBar;
    private Button playButton;
    private Button permissionButton;
    private LinearLayout aiPanel;
    private LinearLayout aiResults;
    private EditText aiInput;

    private long durationMs = 0L;
    private boolean userSeeking = false;
    private String lastPackage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 247, 243));
        getWindow().setNavigationBarColor(Color.rgb(247, 247, 243));
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        buildUi();
        setupMediaCallbacks();
        refreshAccessState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessState();
        refreshSessions();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        detachController();
        try {
            if (sessionsChangedListener != null && sessionManager != null) {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 247, 243));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("纯音", 25, Color.rgb(20,20,20), Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1f));
        statusText = chip("等待连接");
        top.addView(statusText);
        root.addView(top);

        permissionButton = button("授权媒体控制");
        permissionButton.setOnClickListener(v -> openNotificationAccess());
        LinearLayout.LayoutParams permissionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        permissionLp.setMargins(0, dp(6), 0, dp(12));
        root.addView(permissionButton, permissionLp);

        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setBackground(rounded(Color.rgb(232,232,225), 30));
        coverView.setImageDrawable(null);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
        coverLp.setMargins(0, dp(4), 0, dp(22));
        root.addView(coverView, coverLp);

        titleText = text("先在网易云播放一首歌", 24, Color.rgb(20,20,20), Typeface.BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setSingleLine(true);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(titleText);

        artistText = text("纯音会接管显示与控制，音源仍由网易云提供", 14,
                Color.rgb(120,120,114), Typeface.NORMAL);
        artistText.setGravity(Gravity.CENTER);
        artistText.setPadding(0, dp(7), 0, dp(8));
        root.addView(artistText);

        emptyHint = text("", 12, Color.rgb(145,145,138), Typeface.NORMAL);
        emptyHint.setGravity(Gravity.CENTER);
        root.addView(emptyHint);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setPadding(0, dp(14), 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) {
                    currentTimeText.setText(formatTime(durationMs * progress / 1000L));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (controller != null && durationMs > 0) {
                    long target = durationMs * seekBar.getProgress() / 1000L;
                    try { controller.getTransportControls().seekTo(target); } catch (Exception ignored) {}
                }
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        currentTimeText = text("0:00", 12, Color.rgb(130,130,123), Typeface.NORMAL);
        durationText = text("0:00", 12, Color.rgb(130,130,123), Typeface.NORMAL);
        durationText.setGravity(Gravity.END);
        timeRow.addView(currentTimeText, new LinearLayout.LayoutParams(0, dp(24), 1f));
        timeRow.addView(durationText, new LinearLayout.LayoutParams(0, dp(24), 1f));
        root.addView(timeRow);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, dp(14));

        Button prev = circleButton("‹", 56, false);
        prev.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        controls.addView(prev);

        playButton = circleButton("▶", 70, true);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(70), dp(70));
        playLp.setMargins(dp(26), 0, dp(26), 0);
        playButton.setLayoutParams(playLp);
        playButton.setOnClickListener(v -> togglePlayback());
        controls.addView(playButton);

        Button next = circleButton("›", 56, false);
        next.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        controls.addView(next);
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(94)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button openNetease = button("打开网易云");
        openNetease.setOnClickListener(v -> openNetEase());
        Button aiButton = button("AI 找歌");
        aiButton.setOnClickListener(v -> toggleAiPanel());

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half.setMargins(0, 0, dp(6), 0);
        actions.addView(openNetease, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half2.setMargins(dp(6), 0, 0, 0);
        actions.addView(aiButton, half2);
        root.addView(actions);

        aiPanel = new LinearLayout(this);
        aiPanel.setOrientation(LinearLayout.VERTICAL);
        aiPanel.setVisibility(View.GONE);
        aiPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        aiPanel.setBackground(rounded(Color.WHITE, 22));

        TextView aiTitle = text("AI 找歌", 19, Color.rgb(20,20,20), Typeface.BOLD);
        aiPanel.addView(aiTitle);

        TextView aiNote = text("先用本地语义规则做场景筛选；真正的大模型接口下一版再接，避免把 API 密钥塞进 APK。", 12,
                Color.rgb(125,125,118), Typeface.NORMAL);
        aiNote.setPadding(0, dp(6), 0, dp(12));
        aiPanel.addView(aiNote);

        aiInput = new EditText(this);
        aiInput.setHint("例如：写物理，清醒一点，不要人声，也别太吵");
        aiInput.setTextSize(14);
        aiInput.setSingleLine(false);
        aiInput.setMinLines(2);
        aiInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        aiInput.setBackground(rounded(Color.rgb(246,246,241), 16));
        aiPanel.addView(aiInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));

        Button ask = button("生成推荐");
        LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        askLp.setMargins(0, dp(10), 0, dp(6));
        aiPanel.addView(ask, askLp);
        ask.setOnClickListener(v -> runLocalRecommendation(aiInput.getText().toString()));

        aiResults = new LinearLayout(this);
        aiResults.setOrientation(LinearLayout.VERTICAL);
        aiPanel.addView(aiResults);

        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aiLp.setMargins(0, dp(18), 0, 0);
        root.addView(aiPanel, aiLp);

        TextView privacy = text("纯音不读取网易云账号密码，也不解密会员音源；它只控制 Android 已公开的媒体会话。", 11,
                Color.rgb(155,155,148), Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(18), dp(12), dp(4));
        root.addView(privacy);

        setContentView(scroll);
    }

    private void setupMediaCallbacks() {
        controllerCallback = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                updateMetadata(metadata);
            }
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                updatePlayback(state);
            }
            @Override public void onSessionDestroyed() {
                handler.postDelayed(MainActivity.this::refreshSessions, 200);
            }
        };

        sessionsChangedListener = controllers -> chooseController(controllers);
    }

    private void refreshAccessState() {
        boolean granted = hasNotificationAccess();
        permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (granted) {
            statusText.setText(controller == null ? "已授权" : "已连接");
            statusText.setTextColor(Color.rgb(45,90,54));
            statusText.setBackground(rounded(Color.rgb(229,241,230), 999));
            registerSessionListener();
        } else {
            statusText.setText("需授权");
            statusText.setTextColor(Color.rgb(120,88,32));
            statusText.setBackground(rounded(Color.rgb(246,238,218), 999));
            titleText.setText("授权后即可控制网易云");
            artistText.setText("只需一次：系统设置 → 通知访问 → 允许「纯音」");
            emptyHint.setText("这是 Android 对控制其他播放器的系统权限要求。");
            detachController();
        }
    }

    private boolean hasNotificationAccess() {
        ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(cn);
        }
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void openNotificationAccess() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开通知访问设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void registerSessionListener() {
        try {
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, cn, handler);
        } catch (Exception ignored) {}
    }

    private void refreshSessions() {
        if (!hasNotificationAccess() || sessionManager == null) return;
        try {
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            chooseController(sessionManager.getActiveSessions(cn));
        } catch (SecurityException e) {
            refreshAccessState();
        } catch (Exception ignored) {}
    }

    private void chooseController(List<MediaController> controllers) {
        MediaController chosen = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (NETEASE_PACKAGE.equals(c.getPackageName())) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                for (MediaController c : controllers) {
                    PlaybackState state = c.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        chosen = c;
                        break;
                    }
                }
            }
        }

        if (chosen == null) {
            detachController();
            statusText.setText("等待网易云");
            titleText.setText("先在网易云播放一首歌");
            artistText.setText("播放后回到这里，纯音会自动接管");
            emptyHint.setText("");
            coverView.setImageDrawable(null);
            durationMs = 0;
            seekBar.setProgress(0);
            currentTimeText.setText("0:00");
            durationText.setText("0:00");
            playButton.setText("▶");
            return;
        }

        if (controller != null && controller.getSessionToken().equals(chosen.getSessionToken())) {
            updateMetadata(controller.getMetadata());
            updatePlayback(controller.getPlaybackState());
            return;
        }

        detachController();
        controller = chosen;
        lastPackage = chosen.getPackageName();
        controller.registerCallback(controllerCallback, handler);
        statusText.setText(NETEASE_PACKAGE.equals(lastPackage) ? "网易云已连接" : "媒体已连接");
        statusText.setTextColor(Color.rgb(45,90,54));
        statusText.setBackground(rounded(Color.rgb(229,241,230), 999));
        updateMetadata(chosen.getMetadata());
        updatePlayback(chosen.getPlaybackState());
    }

    private void detachController() {
        if (controller != null) {
            try { controller.unregisterCallback(controllerCallback); } catch (Exception ignored) {}
        }
        controller = null;
        lastPackage = "";
    }

    private void updateMetadata(MediaMetadata metadata) {
        if (metadata == null) return;

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }

        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        }

        titleText.setText(TextUtils.isEmpty(title) ? "正在播放" : title);
        artistText.setText(TextUtils.isEmpty(artist) ? "网易云音乐" : artist);
        emptyHint.setText(NETEASE_PACKAGE.equals(lastPackage) ? "" : "当前接管的是其他正在播放的媒体 App");

        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        durationText.setText(formatTime(durationMs));

        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        coverView.setImageBitmap(art);
        if (art == null) {
            coverView.setBackground(rounded(Color.rgb(232,232,225), 30));
        }
    }

    private void updatePlayback(PlaybackState state) {
        if (state == null) {
            playButton.setText("▶");
            return;
        }
        boolean playing = state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING;
        playButton.setText(playing ? "Ⅱ" : "▶");
        updateProgressFromState(state);
    }

    private void updateProgressFromState(PlaybackState state) {
        if (state == null || userSeeking) return;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long delta = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            if (delta > 0) pos += (long) (delta * state.getPlaybackSpeed());
        }
        if (durationMs > 0) {
            pos = Math.max(0, Math.min(durationMs, pos));
            seekBar.setProgress((int) (pos * 1000L / durationMs));
        }
        currentTimeText.setText(formatTime(pos));
    }

    private void togglePlayback() {
        if (controller == null) {
            openNetEase();
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        try {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
        } catch (Exception ignored) {}
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (controller != null) {
                updateProgressFromState(controller.getPlaybackState());
            } else if (hasNotificationAccess()) {
                refreshSessions();
            }
            handler.postDelayed(this, 600);
        }
    };

    private void openNetEase() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NETEASE_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "没有检测到网易云音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动网易云音乐", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchNetEase(String query) {
        if (TextUtils.isEmpty(query)) return;

        // First try Android's explicit in-app search intent. Because the package is fixed
        // to NetEase Cloud Music, the system will never route this to a browser.
        try {
            Intent search = new Intent(Intent.ACTION_SEARCH);
            search.setPackage(NETEASE_PACKAGE);
            search.putExtra(SearchManager.QUERY, query);
            search.putExtra("query", query);
            search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(search);
            return;
        } catch (Exception ignored) {
            // Some NetEase versions do not expose ACTION_SEARCH. In that case we still
            // keep the flow browser-free: copy the exact query and open NetEase itself.
        }

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("纯音搜索词", query));
            }
        } catch (Exception ignored) {}

        openNetEase();
        Toast.makeText(this, "搜索词已复制，可直接粘贴到网易云搜索框", Toast.LENGTH_LONG).show();
    }

    private void toggleAiPanel() {
        aiPanel.setVisibility(aiPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void runLocalRecommendation(String prompt) {
        aiResults.removeAllViews();
        String p = prompt == null ? "" : prompt.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            aiInput.setError("先说一句你现在想听什么");
            return;
        }

        List<Rec> recs = new ArrayList<>();
        if (containsAny(p, "学习","数学","物理","化学","生物","作业","专注","study")) {
            recs.add(new Rec("minimal piano study", "存在感低，适合连续推题"));
            recs.add(new Rec("ambient electronic focus", "有流动感，但通常不抢注意力"));
            recs.add(new Rec("Bach well tempered clavier study", "结构清楚，适合长时间工作"));
        } else if (containsAny(p, "骑车","运动","跑步","ride")) {
            recs.add(new Rec("synthwave cycling", "拍点稳定，适合骑行节奏"));
            recs.add(new Rec("indie rock driving", "推进感明显，不容易听困"));
            recs.add(new Rec("melodic drum and bass", "速度感强，适合需要兴奋度时"));
        } else if (containsAny(p, "夜","晚上","孤独","睡前","night")) {
            recs.add(new Rec("dream pop late night", "柔和、有空间感"));
            recs.add(new Rec("ambient midnight", "安静、克制"));
            recs.add(new Rec("indie folk late night", "有人声但整体收敛"));
        } else {
            recs.add(new Rec("neo classical essentials", "旋律和质感都比较克制"));
            recs.add(new Rec("bossa nova gentle", "轻松，但不至于完全没节奏"));
            recs.add(new Rec("instrumental math rock", "想换口味时会比较新鲜"));
        }

        String suffix = "";
        if (containsAny(p, "不要人声","纯音乐","无人声","instrumental")) suffix += " 纯音乐";
        if (containsAny(p, "日语","日本","日系")) suffix += " 日系";
        if (containsAny(p, "俄语","俄罗斯")) suffix += " 俄语";
        if (containsAny(p, "英文","英语")) suffix += " 英文";
        if (containsAny(p, "清醒","不困")) suffix += " 清醒";
        if (containsAny(p, "别太吵","不要太吵","克制","低刺激")) suffix += " 低刺激";

        for (Rec rec : recs) {
            addRecommendationCard(rec.query + suffix, rec.reason);
        }
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private void addRecommendationCard(String query, String reason) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(10), dp(10));
        card.setBackground(rounded(Color.rgb(246,246,241), 15));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView q = text(query, 14, Color.rgb(30,30,30), Typeface.BOLD);
        TextView r = text(reason, 12, Color.rgb(125,125,118), Typeface.NORMAL);
        r.setPadding(0, dp(3), 0, 0);
        texts.addView(q);
        texts.addView(r);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button search = button("网易云搜");
        search.setTextSize(12);
        search.setOnClickListener(v -> searchNetEase(query));
        card.addView(search, new LinearLayout.LayoutParams(dp(94), dp(40)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(7), 0, 0);
        aiResults.addView(card, lp);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans", style));
        return v;
    }

    private TextView chip(String value) {
        TextView v = text(value, 12, Color.rgb(95,95,88), Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(rounded(Color.rgb(238,238,232), 999));
        v.setMinHeight(dp(34));
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(30,30,30));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(Color.WHITE, 16));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private Button circleButton(String label, int sizeDp, boolean dark) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(dark ? 24 : 34);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(dark ? Color.WHITE : Color.rgb(25,25,25));
        b.setBackground(rounded(dark ? Color.rgb(20,20,20) : Color.TRANSPARENT, 999));
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (color == Color.WHITE) d.setStroke(dp(1), Color.rgb(230,230,223));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private static class Rec {
        final String query;
        final String reason;
        Rec(String query, String reason) {
            this.query = query;
            this.reason = reason;
        }
    }
}
