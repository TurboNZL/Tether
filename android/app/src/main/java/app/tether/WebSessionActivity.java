package app.tether;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
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

    private FrameLayout holder;
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
    private List<String> micLabels = new ArrayList<>();
    private String micCurrent = "";
    private long testStartedAt;
    private boolean heardSound;

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
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        countdown = Ui.text(this, "No timer", 13f, Ui.TEXT_SECONDARY, "sans-serif-medium");
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        root.addView(countdown, new LinearLayout.LayoutParams(-1, -2));

        stripMeter = new LevelMeterView(this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        ml.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        root.addView(stripMeter, ml);

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
        col.addView(aec, lp(0, 0, 16));

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
            WebSession.get().testMic(false);
            sheet = null;
            sheetMeter = null;
            sheetStatus = null;
            micValue = null;
            infoView = null;
        });
        sheet = d;
        d.show();

        testStartedAt = System.currentTimeMillis();
        heardSound = false;
        session.requestDevices();
        session.testMic(true);
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
        String chosen = prefs().getString(PREF_WEB_MIC, "");
        if (!chosen.isEmpty()) return chosen;
        return micCurrent.isEmpty() ? "Automatic (phone mic)" : "Automatic - " + micCurrent;
    }

    private void chooseMic() {
        List<String> items = new ArrayList<>();
        items.add("Automatic (phone mic)");
        items.addAll(micLabels);
        String chosen = prefs().getString(PREF_WEB_MIC, "");
        int checked = chosen.isEmpty() ? 0 : Math.max(0, micLabels.indexOf(chosen) + 1);
        new AlertDialog.Builder(this)
                .setTitle("Microphone")
                .setSingleChoiceItems(items.toArray(new String[0]), checked, (d, which) -> {
                    String label = which == 0 ? "" : micLabels.get(which - 1);
                    prefs().edit().putString(PREF_WEB_MIC, label).apply();
                    WebSession.get().setMic(label);
                    WebSession.get().requestDevices();
                    if (micValue != null) micValue.setText(label.isEmpty() ? micSummary() : label);
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
        WebSession session = WebSession.get();
        session.configure(prefs().getFloat(PREF_VOLUME, 1f), prefs().getString(PREF_WEB_MIC, ""),
                prefs().getBoolean(PREF_WEB_AEC, true));
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
        tintPill(muteButton, Color.WHITE, muted ? 0.30f : 0.14f);
    }

    @Override
    public void onLevel(float rms) {
        stripMeter.setRms(rms);
    }

    @Override
    public void onTestLevel(float rms) {
        if (sheetMeter == null || sheetStatus == null) return;
        if (rms < 0) {
            sheetStatus.setText("Could not open the microphone - check the choice below and the app's permission.");
            sheetStatus.setTextColor(Ui.RED);
            return;
        }
        sheetMeter.setRms(rms);
        if (rms > 0.01f) heardSound = true;
        if (heardSound) {
            sheetStatus.setText("Mic is working.");
            sheetStatus.setTextColor(Ui.GREEN);
        } else if (System.currentTimeMillis() - testStartedAt > 4000) {
            sheetStatus.setText("No sound yet. Speak, or check the microphone choice below.");
            sheetStatus.setTextColor(Ui.AMBER);
        }
    }

    @Override
    public void onDevices(List<String> labels, String current) {
        micLabels = labels;
        micCurrent = current;
        if (micValue != null) micValue.setText(micSummary());
    }

    @Override
    public void onInfo(String text) {
        if (infoView == null) return;
        infoView.setText(text);
        infoView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onClosed(String reason) {
        if (sheet != null) sheet.dismiss();
        Toast.makeText(getApplicationContext(), "Page closed: " + reason, Toast.LENGTH_LONG).show();
        finish();
    }
}
