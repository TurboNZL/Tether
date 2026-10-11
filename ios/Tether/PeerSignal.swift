import Foundation

/// Minimal client for the PeerJS signalling protocol (PeerJS 1.5.x, hosted server 0.peerjs.com),
/// so this app can place and answer calls with the web page and the Android app.
/// All callbacks run on the main thread.
final class PeerSignal {
    var onMessage: (([String: Any]) -> Void)?
    var onClosed: ((String) -> Void)?

    private let session = URLSession(configuration: .default)
    private var task: URLSessionWebSocketTask?
    private var heartbeat: Timer?
    private var closed = false

    func connect(id: String) {
        let token = String(UInt64.random(in: 1...UInt64.max), radix: 36)
        let url = URL(string: "wss://0.peerjs.com:443/peerjs?key=peerjs&id=\(id)&token=\(token)&version=1.5.5")!
        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        receive()
        heartbeat = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.task?.send(.string("{\"type\":\"HEARTBEAT\"}")) { _ in }
        }
    }

    func send(_ message: [String: Any]) {
        guard !closed,
              let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    func close() {
        closed = true
        heartbeat?.invalidate()
        heartbeat = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                // Ignore malformed server messages, as the PeerJS client does.
                if case .string(let text) = message,
                   let data = text.data(using: .utf8),
                   let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    DispatchQueue.main.async {
                        if !self.closed { self.onMessage?(object) }
                    }
                }
                if !self.closed { self.receive() }
            case .failure(let error):
                DispatchQueue.main.async { self.fail("signalling error: \(error.localizedDescription)") }
            }
        }
    }

    private func fail(_ reason: String) {
        if closed { return }
        close()
        onClosed?(reason)
    }
}
