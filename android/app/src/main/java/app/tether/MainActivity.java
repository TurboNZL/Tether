package app.tether;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CallManager.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_MIC_TEST = 3;
    private static final String PREFS = "tether";
    private static final String PREF_WEB_URL = "webUrl";
    private static final String PREF_VOLUME = "volume";
    private static final String PREF_BRIGHTNESS = "brightness";
    private static final String PREF_SESSION_URL = "sessionUrl";
    private static final int MAX_LOG_CHARS = 8000;
    private static final int[] TIMER_MINUTES = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};
    private static final String TIMER_HELP = "Ends the call on this phone only, after the time you pick. "
            + "The other person stays connected until they leave. The countdown starts when the call connects.";

    private final List<AudioDeviceInfo> mics = new ArrayList<>();
    private int selectedMic;

    private FrameLayout root;
    private LinearLayout content;
    private View dimOverlay;
    private TextView brightnessValue;
    private GlassSlider brightnessSlider;
    private FrameLayout dock;
    private GlassButton startButton;
    private LinearLayout activeDock;
    private GlassButton muteButton;
    private GlassButton shareButton;
    private GlassButton endButton;
    private StatusPill statusPill;
    private TextView micValue;
    private final MicTester micTester = new MicTester();
    private TextView micTestButton;
    private TextView micTestStatus;
    private LevelMeterView micMeter;
    private boolean micHeard;
    private long micTestStarted;
    private LinearLayout micList;
    private GlyphView micChevron;
    private EditText webUrl;
    private EditText roomInput;
    private EditText sessionUrl;
    private TextView roomCode;
    private TextView volumeValue;
    private GlassSlider volumeSlider;
    private TextView keepAliveValue;
    private TextView logView;
    private TextView timerValue;
    private TextView timerHint;
    private LinearLayout timerList;
    private GlyphView timerChevron;
    private int timerIndex;

    private String pendingJoinRoom; // null = host
    private boolean pendingStart;
    private String currentRoom;
    private boolean dockActive;
    private boolean dockShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hardware volume keys control media volume, which is what the call audio plays on.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setupWindow();
        buildUi();
        refreshMics();
        CallManager call = CallManager.get();
        call.setVolume(prefs().getFloat(PREF_VOLUME, 1f));
        call.setListener(this);
        syncWithCall();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshKeepAlive();
    }

    @Override
    protected void onPause() {
        stopMicTest(); // the mic is not ours to hold while the screen is in the background
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // The call itself lives in CallManager + CallService; only the view of it goes away.
        CallManager.get().setListener(null);
        super.onDestroy();
    }

    // ---- window ----------------------------------------------------------------------------

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---- UI --------------------------------------------------------------------------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF06070C);
        root.addView(new AuroraBackground(this), match());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        // Scroll-edge effect: the content itself dissolves under the status bar and the dock.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(this, 56));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 120));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, match());


        buildHeader();
        buildMicCard();
        buildVolumeCard();
        buildDisplayCard();
        buildTimerCard();
        buildInviteCard();
        buildJoinCard();
        buildSessionCard();
        buildKeepAliveCard();
        buildDiagnosticsCard();

        buildDock();
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setClickable(false);
        dimOverlay.setFocusable(false);
        root.addView(dimOverlay, match());
        applyBrightness(prefs().getFloat(PREF_BRIGHTNESS, 1f));
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(Ui.dp(this, 16), top + Ui.dp(this, 20), Ui.dp(this, 16), bottom + Ui.dp(this, 120));
            FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) dock.getLayoutParams();
            dlp.bottomMargin = bottom + Ui.dp(this, 14);
            dock.setLayoutParams(dlp);
            return insets;
        });

        animateCardsIn();
    }

    private void buildHeader() {
        TextView title = Ui.text(this, "Tether", 38f, Ui.TEXT_PRIMARY, "semibold");
        content.addView(title, lp(-2, -2, 4, 0, 0, 0));
        TextView sub = Ui.text(this, "A private audio line.", 15f,
                Ui.TEXT_SECONDARY, "sans-serif");
        content.addView(sub, lp(-2, -2, 0, 8, 0, 0));
        statusPill = new StatusPill(this);
        LinearLayout.LayoutParams pl = lp(-2, -2, 0, 18, 0, 6);
        content.addView(statusPill, pl);
    }

    private void buildMicCard() {
        GlassPanel card = card("Microphone");
        LinearLayout body = (LinearLayout) card.getChildAt(2);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple(14));
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        row.setClickable(true);
        micValue = Ui.text(this, "", 17f, Ui.TEXT_PRIMARY, "sans-serif");
        row.addView(micValue, new LinearLayout.LayoutParams(0, -2, 1f));
        micChevron = new GlyphView(this, GlyphView.CHEVRON_DOWN, Ui.TEXT_SECONDARY);
        row.addView(micChevron, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        body.addView(row);

        micList = new LinearLayout(this);
        micList.setOrientation(LinearLayout.VERTICAL);
        micList.setVisibility(View.GONE);
        body.addView(micList);

        TextView hint = Ui.text(this, "Pick the phone's own mic so a Bluetooth headset never has to switch "
                + "into its low quality call mode.", 13f, Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint, lp(-1, -2, 0, 6, 0, 0));
        buildMicTest(body);

        row.setOnClickListener(v -> {
            boolean open = micList.getVisibility() != View.VISIBLE;
            micList.setVisibility(open ? View.VISIBLE : View.GONE);
            micChevron.animate().rotation(open ? 180f : 0f).setDuration(220).start();
        });
        content.addView(card, lp(-1, -2, 0, 10, 0, 0));
    }

    private void buildMicTest(LinearLayout body) {
        micTestButton = Ui.text(this, "Test microphone", 16f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        micTestButton.setGravity(Gravity.CENTER);
        micTestButton.setBackground(flatButton());
        micTestButton.setClickable(true);
        micTestButton.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        micTestButton.setOnClickListener(v -> toggleMicTest());
        body.addView(micTestButton, lp(-1, -2, 0, 12, 0, 0));
        micMeter = new LevelMeterView(this);
        micMeter.setVisibility(View.GONE);
        body.addView(micMeter, lp(-1, Ui.dp(this, 14), 0, 10, 0, 0));
        micTestStatus = Ui.text(this, "", 13f, Ui.TEXT_TERTIARY, "sans-serif");
        micTestStatus.setVisibility(View.GONE);
        body.addView(micTestStatus, lp(-1, -2, 0, 6, 0, 0));
    }

    private void toggleMicTest() {
        if (micTester.isRunning()) {
            stopMicTest();
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC_TEST);
        } else {
            startMicTest();
        }
    }

    private void startMicTest() {
        AudioDeviceInfo mic = selectedMic >= 0 && selectedMic < mics.size() ? mics.get(selectedMic) : null;
        micHeard = false;
        micTestStarted = System.currentTimeMillis();
        micMeter.reset();
        micMeter.setVisibility(View.VISIBLE);
        micTestStatus.setVisibility(View.VISIBLE);
        micTestStatus.setText("Speak - the bar should move.");
        micTestStatus.setTextColor(Ui.TEXT_TERTIARY);
        micTestButton.setText("Stop test");
        micTester.start(mic, new MicTester.Listener() {
            @Override
            public void onLevel(float rms) {
                micMeter.setRms(rms);
                if (rms > 0.01f) micHeard = true;
                if (micHeard) {
                    micTestStatus.setText("Mic is working.");
                    micTestStatus.setTextColor(Ui.GREEN);
                } else if (System.currentTimeMillis() - micTestStarted > 4000) {
                    micTestStatus.setText("No sound yet. Speak, or pick another microphone above.");
                    micTestStatus.setTextColor(Ui.AMBER);
                }
            }

            @Override
            public void onError(String message) {
                micTestButton.setText("Test microphone");
                micMeter.reset();
                micTestStatus.setText(message);
                micTestStatus.setTextColor(Ui.RED);
            }
        });
    }

    private void stopMicTest() {
        micTester.stop();
        micTestButton.setText("Test microphone");
        micMeter.reset();
    }

    private void buildVolumeCard() {
        GlassPanel card = card("Call volume");
        LinearLayout body = (LinearLayout) card.getChildAt(2);

        volumeValue = Ui.text(this, "", 34f, Ui.TEXT_PRIMARY, "sans-serif");
        body.addView(volumeValue, lp(-2, -2, 0, 0, 0, 4));

        volumeSlider = new GlassSlider(this, 2f, 1f);
        float saved = prefs().getFloat(PREF_VOLUME, 1f);
        volumeSlider.setValue(saved);
        updateVolumeLabel(saved);
        volumeSlider.setListener((value, fromUser) -> {
            updateVolumeLabel(value);
            if (fromUser) {
                CallManager.get().setVolume(value);
                prefs().edit().putFloat(PREF_VOLUME, value).apply();
            }
        });
        body.addView(volumeSlider, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = Ui.text(this, "Only the other person's voice. Your phone's media volume stays "
                + "where it is, so you can mix them separately. Past 100% boosts the voice.", 13f,
                Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint, lp(-1, -2, 0, 6, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void updateVolumeLabel(float value) {
        int pct = Math.round(value * 100f);
        volumeValue.setText(String.format(Locale.US, "%d%%", pct));
        volumeSlider.setContentDescription("Call volume " + pct + " percent");
    }

    private void buildDisplayCard() {
        GlassPanel card = card("Brightness");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        brightnessValue = Ui.text(this, "", 34f, Ui.TEXT_PRIMARY, "sans-serif");
        body.addView(brightnessValue, lp(-2, -2, 0, 0, 0, 4));
        brightnessSlider = new GlassSlider(this, 1f, 0f);
        float saved = prefs().getFloat(PREF_BRIGHTNESS, 1f);
        brightnessSlider.setValue(saved);
        applyBrightness(saved);
        brightnessSlider.setListener((value, fromUser) -> {
            applyBrightness(value);
            if (fromUser) prefs().edit().putFloat(PREF_BRIGHTNESS, value).apply();
        });
        body.addView(brightnessSlider, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.text(this, "Dims this app below your screen's minimum brightness. "
                + "Your phone's own brightness setting is unchanged.", 13f, Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint, lp(-1, -2, 0, 6, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void buildTimerCard() {
        GlassPanel card = card("Hang up timer");
        LinearLayout body = (LinearLayout) card.getChildAt(2);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple(14));
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        row.setClickable(true);
        timerValue = Ui.text(this, "", 17f, Ui.TEXT_PRIMARY, "sans-serif");
        row.addView(timerValue, new LinearLayout.LayoutParams(0, -2, 1f));
        timerChevron = new GlyphView(this, GlyphView.CHEVRON_DOWN, Ui.TEXT_SECONDARY);
        row.addView(timerChevron, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        body.addView(row);

        timerList = new LinearLayout(this);
        timerList.setOrientation(LinearLayout.VERTICAL);
        timerList.setVisibility(View.GONE);
        body.addView(timerList);

        timerHint = Ui.text(this, TIMER_HELP, 13f, Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(timerHint, lp(-1, -2, 0, 6, 0, 0));

        row.setOnClickListener(v -> {
            boolean open = timerList.getVisibility() != View.VISIBLE;
            timerList.setVisibility(open ? View.VISIBLE : View.GONE);
            timerChevron.animate().rotation(open ? 180f : 0f).setDuration(220).start();
        });
        renderTimerList();
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private static String timerLabel(int minutes) {
        if (minutes == 0) return "Off";
        if (minutes < 60) return "After " + minutes + " minutes";
        if (minutes == 60) return "After 1 hour";
        if (minutes % 60 == 0) return "After " + minutes / 60 + " hours";
        return "After " + minutes / 60 + "\u00BD hours";
    }

    private void renderTimerList() {
        timerValue.setText(timerLabel(TIMER_MINUTES[timerIndex]));
        timerList.removeAllViews();
        for (int i = 0; i < TIMER_MINUTES.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ripple(12));
            row.setClickable(true);
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 11), Ui.dp(this, 4), Ui.dp(this, 11));
            boolean selected = i == timerIndex;
            TextView name = Ui.text(this, timerLabel(TIMER_MINUTES[i]), 15f,
                    selected ? Ui.TEXT_PRIMARY : Ui.TEXT_SECONDARY, "sans-serif");
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            if (selected) {
                row.addView(new GlyphView(this, GlyphView.CHECK, Ui.GREEN),
                        new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
            }
            row.setOnClickListener(v -> {
                timerIndex = index;
                renderTimerList();
                CallManager.get().setHangupAfter(TIMER_MINUTES[index] * 60_000L);
            });
            timerList.addView(row);
        }
    }

    /** Picks the list entry matching the manager's current setting (e.g. after the activity was recreated). */
    private void syncTimer(CallManager call) {
        timerIndex = 0;
        for (int i = 0; i < TIMER_MINUTES.length; i++) {
            if (TIMER_MINUTES[i] * 60_000L == call.getHangupAfterMs()) timerIndex = i;
        }
        renderTimerList();
        onHangupTimer(call.getHangupRemainingMs());
    }

    private void applyBrightness(float value) {
        brightnessValue.setText(String.format(Locale.US, "%d%%", Math.round(value * 100f)));
        brightnessSlider.setContentDescription("Brightness " + Math.round(value * 100f) + " percent");
        // A black veil over everything, including the dock; on OLED black pixels emit no light.
        if (dimOverlay != null) dimOverlay.setAlpha((1f - value) * 0.9f);
    }

    private void buildInviteCard() {
        GlassPanel card = card("Invite link");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        webUrl = field("https://your-site.netlify.app");
        webUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        webUrl.setText(prefs().getString(PREF_WEB_URL, ""));
        body.addView(webUrl, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.text(this, "The address of the web page, used to build the link you send. "
                + "Start a call and the room code appears here.", 13f, Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint, lp(-1, -2, 0, 8, 0, 0));
        roomCode = Ui.text(this, "", 30f, Ui.TEXT_PRIMARY, "semibold");
        roomCode.setLetterSpacing(0.1f);
        roomCode.setTextIsSelectable(true);
        roomCode.setVisibility(View.GONE);
        body.addView(roomCode, lp(-2, -2, 0, 10, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void buildJoinCard() {
        GlassPanel card = card("Join a call");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        roomInput = field("room code or link");
        body.addView(roomInput, new LinearLayout.LayoutParams(-1, -2));
        TextView join = Ui.text(this, "Join", 16f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        join.setGravity(Gravity.CENTER);
        join.setBackground(flatButton());
        join.setClickable(true);
        join.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        join.setOnClickListener(v -> {
            String room = extractRoom(roomInput.getText().toString());
            if (room.isEmpty()) {
                onLog("enter a room code first");
            } else {
                requestStart(room);
            }
        });
        body.addView(join, lp(-1, -2, 0, 12, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void buildSessionCard() {
        GlassPanel card = card("Web session");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        sessionUrl = field("https://…");
        sessionUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        sessionUrl.setText(prefs().getString(PREF_SESSION_URL, ""));
        body.addView(sessionUrl, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.text(this, "Opens a web call page in an embedded browser (desktop layout) that keeps "
                + "running with the screen off. Has a hang up timer that closes the page, and a mic selection "
                + "shim that prefers the phone's own mic over a Bluetooth headset.", 13f,
                Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint, lp(-1, -2, 0, 8, 0, 0));
        TextView open = Ui.text(this, "Open", 16f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        open.setGravity(Gravity.CENTER);
        open.setBackground(flatButton());
        open.setClickable(true);
        open.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        open.setOnClickListener(v -> openSession());
        body.addView(open, lp(-1, -2, 0, 12, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void openSession() {
        String url = sessionUrl.getText().toString().trim();
        if (url.isEmpty()) {
            onLog("enter the page address first");
            return;
        }
        if (!url.contains("://")) url = "https://" + url;
        if (!url.startsWith("https://")) {
            onLog("only https addresses can be opened");
            return;
        }
        prefs().edit().putString(PREF_SESSION_URL, url).apply();
        startActivity(new Intent(this, WebSessionActivity.class).putExtra(WebSessionActivity.EXTRA_URL, url));
    }

    private void buildKeepAliveCard() {
        GlassPanel card = card("Background");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple(14));
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, "Stay connected with the screen off", 17f, Ui.TEXT_PRIMARY, "sans-serif"));
        keepAliveValue = Ui.text(this, "", 13f, Ui.TEXT_SECONDARY, "sans-serif");
        text.addView(keepAliveValue, lp(-2, -2, 0, 3, 0, 0));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> requestBatteryExemption());
        body.addView(row);
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void buildDiagnosticsCard() {
        GlassPanel card = card("Diagnostics");
        LinearLayout body = (LinearLayout) card.getChildAt(2);
        TextView hint = Ui.text(this, "Check mode, a2dpOn and the incoming codec during a call.", 13f,
                Ui.TEXT_TERTIARY, "sans-serif");
        body.addView(hint);
        logView = Ui.text(this, "", 11.5f, Ui.TEXT_SECONDARY, "monospace");
        logView.setTextIsSelectable(true);
        logView.setIncludeFontPadding(true);
        body.addView(logView, lp(-1, -2, 0, 8, 0, 0));
        content.addView(card, lp(-1, -2, 0, 14, 0, 0));
    }

    private void buildDock() {
        dock = new FrameLayout(this);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, Ui.dp(this, 64), Gravity.BOTTOM);
        dl.leftMargin = Ui.dp(this, 16);
        dl.rightMargin = Ui.dp(this, 16);
        dl.bottomMargin = Ui.dp(this, 14);
        root.addView(dock, dl);

        startButton = new GlassButton(this, GlyphView.PLAY, "Start call");
        startButton.setTint(Ui.GREEN, 0.34f);
        startButton.setOnClickListener(v -> requestStart(null));
        dock.addView(startButton, match());

        activeDock = new LinearLayout(this);
        activeDock.setOrientation(LinearLayout.HORIZONTAL);
        muteButton = new GlassButton(this, GlyphView.MIC, "Mute");
        muteButton.setOnClickListener(v -> CallManager.get().setMuted(!CallManager.get().isMuted()));
        shareButton = new GlassButton(this, GlyphView.SHARE, "Share");
        shareButton.setOnClickListener(v -> share());
        endButton = new GlassButton(this, GlyphView.END, "End");
        endButton.setTint(Ui.RED, 0.42f);
        endButton.setOnClickListener(v -> CallManager.get().hangup("you hung up"));
        int gap = Ui.dp(this, 10);
        activeDock.addView(muteButton, new LinearLayout.LayoutParams(0, -1, 1.25f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -1, 1f);
        sl.leftMargin = gap;
        activeDock.addView(shareButton, sl);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -1, 1f);
        el.leftMargin = gap;
        activeDock.addView(endButton, el);
        dock.addView(activeDock, match());
        activeDock.setVisibility(View.GONE);
    }

    private void showDock(boolean active) {
        if (dockShown && dockActive == active) return;
        dockShown = true;
        dockActive = active;
        View in = active ? activeDock : startButton;
        View out = active ? startButton : activeDock;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        if (active) {
            for (int i = 0; i < activeDock.getChildCount(); i++) {
                final GlassPanel p = (GlassPanel) activeDock.getChildAt(i);
                if (SceneClock.isReducedMotion(this)) continue;
                p.postDelayed(p::materialise, 50L * i);
            }
        } else if (!SceneClock.isReducedMotion(this)) {
            startButton.materialise();
        }
    }

    private void animateCardsIn() {
        if (SceneClock.isReducedMotion(this)) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(this, 18));
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(520)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
    }

    // ---- view factories --------------------------------------------------------------------

    /** A glass card with a title; children 0/1 are the glass layers, 2 is the vertical body. */
    private GlassPanel card(String title) {
        GlassPanel card = new GlassPanel(this);
        card.setCornerRadiusDp(30f);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        body.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 18));
        TextView t = Ui.text(this, title, 14f, Ui.TEXT_SECONDARY, "sans-serif-medium");
        body.addView(t, lp(-2, -2, 0, 0, 0, 8));
        card.addView(body, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setHint(hint);
        e.setTextColor(Ui.TEXT_PRIMARY);
        e.setHintTextColor(Ui.TEXT_TERTIARY);
        e.setTextSize(16f);
        e.setHighlightColor(0x555B8CFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59000000);
        bg.setCornerRadius(Ui.dpf(this, 16f));
        bg.setStroke(Ui.dp(this, 1), 0x24FFFFFF);
        e.setBackground(bg);
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));
        return e;
    }

    private GradientDrawable flatButton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x24FFFFFF);
        bg.setCornerRadius(Ui.dpf(this, 100f));
        bg.setStroke(Ui.dp(this, 1), 0x33FFFFFF);
        return bg;
    }

    private RippleDrawable ripple(float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(Ui.dpf(this, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), new ColorDrawable(Color.TRANSPARENT), mask);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(Ui.dp(this, l), Ui.dp(this, t), Ui.dp(this, r), Ui.dp(this, b));
        return p;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---- microphones -----------------------------------------------------------------------

    private void refreshMics() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mics.clear();
        int preferred = -1;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC && preferred < 0) preferred = mics.size();
            mics.add(d);
        }
        selectedMic = Math.max(preferred, 0);
        renderMicList();
    }

    private void renderMicList() {
        micList.removeAllViews();
        if (mics.isEmpty()) {
            micValue.setText("System default");
            return;
        }
        micValue.setText(CallManager.describe(mics.get(selectedMic)));
        for (int i = 0; i < mics.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ripple(12));
            row.setClickable(true);
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 11), Ui.dp(this, 4), Ui.dp(this, 11));
            boolean selected = i == selectedMic;
            TextView name = Ui.text(this, CallManager.describe(mics.get(i)), 15f,
                    selected ? Ui.TEXT_PRIMARY : Ui.TEXT_SECONDARY, "sans-serif");
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            if (selected) {
                row.addView(new GlyphView(this, GlyphView.CHECK, Ui.GREEN),
                        new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
            }
            row.setOnClickListener(v -> {
                selectedMic = index;
                renderMicList();
                if (micTester.isRunning()) {
                    stopMicTest();
                    startMicTest();
                }
            });
            micList.addView(row);
        }
    }

    // ---- call state ------------------------------------------------------------------------

    /** Re-show state if the activity was recreated during a running call. */
    private void syncWithCall() {
        CallManager call = CallManager.get();
        logView.setText(call.getLog());
        onStatus(call.getStatus());
        setCallControls(call.isActive());
        updateMute(call.isMuted());
        syncTimer(call);
        if (call.isActive() && currentRoom != null) onRoomReady(currentRoom);
    }

    private void setCallControls(boolean active) {
        showDock(active);
        webUrl.setEnabled(!active);
        roomInput.setEnabled(!active);
        micList.setEnabled(!active);
        if (!active) {
            roomCode.setVisibility(View.GONE);
            shareButton.setVisibility(View.VISIBLE);
            updateMute(false);
        }
    }

    private void updateMute(boolean muted) {
        if (muted) {
            muteButton.set(GlyphView.MIC_OFF, "Unmute");
            muteButton.setTint(Color.WHITE, 0.26f);
        } else {
            muteButton.set(GlyphView.MIC, "Mute");
            muteButton.setTint(null, 0f);
        }
        onStatus(CallManager.get().getStatus());
    }

    private void refreshKeepAlive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ok = pm.isIgnoringBatteryOptimizations(getPackageName());
        keepAliveValue.setText(ok ? "Allowed - Android won't pause calls to save battery"
                : "Tap to stop Android pausing calls to save battery");
        keepAliveValue.setTextColor(ok ? Ui.GREEN : Ui.AMBER);
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---- actions ---------------------------------------------------------------------------

    private void requestStart(String joinRoom) {
        pendingJoinRoom = joinRoom;
        pendingStart = true;
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            beginCall();
        } else {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_MIC_TEST) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startMicTest();
            } else {
                micTestStatus.setVisibility(View.VISIBLE);
                micTestStatus.setText("Microphone permission denied - allow it in Android settings > Apps > Tether");
                micTestStatus.setTextColor(Ui.RED);
            }
            return;
        }
        if (requestCode != REQUEST_PERMISSIONS || !pendingStart) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginCall();
        } else {
            pendingStart = false;
            onLog("microphone permission denied - allow it in Android settings > Apps > Tether");
        }
    }

    private void beginCall() {
        pendingStart = false;
        stopMicTest();
        prefs().edit().putString(PREF_WEB_URL, webUrl.getText().toString().trim()).apply();
        AudioDeviceInfo mic = selectedMic >= 0 && selectedMic < mics.size() ? mics.get(selectedMic) : null;
        currentRoom = null;
        setCallControls(true);
        shareButton.setVisibility(pendingJoinRoom == null ? View.VISIBLE : View.GONE);
        CallManager.get().start(this, mic, pendingJoinRoom);
    }

    private String shareLink() {
        String base = webUrl.getText().toString().trim();
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        if (base.isEmpty()) return currentRoom == null ? "" : currentRoom;
        return base + "?join=" + currentRoom;
    }

    private void share() {
        if (currentRoom == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, shareLink());
        startActivity(Intent.createChooser(send, "Share call link"));
    }

    private static String extractRoom(String input) {
        String s = input.trim();
        int i = s.indexOf("join=");
        if (i >= 0) {
            s = s.substring(i + 5);
            int amp = s.indexOf('&');
            if (amp >= 0) s = s.substring(0, amp);
        }
        if (s.startsWith(CallManager.PEER_PREFIX)) s = s.substring(CallManager.PEER_PREFIX.length());
        return s.trim();
    }

    // ---- CallManager.Listener --------------------------------------------------------------

    @Override
    public void onLog(String line) {
        String next = line + "\n" + logView.getText();
        if (next.length() > MAX_LOG_CHARS) next = next.substring(0, MAX_LOG_CHARS);
        logView.setText(next);
    }

    @Override
    public void onStatus(String status) {
        boolean muted = CallManager.get().isMuted() && CallManager.get().isActive();
        String label = status.isEmpty() ? status : Character.toUpperCase(status.charAt(0)) + status.substring(1);
        int color;
        boolean pulse = false;
        if (status.equals("idle")) {
            label = "Not in a call";
            color = Ui.TEXT_TERTIARY;
        } else if (status.equals("connected")) {
            color = muted ? Ui.AMBER : Ui.GREEN;
            if (muted) label = "Connected - mic muted";
        } else {
            color = Ui.AMBER;
            pulse = true;
        }
        statusPill.set(label, color, pulse);
    }

    @Override
    public void onRoomReady(String room) {
        currentRoom = room;
        roomCode.setText(room);
        roomCode.setVisibility(View.VISIBLE);
    }

    @Override
    public void onMuted(boolean muted) {
        updateMute(muted);
    }

    @Override
    public void onHangupTimer(long remainingMs) {
        if (remainingMs < 0) {
            timerHint.setText(TIMER_HELP);
            return;
        }
        long s = (remainingMs + 999) / 1000;
        String clock = s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        timerHint.setText("Hanging up in " + clock + ". Only your end hangs up.");
    }

    @Override
    public void onEnded() {
        currentRoom = null;
        setCallControls(false);
        syncTimer(CallManager.get()); // the manager clears the timer when a call ends
    }
}
