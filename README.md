# Tether

A private audio line: a web page and a native Android app that call each other (PeerJS-compatible).
Both pin the microphone to a chosen device (e.g. the phone's built-in mic) so a Bluetooth headset can
stay in its high quality A2DP mode instead of switching to call mode.

## Web / PWA

`index.html` + `manifest.webmanifest` + `sw.js` + `icons/` + `vendor/` is a static site. Serve it over HTTPS
(Netlify, Vercel, GitHub Pages, or `python3 -m http.server 8000` behind `ngrok http 8000`).

- Host taps **Enable mics**, picks the phone mic, **Start call**, and shares the link. The other person opens it and taps **Join**.
- **Install**: iPhone/iPad - Safari > Share > *Add to Home Screen*. Android/desktop Chrome - menu > *Install app*.
  It then opens full screen and loads offline (calls still need the network).
- Call volume (0-200%, via Web Audio gain so it also works on iOS) and a brightness veil are built in.
- **Hang up timer**: pick a delay (15 min - 8 h) and this device hangs up when it runs out. It counts from when the call
  connects, only ends your side (the other person stays connected until they leave), and resets to Off after each call.
- iOS caveats: Safari cannot choose the audio output, and iOS suspends web apps (and their mic) when the app is
  backgrounded or the screen locks. The page asks for a screen wake lock while in a call; for calls that must survive
  the screen turning off, use the Android app.

## Android app (`android/`)

Talks the PeerJS signalling protocol, so it can join a room created by the web page and vice versa. To keep a
headset on A2DP it never switches Android to call mode: it captures with the plain MIC source and plays the remote
audio as `USAGE_MEDIA`. The in-app diagnostics show `mode`, `a2dpOn` and the incoming codec during a call.

- **Persistent call**: a microphone foreground service plus wake/Wi-Fi locks keep the call alive with the screen off.
  Mute only disables the outgoing track, so the call stays connected and unmute is instant. The notification has Mute
  and Hang up. A dropped signalling socket does not end a connected call. Allow "Stay connected with the screen off"
  (battery exemption) in the app.
- **In-app volume**: scales only the remote voice (0-200%) before Android's mixer, independent of the phone's media volume.
- **Brightness**: a slider that dims the whole app below the system minimum.
- **Hang up timer**: the same delay picker as the web page. The countdown shows in the app and the notification, and runs
  from the foreground service, so it still fires with the screen off.
- **UI**: dark Liquid Glass from plain Views: a drifting aurora scene behind frosted panels with an SDF edge lens
  (AGSL, Android 13+; blur only on 12, flat on older), a specular rim, gel-like press feedback and a glass-lens slider.

- **Web session**: opens a configurable https page in an embedded browser (desktop layout) and keeps it running with the
  screen off, using a microphone foreground service plus wake/Wi-Fi locks. A **hang up timer** (a delay, or a clock time)
  ends the call on this end by closing the page. A small **mic selection shim** wraps `getUserMedia` so the phone's own
  mic is preferred over a Bluetooth headset, like the web client does. The address is a setting; nothing is hard-coded.

### Build

GitHub Actions (`.github/workflows/build-apk.yml`) builds a debug APK on every push to `android/**` and uploads it as
the `tether-apk` artifact (Actions tab > the run > Artifacts). On `main` it also publishes
`https://github.com/<owner>/<repo>/releases/download/latest/tether.apk`. Builds are signed with the committed
`android/app/debug.keystore`, so each new APK installs over the last one. Locally: `cd android && ./gradlew assembleDebug`.

The room-code prefix (`tether-`) must match between `index.html` (`PREFIX`) and `CallManager.PEER_PREFIX`.
