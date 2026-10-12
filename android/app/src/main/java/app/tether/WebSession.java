package app.tether;

import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An embedded browser session: one web call page plus a hang-up timer. It owns the WebView so the
 * page and the timer keep running when the activity is stopped (screen off, app in the background);
 * {@link WebSessionService} keeps the process alive. All methods must be called on the main thread.
 *
 * The page is closed (navigated to about:blank) when the timer runs out, which ends the call on this
 * end only; no site-specific selectors are involved.
 */
final class WebSession {
    interface Listener {
        /** Countdown update; {@code remainingMs} is -1 when no timer is running. */
        void onTimer(long remainingMs);

        /** The page was closed, by the timer, the Close button or the notification. */
        void onClosed(String reason);

        /** Mute changed, possibly from the notification rather than the screen. */
        void onMuted(boolean muted);

        /** Live level (0..1 RMS) of the call's outgoing audio. */
        void onLevel(float rms);

        /** What the page sees of this browser (for checking the desktop emulation). */
        void onInfo(String text);

        /** Something the user should know, e.g. the chosen microphone could not be opened. */
        void onNotice(String text);

        /**
         * Progress of the record-and-play-back test: state is "recording", "playing", "done" or "error";
         * peak is 0..1 linear, verdict one of "clipping", "good", "quiet", "silent"; message is set on error.
         */
        void onLoopback(String state, float peak, String verdict, String message);
    }

    private static final WebSession INSTANCE = new WebSession();

    static WebSession get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private WebView webView;
    private Listener listener;
    private boolean active;
    private String startHost = "";
    private boolean muted;
    private float volume = 1f;        // 0..2, applied to the other person's voice inside the page
    private String micKey = "";       // "" = the page's own capture; otherwise an input device, captured natively
    private boolean aec = true;
    private float zoom = 1f;          // 0.5..3: the page's layout width is 1280 / zoom
    private float micGain = 1f;       // 0..3: gain on what the other person hears
    private final VirtualMic vmic = new VirtualMic();
    private boolean pageWantsMic;     // the page currently holds the virtual microphone
    private boolean pageLoaded;       // a real page has finished loading (not the blank page)
    private int muteMisses;           // consecutive checks the page did not answer
    private boolean muteWarned;       // one warning per page load, never a stream of them
    private final Runnable muteCheck = new Runnable() {
        @Override
        public void run() {
            if (!active || !muted) return;
            checkMute();
            main.postDelayed(this, 3000);
        }
    };
    private boolean callMeter;
    private String script;            // assets/session.js, read once
    private long hangupAt;            // SystemClock.elapsedRealtime() deadline, 0 = no timer
    private long notifiedMinutes = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active || hangupAt == 0) return;
            // A real-time deadline, not a tick count, so a late tick cannot drift it.
            long left = hangupAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                close("hang-up timer reached");
                return;
            }
            if (listener != null) listener.onTimer(left);
            long minutes = (left + 59_999) / 60_000;
            if (minutes != notifiedMinutes) {
                notifiedMinutes = minutes;
                WebSessionService.refresh();
            }
            main.postDelayed(this, 1000);
        }
    };

    boolean isActive() {
        return active;
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isMuted() {
        return muted;
    }

    /**
     * Mutes or unmutes the outgoing audio only; the page, the call and the foreground service keep
     * running, so unmuting is instant.
     */
    void setMuted(boolean value) {
        muted = value;
        vmic.setMuted(value);          // the native microphone path goes silent too, whatever the page does
        js("__tether.setMute(" + value + ")");
        main.removeCallbacks(muteCheck);
        muteMisses = 0;
        muteWarned = false;
        if (value) {
            checkMute();
            main.postDelayed(muteCheck, 3000);
        }
        if (listener != null) listener.onMuted(value);
        WebSessionService.refresh();
    }

    /**
     * Asks the page whether the mute is real. The page answers straight away (this is not slowed down in the
     * background like page timers are). A leak is fixed at once and reported; a page that cannot be asked is
     * reported only if a real page is loaded, only after several misses in a row, and only once per page load.
     */
    private void checkMute() {
        if (webView == null || !active || !muted) return;
        webView.evaluateJavascript("window.__tether ? window.__tether.muteStatus() : null", value -> {
            if (!active || !muted) return;
            boolean answered = false;
            boolean silent = true;
            if (value != null && !value.equals("null")) {
                try {
                    silent = new JSONObject(value).optBoolean("silent", true);
                    answered = true;
                } catch (JSONException ignored) {
                    // Treated as no answer.
                }
            }
            if (answered) {
                muteMisses = 0;
                if (!silent) {
                    js("__tether.setMute(true)"); // lock it again
                    if (listener != null) listener.onNotice("MUTE LEAK FIXED - something tried to un-mute the call.");
                }
                return;
            }
            if (!pageLoaded) return; // no page, no call: nothing to vouch for
            if (++muteMisses >= 3 && !muteWarned && listener != null) {
                muteWarned = true;
                listener.onNotice("MUTE NOT CONFIRMED - the page is not answering. Close the page if you must be sure.");
            }
        });
    }

    /** Settings picked before the page opens (they are re-applied to every page that loads). */
    void configure(float volumeGain, String mic, boolean echoCancellation, float pageZoom, float micVolume) {
        volume = volumeGain;
        micKey = mic == null ? "" : mic;
        aec = echoCancellation;
        zoom = pageZoom;
        micGain = micVolume;
    }

    /** Microphone volume, 0..3: gain on what the other person hears (above 1 boosts a quiet mic). */
    void setMicGain(float gain) {
        micGain = Math.max(0f, Math.min(3f, gain));
        js(String.format(Locale.US, "__tether.setMicGain(%.3f)", micGain));
    }

    /**
     * Records {@code seconds} the way the call captures (including the mic volume), plays it back through
     * the page, and rates the level. Progress arrives in {@link Listener#onLoopback}.
     */
    void startLoopback(int seconds) {
        js("__tether.loopback(" + seconds + ")");
    }

    /** Gain on the other person's voice, 0..2 (above 1 boosts it). */
    void setVolume(float gain) {
        volume = Math.max(0f, Math.min(2f, gain));
        js(String.format(Locale.US, "__tether.setVolume(%.3f)", volume));
    }

    /** Page zoom, 0.5..3: lays the page out again at 1280 / zoom CSS pixels wide (not a magnifier). */
    void setZoom(float value) {
        zoom = Math.max(0.5f, Math.min(3f, value));
        js(String.format(Locale.US, "__tether.setZoom(%.3f)", zoom));
    }

    /**
     * Choose the microphone: "" for the page's own capture, or a key from {@link #micKey(AudioDeviceInfo)}
     * to capture that input natively and hand it to the page. Switches a live call without renegotiating.
     */
    void setMicChoice(String key) {
        micKey = key == null ? "" : key;
        js("__tether.configure({micLabel:" + JSONObject.quote(micLabel()) + "})");
        js("__tether.setVirtualMic(" + !micKey.isEmpty() + ")");
        if (micKey.isEmpty()) {
            stopCapture();
        } else if (pageWantsMic) {
            stopCapture();
            startCapture();
        }
    }

    /** Echo cancellation and noise suppression on or off; switches the live capture. */
    void setProcessing(boolean on) {
        aec = on;
        js("__tether.setProcessing(" + on + ")");
        if (vmic.isRunning()) {
            stopCapture();
            startCapture();
        }
    }

    /** Stream the call's outgoing level to {@link Listener#onLevel}. */
    void setCallMeter(boolean on) {
        callMeter = on;
        js("__tether.setMeter(" + on + ")");
    }

    // ---- native microphone -------------------------------------------------------------------

    /** The phone's input devices, in the order the system lists them. */
    static List<AudioDeviceInfo> inputs(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioDeviceInfo> list = new ArrayList<>();
        Collections.addAll(list, am.getDevices(AudioManager.GET_DEVICES_INPUTS));
        return list;
    }

    /** A key that identifies an input across runs (device ids are not stable). */
    static String micKey(AudioDeviceInfo d) {
        return d.getType() + "|" + d.getProductName();
    }

    /** The chosen input's name, which is also what the page lists as its microphone. */
    private String micLabel() {
        AudioDeviceInfo d = micKey.isEmpty() ? null : findInput(micKey);
        return d == null ? "" : CallManager.describe(d);
    }

    private AudioDeviceInfo findInput(String key) {
        if (app == null) return null;
        for (AudioDeviceInfo d : inputs(app)) {
            if (micKey(d).equals(key)) return d;
        }
        return null;
    }

    private void startCapture() {
        if (micKey.isEmpty() || vmic.isRunning() || !active) return;
        AudioDeviceInfo device = findInput(micKey);
        if (device == null && listener != null) {
            listener.onNotice("The chosen microphone is not available now; using the system default input.");
        }
        vmic.setMuted(muted);
        vmic.start(device, aec, new VirtualMic.Listener() {
            @Override
            public void onFrame(String base64Pcm) {
                js("__tether.pcm('" + base64Pcm + "')");
            }

            @Override
            public void onError(String message) {
                if (listener != null) listener.onNotice("Microphone: " + message);
            }
        });
    }

    private void stopCapture() {
        vmic.stop();
    }

    void requestInfo() {
        js("__tether.info()");
    }

    private void js(String code) {
        if (webView != null) webView.evaluateJavascript("window.__tether && " + code, null);
    }

    /** The shared WebView if it exists; never creates one. */
    WebView peekWebView() {
        return webView;
    }

    /** Time left on the timer, or -1 when none is running. */
    long getRemainingMs() {
        return hangupAt == 0 ? -1 : Math.max(0, hangupAt - SystemClock.elapsedRealtime());
    }

    // ---- timer -----------------------------------------------------------------------------

    /** Hang up after {@code delayMs}; 0 or less turns the timer off. */
    void arm(long delayMs) {
        main.removeCallbacks(tick);
        notifiedMinutes = -1;
        if (delayMs <= 0 || !active) {
            hangupAt = 0;
            if (listener != null) listener.onTimer(-1);
            WebSessionService.refresh();
            return;
        }
        hangupAt = SystemClock.elapsedRealtime() + delayMs;
        tick.run();
    }

    /** "42 min" or "1 h 05 min", rounded up to the minute. */
    static String formatMinutes(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        return minutes < 60 ? minutes + " min"
                : String.format(java.util.Locale.US, "%d h %02d min", minutes / 60, minutes % 60);
    }

    // ---- page ------------------------------------------------------------------------------

    /** The shared WebView, created on first use. Attach it to a view with {@link #attach}. */
    WebView webView(Context context) {
        if (webView == null) {
            app = context.getApplicationContext();
            webView = createWebView();
        }
        return webView;
    }

    /** Point the WebView's dialogs and permission prompts at the visible activity. */
    void attach(Context activity) {
        if (webView != null) ((MutableContextWrapper) webView.getContext()).setBaseContext(activity);
    }

    /** Release the activity so a stopped or destroyed screen is not leaked. */
    void detach() {
        if (webView != null && app != null) ((MutableContextWrapper) webView.getContext()).setBaseContext(app);
    }

    /** Open {@code url} and keep it alive in the background until {@link #close}. */
    void open(Context context, String url) {
        WebView wv = webView(context);
        Uri uri = Uri.parse(url);
        startHost = uri.getHost() == null ? "" : uri.getHost();
        active = true;
        hangupAt = 0;
        muted = false;
        app.startForegroundService(new Intent(app, WebSessionService.class));
        wv.loadUrl(url);
    }

    /** Ends the call on this end by closing the page; the cookies (the login) are kept. */
    void close(String reason) {
        if (!active) return;
        active = false;
        main.removeCallbacks(tick);
        hangupAt = 0;
        muted = false;
        vmic.setMuted(false);
        main.removeCallbacks(muteCheck);
        pageLoaded = false;
        pageWantsMic = false;
        stopCapture();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.clearHistory();
        }
        if (app != null) app.stopService(new Intent(app, WebSessionService.class));
        if (listener != null) {
            listener.onTimer(-1);
            listener.onMuted(false);
            listener.onClosed(reason);
        }
    }

    // ---- WebView ---------------------------------------------------------------------------

    private WebView createWebView() {
        WebView wv = new WebView(new MutableContextWrapper(app));
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        // Ask for the desktop layout of the site, and let it be pinch-zoomed on a phone.
        String mobileUa = st.getUserAgentString();
        st.setUserAgentString(desktopUserAgent(mobileUa));
        applyDesktopClientHints(st, mobileUa);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, true);

        wv.addJavascriptInterface(new Bridge(), "TetherBridge");
        // Before any page script runs, where the browser supports it; the callbacks below cover the rest
        // (the script is idempotent).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, sessionScript(), Collections.singleton("*"));
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                muteMisses = 0;
                muteWarned = false;
                pageWantsMic = false; // a new document starts without the virtual mic; it will ask again
                stopCapture();
                view.evaluateJavascript(pageScript(), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = !"about:blank".equals(url);
                view.evaluateJavascript(pageScript(), null);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Microphone only, and only for the page that was opened (and its own subdomains).
                List<String> allowed = new ArrayList<>();
                if (hostAllowed(request.getOrigin())) {
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) allowed.add(r);
                    }
                }
                if (allowed.isEmpty()) {
                    request.deny();
                } else {
                    request.grant(allowed.toArray(new String[0]));
                }
            }
        });
        return wv;
    }

    /** assets/session.js: desktop emulation, mic selection, mute, volume and level meters. */
    private String sessionScript() {
        if (script == null) {
            try (InputStream in = app.getAssets().open("session.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                script = out.toString("UTF-8");
            } catch (IOException e) {
                script = "";
            }
        }
        return script;
    }

    /** The script plus the current settings, so a freshly loaded page starts in the right state. */
    private String pageScript() {
        try {
            JSONObject settings = new JSONObject()
                    .put("muted", muted).put("volume", volume).put("aec", aec).put("zoom", zoom)
                    .put("micGain", micGain).put("virtual", !micKey.isEmpty()).put("micLabel", micLabel());
            return sessionScript() + "\nwindow.__tether.configure(" + settings + ");"
                    + (callMeter ? "\nwindow.__tether.setMeter(true);" : "");
        } catch (JSONException e) {
            return sessionScript();
        }
    }

    /** Messages from the page's script (levels, the mic list, page info); they arrive off the main thread. */
    private final class Bridge {
        @JavascriptInterface
        public void post(final String type, final String data) {
            main.post(() -> dispatch(type, data));
        }
    }

    private void dispatch(String type, String data) {
        if (type == null || data == null) return;
        try {
            switch (type) {
                case "level":
                    if (listener != null) listener.onLevel(Float.parseFloat(data));
                    break;
                case "loopback": {
                    JSONObject o = new JSONObject(data);
                    if (listener != null) {
                        listener.onLoopback(o.optString("state"), (float) o.optDouble("peak", 0),
                                o.optString("verdict"), o.optString("message"));
                    }
                    break;
                }
                case "vmic":
                    pageWantsMic = "on".equals(data);
                    if (pageWantsMic) startCapture(); else stopCapture();
                    break;
                case "info": {
                    JSONObject o = new JSONObject(data);
                    if (listener == null) break;
                    listener.onInfo("user agent: " + o.optString("ua")
                            + "\nplatform: " + o.optString("platform")
                            + "\nlayout width: " + o.optInt("width") + " px (pixel ratio " + o.optString("dpr") + ")"
                            + "\nviewport: " + o.optString("viewport")
                            + "\nmicrophone: " + (micKey.isEmpty() ? "page default" : "native input, " + micKey)
                            + "\nclient hint mobile: " + o.optString("uaMobile")
                            + "\npage: " + o.optString("host")
                            + "\nmic API: " + o.optString("mediaDevices")
                            + "\ncalls open: " + o.optInt("calls"));
                    break;
                }
                default:
                    break;
            }
        } catch (JSONException | NumberFormatException ignored) {
            // Malformed message from the page: ignore.
        }
    }

    private boolean hostAllowed(Uri origin) {
        String host = origin == null ? null : origin.getHost();
        if (host == null || startHost.isEmpty()) return false;
        String root = rootDomain(startHost);
        return host.equals(root) || host.endsWith("." + root);
    }

    /** The last two labels of a host name ("a.b.example.com" -> "example.com"). */
    private static String rootDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String desktopUserAgent(String mobile) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(mobile == null ? "" : mobile);
        String major = m.find() ? m.group(1) : "120";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    /** Client hints say "desktop Linux Chrome" too, since some sites read them instead of the UA string. */
    private static void applyDesktopClientHints(WebSettings st, String mobileUa) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(mobileUa == null ? "" : mobileUa);
        String major = m.find() ? m.group(1) : "120";
        String full = major + ".0.0.0";
        List<UserAgentMetadata.BrandVersion> brands = new ArrayList<>();
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build());
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build());
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build());
        UserAgentMetadata md = new UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(full)
                .setPlatform("Linux")
                .setPlatformVersion("6.5.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .build();
        WebSettingsCompat.setUserAgentMetadata(st, md);
    }
}
