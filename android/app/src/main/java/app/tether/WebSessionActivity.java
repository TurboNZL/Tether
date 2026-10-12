package app.tether;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PictureInPictureParams;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Shows the embedded web session. The page and the hang-up timer live in {@link WebSession}, so
 * leaving this screen (or locking the phone) does not stop them; only Close, the timer or the
 * notification action end the call. The Settings sheet carries the usual Tether settings: microphone
 * (with a live level test), call volume with boost, brightness and echo cancellation.
 */
public class WebSessionActivity extends Activity implements WebSession.Listener {
    static final String EXTRA_URL = "url";
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int[] TIMER_MINUTES = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};

    // Shared with the rest of the app, so volume and brightness are the same everywhere.
    private static final String PREFS = "tether";
    private static final String PREF_VOLUME = "volume";
    private static final String PREF_BRIGHTNESS = "brightness";
    private static final String PREF_WEB_MIC = "webMic";
    private static final String PREF_WEB_AEC = "webAec";
    private static final String PREF_WEB_ZOOM = "webZoom";
    private static final String PREF_WEB_MIC_GAIN = "webMicGain";
    private static final int LOOPBACK_SECONDS = 4;

    private FrameLayout holder;
    private View[] chrome = new View[0]; // the bar, countdown and level strip: hidden in the small window
    private TextView countdown;
    private TextView muteButton;
    private LevelMeterView stripMeter;
    private View dimOverlay;
    private String pendingUrl;

    // Settings sheet (null while closed)
    private Dialog sheet;
    private LevelMeterView sheetMeter;
    private TextView sheetStatus;
    private TextView micValue;
    private TextView infoView;
    private final MicTester micTester = new MicTester();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable zoomApply;
    private long testStartedAt;
    private boolean heardSound;
    private float micGain = 1f;
    private TextView loopbackStatus;
    private TextView loopbackAction;
    private TextView gainValue;
    private GlassSlider gainSlider;
    private float suggestedGain;
    private final MicPreview micPreview = new MicPreview();
    private Runnable loopbackWatchdog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(0xFF06070C);
        getWindow().setNavigationBarColor(0xFF06070C);
        buildUi();
        applyBrightness(prefs().getFloat(PREF_BRIGHTNESS, 1f));

        WebSession session = WebSession.get();
        session.setListener(this);
        if (session.isActive()) {
            showPage();
            onTimer(session.getRemainingMs());
            onMuted(session.isMuted());
            return;
        }
        pendingUrl = getIntent().getStringExtra(EXTRA_URL);
        if (pendingUrl == null) {
            finish();
            return;
        }
        requestPermissionsThenOpen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (WebSession.get().isActive()) showPage();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (WebSession.get().isActive()) WebSession.get().setCallMeter(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        keepPageRunning();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The level strip is only useful while it can be seen.
        WebSession.get().setCallMeter(false);
        if (sheet != null) sheet.dismiss();
        keepPageRunning();
    }

    /** Leaving the screen (home, another app, screen off) must never pause the page. */
    private void keepPageRunning() {
        WebView wv = WebSession.get().peekWebView();
        if (wv != null && WebSession.get().isActive()) {
            wv.onResume();
            wv.resumeTimers();
        }
    }

    @Override
    protected void onDestroy() {
        // The page and the timer outlive this screen; only the view of them goes away.
        WebSession session = WebSession.get();
        session.setListener(null);
        WebView wv = session.peekWebView();
        if (wv != null && wv.getParent() == holder) holder.removeView(wv);
        session.detach();
        super.onDestroy();
    }

    // ---- picture in picture ----------------------------------------------------------------

    private boolean canPip() {
        return Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams.Builder pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        // From Android 12 the window shrinks by itself when you go Home mid-call.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(WebSession.get().isActive());
        return b;
    }

    private void updatePip() {
        if (!canPip()) return;
        try {
            setPictureInPictureParams(pipParams().build());
        } catch (RuntimeException ignored) {
            // Not allowed right now; the Home gesture still works through onUserLeaveHint.
        }
    }

    /** Shrinks the page into a small floating window, which also keeps Android from unloading the app. */
    private void enterPip() {
        if (!canPip() || isInPictureInPictureMode() || !WebSession.get().isActive()) return;
        if (sheet != null) sheet.dismiss();
        try {
            enterPictureInPictureMode(pipParams().build());
        } catch (RuntimeException e) {
            Toast.makeText(this, "Picture in picture is not available here.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ already enters by itself (auto-enter); older versions need the nudge.
        if (Build.VERSION.SDK_INT < 31) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        for (View v : chrome) v.setVisibility(inPip ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // Never navigate the page back mid-call (that could unload it); just leave. The call keeps
        // running and the notification brings you back.
        moveTaskToBack(true);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---- UI --------------------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF06070C);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 2));
        int gap = Ui.dp(this, 6);

        TextView close = pill("Close", Ui.RED);
        close.setOnClickListener(v -> confirmClose());
        bar.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));

        muteButton = pill("Mute", Color.WHITE);
        muteButton.setOnClickListener(v -> WebSession.get().setMuted(!WebSession.get().isMuted()));
        bar.addView(muteButton, weighted(gap));

        TextView timer = pill("Timer", Color.WHITE);
        timer.setOnClickListener(v -> showTimerDialog());
        bar.addView(timer, weighted(gap));

        TextView settings = pill("Settings", Color.WHITE);
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, weighted(gap));

        TextView pip = pill("Mini", Color.WHITE);
        pip.setOnClickListener(v -> enterPip());
        if (canPip()) bar.addView(pip, weighted(gap));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        countdown = Ui.text(this, "No timer", 13f, Ui.TEXT_SECONDARY, "sans-serif-medium");
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        root.addView(countdown, new LinearLayout.LayoutParams(-1, -2));

        stripMeter = new LevelMeterView(this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        ml.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        root.addView(stripMeter, ml);

        chrome = new View[]{bar, countdown, stripMeter};
        holder = new FrameLayout(this);
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Brightness veil over everything, the same trick as the main screen.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setClickable(false);
        dimOverlay.setFocusable(false);
        frame.addView(dimOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    private LinearLayout.LayoutParams weighted(int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.leftMargin = leftMargin;
        return p;
    }

    private TextView pill(String label, int tint) {
        TextView t = Ui.text(this, label, 14f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 11), Ui.dp(this, 4), Ui.dp(this, 11));
        t.setClickable(true);
        tintPill(t, tint, tint == Color.WHITE ? 0.14f : 0.35f);
        return t;
    }

    private void tintPill(TextView t, int tint, float alpha) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.withAlpha(tint, alpha));
        bg.setCornerRadius(Ui.dpf(this, 100f));
        bg.setStroke(Ui.dp(this, 1), 0x33FFFFFF);
        t.setBackground(bg);
    }

    private void applyBrightness(float value) {
        if (dimOverlay != null) dimOverlay.setAlpha((1f - value) * 0.9f);
    }

    /** Ending the call is one tap away from the page, so ask first. */
    private void confirmClose() {
        new AlertDialog.Builder(this)
                .setTitle("Close the page?")
                .setMessage("This ends the call on your end.")
                .setPositiveButton("Close page", (d, w) -> WebSession.get().close("you closed the page"))
                .setNegativeButton("Keep it open", null)
                .show();
    }

    private void showPage() {
        WebSession session = WebSession.get();
        WebView wv = session.webView(this);
        session.attach(this);
        if (wv.getParent() != holder) {
            if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
            holder.addView(wv, new FrameLayout.LayoutParams(-1, -1));
        }
        updatePip();
    }

    // ---- settings sheet --------------------------------------------------------------------

    private void showSettings() {
        if (sheet != null) return;
        WebSession session = WebSession.get();
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101420);
        bg.setCornerRadii(new float[]{Ui.dpf(this, 28), Ui.dpf(this, 28), Ui.dpf(this, 28), Ui.dpf(this, 28), 0, 0, 0, 0});
        bg.setStroke(Ui.dp(this, 1), 0x33FFFFFF);
        col.setBackground(bg);
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));

        col.addView(Ui.text(this, "Settings", 20f, Ui.TEXT_PRIMARY, "semibold"), lp(0, 0, 14));

        // Mic level test
        col.addView(label("Microphone level"));
        sheetMeter = new LevelMeterView(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 14));
        mp.topMargin = Ui.dp(this, 8);
        col.addView(sheetMeter, mp);
        sheetStatus = Ui.text(this, "Speak - the bar should move.", 13f, Ui.TEXT_TERTIARY, "sans-serif");
        col.addView(sheetStatus, lp(8, 0, 16));

        // Microphone choice
        col.addView(label("Microphone"));
        micValue = Ui.text(this, micSummary(), 17f, Ui.TEXT_PRIMARY, "sans-serif");
        micValue.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        micValue.setClickable(true);
        micValue.setOnClickListener(v -> chooseMic());
        col.addView(micValue, lp(0, 0, 4));
        Switch aec = new Switch(this);
        aec.setText("Echo cancellation & noise suppression");
        aec.setTextColor(Ui.TEXT_SECONDARY);
        aec.setChecked(prefs().getBoolean(PREF_WEB_AEC, true));
        aec.setOnCheckedChangeListener((b, on) -> {
            prefs().edit().putBoolean(PREF_WEB_AEC, on).apply();
            WebSession.get().setProcessing(on);
        });
        col.addView(aec, lp(0, 0, 12));

        // Microphone volume, and a playback test to hear how loud it is
        micGain = prefs().getFloat(PREF_WEB_MIC_GAIN, 1f);
        gainValue = Ui.text(this, pct(micGain), 30f, Ui.TEXT_PRIMARY, "sans-serif");
        col.addView(label("Microphone volume"));
        col.addView(gainValue, lp(4, 0, 2));
        gainSlider = new GlassSlider(this, 3f, 1f);
        gainSlider.setValue(micGain);
        gainSlider.setListener((value, fromUser) -> {
            gainValue.setText(pct(value));
            micGain = value;
            if (!fromUser) return;
            prefs().edit().putFloat(PREF_WEB_MIC_GAIN, value).apply();
            WebSession.get().setMicGain(value);
        });
        col.addView(gainSlider, new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.text(this, "How loudly the other person hears you. Past 100% boosts a quiet mic; the bar "
                + "above shows the result.", 13f, Ui.TEXT_TERTIARY, "sans-serif"), lp(0, 0, 10));
        TextView playback = Ui.text(this, "Record " + LOOPBACK_SECONDS + " s and play it back", 15f,
                Ui.TEXT_PRIMARY, "sans-serif-medium");
        playback.setGravity(Gravity.CENTER);
        playback.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        tintPill(playback, Color.WHITE, 0.14f);
        playback.setClickable(true);
        playback.setOnClickListener(v -> startLoopback());
        col.addView(playback, lp(0, 0, 6));
        loopbackStatus = Ui.text(this, "Hear yourself the way the other person will. Works even while you are muted.", 13f, Ui.TEXT_TERTIARY,
                "sans-serif");
        col.addView(loopbackStatus, lp(0, 0, 6));
        // After a test that was faint or too loud: one tap to set the volume that would have been right.
        loopbackAction = Ui.text(this, "", 15f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        loopbackAction.setGravity(Gravity.CENTER);
        loopbackAction.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        tintPill(loopbackAction, Ui.GREEN, 0.30f);
        loopbackAction.setClickable(true);
        loopbackAction.setVisibility(View.GONE);
        loopbackAction.setOnClickListener(v -> applySuggestedGain());
        col.addView(loopbackAction, lp(0, 0, 16));

        // Call volume
        float vol = prefs().getFloat(PREF_VOLUME, 1f);
        TextView volValue = Ui.text(this, pct(vol), 30f, Ui.TEXT_PRIMARY, "sans-serif");
        col.addView(label("Call volume"));
        col.addView(volValue, lp(4, 0, 2));
        GlassSlider volSlider = new GlassSlider(this, 2f, 1f);
        volSlider.setValue(vol);
        volSlider.setListener((value, fromUser) -> {
            volValue.setText(pct(value));
            if (fromUser) {
                prefs().edit().putFloat(PREF_VOLUME, value).apply();
                WebSession.get().setVolume(value);
            }
        });
        col.addView(volSlider, new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.text(this, "Only the other person's voice. Past 100% boosts it.", 13f,
                Ui.TEXT_TERTIARY, "sans-serif"), lp(0, 0, 14));

        // Brightness
        float bright = prefs().getFloat(PREF_BRIGHTNESS, 1f);
        TextView brightValue = Ui.text(this, pct(bright), 30f, Ui.TEXT_PRIMARY, "sans-serif");
        col.addView(label("Brightness"));
        col.addView(brightValue, lp(4, 0, 2));
        GlassSlider brightSlider = new GlassSlider(this, 1f, 0f);
        brightSlider.setValue(bright);
        brightSlider.setListener((value, fromUser) -> {
            brightValue.setText(pct(value));
            applyBrightness(value);
            if (fromUser) prefs().edit().putFloat(PREF_BRIGHTNESS, value).apply();
        });
        col.addView(brightSlider, new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.text(this, "Dims this app below your screen's minimum brightness.", 13f,
                Ui.TEXT_TERTIARY, "sans-serif"), lp(0, 0, 14));

        // Page zoom: lays the page out again at a different width, like browser zoom (not a magnifier)
        float zoom = Math.max(0.5f, prefs().getFloat(PREF_WEB_ZOOM, 1f));
        TextView zoomValue = Ui.text(this, pct(zoom), 30f, Ui.TEXT_PRIMARY, "sans-serif");
        col.addView(label("Page zoom"));
        col.addView(zoomValue, lp(4, 0, 2));
        GlassSlider zoomSlider = new GlassSlider(this, 3f, 1f);
        zoomSlider.setValue(zoom);
        zoomSlider.setListener((value, fromUser) -> {
            float z = Math.max(0.5f, value);
            zoomValue.setText(pct(z));
            if (!fromUser) return;
            prefs().edit().putFloat(PREF_WEB_ZOOM, z).apply();
            // Laying the page out again is heavy: wait until the slider has settled.
            if (zoomApply != null) ui.removeCallbacks(zoomApply);
            zoomApply = () -> WebSession.get().setZoom(z);
            ui.postDelayed(zoomApply, 250);
        });
        col.addView(zoomSlider, new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.text(this, "Makes the whole page bigger or smaller by changing the width it is laid out "
                + "at, like browser zoom. Pinch still magnifies.", 13f, Ui.TEXT_TERTIARY, "sans-serif"), lp(0, 0, 14));

        // Page info: what the page sees, to check the desktop layout
        TextView info = Ui.text(this, "Show page info", 15f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        tintPill(info, Color.WHITE, 0.14f);
        info.setClickable(true);
        info.setOnClickListener(v -> WebSession.get().requestInfo());
        col.addView(info, lp(0, 0, 8));
        infoView = Ui.text(this, "", 11.5f, Ui.TEXT_SECONDARY, "monospace");
        infoView.setTextIsSelectable(true);
        infoView.setVisibility(View.GONE);
        col.addView(infoView);

        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.35f);
        }
        d.setOnDismissListener(x -> {
            micTester.stop();
            if (loopbackWatchdog != null) ui.removeCallbacks(loopbackWatchdog);
            micPreview.cancel();
            loopbackStatus = null;
            loopbackAction = null;
            gainValue = null;
            gainSlider = null;
            sheet = null;
            sheetMeter = null;
            sheetStatus = null;
            micValue = null;
            infoView = null;
        });
        sheet = d;
        d.show();

        startMicTest();
    }

    // ---- microphone: the phone's real inputs, and a level test -------------------------------------

    private AudioDeviceInfo chosenInput() {
        String key = prefs().getString(PREF_WEB_MIC, "");
        for (AudioDeviceInfo d : WebSession.inputs(this)) {
            if (WebSession.micKey(d).equals(key)) return d;
        }
        return null;
    }

    /** There is no "automatic" input: until one is picked, the phone's own microphone is used (and shown). */
    private void ensureMicChoice() {
        if (!prefs().getString(PREF_WEB_MIC, "").isEmpty()) return;
        AudioDeviceInfo pick = null;
        for (AudioDeviceInfo d : WebSession.inputs(this)) {
            if (pick == null) pick = d;
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { pick = d; break; }
        }
        if (pick != null) prefs().edit().putString(PREF_WEB_MIC, WebSession.micKey(pick)).apply();
    }

    private void startMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMicTestError("microphone permission denied - allow it in Android settings > Apps > Tether");
            return;
        }
        micTester.stop();
        testStartedAt = System.currentTimeMillis();
        heardSound = false;
        if (sheetStatus != null) {
            sheetStatus.setText("Speak - the bar should move.");
            sheetStatus.setTextColor(Ui.TEXT_TERTIARY);
        }
        micTester.start(chosenInput(), new MicTester.Listener() {
            @Override
            public void onLevel(float rms) {
                if (sheetMeter == null || sheetStatus == null) return;
                float heard = Math.min(1f, rms * micGain); // what the other person will hear
                sheetMeter.setRms(heard);
                if (rms > 0.01f) heardSound = true;
                if (heardSound) {
                    sheetStatus.setText("Mic is working.");
                    sheetStatus.setTextColor(Ui.GREEN);
                } else if (System.currentTimeMillis() - testStartedAt > 4000) {
                    sheetStatus.setText("No sound yet. Speak, or pick another microphone below.");
                    sheetStatus.setTextColor(Ui.AMBER);
                }
            }

            @Override
            public void onError(String message) {
                onMicTestError(message);
            }
        });
    }

    private void startLoopback() {
        if (loopbackStatus == null) return;
        if (loopbackAction != null) loopbackAction.setVisibility(View.GONE);
        // The app reads the chosen input itself, so the preview works whether or not the call is muted.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMicTestError("microphone permission denied - allow it in Android settings > Apps > Tether");
            return;
        }
        micPreview.cancel();
        micTester.stop(); // one reader at a time keeps the level bar from fighting the preview
        micPreview.start(chosenInput(), prefs().getBoolean(PREF_WEB_AEC, true), micGain, LOOPBACK_SECONDS,
                (state, peak, verdict, message) -> {
                    onLoopback(state, peak, verdict, message);
                    if (state.equals("done") || state.equals("error")) startMicTest(); // the level bar resumes
                });
    }

    @Override
    public void onLoopback(String state, float peak, String verdict, String message) {
        if (loopbackStatus == null) return;
        String level = peak > 0 ? String.format(Locale.US, " (peak %d dB)", Math.round(20.0 * Math.log10(peak))) : "";
        switch (state) {
            case "recording":
                loopbackStatus.setText("Recording - speak normally…");
                loopbackStatus.setTextColor(Ui.AMBER);
                break;
            case "playing":
                loopbackStatus.setText("Playing it back" + level + " - it uses your phone's media volume, so press "
                        + "the volume keys now if you hear nothing.");
                loopbackStatus.setTextColor(Ui.AMBER);
                break;
            case "done":
                switch (verdict) {
                    case "clipping":
                        loopbackStatus.setText("Too loud" + level + " - it will distort. Turn the microphone volume down.");
                        loopbackStatus.setTextColor(Ui.RED);
                        break;
                    case "good":
                        loopbackStatus.setText("Good level" + level + ".");
                        loopbackStatus.setTextColor(Ui.GREEN);
                        break;
                    case "quiet":
                        loopbackStatus.setText("Faint" + level + " - the other person would hear you quietly. A phone's "
                                + "raw mic is quieter than a normal call, which boosts it for you.");
                        loopbackStatus.setTextColor(Ui.AMBER);
                        break;
                    default:
                        loopbackStatus.setText("Almost silent" + level + " - check the microphone choice and the "
                                + "microphone volume.");
                        loopbackStatus.setTextColor(Ui.RED);
                        break;
                }
                offerGainFix(peak, verdict);
                break;
            default:
                loopbackStatus.setText("Test failed: " + message);
                loopbackStatus.setTextColor(Ui.RED);
                break;
        }
    }

    /** If the test was faint or too loud, offer the microphone volume that would put the peak near -6 dB. */
    private void offerGainFix(float peak, String verdict) {
        if (loopbackAction == null) return;
        loopbackAction.setVisibility(View.GONE);
        boolean worthFixing = "quiet".equals(verdict) || "clipping".equals(verdict)
                || ("silent".equals(verdict) && peak > 0.006f); // above about -44 dB there is something to scale up
        if (!worthFixing || peak <= 0f) return;
        float wanted = micGain * 0.5f / peak;               // the test already includes the current volume
        suggestedGain = Math.max(0.1f, Math.min(3f, wanted));
        if (Math.abs(suggestedGain - micGain) < 0.05f) return;
        loopbackAction.setText(String.format(Locale.US, "Set microphone volume to %d%%%s",
                Math.round(suggestedGain * 100f), wanted > 3f ? " (the most it can do)" : ""));
        loopbackAction.setVisibility(View.VISIBLE);
    }

    private void applySuggestedGain() {
        if (gainSlider == null || gainValue == null) return;
        micGain = suggestedGain;
        gainSlider.setValue(micGain);
        gainValue.setText(pct(micGain));
        prefs().edit().putFloat(PREF_WEB_MIC_GAIN, micGain).apply();
        WebSession.get().setMicGain(micGain);
        loopbackAction.setVisibility(View.GONE);
        loopbackStatus.setText("Set to " + pct(micGain) + ". Run the test again to check it.");
        loopbackStatus.setTextColor(Ui.TEXT_SECONDARY);
    }

    private void onMicTestError(String message) {
        if (sheetStatus == null) return;
        sheetStatus.setText("Could not open the microphone: " + message);
        sheetStatus.setTextColor(Ui.RED);
    }

    private TextView label(String text) {
        return Ui.text(this, text, 14f, Ui.TEXT_SECONDARY, "sans-serif-medium");
    }

    private LinearLayout.LayoutParams lp(int top, int left, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(Ui.dp(this, left), Ui.dp(this, top), 0, Ui.dp(this, bottom));
        return p;
    }

    private static String pct(float value) {
        return String.format(Locale.US, "%d%%", Math.round(value * 100f));
    }

    private String micSummary() {
        ensureMicChoice();
        AudioDeviceInfo d = chosenInput();
        return d == null ? "Chosen input not available" : CallManager.describe(d);
    }

    private void chooseMic() {
        List<AudioDeviceInfo> inputs = WebSession.inputs(this);
        List<String> items = new ArrayList<>();
        for (AudioDeviceInfo d : inputs) items.add(CallManager.describe(d));
        String key = prefs().getString(PREF_WEB_MIC, "");
        int checked = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (WebSession.micKey(inputs.get(i)).equals(key)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Microphone")
                .setSingleChoiceItems(items.toArray(new String[0]), checked, (d, which) -> {
                    String chosen = WebSession.micKey(inputs.get(which));
                    prefs().edit().putString(PREF_WEB_MIC, chosen).apply();
                    WebSession.get().setMicChoice(chosen);
                    if (micValue != null) micValue.setText(micSummary());
                    InputRoute.apply(this, chosenInput());
                    startMicTest(); // test the one just picked
                    d.dismiss();
                })
                .show();
    }

    // ---- timer -----------------------------------------------------------------------------

    private void showTimerDialog() {
        List<String> labels = new ArrayList<>();
        for (int minutes : TIMER_MINUTES) labels.add(timerLabel(minutes));
        labels.add("At a set time…");
        new AlertDialog.Builder(this)
                .setTitle("Hang up timer")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < TIMER_MINUTES.length) {
                        WebSession.get().arm(TIMER_MINUTES[which] * 60_000L);
                    } else {
                        pickClockTime();
                    }
                })
                .show();
    }

    private static String timerLabel(int minutes) {
        if (minutes == 0) return "Off";
        if (minutes < 60) return "In " + minutes + " minutes";
        if (minutes == 60) return "In 1 hour";
        if (minutes % 60 == 0) return "In " + minutes / 60 + " hours";
        return "In " + minutes / 60 + "½ hours";
    }

    /** Hang up at the next occurrence of a clock time (tonight's 03:30, say). */
    private void pickClockTime() {
        Calendar now = Calendar.getInstance();
        new TimePickerDialog(this, (view, hour, minute) -> {
            Calendar at = Calendar.getInstance();
            at.set(Calendar.HOUR_OF_DAY, hour);
            at.set(Calendar.MINUTE, minute);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_MONTH, 1);
            WebSession.get().arm(at.getTimeInMillis() - System.currentTimeMillis());
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
    }

    // ---- permissions and start -------------------------------------------------------------

    private void requestPermissionsThenOpen() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            openPage();
        } else {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            openPage();
        } else {
            Toast.makeText(this, "Microphone permission denied - allow it in Android settings > Apps > Tether",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void openPage() {
        ensureMicChoice();
        WebSession session = WebSession.get();
        session.configure(prefs().getFloat(PREF_VOLUME, 1f), prefs().getString(PREF_WEB_MIC, ""),
                prefs().getBoolean(PREF_WEB_AEC, true), prefs().getFloat(PREF_WEB_ZOOM, 1f),
                prefs().getFloat(PREF_WEB_MIC_GAIN, 1f));
        showPage();
        session.open(this, pendingUrl);
        session.setCallMeter(true);
    }

    // ---- WebSession.Listener ---------------------------------------------------------------

    @Override
    public void onTimer(long remainingMs) {
        if (remainingMs < 0) {
            countdown.setText("No timer");
            return;
        }
        long s = (remainingMs + 999) / 1000;
        countdown.setText(s >= 3600
                ? String.format(Locale.US, "Closes in %d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.US, "Closes in %d:%02d", s / 60, s % 60));
    }

    @Override
    public void onMuted(boolean muted) {
        muteButton.setText(muted ? "Unmute" : "Mute");
        // Red while muted, so the state is obvious at a glance.
        tintPill(muteButton, muted ? Ui.RED : Color.WHITE, muted ? 0.55f : 0.14f);
    }

    @Override
    public void onLevel(float rms) {
        stripMeter.setRms(rms);
    }

    @Override
    public void onInfo(String text) {
        if (infoView == null) return;
        infoView.setText(text);
        infoView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onNotice(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onClosed(String reason) {
        if (sheet != null) sheet.dismiss();
        Toast.makeText(getApplicationContext(), "Page closed: " + reason, Toast.LENGTH_LONG).show();
        finish();
    }
}
