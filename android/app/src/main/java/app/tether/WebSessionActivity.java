package app.tether;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Shows the embedded web session. The page and the hang-up timer live in {@link WebSession}, so
 * leaving this screen (or locking the phone) does not stop them; only Close, the timer or the
 * notification action end the call.
 */
public class WebSessionActivity extends Activity implements WebSession.Listener {
    static final String EXTRA_URL = "url";
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int[] TIMER_MINUTES = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};

    private FrameLayout holder;
    private TextView countdown;
    private TextView muteButton;
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF06070C);
        getWindow().setNavigationBarColor(0xFF06070C);
        buildUi();

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
    protected void onPause() {
        super.onPause();
        keepPageRunning();
    }

    @Override
    protected void onStop() {
        super.onStop();
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

    // ---- UI --------------------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF06070C);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 2));
        int gap = Ui.dp(this, 8);

        TextView close = pill("Close page", Ui.RED);
        close.setOnClickListener(v -> confirmClose());
        bar.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));

        muteButton = pill("Mute", Color.WHITE);
        muteButton.setOnClickListener(v -> WebSession.get().setMuted(!WebSession.get().isMuted()));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1f);
        ml.leftMargin = gap;
        bar.addView(muteButton, ml);

        TextView timer = pill("Timer", Color.WHITE);
        timer.setOnClickListener(v -> showTimerDialog());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = gap;
        bar.addView(timer, tl);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        countdown = Ui.text(this, "No timer", 13f, Ui.TEXT_SECONDARY, "sans-serif-medium");
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        root.addView(countdown, new LinearLayout.LayoutParams(-1, -2));

        holder = new FrameLayout(this);
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private TextView pill(String label, int tint) {
        TextView t = Ui.text(this, label, 14f, Ui.TEXT_PRIMARY, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 11));
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
        showPage();
        WebSession.get().open(this, pendingUrl);
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
    public void onClosed(String reason) {
        Toast.makeText(getApplicationContext(), "Page closed: " + reason, Toast.LENGTH_LONG).show();
        finish();
    }
}
