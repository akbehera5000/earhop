import SwiftUI

/// How clips travel, set apart from the handoff connection. Used in the popover and in Settings, Clipboard.
enum ClipMode: String, CaseIterable, Identifiable {
    case ble, both, wifi
    var id: String { rawValue }
    var title: String {
        switch self { case .ble: return "Bluetooth"; case .both: return "Both"; case .wifi: return "Wi-Fi" }
    }
    var symbol: String { (LinkMode(rawValue: rawValue) ?? .both).symbol }
    var explanation: String {
        switch self {
        case .both: return "Text goes over Bluetooth LE and Wi-Fi at the same time and the first copy to arrive wins. Images go over Wi-Fi. Recommended."
        case .ble: return "Text goes over Bluetooth LE only, so it works without Wi-Fi, within about one room. Images and text over 64 KB still use Wi-Fi when there is one, since Bluetooth LE cannot carry them."
        case .wifi: return "Everything goes over Wi-Fi. The devices must be on the same network. Works across rooms and is fastest for long text."
        }
    }
}

/// The clipboard method switch, shared by the popover card and the Settings page.
struct ClipModePicker: View {
    @ObservedObject var state: AppState
    var body: some View {
        Picker("Clipboard method", selection: $state.cfg.clipTransport) {
            ForEach(ClipMode.allCases) { m in Label(m.title, systemImage: m.symbol).tag(m.rawValue) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .accessibilityLabel("How clipboard items travel to your other devices")
    }
}

/// A live dot per link for clips: green when it reaches a device, orange when on but nobody is in reach, grey when off.
struct ClipLinks: View {
    @ObservedObject var state: AppState
    private var mode: ClipMode { ClipMode(rawValue: state.cfg.clipTransport) ?? .both }
    var body: some View {
        HStack(spacing: 12) {
            pill("Bluetooth LE", on: mode != .wifi, count: state.clipBlePeers)
            pill("Wi-Fi", on: mode != .ble, count: state.clipWifiPeers, note: mode == .ble ? "big clips" : nil)
            Spacer(minLength: 0)
        }
    }
    private func pill(_ name: String, on: Bool, count: Int, note: String? = nil) -> some View {
        let used = on || note != nil
        let color: Color = !used ? .secondary.opacity(0.5) : (count > 0 ? .green : .orange)
        let word = !used ? "off" : (count > 0 ? "\(count) device\(count == 1 ? "" : "s")" : "nobody in reach") + (on ? "" : " (\(note!))")
        return HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text("\(name): \(word)").font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name) \(word)")
    }
}

/// One recent clip: direction, preview, device and time. Clicking copies it here again.
struct ClipRecentRow: View {
    @ObservedObject var state: AppState
    let r: ClipRecent
    var body: some View {
        Button { state.onClipRecopy?(r) } label: {
            HStack(spacing: 8) {
                Image(systemName: r.sent ? "arrow.up.circle" : "arrow.down.circle")
                    .foregroundStyle(r.sent ? Color.secondary : state.cfg.accentColor).frame(width: 16)
                if r.kind == "image", let d = r.image, let img = NSImage(data: d) {
                    Image(nsImage: img).resizable().scaledToFill().frame(width: 22, height: 22).clipShape(RoundedRectangle(cornerRadius: 4))
                }
                Text(preview).font(.caption).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 4)
                Text(r.sent ? "to \(r.dev)" : "from \(r.dev)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                Text(r.date, style: .time).font(.caption2).foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help("Copy this again")
        .accessibilityLabel("\(r.sent ? "Sent" : "Received") \(preview), \(r.sent ? "to" : "from") \(r.dev). Copy again.")
    }
    private var preview: String {
        if r.kind == "image" { return "Image" }
        let t = (r.text ?? "").replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? "Text" : t
    }
}

/// The pause menu used by the clipboard and earbud switching cards: 15 minutes, 1 hour, until tomorrow 7:00, resume.
struct PauseMenu: View {
    let paused: Bool
    let what: String
    let onPause: (Date) -> Void
    var body: some View {
        Menu {
            if paused { Button("Resume now") { onPause(.distantPast) } }
            Button("Pause for 15 minutes") { onPause(Date().addingTimeInterval(15 * 60)) }
            Button("Pause for 1 hour") { onPause(Date().addingTimeInterval(3600)) }
            Button("Pause until tomorrow") {
                onPause(Calendar.current.startOfDay(for: Date().addingTimeInterval(86_400)).addingTimeInterval(7 * 3600))
            }
        } label: { Image(systemName: paused ? "pause.circle.fill" : "pause.circle") }
            .menuStyle(.borderlessButton).fixedSize()
            .help("Pause \(what)")
            .accessibilityLabel("Pause \(what)")
    }
}

/// Popover card: on or off, the method with live links, recent clips, Send now and Pause.
struct ClipCard: View {
    @ObservedObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Clipboard").font(.caption).foregroundStyle(.secondary)
                if state.cfg.clipSync, state.clipPaused {
                    Text("paused until \(state.clipPausedUntil.formatted(date: .omitted, time: .shortened))")
                        .font(.caption2).padding(.horizontal, 6).padding(.vertical, 1).glassCapsule(tint: .orange)
                }
                Spacer()
                Toggle("Clipboard sync", isOn: $state.cfg.clipSync)
                    .toggleStyle(.switch).controlSize(.mini).labelsHidden()
                    .accessibilityLabel("Clipboard sync")
            }
            if state.cfg.clipSync {
                ClipModePicker(state: state)
                ClipLinks(state: state)
                if !state.clipRecent.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(state.clipRecent.prefix(3)) { r in ClipRecentRow(state: state, r: r) }
                    }
                    .padding(.top, 2)
                } else {
                    Text(statusLine).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack(spacing: 8) {
                    Button { state.onClipSend?() } label: { Label("Send now", systemImage: "paperplane") }
                        .glassButton().controlSize(.small)
                        .help("Send what is on this Mac's clipboard to your other devices")
                    Toggle("Auto", isOn: $state.cfg.clipAuto)
                        .toggleStyle(.switch).controlSize(.mini)
                        .help("Send copies automatically")
                    Spacer(minLength: 0)
                    PauseMenu(paused: state.clipPaused, what: "clipboard sync") { until in
                        state.clipPausedUntil = until
                        state.onClipPause?(until)
                    }
                }
            } else {
                Text("Off. Copies stay on this Mac.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassCard()
    }

    private var statusLine: String {
        if !state.clipStatus.isEmpty, Date().timeIntervalSince(state.clipAt) < 600 {
            return "\(state.clipStatus), \(state.clipAt.formatted(date: .omitted, time: .shortened))"
        }
        return state.cfg.clipAuto ? "Copies are sent automatically" : "Automatic sending is off"
    }
}

/// Settings, Clipboard.
struct ClipboardPage: View {
    @ObservedObject var state: AppState
    private var mode: ClipMode { ClipMode(rawValue: state.cfg.clipTransport) ?? .both }
    private var others: [String] {
        var names = ["phone", "tab"]
        for p in state.peers where !names.contains(p.name) && p.name != state.cfg.deviceName { names.append(p.name) }
        return names.filter { $0 != state.cfg.deviceName }
    }

    private func pauseFor(_ minutes: Int) {
        let until = Date().addingTimeInterval(Double(minutes) * 60)
        state.clipPausedUntil = until
        state.onClipPause?(until)
    }

    var body: some View {
        Form {
            Section {
                Toggle("Sync clipboard with my other devices", isOn: $state.cfg.clipSync)
            } footer: {
                Text("Always encrypted with your shared secret. Items your password manager marks as secret are never sent.")
            }
            Section {
                ClipModePicker(state: state).padding(.vertical, 4)
                ClipLinks(state: state)
                Text(mode.explanation).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            } header: { Text("Method") } footer: {
                Text("This is only for the clipboard. Earbud switching has its own setting on the Connection page. Clips from the other devices are accepted over either link.")
            }
            .disabled(!state.cfg.clipSync)
            Section {
                Toggle("Send copies automatically", isOn: $state.cfg.clipAuto)
                Toggle("Receive clips from other devices", isOn: $state.cfg.clipReceive)
                Toggle("Include images (Wi-Fi)", isOn: $state.cfg.clipImages)
                Toggle("Notify me when a clip arrives", isOn: $state.cfg.clipNotify)
            } header: { Text("Behaviour") } footer: {
                Text("With automatic sending off, use Send now in the menu bar popover.")
            }
            .disabled(!state.cfg.clipSync)
            Section {
                ForEach(others, id: \.self) { name in
                    Toggle(isOn: Binding(
                        get: { !state.cfg.clipExclude.contains(name) },
                        set: { on in
                            state.cfg.clipExclude.removeAll { $0 == name }
                            if !on { state.cfg.clipExclude.append(name) }
                        })) {
                        Label(DeviceName.pretty(name), systemImage: DeviceName.symbol(name))
                    }
                }
            } header: { Text("Send to") }
            .disabled(!state.cfg.clipSync)
            Section {
                Picker("Keep in the menu", selection: $state.cfg.clipHistory) {
                    Text("Off").tag(0); Text("5 clips").tag(5); Text("10 clips").tag(10); Text("20 clips").tag(20)
                }
                ForEach(state.clipRecent.prefix(8)) { r in ClipRecentRow(state: state, r: r) }
                if !state.clipRecent.isEmpty {
                    Button("Clear recent clips") { state.clipRecent = [] }
                }
            } header: { Text("Recent clips") } footer: {
                Text("Kept in memory only, never saved to disk, and gone when EarHop quits. Click one to copy it again.")
            }
            .disabled(!state.cfg.clipSync)
            Section {
                LabeledContent("Pause") {
                    HStack {
                        if state.clipPaused {
                            Text("until \(state.clipPausedUntil.formatted(date: .omitted, time: .shortened))").foregroundStyle(.orange)
                            Button("Resume") { state.clipPausedUntil = .distantPast; state.onClipPause?(.distantPast) }
                        } else {
                            Button("15 minutes") { pauseFor(15) }
                            Button("1 hour") { pauseFor(60) }
                        }
                    }
                }
                LabeledContent("Last") {
                    Text(state.clipStatus.isEmpty ? "Nothing yet" : "\(state.clipStatus), \(state.clipAt.formatted(date: .omitted, time: .shortened))")
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Button("Send clipboard now") { state.onClipSend?() }.glassButton(prominent: true)
                    Spacer()
                }
            } header: { Text("Status") } footer: {
                Text("On the phone, automatic sending needs the EarHop clipboard helper (started once from a computer, and again after the phone restarts). The tablet uses root. Without either, Android sends with the Send clipboard tile, the notification button or Share.")
            }
            .disabled(!state.cfg.clipSync)
        }
    }
}
