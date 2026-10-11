package app.tether;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One audio call that interoperates with the PeerJS web page.
 *
 * The point of this class: capture from a chosen (phone) mic while leaving Android's audio mode at
 * MODE_NORMAL and playing the remote audio as USAGE_MEDIA, so a Bluetooth headset stays on A2DP.
 * All methods must be called on the main thread.
 */
final class CallManager {
    interface Listener {
        void onLog(String line);

        void onStatus(String status);

        /** Host only: the room code others can join. */
        void onRoomReady(String room);

        void onEnded();

        /** Mute changed, possibly from the notification rather than the app. */
        void onMuted(boolean muted);

        /** Hang-up timer countdown, once a second; {@code remainingMs} is -1 when no timer is running. */
        void onHangupTimer(long remainingMs);
    }

    static final String PEER_PREFIX = "tether-";
    private static final String CONNECTION_TYPE = "media";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final CallManager INSTANCE = new CallManager();
    private static boolean webrtcInitialized;

    static CallManager get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private Listener listener;
    private String status = "idle";

    private Context app;
    private boolean active;
    private boolean host;
    private String room;
    private PeerSignal signal;
    private JavaAudioDeviceModule adm;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private AudioTrack remoteTrack;
    private boolean muted;
    private boolean mediaConnected;   // ICE connected at least once: signalling is no longer needed
    private double volume = 1.0;      // in-app gain for the remote voice, independent of Android's volume
    private String remoteId;
    private String connectionId;
    private boolean signalReady; // our offer/answer has been sent, local candidates may flow
    private boolean remoteDescriptionSet;
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private final List<IceCandidate> pendingLocalCandidates = new ArrayList<>();
    private long lastBytes;
    private long lastStatsAt;
    private long hangupAfterMs;       // chosen hang-up delay, 0 = off; counts from when the call connects
    private long hangupAt;            // SystemClock.elapsedRealtime() deadline, 0 = not counting
    private long notifiedMinutes = -1;

    private final Runnable hangupTick = new Runnable() {
        @Override
        public void run() {
            if (!active || hangupAt == 0) return;
            // Compare against a real-time deadline rather than counting ticks, so a late tick cannot drift it.
            long left = hangupAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                hangup("hang-up timer reached");
                return;
            }
            if (listener != null) listener.onHangupTimer(left);
            long minutes = (left + 59_999) / 60_000;
            if (minutes != notifiedMinutes) {
                notifiedMinutes = minutes;
                CallService.refresh();
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable diagnostics = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            logRouting();
            logInboundStats();
            main.postDelayed(this, 5000);
        }
    };

    boolean isActive() {
        return active;
    }

    String getStatus() {
        return status;
    }

    String getLog() {
        return logBuffer.toString();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isMuted() {
        return muted;
    }

    /**
     * Muting only disables the outgoing track: the capture, the peer connection and the foreground
     * service all keep running, so the call stays live and unmuting is instant.
     */
    void setMuted(boolean value) {
        muted = value;
        if (audioTrack != null) audioTrack.setEnabled(!muted);
        log(muted ? "mic muted (call stays connected)" : "mic live");
        if (listener != null) listener.onMuted(muted);
        CallService.refresh();
    }

    double getVolume() {
        return volume;
    }

    /** Gain applied to the remote audio before it reaches Android's mixer (0 = silent, 1 = unchanged). */
    void setVolume(double gain) {
        volume = Math.max(0.0, Math.min(2.0, gain));
        if (remoteTrack != null) remoteTrack.setVolume(volume);
    }

    long getHangupAfterMs() {
        return hangupAfterMs;
    }

    /** Time left on the hang-up timer, or -1 when it is off or has not started counting yet. */
    long getHangupRemainingMs() {
        return hangupAt == 0 ? -1 : Math.max(0, hangupAt - SystemClock.elapsedRealtime());
    }

    /**
     * Hangs up this end only after {@code ms} (0 = off); the other person stays connected until they
     * leave. Counts from now if the call is connected, otherwise from the moment it connects.
     */
    void setHangupAfter(long ms) {
        hangupAfterMs = Math.max(0, ms);
        armHangupTimer();
    }

    private void armHangupTimer() {
        main.removeCallbacks(hangupTick);
        hangupAt = 0;
        notifiedMinutes = -1;
        if (hangupAfterMs > 0 && active && mediaConnected) {
            hangupAt = SystemClock.elapsedRealtime() + hangupAfterMs;
            log("will hang up in " + hangupAfterMs / 60_000 + " min");
            hangupTick.run();
        } else {
            if (listener != null) listener.onHangupTimer(-1);
            CallService.refresh();
        }
    }

    /** "42 min" or "1 h 05 min", rounded up to the minute. */
    static String formatMinutes(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        return minutes < 60 ? minutes + " min" : String.format(java.util.Locale.US, "%d h %02d min", minutes / 60, minutes % 60);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** Start hosting (room == null) or join an existing room created by the web page or this app. */
    void start(Context context, AudioDeviceInfo mic, String joinRoom) {
        if (active) return;
        app = context.getApplicationContext();
        active = true;
        host = joinRoom == null;
        room = host ? randomString(6) : joinRoom;
        remoteId = host ? null : PEER_PREFIX + joinRoom;
        connectionId = null;
        signalReady = false;
        remoteDescriptionSet = false;
        mediaConnected = false;
        muted = false;
        hangupAt = 0; // hangupAfterMs is kept: it may have been picked before the call started
        remoteTrack = null;
        pendingRemoteCandidates.clear();
        pendingLocalCandidates.clear();
        lastBytes = 0;
        lastStatsAt = 0;
        logBuffer.setLength(0);

        // Keeps the mic capturing while the screen is off or the app is in the background.
        app.startForegroundService(new Intent(app, CallService.class));

        try {
            createFactory(mic);
        } catch (RuntimeException e) {
            log("audio setup failed: " + e);
            hangup("audio setup failed");
            return;
        }

        setStatus(host ? "connecting to server…" : "calling…");
        signal = new PeerSignal(new PeerSignal.Listener() {
            @Override
            public void onMessage(JSONObject message) {
                handleSignal(message);
            }

            @Override
            public void onClosed(String reason) {
                if (!active) return;
                if (mediaConnected) {
                    // The media path is peer-to-peer; losing the signalling socket (doze, a network
                    // change, the free server dropping idle clients) must not end a live call.
                    log(reason + " - call continues");
                } else {
                    hangup(reason);
                }
            }
        });
        String ownId = host ? PEER_PREFIX + room : PEER_PREFIX + "app-" + randomString(6);
        signal.connect(ownId);
        main.postDelayed(diagnostics, 5000);
    }

    void hangup(String reason) {
        if (!active) return;
        active = false;
        main.removeCallbacks(diagnostics);
        main.removeCallbacks(hangupTick);
        hangupAt = 0;
        hangupAfterMs = 0; // each call starts with the timer off
        log("call ended: " + reason);
        if (signal != null) signal.close();
        signal = null;
        remoteTrack = null; // owned by the connection; never touch it after the connection is gone
        if (pc != null) {
            pc.close();
            pc.dispose();
            pc = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (adm != null) {
            adm.release();
            adm = null;
        }
        app.stopService(new Intent(app, CallService.class));
        muted = false;
        setStatus("idle");
        if (listener != null) listener.onEnded();
    }

    // ---- audio / WebRTC setup --------------------------------------------------------------

    private void createFactory(AudioDeviceInfo mic) {
        if (!webrtcInitialized) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
            webrtcInitialized = true;
        }
        // Playout as plain media (not "voice communication") so Android sends it to the A2DP
        // headset; capture from the plain MIC source so the headset's own mic is never requested.
        AudioAttributes playback = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        adm = JavaAudioDeviceModule.builder(app)
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioAttributes(playback)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule();
        if (mic != null) adm.setPreferredInputDevice(mic);
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
        log("mic: " + (mic == null ? "system default" : describe(mic)));
        logRouting();
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        List<String> turn = new ArrayList<>();
        turn.add("turn:eu-0.turn.peerjs.com:3478");
        turn.add("turn:us-0.turn.peerjs.com:3478");
        servers.add(PeerConnection.IceServer.builder(turn)
                .setUsername("peerjs").setPassword("peerjsp").createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc = factory.createPeerConnection(config, new PcObserver());

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("tether-audio", audioSource);
        audioTrack.setEnabled(!muted);
        pc.addTrack(audioTrack, Collections.singletonList("tether-stream"));
    }

    // ---- signalling ------------------------------------------------------------------------

    private void handleSignal(JSONObject m) {
        String type = m.optString("type");
        JSONObject payload = m.optJSONObject("payload");
        switch (type) {
            case "OPEN":
                log("signalling open");
                if (host) {
                    setStatus("waiting for the other person…");
                    if (listener != null) listener.onRoomReady(room);
                } else {
                    startOutgoingCall();
                }
                break;
            case "ID-TAKEN":
                hangup("room code already taken, try again");
                break;
            case "INVALID-KEY":
            case "ERROR":
                hangup("server error: " + (payload != null ? payload.optString("msg") : type));
                break;
            case "EXPIRE":
                hangup("the other person is not available (wrong code?)");
                break;
            case "LEAVE":
                if (pc != null && m.optString("src").equals(remoteId)) hangup("the other person left");
                break;
            case "OFFER":
                if (host && pc == null && payload != null) handleOffer(m.optString("src"), payload);
                break;
            case "ANSWER":
                if (!host && payload != null && sameConnection(payload)) handleAnswer(payload);
                break;
            case "CANDIDATE":
                if (payload != null && (connectionId == null || sameConnection(payload))) {
                    handleCandidate(payload);
                }
                break;
            default:
                break;
        }
    }

    private boolean sameConnection(JSONObject payload) {
        return payload.optString("connectionId").equals(connectionId);
    }

    /** We are the caller (joining a room hosted by the web page). */
    private void startOutgoingCall() {
        connectionId = "mc_" + randomString(10);
        createPeerConnection();
        pc.createOffer(new SdpAdapter("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                pc.setLocalDescription(new SdpAdapter("setLocal(offer)") {
                    @Override
                    public void onSetSuccess() {
                        sendSdp("OFFER", offer);
                    }
                }, offer);
            }
        }, new MediaConstraints());
    }

    /** We are the callee (the web page or another app instance called our room). */
    private void handleOffer(String from, JSONObject payload) {
        remoteId = from;
        connectionId = payload.optString("connectionId");
        setStatus("incoming call…");
        createPeerConnection();
        SessionDescription offer = parseSdp(payload.optJSONObject("sdp"), SessionDescription.Type.OFFER);
        if (offer == null) {
            hangup("bad offer");
            return;
        }
        pc.setRemoteDescription(new SdpAdapter("setRemote(offer)") {
            @Override
            public void onSetSuccess() {
                onRemoteDescriptionSet();
                pc.createAnswer(new SdpAdapter("createAnswer") {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SdpAdapter("setLocal(answer)") {
                            @Override
                            public void onSetSuccess() {
                                sendSdp("ANSWER", answer);
                            }
                        }, answer);
                    }
                }, new MediaConstraints());
            }
        }, offer);
    }

    private void handleAnswer(JSONObject payload) {
        SessionDescription answer = parseSdp(payload.optJSONObject("sdp"), SessionDescription.Type.ANSWER);
        if (answer == null || pc == null) return;
        pc.setRemoteDescription(new SdpAdapter("setRemote(answer)") {
            @Override
            public void onSetSuccess() {
                onRemoteDescriptionSet();
            }
        }, answer);
    }

    private void handleCandidate(JSONObject payload) {
        JSONObject c = payload.optJSONObject("candidate");
        if (c == null || c.optString("candidate").isEmpty()) return;
        IceCandidate candidate = new IceCandidate(
                c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.optString("candidate"));
        if (pc != null && remoteDescriptionSet) {
            pc.addIceCandidate(candidate);
        } else {
            pendingRemoteCandidates.add(candidate);
        }
    }

    private void onRemoteDescriptionSet() {
        main.post(() -> {
            if (pc == null) return;
            remoteDescriptionSet = true;
            for (IceCandidate c : pendingRemoteCandidates) pc.addIceCandidate(c);
            pendingRemoteCandidates.clear();
        });
    }

    private void sendSdp(String type, SessionDescription sdp) {
        main.post(() -> {
            if (signal == null) return;
            try {
                JSONObject sdpJson = new JSONObject()
                        .put("type", sdp.type.canonicalForm())
                        .put("sdp", sdp.description);
                JSONObject payload = new JSONObject()
                        .put("sdp", sdpJson)
                        .put("type", CONNECTION_TYPE)
                        .put("connectionId", connectionId);
                if (type.equals("OFFER")) payload.put("metadata", JSONObject.NULL);
                signal.send(new JSONObject().put("type", type).put("payload", payload).put("dst", remoteId));
            } catch (JSONException e) {
                log("failed to build " + type + ": " + e);
                return;
            }
            log("sent " + type);
            signalReady = true;
            for (IceCandidate c : pendingLocalCandidates) sendCandidate(c);
            pendingLocalCandidates.clear();
        });
    }

    private void sendCandidate(IceCandidate c) {
        if (signal == null) return;
        try {
            JSONObject candidate = new JSONObject()
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex);
            JSONObject payload = new JSONObject()
                    .put("candidate", candidate)
                    .put("type", CONNECTION_TYPE)
                    .put("connectionId", connectionId);
            signal.send(new JSONObject().put("type", "CANDIDATE").put("payload", payload).put("dst", remoteId));
        } catch (JSONException e) {
            log("failed to build candidate: " + e);
        }
    }

    private static SessionDescription parseSdp(JSONObject json, SessionDescription.Type type) {
        if (json == null) return null;
        String sdp = json.optString("sdp");
        return sdp.isEmpty() ? null : new SessionDescription(type, sdp);
    }

    // ---- diagnostics -----------------------------------------------------------------------

    private void logRouting() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        StringBuilder outputs = new StringBuilder();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (outputs.length() > 0) outputs.append(", ");
            outputs.append(typeName(d.getType()));
        }
        log("audio mode=" + modeName(am.getMode()) + " a2dpOn=" + am.isBluetoothA2dpOn()
                + " scoOn=" + am.isBluetoothScoOn() + " outputs=[" + outputs + "]");
    }

    private void logInboundStats() {
        if (pc == null) return;
        pc.getStats(report -> main.post(() -> readInbound(report)));
    }

    private void readInbound(RTCStatsReport report) {
        Map<String, RTCStats> all = report.getStatsMap();
        for (RTCStats s : all.values()) {
            if (!s.getType().equals("inbound-rtp")) continue;
            Map<String, Object> members = s.getMembers();
            if (!"audio".equals(members.get("kind"))) continue;
            long bytes = members.get("bytesReceived") instanceof Number
                    ? ((Number) members.get("bytesReceived")).longValue() : 0;
            long now = System.currentTimeMillis();
            long kbps = lastStatsAt == 0 ? 0 : (bytes - lastBytes) * 8 / Math.max(1, now - lastStatsAt);
            lastBytes = bytes;
            lastStatsAt = now;
            String codec = "?";
            Object codecId = members.get("codecId");
            if (codecId != null && all.get(codecId.toString()) != null) {
                Object mime = all.get(codecId.toString()).getMembers().get("mimeType");
                if (mime != null) codec = mime.toString();
            }
            log("in: " + codec + " " + kbps + " kbps lost=" + members.get("packetsLost"));
        }
    }

    static String describe(AudioDeviceInfo d) {
        return typeName(d.getType()) + " – " + d.getProductName();
    }

    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Phone mic";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth call (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB device";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "Telephony";
            default: return "Type " + type;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            default: return String.valueOf(mode);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void log(String line) {
        logBuffer.insert(0, line + "\n");
        if (listener != null) listener.onLog(line);
    }

    private void setStatus(String s) {
        status = s;
        if (listener != null) listener.onStatus(s);
        CallService.refresh();
    }

    private static String randomString(int length) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** SdpObserver that logs failures; subclasses override only what they need. */
    private class SdpAdapter implements SdpObserver {
        private final String label;

        SdpAdapter(String label) {
            this.label = label;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            main.post(() -> log(label + " failed: " + error));
        }

        @Override
        public void onSetFailure(String error) {
            main.post(() -> log(label + " failed: " + error));
        }
    }

    /** PeerConnection.Observer; callbacks arrive on WebRTC threads, so everything hops to main. */
    private class PcObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            main.post(() -> {
                if (!active) return;
                log("ice: " + state);
                switch (state) {
                    case CONNECTED:
                    case COMPLETED:
                        boolean first = !mediaConnected;
                        mediaConnected = true;
                        setStatus("connected");
                        if (first && hangupAfterMs > 0) armHangupTimer();
                        break;
                    case DISCONNECTED:
                        setStatus("connection unstable…");
                        break;
                    case FAILED:
                    case CLOSED:
                        hangup("connection lost");
                        break;
                    default:
                        break;
                }
            });
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            main.post(() -> {
                if (!active) return;
                if (signalReady) sendCandidate(candidate);
                else pendingLocalCandidates.add(candidate);
            });
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(MediaStream stream) {
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
        }

        @Override
        public void onDataChannel(DataChannel channel) {
            // PeerJS callers open an unused data channel next to the media; ignore it.
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            main.post(() -> {
                if (!active || pc == null) return;
                if (receiver.track() instanceof AudioTrack) {
                    remoteTrack = (AudioTrack) receiver.track();
                    remoteTrack.setVolume(volume);
                    log("remote voice gain " + Math.round(volume * 100) + "%");
                }
            });
        }

        @Override
        public void onRenegotiationNeeded() {
        }
    }
}
