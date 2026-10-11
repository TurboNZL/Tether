import SwiftUI

struct ContentView: View {
    @StateObject private var call = CallManager()
    @AppStorage("webUrl") private var webUrl = ""
    @State private var joinText = ""

    private static let timerChoices = [0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480]
    private static let timerHelp = "Ends the call on this phone only, after the time you pick. The other person "
        + "stays connected until they leave. The countdown starts when the call connects."

    private let green = Color(red: 52 / 255, green: 211 / 255, blue: 153 / 255)
    private let red = Color(red: 1, green: 69 / 255, blue: 58 / 255)
    private let amber = Color(red: 251 / 255, green: 191 / 255, blue: 36 / 255)

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.024, green: 0.027, blue: 0.047), Color(red: 0.043, green: 0.055, blue: 0.094)],
                startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if call.active, let room = call.room { inviteCard(room) }
                    timerCard
                    if !call.active { joinCard }
                    linkCard
                    diagnosticsCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 20)
                .padding(.bottom, 100)
            }
        }
        .preferredColorScheme(.dark)
        .safeAreaInset(edge: .bottom) { dock }
    }

    // MARK: - header

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tether").font(.system(size: 38, weight: .semibold))
            Text("A private audio line.").font(.system(size: 15)).foregroundStyle(.secondary)
            HStack(spacing: 9) {
                Circle().fill(statusColor).frame(width: 9, height: 9)
                Text(statusLabel).font(.system(size: 14, weight: .medium))
            }
            .padding(.vertical, 9).padding(.horizontal, 14)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.top, 10)
        }
    }

    private var statusLabel: String {
        switch call.status {
        case "idle": return "Not in a call"
        case "connected": return call.muted ? "Connected - mic muted" : "Connected"
        default: return call.status.prefix(1).uppercased() + call.status.dropFirst()
        }
    }

    private var statusColor: Color {
        switch call.status {
        case "idle": return .gray
        case "connected": return call.muted ? amber : green
        default: return amber
        }
    }

    // MARK: - cards

    private func card<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.system(size: 13)).foregroundStyle(.tertiary)
    }

    private func inviteCard(_ room: String) -> some View {
        card("Invite") {
            Text(room)
                .font(.system(size: 30, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
            hint("Send the link to the other person, or share it from the bar below.")
        }
    }

    private var timerCard: some View {
        card("Hang up timer") {
            Picker("Hang up timer", selection: $call.hangupAfterMinutes) {
                ForEach(Self.timerChoices, id: \.self) { Text(Self.timerLabel($0)).tag($0) }
            }
            .pickerStyle(.menu)
            .tint(.white)
            hint(timerHint)
        }
    }

    private var timerHint: String {
        guard let left = call.hangupRemaining else { return Self.timerHelp }
        let s = Int(left.rounded(.up))
        let clock = s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
        return "Hanging up in \(clock). Only your end hangs up."
    }

    private static func timerLabel(_ minutes: Int) -> String {
        if minutes == 0 { return "Off" }
        if minutes < 60 { return "After \(minutes) minutes" }
        if minutes == 60 { return "After 1 hour" }
        if minutes % 60 == 0 { return "After \(minutes / 60) hours" }
        return "After \(minutes / 60)\u{00BD} hours"
    }

    private var joinCard: some View {
        card("Join a call") {
            TextField("room code or link", text: $joinText)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(Color.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 16))
            Button {
                let room = Self.extractRoom(joinText)
                if !room.isEmpty { call.start(joinRoom: room) }
            } label: {
                Text("Join").font(.system(size: 16, weight: .medium)).frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(Color.white.opacity(0.14), in: Capsule())
            }
            .buttonStyle(.plain)
        }
    }

    private var linkCard: some View {
        card("Invite link") {
            TextField("https://your-site.pages.dev", text: $webUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .disabled(call.active)
                .padding(14)
                .background(Color.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 16))
            hint("The address of the web page, used to build the link you send.")
        }
    }

    private var diagnosticsCard: some View {
        card("Diagnostics") {
            DisclosureGroup("Log") {
                Text(call.logLines.joined(separator: "\n"))
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .tint(.secondary)
        }
    }

    // MARK: - dock

    private var dock: some View {
        HStack(spacing: 10) {
            if call.active {
                dockButton(call.muted ? "Unmute" : "Mute",
                           icon: call.muted ? "mic.slash.fill" : "mic.fill",
                           tint: call.muted ? Color.white.opacity(0.26) : nil) { call.setMuted(!call.muted) }
                if call.room != nil, let url = shareURL {
                    ShareLink(item: url) { dockLabel("Share", icon: "square.and.arrow.up", tint: nil) }
                        .buttonStyle(.plain)
                }
                dockButton("End", icon: "phone.down.fill", tint: red.opacity(0.5)) { call.hangup("you hung up") }
            } else {
                dockButton("Start call", icon: "play.fill", tint: green.opacity(0.4)) { call.start(joinRoom: nil) }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func dockLabel(_ title: String, icon: String, tint: Color?) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
            Text(title).font(.system(size: 16, weight: .medium))
        }
        .frame(maxWidth: .infinity, minHeight: 58)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().fill(tint ?? .clear))
    }

    private func dockButton(_ title: String, icon: String, tint: Color?, action: @escaping () -> Void) -> some View {
        Button(action: action) { dockLabel(title, icon: icon, tint: tint) }.buttonStyle(.plain)
    }

    // MARK: - helpers

    private var shareURL: String? {
        guard let room = call.room else { return nil }
        var base = webUrl.trimmingCharacters(in: .whitespaces)
        if let q = base.firstIndex(of: "?") { base = String(base[..<q]) }
        return base.isEmpty ? room : base + "?join=" + room
    }

    private static func extractRoom(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = s.range(of: "join=") {
            s = String(s[r.upperBound...])
            if let amp = s.firstIndex(of: "&") { s = String(s[..<amp]) }
        }
        if s.hasPrefix(CallManager.peerPrefix) { s = String(s.dropFirst(CallManager.peerPrefix.count)) }
        return s.trimmingCharacters(in: .whitespaces)
    }
}
