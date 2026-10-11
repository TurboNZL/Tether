import AVFoundation
import Foundation
import WebRTC

/// One audio call that interoperates with the PeerJS web page and the Android app.
///
/// The point of this class on iOS: keep the call alive with the screen locked (the "audio" background
/// mode plus an active audio session) and capture from the phone's own mic while Bluetooth headphones
/// stay on A2DP, by never asking for the headset's call (HFP) profile.
/// All methods must be called on the main thread.
final class CallManager: NSObject, ObservableObject {
    static let peerPrefix = "tether-"
    private static let alphabet = Array("abcdefghijklmnopqrstuvwxyz0123456789")

    @Published private(set) var status = "idle"
    @Published private(set) var active = false
    @Published private(set) var muted = false
    @Published private(set) var room: String?
    @Published private(set) var logLines: [String] = []
    /// Seconds left on the hang-up timer, nil when it is off or has not started counting yet.
    @Published private(set) var hangupRemaining: TimeInterval?
    /// Chosen hang-up delay in minutes, 0 = off. Counts from when the call connects.
    @Published var hangupAfterMinutes = 0 {
        didSet { if oldValue != hangupAfterMinutes { armHangupTimer() } }
    }

    private var host = false
    private var hostCode = ""
    private var signal: PeerSignal?
    private var factory: RTCPeerConnectionFactory?
    private var pc: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var remoteId: String?
    private var connectionId: String?
    private var signalReady = false          // our offer/answer was sent, local candidates may flow
    private var remoteDescriptionSet = false
    private var mediaConnected = false       // ICE connected once: signalling is no longer needed
    private var pendingRemote: [RTCIceCandidate] = []
    private var pendingLocal: [RTCIceCandidate] = []
    private var hangupAt: Date?
    private var ticker: Timer?
    private var observers: [NSObjectProtocol] = []

    private static let offerConstraints = RTCMediaConstraints(
        mandatoryConstraints: ["OfferToReceiveAudio": "true"], optionalConstraints: nil)

    // MARK: - lifecycle

    /// Start hosting (joinRoom == nil) or join a room created by the web page or another app.
    func start(joinRoom: String?) {
        guard !active else { return }
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                if granted {
                    self.begin(joinRoom: joinRoom)
                } else {
                    self.log("microphone permission denied - allow it in Settings > Tether")
                }
            }
        }
    }

    private func begin(joinRoom: String?) {
        guard !active else { return }
        active = true
        host = joinRoom == nil
        hostCode = joinRoom ?? Self.randomString(6)
        room = nil
        remoteId = host ? nil : Self.peerPrefix + hostCode
        connectionId = nil
        signalReady = false
        remoteDescriptionSet = false
        mediaConnected = false
        muted = false
        pendingRemote = []
        pendingLocal = []
        hangupAt = nil
        hangupRemaining = nil
        logLines = []

        configureAudio()
        factory = RTCPeerConnectionFactory()

        setStatus(host ? "connecting to server…" : "calling…")
        let signal = PeerSignal()
        signal.onMessage = { [weak self] message in self?.handleSignal(message) }
        signal.onClosed = { [weak self] reason in
            guard let self = self, self.active else { return }
            if self.mediaConnected {
                // The media path is peer-to-peer; losing the signalling socket must not end a live call.
                self.log("\(reason) - call continues")
            } else {
                self.hangup(reason)
            }
        }
        self.signal = signal
        signal.connect(id: host ? Self.peerPrefix + hostCode : Self.peerPrefix + "app-" + Self.randomString(6))

        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    func hangup(_ reason: String) {
        guard active else { return }
        active = false
        ticker?.invalidate()
        ticker = nil
        hangupAt = nil
        hangupRemaining = nil
        hangupAfterMinutes = 0 // each call starts with the timer off
        log("call ended: \(reason)")
        signal?.close()
        signal = nil
        pc?.close()
        pc = nil
        audioTrack = nil
        factory = nil
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        muted = false
        room = nil
        setStatus("idle")
    }

    /// Muting only disables the outgoing track: capture and the connection keep running, so unmuting is instant.
    func setMuted(_ value: Bool) {
        muted = value
        audioTrack?.isEnabled = !value
        log(value ? "mic muted (call stays connected)" : "mic live")
    }

    // MARK: - hang-up timer

    private func armHangupTimer() {
        hangupAt = nil
        hangupRemaining = nil
        guard hangupAfterMinutes > 0, active, mediaConnected else { return }
        hangupAt = Date().addingTimeInterval(TimeInterval(hangupAfterMinutes * 60))
        log("will hang up in \(hangupAfterMinutes) min")
        tick()
    }

    private func tick() {
        guard active, let at = hangupAt else { return }
        // Compare against a real deadline rather than counting ticks, so a late tick cannot drift it.
        let left = at.timeIntervalSinceNow
        if left <= 0 {
            hangup("hang-up timer reached")
        } else {
            hangupRemaining = left
        }
    }

    // MARK: - audio session

    private func configureAudio() {
        // WebRTC's default is voice-chat mode with Bluetooth HFP, which drops a headset into its low
        // quality call profile. Replace it: A2DP only, so the phone's own mic is used for capture.
        let config = RTCAudioSessionConfiguration.webRTC()
        config.category = AVAudioSession.Category.playAndRecord.rawValue
        config.categoryOptions = [.allowBluetoothA2DP, .defaultToSpeaker]
        config.mode = AVAudioSession.Mode.default.rawValue
        RTCAudioSessionConfiguration.setWebRTC(config)

        let rtc = RTCAudioSession.sharedInstance()
        rtc.useManualAudio = true
        rtc.isAudioEnabled = false
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothA2DP, .defaultToSpeaker])
            try session.setActive(true)
            rtc.isAudioEnabled = true
        } catch {
            log("audio session failed: \(error.localizedDescription)")
        }

        // After a phone call, Siri or an alarm, take the audio back instead of staying silent.
        let token = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self = self, self.active,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
            self.log("audio interruption ended - resuming")
            try? AVAudioSession.sharedInstance().setActive(true)
            RTCAudioSession.sharedInstance().isAudioEnabled = false
            RTCAudioSession.sharedInstance().isAudioEnabled = true
        }
        observers.append(token)
        logRouting()
    }

    private func logRouting() {
        let route = AVAudioSession.sharedInstance().currentRoute
        let inputs = route.inputs.map { "\($0.portType.rawValue) (\($0.portName))" }.joined(separator: ", ")
        let outputs = route.outputs.map { "\($0.portType.rawValue) (\($0.portName))" }.joined(separator: ", ")
        log("route in=[\(inputs)] out=[\(outputs)]")
    }

    // MARK: - peer connection

    private func createPeerConnection() {
        guard let factory = factory else { return }
        let config = RTCConfiguration()
        config.iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
            RTCIceServer(urlStrings: ["turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"],
                         username: "peerjs", credential: "peerjsp"),
        ]
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        pc = factory.peerConnection(with: config, constraints: constraints, delegate: self)

        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        let track = factory.audioTrack(with: source, trackId: "tether-audio")
        track.isEnabled = !muted
        pc?.add(track, streamIds: ["tether-stream"])
        audioTrack = track
    }

    // MARK: - signalling

    private func handleSignal(_ m: [String: Any]) {
        let type = m["type"] as? String ?? ""
        let payload = m["payload"] as? [String: Any]
        switch type {
        case "OPEN":
            log("signalling open")
            if host {
                room = hostCode
                setStatus("waiting for the other person…")
            } else {
                startOutgoingCall()
            }
        case "ID-TAKEN":
            hangup("room code already taken, try again")
        case "INVALID-KEY", "ERROR":
            hangup("server error: \((payload?["msg"] as? String) ?? type)")
        case "EXPIRE":
            hangup("the other person is not available (wrong code?)")
        case "LEAVE":
            if pc != nil, m["src"] as? String == remoteId { hangup("the other person left") }
        case "OFFER":
            if host, pc == nil, let payload = payload { handleOffer(from: m["src"] as? String ?? "", payload) }
        case "ANSWER":
            if !host, let payload = payload, sameConnection(payload) { handleAnswer(payload) }
        case "CANDIDATE":
            if let payload = payload, connectionId == nil || sameConnection(payload) { handleCandidate(payload) }
        default:
            break
        }
    }

    private func sameConnection(_ payload: [String: Any]) -> Bool {
        (payload["connectionId"] as? String) == connectionId
    }

    /// We are the caller (joining a room hosted by the web page or the Android app).
    private func startOutgoingCall() {
        connectionId = "mc_" + Self.randomString(10)
        createPeerConnection()
        pc?.offer(for: Self.offerConstraints) { [weak self] sdp, error in
            DispatchQueue.main.async {
                guard let self = self, self.active, let pc = self.pc else { return }
                guard let offer = sdp else {
                    self.log("createOffer failed: \(error?.localizedDescription ?? "?")")
                    return
                }
                pc.setLocalDescription(offer) { error in
                    DispatchQueue.main.async {
                        if let error = error {
                            self.log("setLocal(offer) failed: \(error.localizedDescription)")
                        } else {
                            self.sendSdp("OFFER", offer)
                        }
                    }
                }
            }
        }
    }

    /// We are the callee (the web page or the Android app called our room).
    private func handleOffer(from: String, _ payload: [String: Any]) {
        remoteId = from
        connectionId = payload["connectionId"] as? String
        setStatus("incoming call…")
        createPeerConnection()
        guard let offer = Self.parseSdp(payload["sdp"] as? [String: Any], type: .offer), let pc = pc else {
            hangup("bad offer")
            return
        }
        pc.setRemoteDescription(offer) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self, self.active, let pc = self.pc else { return }
                if let error = error {
                    self.log("setRemote(offer) failed: \(error.localizedDescription)")
                    return
                }
                self.onRemoteDescriptionSet()
                pc.answer(for: Self.offerConstraints) { sdp, error in
                    DispatchQueue.main.async {
                        guard self.active, let pc = self.pc else { return }
                        guard let answer = sdp else {
                            self.log("createAnswer failed: \(error?.localizedDescription ?? "?")")
                            return
                        }
                        pc.setLocalDescription(answer) { error in
                            DispatchQueue.main.async {
                                if let error = error {
                                    self.log("setLocal(answer) failed: \(error.localizedDescription)")
                                } else {
                                    self.sendSdp("ANSWER", answer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func handleAnswer(_ payload: [String: Any]) {
        guard let answer = Self.parseSdp(payload["sdp"] as? [String: Any], type: .answer), let pc = pc else { return }
        pc.setRemoteDescription(answer) { [weak self] error in
            DispatchQueue.main.async {
                if let error = error {
                    self?.log("setRemote(answer) failed: \(error.localizedDescription)")
                } else {
                    self?.onRemoteDescriptionSet()
                }
            }
        }
    }

    private func handleCandidate(_ payload: [String: Any]) {
        guard let c = payload["candidate"] as? [String: Any],
              let text = c["candidate"] as? String, !text.isEmpty else { return }
        let candidate = RTCIceCandidate(
            sdp: text,
            sdpMLineIndex: Int32((c["sdpMLineIndex"] as? Int) ?? 0),
            sdpMid: (c["sdpMid"] as? String) ?? "0")
        if let pc = pc, remoteDescriptionSet {
            pc.add(candidate) { _ in }
        } else {
            pendingRemote.append(candidate)
        }
    }

    private func onRemoteDescriptionSet() {
        guard let pc = pc else { return }
        remoteDescriptionSet = true
        pendingRemote.forEach { pc.add($0) { _ in } }
        pendingRemote = []
    }

    private func sendSdp(_ type: String, _ sdp: RTCSessionDescription) {
        guard let signal = signal, let connectionId = connectionId, let remoteId = remoteId else { return }
        var payload: [String: Any] = [
            "sdp": ["type": type == "OFFER" ? "offer" : "answer", "sdp": sdp.sdp],
            "type": "media",
            "connectionId": connectionId,
        ]
        if type == "OFFER" { payload["metadata"] = NSNull() }
        signal.send(["type": type, "payload": payload, "dst": remoteId])
        log("sent \(type)")
        signalReady = true
        pendingLocal.forEach { sendCandidate($0) }
        pendingLocal = []
    }

    private func sendCandidate(_ c: RTCIceCandidate) {
        guard let signal = signal, let connectionId = connectionId, let remoteId = remoteId else { return }
        let candidate: [String: Any] = [
            "candidate": c.sdp,
            "sdpMid": c.sdpMid ?? "0",
            "sdpMLineIndex": Int(c.sdpMLineIndex),
        ]
        let payload: [String: Any] = ["candidate": candidate, "type": "media", "connectionId": connectionId]
        signal.send(["type": "CANDIDATE", "payload": payload, "dst": remoteId])
    }

    private static func parseSdp(_ json: [String: Any]?, type: RTCSdpType) -> RTCSessionDescription? {
        guard let sdp = json?["sdp"] as? String, !sdp.isEmpty else { return nil }
        return RTCSessionDescription(type: type, sdp: sdp)
    }

    // MARK: - helpers

    private func log(_ line: String) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        logLines.insert("[\(stamp)] \(line)", at: 0)
        if logLines.count > 200 { logLines.removeLast(logLines.count - 200) }
    }

    private func setStatus(_ value: String) {
        status = value
    }

    private static func randomString(_ length: Int) -> String {
        String((0..<length).map { _ in alphabet.randomElement()! })
    }
}

// MARK: - RTCPeerConnectionDelegate (callbacks arrive on WebRTC threads, so everything hops to main)

extension CallManager: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        DispatchQueue.main.async {
            guard self.active else { return }
            switch newState {
            case .connected, .completed:
                let first = !self.mediaConnected
                self.mediaConnected = true
                self.setStatus("connected")
                if first {
                    self.log("ice: connected")
                    self.logRouting()
                    if self.hangupAfterMinutes > 0 { self.armHangupTimer() }
                }
            case .disconnected:
                self.setStatus("connection unstable…")
            case .failed, .closed:
                self.hangup("connection lost")
            default:
                break
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        DispatchQueue.main.async {
            guard self.active else { return }
            if self.signalReady {
                self.sendCandidate(candidate)
            } else {
                self.pendingLocal.append(candidate)
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        // PeerJS callers open an unused data channel next to the media; ignore it.
    }
}
