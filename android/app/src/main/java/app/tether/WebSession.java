package app.tether;

import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;
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
    }

    /**
     * Mic shim, injected into every page. It (1) wraps getUserMedia so that, unless the page asks for a
     * specific device, the phone's own mic is chosen over a Bluetooth headset (the same rule the web page
     * uses; labels are only exposed after a grant, so the first call takes and releases a throwaway
     * stream), and (2) tracks the call's outgoing audio so {@code window.__setMute(true/false)} can switch
     * it off and on without touching the page's own UI or ending the call.
     */
    private static final String MIC_SHIM = String.join("\n",
            "(function () {",
            "  if (window.__micShim) return;",
            "  window.__micShim = true;",
            "  var BT = /bluetooth|bt[\\s_-]|headset|buds|airpods|a2dp|hfp|sco/i;",
            "  var md = navigator.mediaDevices, muted = false, tracks = [], pcs = [], timer = null;",
            "  function apply() {",
            "    tracks = tracks.filter(function (t) { return t.readyState !== 'ended'; });",
            "    tracks.forEach(function (t) { t.enabled = !muted; });",
            "    pcs = pcs.filter(function (p) { return p.connectionState !== 'closed'; });",
            "    pcs.forEach(function (p) {",
            "      p.getSenders().forEach(function (s) { if (s.track && s.track.kind === 'audio') s.track.enabled = !muted; });",
            "    });",
            "  }",
            "  window.__setMute = function (m) {",
            "    muted = !!m; apply();",
            "    if (muted && !timer) timer = setInterval(apply, 2000);",
            "    if (!muted && timer) { clearInterval(timer); timer = null; }",
            "  };",
            "  ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) {",
            "    var P = window[n];",
            "    if (!P) return;",
            "    window[n] = new Proxy(P, { construct: function (t, a, nt) {",
            "      var pc = Reflect.construct(t, a, nt); pcs.push(pc); return pc;",
            "    } });",
            "  });",
            "  if (!md || !md.getUserMedia) return;",
            "  var orig = md.getUserMedia.bind(md);",
            "  async function phoneMic() {",
            "    var list = async function () {",
            "      return (await md.enumerateDevices()).filter(function (d) {",
            "        return d.kind === 'audioinput' && d.deviceId && d.deviceId !== 'default' && d.deviceId !== 'communications';",
            "      });",
            "    };",
            "    var devs = await list();",
            "    if (devs.length && !devs[0].label) {",
            "      var tmp = await orig({ audio: true });",
            "      tmp.getTracks().forEach(function (t) { t.stop(); });",
            "      devs = await list();",
            "    }",
            "    return devs.find(function (d) { return d.label && !BT.test(d.label); });",
            "  }",
            "  md.getUserMedia = async function (c) {",
            "    if (c && c.audio && !c.audio.deviceId) {",
            "      try {",
            "        var phone = await phoneMic();",
            "        if (phone) {",
            "          var audio = c.audio === true ? {} : Object.assign({}, c.audio);",
            "          audio.deviceId = { exact: phone.deviceId };",
            "          c = Object.assign({}, c, { audio: audio });",
            "        }",
            "      } catch (e) { /* fall back to the page's own request */ }",
            "    }",
            "    var stream = await orig(c);",
            "    stream.getAudioTracks().forEach(function (t) { tracks.push(t); t.enabled = !muted; });",
            "    return stream;",
            "  };",
            "})();");

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
        if (webView != null) webView.evaluateJavascript("window.__setMute && window.__setMute(" + value + ")", null);
        if (listener != null) listener.onMuted(value);
        WebSessionService.refresh();
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
        st.setUserAgentString(desktopUserAgent(st.getUserAgentString()));
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(shimScript(), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(shimScript(), null);
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

    /** The shim, plus the current mute state so a freshly loaded page starts in the right state. */
    private String shimScript() {
        return MIC_SHIM + "\nwindow.__setMute(" + muted + ");";
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
}
