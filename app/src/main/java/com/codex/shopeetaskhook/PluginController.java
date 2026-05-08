package com.codex.shopeetaskhook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

public class PluginController {

    private static final String LOGIN_URL = "https://eqwofaygdsjko.uk:443/api/user/login";
    private static final String TAKE_TASK_URL = "https://eqwofaygdsjko.uk:443/api/task/take";
    private static final String SUBMIT_TASK_URL = "https://eqwofaygdsjko.uk:443/api/task/submit/v2";
    private static final String SUBMIT_APP_VERSION = "vv2";
    private static final String PREFS_NAME = "shp_rebuild_prefs";
    private static final String TAG = "ShopeeTaskHook";

    private static PluginController instance;
    private Application app;
    private ClassLoader appClassLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    // UI
    private WindowManager windowManager;
    private View panelView;
    private View bubbleView;
    private View miniView;
    private TextView statusLabel;
    private TextView taskLabel;
    private TextView counterLabel;
    private EditText usernameField;
    private EditText passwordField;
    private EditText intervalField;
    private Button startTaskButton;
    private Button resetCountButton;
    private Button miniModeButton;
    private TextView logView;
    private ScrollView logScrollView;
    private TextView miniInfoLabel;
    private TextView riskControlLabel;

    // State
    private String token;
    private boolean isRunning;
    private boolean isCollapsed = true;
    private boolean isMiniMode;
    private boolean isRiskControlled;
    private boolean requestInFlight;
    private boolean submittingCurrentTask;
    private boolean waitingForPDP;
    private int successCount;
    private int consecutivePDPFailures;
    private SHPTask currentTask;
    private Runnable pendingPollRunnable;
    private Runnable pendingCountdownRunnable;
    private long nextFireTime;
    private boolean overlayCreated;

    public static void init(Application app, ClassLoader classLoader) {
        if (instance != null) return;
        instance = new PluginController();
        instance.app = app;
        instance.appClassLoader = classLoader;
        instance.loadDefaults();
        instance.registerActivityLifecycle();
    }

    public static PluginController getInstance() {
        return instance;
    }

    private void registerActivityLifecycle() {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override
            public void onActivityResumed(Activity a) {
                if (overlayCreated) return;
                mainHandler.postDelayed(() -> ensureOverlay(), 1500);
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    // ==================== Preferences ====================

    private SharedPreferences getPrefs() {
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadDefaults() {
        SharedPreferences prefs = getPrefs();
        token = prefs.getString("token", null);
        isRunning = false;
        isCollapsed = prefs.getBoolean("collapsed", true);
        isMiniMode = prefs.getBoolean("miniMode", false);
        successCount = prefs.getInt("successCount", 0);

        String taskJson = prefs.getString("currentTask", null);
        if (taskJson != null) {
            try {
                currentTask = SHPTask.fromJSON(new JSONObject(taskJson));
            } catch (Exception ignored) {}
        }
        currentTask = null;
    }

    private void persistDefaults() {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putBoolean("running", isRunning);
        editor.putBoolean("collapsed", isCollapsed);
        editor.putBoolean("miniMode", isMiniMode);
        editor.putInt("successCount", successCount);
        if (token != null) editor.putString("token", token);
        else editor.remove("token");
        if (currentTask != null) editor.putString("currentTask", currentTask.toJSON().toString());
        else editor.remove("currentTask");
        if (usernameField != null && usernameField.getText().length() > 0)
            editor.putString("username", usernameField.getText().toString());
        if (passwordField != null && passwordField.getText().length() > 0)
            editor.putString("password", passwordField.getText().toString());
        if (intervalField != null && intervalField.getText().length() > 0)
            editor.putString("intervalRange", intervalField.getText().toString());
        editor.apply();
    }

    // ==================== UI ====================

    @SuppressLint("ClickableViewAccessibility")
    private void ensureOverlay() {
        if (overlayCreated) return;

        if (!Settings.canDrawOverlays(app)) {
            executor.execute(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "appops set " + app.getPackageName() + " SYSTEM_ALERT_WINDOW allow"});
                    p.waitFor();
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": overlay permission error: " + e.getMessage());
                }
                mainHandler.postDelayed(this::ensureOverlay, 800);
            });
            return;
        }

        overlayCreated = true;
        try {
            windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            buildPanel();
            buildBubble();
            buildMiniView();
            refreshUI();
        } catch (Throwable t) {
            overlayCreated = false;
            XposedBridge.log(TAG + ": overlay build failed: " + t.getMessage());
        }
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                app.getResources().getDisplayMetrics());
    }

    private WindowManager.LayoutParams makeOverlayParams(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildPanel() {
        int panelW = dp(320);
        int panelH = dp(460);
        int pad = dp(14);

        LinearLayout panel = new LinearLayout(app);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF0121A29);
        panel.setPadding(pad, 0, pad, pad);

        // Header
        FrameLayout header = new FrameLayout(app);
        header.setBackgroundColor(0xFA1A2940);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(42)));

        TextView title = new TextView(app);
        title.setText("格界");
        title.setTextColor(0xFFEBFAFA);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(pad, 0, 0, 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new FrameLayout.LayoutParams(-2, -1, Gravity.START | Gravity.CENTER_VERTICAL));

        Button collapseBtn = makeTextButton("隐藏", 0xFF8CE6DB);
        collapseBtn.setOnClickListener(v -> toggleCollapsed());
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(dp(56), -1, Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(collapseBtn, cbp);

        panel.addView(header);

        // Status
        statusLabel = new TextView(app);
        statusLabel.setTextColor(0xFFE3F0F5);
        statusLabel.setTextSize(12);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusLabel.setPadding(0, dp(10), 0, 0);
        panel.addView(statusLabel, new LinearLayout.LayoutParams(-1, -2));

        // Task
        taskLabel = new TextView(app);
        taskLabel.setTextColor(0xFFA6C2D6);
        taskLabel.setTextSize(11);
        taskLabel.setMaxLines(2);
        panel.addView(taskLabel, new LinearLayout.LayoutParams(-1, -2));

        // Counter
        counterLabel = new TextView(app);
        counterLabel.setTextColor(0xFFE0D494);
        counterLabel.setTextSize(11);
        counterLabel.setTypeface(Typeface.DEFAULT_BOLD);
        counterLabel.setPadding(0, dp(2), 0, dp(6));
        panel.addView(counterLabel, new LinearLayout.LayoutParams(-1, -2));

        // Username + Start button row
        LinearLayout row1 = new LinearLayout(app);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout fieldsCol = new LinearLayout(app);
        fieldsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fieldsLP = new LinearLayout.LayoutParams(0, -2, 1f);
        fieldsLP.rightMargin = pad;

        usernameField = makeTextField("用户名");
        usernameField.setText(getPrefs().getString("username", ""));
        fieldsCol.addView(usernameField, new LinearLayout.LayoutParams(-1, dp(38)));

        View spacer = new View(app);
        fieldsCol.addView(spacer, new LinearLayout.LayoutParams(-1, dp(8)));

        passwordField = makeTextField("密码");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setText(getPrefs().getString("password", ""));
        fieldsCol.addView(passwordField, new LinearLayout.LayoutParams(-1, dp(38)));

        row1.addView(fieldsCol, fieldsLP);

        startTaskButton = new Button(app);
        startTaskButton.setText("启动任务");
        startTaskButton.setTextColor(Color.WHITE);
        startTaskButton.setTextSize(15);
        startTaskButton.setTypeface(Typeface.DEFAULT_BOLD);
        startTaskButton.setAllCaps(false);
        startTaskButton.setBackgroundColor(0xFF3170C7);
        startTaskButton.setOnClickListener(v -> startTaskButtonTapped());
        row1.addView(startTaskButton, new LinearLayout.LayoutParams(dp(95), dp(84)));

        panel.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        // Interval field
        intervalField = makeTextField("间隔 1-8 秒");
        intervalField.setText(getPrefs().getString("intervalRange", "1-8"));
        intervalField.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams iflp = new LinearLayout.LayoutParams(-1, dp(38));
        iflp.topMargin = dp(8);
        panel.addView(intervalField, iflp);

        // Reset + Mini mode
        LinearLayout row2 = new LinearLayout(app);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = dp(8);

        resetCountButton = makeColorButton("重置计数", 0xFF5E636F);
        resetCountButton.setOnClickListener(v -> { successCount = 0; persistDefaults(); refreshUI(); });
        row2.addView(resetCountButton, new LinearLayout.LayoutParams(0, dp(38), 1f));

        View sp2 = new View(app);
        row2.addView(sp2, new LinearLayout.LayoutParams(pad, 0));

        miniModeButton = makeColorButton("☐ 小窗模式", 0xFF232F42);
        miniModeButton.setOnClickListener(v -> toggleMiniMode());
        row2.addView(miniModeButton, new LinearLayout.LayoutParams(0, dp(38), 1f));

        panel.addView(row2, r2lp);

        // Log
        logScrollView = new ScrollView(app);
        logScrollView.setBackgroundColor(0xF20A0F1A);

        logView = new TextView(app);
        logView.setTextColor(0xFFC0DDE3);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        logScrollView.addView(logView, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams logLP = new LinearLayout.LayoutParams(-1, 0, 1f);
        logLP.topMargin = dp(10);
        panel.addView(logScrollView, logLP);

        panelView = panel;
        WindowManager.LayoutParams panelParams = makeOverlayParams(panelW, panelH);
        panelParams.x = dp(18);
        panelParams.y = dp(120);
        panelParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        windowManager.addView(panelView, panelParams);
        enableDrag(header, panelView);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildBubble() {
        Button bubble = new Button(app);
        bubble.setText("格界");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setAllCaps(false);
        bubble.setBackgroundColor(0xF0279EA1);
        bubble.setOnClickListener(v -> toggleCollapsed());

        bubbleView = bubble;
        WindowManager.LayoutParams bp = makeOverlayParams(dp(56), dp(56));
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.x = dp(12);
        bp.y = dp(300);
        windowManager.addView(bubbleView, bp);
        enableDrag(bubbleView, bubbleView);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildMiniView() {
        LinearLayout mini = new LinearLayout(app);
        mini.setOrientation(LinearLayout.VERTICAL);
        mini.setBackgroundColor(0xC00D1424);
        mini.setPadding(dp(8), dp(6), dp(8), dp(6));

        miniInfoLabel = new TextView(app);
        miniInfoLabel.setTextColor(0xFFD9F0EB);
        miniInfoLabel.setTextSize(11);
        miniInfoLabel.setTypeface(Typeface.MONOSPACE);
        miniInfoLabel.setMaxLines(3);
        mini.addView(miniInfoLabel);

        mini.setOnClickListener(v -> { isCollapsed = false; persistDefaults(); refreshUI(); });

        miniView = mini;
        WindowManager.LayoutParams mp = makeOverlayParams(dp(160), dp(52));
        mp.gravity = Gravity.TOP | Gravity.END;
        mp.x = dp(12);
        mp.y = dp(60);
        windowManager.addView(miniView, mp);
        enableDrag(miniView, miniView);

        // Risk control label
        riskControlLabel = new TextView(app);
        riskControlLabel.setText("此账号已风控！");
        riskControlLabel.setTextColor(Color.WHITE);
        riskControlLabel.setBackgroundColor(0xEBE64D40);
        riskControlLabel.setTextSize(15);
        riskControlLabel.setTypeface(Typeface.DEFAULT_BOLD);
        riskControlLabel.setGravity(Gravity.CENTER);
        riskControlLabel.setVisibility(View.GONE);

        WindowManager.LayoutParams rp = makeOverlayParams(-1, dp(44));
        rp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rp.y = dp(34);
        windowManager.addView(riskControlLabel, rp);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void enableDrag(View touchView, View wmView) {
        final float[] downXY = new float[2];
        final int[] origXY = new int[2];
        final boolean[] dragging = {false};

        touchView.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams lp;
            try {
                lp = (WindowManager.LayoutParams) wmView.getLayoutParams();
            } catch (ClassCastException e) {
                return false;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getRawX();
                    downXY[1] = event.getRawY();
                    origXY[0] = lp.x;
                    origXY[1] = lp.y;
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downXY[0];
                    float dy = event.getRawY() - downXY[1];
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                        dragging[0] = true;
                        lp.x = origXY[0] + (int) dx;
                        lp.y = origXY[1] + (int) dy;
                        try {
                            windowManager.updateViewLayout(wmView, lp);
                        } catch (Exception ignored) {}
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        dragging[0] = false;
                        return true;
                    }
                    return false;
            }
            return false;
        });
    }

    private EditText makeTextField(String hint) {
        EditText field = new EditText(app);
        field.setBackgroundColor(0xFF1C2433);
        field.setTextColor(0xFFF2F8FA);
        field.setHintTextColor(0xFF7A94AB);
        field.setHint(hint);
        field.setTextSize(13);
        field.setSingleLine(true);
        field.setPadding(dp(10), dp(6), dp(10), dp(6));
        return field;
    }

    private Button makeTextButton(String text, int textColor) {
        Button btn = new Button(app);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        btn.setBackgroundColor(Color.TRANSPARENT);
        return btn;
    }

    private Button makeColorButton(String text, int bgColor) {
        Button btn = new Button(app);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        btn.setBackgroundColor(bgColor);
        return btn;
    }

    // ==================== UI Actions ====================

    private void toggleCollapsed() {
        isCollapsed = !isCollapsed;
        persistDefaults();
        refreshUI();
    }

    private void toggleMiniMode() {
        isMiniMode = !isMiniMode;
        persistDefaults();
        refreshUI();
    }

    private void refreshUI() {
        mainHandler.post(() -> {
            if (panelView == null) return;

            String tokenText = (token != null && !token.isEmpty()) ? "已登录" : "未登录";
            String modeText = isRunning ? "运行中" : "已暂停";
            statusLabel.setText("状态: " + tokenText + " | " + modeText);

            if (currentTask != null && currentTask.itemID != null && currentTask.shopID != null) {
                taskLabel.setText("当前任务: shop=" + currentTask.shopID + "  item=" + currentTask.itemID);
            } else {
                taskLabel.setText("当前任务: 暂无");
            }

            counterLabel.setText("本地成功上传: " + successCount);

            startTaskButton.setText(isRunning ? "停止任务" : "启动任务");
            startTaskButton.setBackgroundColor(isRunning ? 0xFFCC5738 : 0xFF3170C7);

            miniModeButton.setText(isMiniMode ? "☑ 小窗模式" : "☐ 小窗模式");
            miniModeButton.setTextColor(isMiniMode ? 0xFF76E3DB : 0xFF8D9EB3);

            boolean showMini = isCollapsed && isMiniMode;
            panelView.setVisibility(isCollapsed ? View.GONE : View.VISIBLE);
            bubbleView.setVisibility(isCollapsed && !showMini ? View.VISIBLE : View.GONE);
            miniView.setVisibility(showMini ? View.VISIBLE : View.GONE);
            riskControlLabel.setVisibility(isRiskControlled ? View.VISIBLE : View.GONE);

            updateMiniContent();
        });
    }

    private void updateMiniContent() {
        if (miniInfoLabel == null) return;
        String status = isRunning ? "运行中" : "已暂停";
        String count = "成功:" + successCount;
        String countdown = "";
        if (nextFireTime > 0 && isRunning) {
            long remaining = (nextFireTime - System.currentTimeMillis()) / 1000;
            if (remaining > 0) countdown = " | " + remaining + "s";
        }
        miniInfoLabel.setText(status + " | " + count + countdown);
    }

    private void appendLog(String message) {
        if (message == null || message.isEmpty()) return;
        mainHandler.post(() -> {
            if (logView == null) return;
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String line = "[" + timestamp + "] " + message + "\n";
            logView.append(line);

            String text = logView.getText().toString();
            String[] lines = text.split("\n");
            if (lines.length > 80) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 80; i < lines.length; i++) {
                    sb.append(lines[i]).append("\n");
                }
                logView.setText(sb.toString());
            }

            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ==================== Task Control ====================

    private void startTaskButtonTapped() {
        saveCredentials();
        isRunning = !isRunning;
        persistDefaults();
        refreshUI();

        if (isRunning) {
            isRiskControlled = false;
            consecutivePDPFailures = 0;
            token = null;
            persistDefaults();
            appendLog("已启动,重新登录");
            login(success -> {
                if (success) {
                    scheduleNextTaskCycle(null, true);
                } else {
                    isRunning = false;
                    persistDefaults();
                    refreshUI();
                }
            });
        } else {
            cancelScheduledCycle();
            appendLog("已停止");
        }
    }

    private void saveCredentials() {
        if (usernameField != null && usernameField.getText().length() > 0)
            getPrefs().edit().putString("username", usernameField.getText().toString()).apply();
        if (passwordField != null && passwordField.getText().length() > 0)
            getPrefs().edit().putString("password", passwordField.getText().toString()).apply();
    }

    private void scheduleNextTaskCycle(String reason, boolean immediate) {
        mainHandler.post(() -> {
            cancelScheduledCycle();
            if (!isRunning) return;

            long delay = immediate ? 200 : randomTaskDelay();
            if (!immediate) {
                appendLog((delay / 1000) + "s后继续");
            }

            nextFireTime = System.currentTimeMillis() + delay;
            pendingPollRunnable = this::pollTimerFired;
            mainHandler.postDelayed(pendingPollRunnable, delay);
            startCountdownUpdater();
        });
    }

    private void cancelScheduledCycle() {
        if (pendingPollRunnable != null) {
            mainHandler.removeCallbacks(pendingPollRunnable);
            pendingPollRunnable = null;
        }
        if (pendingCountdownRunnable != null) {
            mainHandler.removeCallbacks(pendingCountdownRunnable);
            pendingCountdownRunnable = null;
        }
        nextFireTime = 0;
    }

    private void startCountdownUpdater() {
        if (!isMiniMode) return;
        pendingCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                updateMiniContent();
                if (nextFireTime > 0 && isRunning) {
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
        mainHandler.postDelayed(pendingCountdownRunnable, 1000);
    }

    private void pollTimerFired() {
        pendingPollRunnable = null;
        nextFireTime = 0;
        if (!isRunning) return;
        if (currentTask != null) {
            openCurrentTask();
            return;
        }
        fetchTask(false);
    }

    private long randomTaskDelay() {
        String raw = intervalField != null ? intervalField.getText().toString() : getPrefs().getString("intervalRange", "1-8");
        raw = raw.replace("秒", "").replace("s", "").replace("~", "-").replace("—", "-").replace("－", "-").replace(" ", "");

        int minVal = 1, maxVal = 8;
        String[] parts = raw.split("-");
        try {
            if (parts.length >= 2) {
                int f = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                if (f > 0) minVal = f;
                if (s > 0) maxVal = s;
            } else if (!raw.isEmpty()) {
                int v = Integer.parseInt(raw);
                if (v > 0) { minVal = v; maxVal = v; }
            }
        } catch (NumberFormatException ignored) {}

        minVal = Math.max(1, Math.min(minVal, 60));
        maxVal = Math.max(1, Math.min(maxVal, 60));
        if (minVal > maxVal) { int t = minVal; minVal = maxVal; maxVal = t; }

        int seconds = minVal >= maxVal ? minVal : minVal + random.nextInt(maxVal - minVal + 1);
        return seconds * 1000L;
    }

    private void finishCurrentTaskAndContinue(boolean success, String reason) {
        mainHandler.post(() -> {
            submittingCurrentTask = false;
            if (success) successCount++;

            currentTask = null;
            clearPendingState();
            persistDefaults();
            refreshUI();

            if (success) {
                appendLog("成功#" + successCount);
            } else if (reason != null && !reason.isEmpty()) {
                appendLog(reason);
            }

            if (isRunning) {
                scheduleNextTaskCycle(success ? "任务完成" : "任务失败，继续下一条", false);
            }
        });
    }

    private void clearPendingState() {
        waitingForPDP = false;
    }

    // ==================== Networking ====================

    private interface LoginCallback {
        void onResult(boolean success);
    }

    private void login(LoginCallback callback) {
        String username = usernameField != null ? usernameField.getText().toString().trim() : getPrefs().getString("username", "");
        String password = passwordField != null ? passwordField.getText().toString().trim() : getPrefs().getString("password", "");

        if (username.isEmpty() || password.isEmpty()) {
            appendLog("请填写账号密码");
            refreshUI();
            if (callback != null) callback.onResult(false);
            return;
        }

        appendLog("登录中...");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("password", password);

                JSONResult result = httpRequest(LOGIN_URL, "POST", body, false);

                if (result.error != null) {
                    appendLog("登录失败:" + result.error);
                    if (callback != null) mainHandler.post(() -> callback.onResult(false));
                    return;
                }

                String extractedToken = extractToken(result.json, result.raw);
                if (extractedToken == null) {
                    appendLog("登录无token HTTP=" + result.statusCode);
                    if (callback != null) mainHandler.post(() -> callback.onResult(false));
                    return;
                }

                token = extractedToken;
                persistDefaults();
                appendLog("登录成功");
                refreshUI();
                if (callback != null) mainHandler.post(() -> callback.onResult(true));

            } catch (Exception e) {
                appendLog("登录异常:" + e.getMessage());
                if (callback != null) mainHandler.post(() -> callback.onResult(false));
            }
        });
    }

    private String extractToken(JSONObject json, String raw) {
        if (json != null) {
            String[] keys = {"access_token", "accessToken", "token", "jwt"};
            for (String key : keys) {
                String val = json.optString(key, null);
                if (val != null && !val.isEmpty()) return val;
            }
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                for (String key : keys) {
                    String val = data.optString(key, null);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        }

        if (raw != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(?:access_token|accessToken|token|jwt)\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(raw);
            if (m.find()) return m.group(1);
        }

        return null;
    }

    private void fetchTask(boolean force) {
        if (requestInFlight || submittingCurrentTask) return;

        if (currentTask != null) {
            if (force) appendLog("已有任务");
            return;
        }

        if (token == null || token.isEmpty()) {
            appendLog("自动登录");
            login(success -> {
                if (success) fetchTask(force);
                else if (isRunning) scheduleNextTaskCycle("登录失败", false);
            });
            return;
        }

        requestInFlight = true;
        appendLog("取任务...");

        executor.execute(() -> {
            try {
                JSONResult result = httpRequest(TAKE_TASK_URL, "GET", null, true);
                requestInFlight = false;

                if (result.statusCode == 401) {
                    token = null;
                    persistDefaults();
                    appendLog("401,重新登录");
                    login(success -> {
                        if (success) fetchTask(force);
                        else if (isRunning) scheduleNextTaskCycle("重新登录失败", false);
                    });
                    return;
                }

                if (result.error != null) {
                    appendLog("取任务失败:" + result.error);
                    if (isRunning) scheduleNextTaskCycle("取任务失败", false);
                    return;
                }

                if (result.json == null) {
                    appendLog("非JSON:" + (result.raw != null ? result.raw.substring(0, Math.min(200, result.raw.length())) : ""));
                    if (isRunning) scheduleNextTaskCycle("任务返回非JSON", false);
                    return;
                }

                SHPTask task = SHPTask.fromServerResponse(result.json);
                if (task == null) {
                    String bizCode = result.json.optString("code", "-");
                    String bizMsg = result.json.optString("msg", "-");
                    appendLog("无任务 code=" + bizCode + " msg=" + bizMsg);
                    if (isRunning) scheduleNextTaskCycle("暂未取到任务", false);
                    return;
                }

                mainHandler.post(() -> {
                    currentTask = task;
                    persistDefaults();
                    refreshUI();
                    appendLog("任务 " + task.shopID + "/" + task.itemID);
                    openCurrentTask();
                });

            } catch (Exception e) {
                requestInFlight = false;
                appendLog("取任务异常:" + e.getMessage());
                if (isRunning) scheduleNextTaskCycle("取任务异常", false);
            }
        });
    }

    private void openCurrentTask() {
        mainHandler.post(() -> {
            if (currentTask == null) {
                appendLog("没有待打开的任务");
                return;
            }

            waitingForPDP = true;

            // Open Shopee product page via deep link
            try {
                String productUrl = currentTask.productURL != null ? currentTask.productURL :
                        SHPTask.buildProductURL(currentTask.shopID, currentTask.itemID);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(productUrl));
                intent.setPackage("com.shopee.tw");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(intent);
                appendLog("跳转成功");
            } catch (Exception e) {
                appendLog("跳转失败:" + e.getMessage());
                try {
                    String url = "shopee://product/" + currentTask.shopID + "/" + currentTask.itemID;
                    Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(fallback);
                    appendLog("备用跳转成功");
                } catch (Exception e2) {
                    appendLog("跳转失败:无法打开");
                }
            }

            // PDP timeout (15 seconds)
            String currentItemID = currentTask.itemID;
            mainHandler.postDelayed(() -> {
                if (!waitingForPDP || submittingCurrentTask || currentTask == null) return;
                if (!currentTask.itemID.equals(currentItemID)) return;

                consecutivePDPFailures++;
                if (consecutivePDPFailures >= 3) {
                    appendLog("PDP连续超时,疑似风控");
                    handleRiskControlDetected();
                } else {
                    appendLog("PDP超时,跳过");
                    finishCurrentTaskAndContinue(false, "PDP超时");
                }
            }, 15000);
        });
    }

    // ==================== PDP Inspection ====================

    public void inspectCapturedData(String jsonString, String urlString) {
        if (jsonString == null || jsonString.isEmpty() || urlString == null) return;
        if (!urlString.contains("/api/v4/pdp/")) return;

        mainHandler.post(() -> {
            if (currentTask == null || submittingCurrentTask || !waitingForPDP) return;

            if (currentTask.itemID != null && !urlString.contains(currentTask.itemID)) return;
            if (currentTask.shopID != null && !urlString.contains(currentTask.shopID)) return;

            if (jsonString.length() < 10240) {
                int errorCode = 0;
                try {
                    JSONObject obj = new JSONObject(jsonString);
                    errorCode = obj.optInt("error", 0);
                } catch (Exception ignored) {}

                int ec = errorCode;
                appendLog("PDP响应偏小 error=" + ec + ",等待完整数据...");

                String cid = currentTask.itemID;
                mainHandler.postDelayed(() -> {
                    if (!waitingForPDP || currentTask == null) return;
                    if (cid != null && !cid.equals(currentTask.itemID)) return;

                    consecutivePDPFailures++;
                    waitingForPDP = false;
                    appendLog("PDP异常 error=" + ec + " 连续" + consecutivePDPFailures + "次");
                    if (consecutivePDPFailures >= 3) {
                        handleRiskControlDetected();
                    } else {
                        finishCurrentTaskAndContinue(false, "PDP数据异常");
                    }
                }, 3000);
                return;
            }

            consecutivePDPFailures = 0;
            waitingForPDP = false;
            appendLog("捕获PDP,提交");
            submitCapturedJSON(jsonString, urlString);
        });
    }

    public void inspectParsedJSON(String raw) {
        if (raw == null || raw.length() < 10240) return;
        if (currentTask == null || submittingCurrentTask || !waitingForPDP) return;

        if (currentTask.itemID != null && !raw.contains(currentTask.itemID)) return;
        if (currentTask.shopID != null && !raw.contains(currentTask.shopID)) return;

        mainHandler.post(() -> {
            if (!waitingForPDP || currentTask == null || submittingCurrentTask) return;
            appendLog("JSON兜底命中");
            consecutivePDPFailures = 0;
            waitingForPDP = false;
            String sourceURL = currentTask.pdpURL != null ? currentTask.pdpURL :
                    SHPTask.buildPDPURL(currentTask.shopID, currentTask.itemID);
            submitCapturedJSON(raw, sourceURL);
        });
    }

    // ==================== Submit ====================

    private void submitCapturedJSON(String jsonString, String sourceURL) {
        if (jsonString == null || jsonString.isEmpty() || currentTask == null || submittingCurrentTask) return;

        submittingCurrentTask = true;
        String submitURL = sourceURL != null ? sourceURL : currentTask.pdpURL;
        if (submitURL == null) submitURL = SHPTask.buildPDPURL(currentTask.shopID, currentTask.itemID);

        final String finalURL = submitURL;
        appendLog("提交中...");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("appVersion", SUBMIT_APP_VERSION);
                body.put("url", finalURL != null ? finalURL : "");
                body.put("result", jsonString);

                JSONResult result = httpRequest(SUBMIT_TASK_URL, "POST", body, true);

                if (result.error != null) {
                    appendLog("提交失败:" + result.error);
                    finishCurrentTaskAndContinue(false, "提交失败,跳过");
                    return;
                }

                appendLog("提交HTTP=" + result.statusCode);

                if (result.statusCode == 401) {
                    token = null;
                    persistDefaults();
                    appendLog("提交401,重登");
                    finishCurrentTaskAndContinue(false, "提交失败,跳过");
                    return;
                }

                if (result.statusCode < 200 || result.statusCode >= 300) {
                    finishCurrentTaskAndContinue(false, "提交失败HTTP=" + result.statusCode);
                    return;
                }

                if (result.json != null) {
                    String respCode = result.json.optString("code", "");
                    String respMsg = result.json.optString("msg", result.json.optString("message", ""));

                    if (!respCode.isEmpty() && !respCode.equals("200")) {
                        appendLog("提交失败:" + (respMsg.isEmpty() ? respCode : respMsg));
                        finishCurrentTaskAndContinue(false, null);
                        return;
                    }

                    if (!respMsg.isEmpty()) {
                        String lower = respMsg.toLowerCase();
                        if (lower.contains("不正确") || lower.contains("失败") || lower.contains("错误")
                                || lower.contains("invalid") || lower.contains("fail") || lower.contains("error")) {
                            appendLog("提交失败:" + respMsg);
                            finishCurrentTaskAndContinue(false, null);
                            return;
                        }
                    }
                }

                finishCurrentTaskAndContinue(true, null);

            } catch (Exception e) {
                appendLog("提交异常:" + e.getMessage());
                finishCurrentTaskAndContinue(false, "提交异常");
            }
        });
    }

    // ==================== Risk Control ====================

    private void handleRiskControlDetected() {
        mainHandler.post(() -> {
            if (isRiskControlled) return;
            isRiskControlled = true;

            isRunning = false;
            cancelScheduledCycle();
            currentTask = null;
            clearPendingState();
            persistDefaults();

            appendLog("检测到风控,已自动停止");
            refreshUI();
        });
    }

    // ==================== HTTP Client ====================

    private static class JSONResult {
        int statusCode;
        JSONObject json;
        String raw;
        String error;
    }

    private JSONResult httpRequest(String urlString, String method, JSONObject body, boolean authorized) {
        JSONResult result = new JSONResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            if (authorized && token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
                conn.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }

            result.statusCode = conn.getResponseCode();

            java.io.InputStream is = result.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                result.raw = sb.toString();
            }

            if (result.raw != null && !result.raw.isEmpty()) {
                try {
                    result.json = new JSONObject(result.raw);
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            result.error = e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }
}
