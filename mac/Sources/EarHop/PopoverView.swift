import SwiftUI

struct PopoverView: View {
    @ObservedObject var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var diagBox = Box(false)

    private var accent: Color { state.cfg.accentColor }
    private var switching: Bool { state.stage != nil || state.phase == .claimPending || state.phase == .releasePending }

    /// Where the earbuds are right now, in words.
    private var whereText: String {
        if state.owner { return "Connected to this Mac" }
        if let h = state.holder { return "Connected to \(DeviceName.pretty(h))" }
        return "Not connected"
    }

    private var stateWord: String {
        if switching { return "Switching" }
        if state.safeMode { return "Safe mode" }
        if state.inCall { return "Call" }
        if state.owner && state.onSpeaker { return state.playing ? "On speakers" : "Ready" }
        if state.owner { return state.playing ? "Playing" : (state.phase == .ownerPausedByEarRemoval ? "Paused" : "Ready") }
        if state.playing { return "Playing here" }
        return state.holder == nil ? "Idle" : "Ready"
    }

    /// Measured height of everything in the popover, so it can be as tall as its content but never taller than the screen.
    @State private var contentHeight: CGFloat = 600

    /// The tallest the popover may be: the screen's usable height (below the menu bar, above the Dock) less a margin.
    private var maxHeight: CGFloat { max(320, (NSScreen.main?.visibleFrame.height ?? 800) - 24) }

    var body: some View {
        // Scrolls when the content is taller than the screen; otherwise it is exactly as tall as the content.
        ScrollView(.vertical, showsIndicators: contentHeight > maxHeight) {
            content
                .background(GeometryReader { g in Color.clear.preference(key: ContentHeightKey.self, value: g.size.height) })
        }
        .onPreferenceChange(ContentHeightKey.self) { contentHeight = $0 }
        .frame(width: 340, height: min(contentHeight, maxHeight))
        .tint(accent)
    }

    /// Compact: the earbuds, sound (Buds here), switching and clipboard, devices. Everything else is in Settings.
    private var content: some View {
        GlassGroup {
            VStack(spacing: 10) {
                topBar
                earbudsPicker
                banners
                hero
                earbudsCard
                HStack(spacing: 10) { switchingTile; clipTile }
                devicesGrid
                footer
            }
        }
        .padding(12)
    }

    // MARK: top bar

    /// "auto", or a pair chosen by hand (which turns automatic detection off).
    private var earbudsChoice: Binding<String> {
        Binding(get: { state.cfg.earbudsMode == "auto" ? "auto" : state.cfg.earbuds }, set: { v in
            var c = state.cfg
            if v == "auto" { c.earbudsMode = "auto" } else { c.earbudsMode = "manual"; c.earbuds = v }
            state.cfg = c
        })
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Image(systemName: state.cfg.isBuds ? "earbuds" : "airpodspro")
                .font(.title3).foregroundStyle(accent).frame(width: 30, height: 30)
                .background(Circle().fill(accent.opacity(0.15)))
            VStack(alignment: .leading, spacing: 1) {
                Text(state.cfg.isBuds ? "Galaxy Buds2 Pro" : "AirPods Pro 2").font(.headline)
                Text(whereText).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 6)
            Text(stateWord)
                .font(.caption.weight(.semibold))
                .foregroundStyle(state.safeMode ? .orange : (state.owner || switching ? accent : .primary))
                .padding(.horizontal, 9).padding(.vertical, 4)
                .glassCapsule()
                .contentTransition(.opacity)
        }
        .padding(.horizontal, 4)
    }

    /// Which earbuds EarHop moves, as a horizontal selector like the one in Settings, Earbuds.
    private var earbudsPicker: some View {
        Picker("Earbuds", selection: earbudsChoice) {
            Text("Automatic").tag("auto")
            Text("AirPods").tag("airpods")
            Text("Galaxy Buds").tag("buds")
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .controlSize(.small)
        .padding(.horizontal, 4)
        .help("Automatic follows the pair connected here, or the pair the device holding the earbuds reports")
        .accessibilityLabel("Earbuds")
    }

    @ViewBuilder private var banners: some View {
        if state.safeMode {
            banner("exclamationmark.triangle.fill", .orange, "Automatic switching paused after repeated failures. Take still works.", "Resume") { state.onClearSafe?() }
        }
        if !state.keysAllowed && state.cfg.mediaControl == "key" {
            banner("hand.raised.fill", .orange, "Allow EarHop under Accessibility so it can pause and resume.", "Open") {
                AudioMonitor.requestKeyPermission(); AudioMonitor.openKeyPermissionSettings()
            }
        }
        if state.cfg.activeAddress.isEmpty {
            banner("sparkles", accent, "Connect your \(state.cfg.earbudsName) to this Mac, then let EarHop find them.", "Detect") { state.onDetect?() }
        }
        ForEach(state.duplicates.filter { Date().timeIntervalSince($0.value) < 180 }.map { $0.key }.sorted(), id: \.self) { dev in
            banner("person.2.slash", .orange, "Two devices are both named \(dev). Give each device its own name.", nil) {}
        }
        ForEach(state.rejected.filter { Date().timeIntervalSince($0.value.at) < 180 }.map { $0.key }.sorted(), id: \.self) { dev in
            banner("lock.trianglebadge.exclamationmark.fill", .orange,
                   "\(DeviceName.pretty(dev)) uses a different secret, so it is ignored. Pair it again with the QR code.", "Pair") { state.onOpenSettings?() }
        }
        if !state.cfg.liveMode {
            banner("eye", .secondary, "Dry run: EarHop only logs what it would do.", "Go live") { state.cfg.liveMode = true }
        }
    }

    // MARK: hero card

    /// What the picture shows. Galaxy Buds report each bud and the case over their channel; AirPods give the wear state
    /// and the levels the Mac reads.
    private var budsModel: BudsPicture.Model {
        var m = BudsPicture.Model(playing: state.playing, here: state.owner, stem: !state.cfg.isBuds)
        func place(_ p: String?, _ side: Int) -> String {
            if let p = p, p != "unknown" { return p == "disconnected" ? "unknown" : p }
            guard state.owner else { return "unknown" }
            switch state.ear {
            case .both: return "wearing"
            case .one: return side == 0 ? "wearing" : "idle"
            case .none: return "idle"
            case .unknown: return "unknown"
            }
        }
        if state.cfg.isBuds {
            let b = state.buds, live = state.budsConnected && state.owner
            m.left = .init(battery: live ? b.battL : -1, place: place(live ? b.placeL : nil, 0), charging: live && b.chargeL)
            m.right = .init(battery: live ? b.battR : -1, place: place(live ? b.placeR : nil, 1), charging: live && b.chargeR)
            m.caseLevel = live ? b.battCase : -1
            m.caseCharging = live && b.chargeCase
            // Connected to another device: the levels it shares, and the wear state from its status.
            if !live, let r = state.remoteBattery, r.fresh {
                let ear = state.peers.first { $0.name == state.holder }?.ear ?? .unknown
                let worn = ear == .both ? "wearing" : ear == .none ? "idle" : "unknown"
                m.left = .init(battery: r.l, place: worn, charging: r.cl)
                m.right = .init(battery: r.r, place: worn, charging: r.cr)
                m.caseLevel = r.c
                m.caseCharging = r.cc
            }
        } else {
            m.left = .init(battery: state.battery?.0 ?? -1, place: place(nil, 0))
            m.right = .init(battery: state.battery?.1 ?? -1, place: place(nil, 1))
            m.caseLevel = state.caseBattery ?? -1
        }
        return m
    }

    private var hero: some View {
        VStack(spacing: 8) {
            BudsPicture(model: budsModel, tint: accent, height: 132)
            if switching { stages }
            if let f = state.lastFailure, !switching {
                note("exclamationmark.circle.fill", .orange, "Last switch failed: \(f).")
            } else if let h = state.holdReason, !state.owner, !switching {
                note("pause.circle", .secondary, "Not switching: \(h).")
            }
            actions
            lastSwitch
        }
        .padding(12)
        .glassCard(cornerRadius: 22)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.7), value: switching)
    }

    /// The four handoff stages, lit up to the current one. Success is only shown once ownership is verified.
    private var stages: some View {
        let names = ["Pausing", "Releasing", "Connecting", "Confirming"]
        let s = state.stage ?? ""
        let current = s.hasPrefix("Confirming") ? 3 : s.hasPrefix("Connecting") ? 2 : (s.hasPrefix("Waiting") || s.hasPrefix("Releasing")) ? 1 : 0
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                ForEach(0 ..< names.count, id: \.self) { i in
                    Capsule().fill(i <= current ? accent : Color.secondary.opacity(0.25)).frame(height: 4)
                }
            }
            Text(state.stage ?? (state.phase == .releasePending ? "Releasing" : "Switching")).font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Switching, step \(current + 1) of 4, \(state.stage ?? "")")
    }


    // MARK: earbuds

    /// Galaxy Buds here: noise control (three round buttons) and the equalizer. Always: the ear and call options.
    private var earbudsCard: some View {
        let link = state.budsLink
        let budsHere = state.cfg.isBuds && state.budsConnected
        return VStack(alignment: .leading, spacing: 10) {
            if budsHere {
                NoiseButtons(mode: state.buds.noise, accent: accent) { link?.setNoise($0) }
                Text("Equalizer").font(.caption).foregroundStyle(.secondary).padding(.top, 2)
                EqButtons(preset: state.buds.eq, accent: accent) { link?.setEq($0) }
                Divider()
            }
            optionRow("Pause when an earbud comes out", $state.cfg.pauseWhenOneOut)
            optionRow("Play on the speakers when they are out", $state.cfg.speakerFallback)
            optionRow("Calls and meetings come first", $state.cfg.detectCalls)
            if budsHere, let v = state.buds.touchOn {
                optionRow("Touch controls", Binding(get: { v }, set: { link?.setTouch($0) }))
            }
        }
        .toggleStyle(.switch).controlSize(.small)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .glassCard(cornerRadius: 22)
    }

    /// A label on the left and its switch lined up on the right.
    private func optionRow(_ title: String, _ value: Binding<Bool>) -> some View {
        HStack {
            Text(title).font(.callout)
            Spacer(minLength: 8)
            Toggle(title, isOn: value).labelsHidden()
        }
    }

    // MARK: actions

    private var actions: some View {
        HStack(spacing: 8) {
            Button { state.onTake?() } label: {
                Label(state.owner ? "Here" : "Take", systemImage: state.owner ? "checkmark.circle.fill" : "arrow.down.circle.fill").frame(maxWidth: .infinity)
            }
            .glassButton(prominent: true)
            .keyboardShortcut(.defaultAction)
            .disabled(state.owner || switching)
            .accessibilityLabel("Take the \(state.cfg.earbudsShort)")

            Button { state.onRelease?() } label: {
                Label("Release", systemImage: "arrow.up.circle").frame(maxWidth: .infinity)
            }
            .glassButton()
            .disabled(!state.owner || switching)
            .accessibilityLabel("Release the \(state.cfg.earbudsShort)")

            // Sleep timer: pause and let go of the earbuds after a while.
            Menu {
                if state.sleepUntil > Date() {
                    Text("Pauses and releases at \(state.sleepUntil.formatted(date: .omitted, time: .shortened))")
                    Button("Cancel the sleep timer") { state.onSleep?(0) }
                    Divider()
                }
                ForEach([15, 30, 45, 60, 90], id: \.self) { m in Button("In \(m) minutes") { state.onSleep?(m) } }
            } label: { Image(systemName: state.sleepUntil > Date() ? "moon.zzz.fill" : "moon.zzz") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                .help(state.sleepUntil > Date() ? "Sleep timer: \(state.sleepUntil.formatted(date: .omitted, time: .shortened))" : "Sleep timer")
                .accessibilityLabel("Sleep timer")
        }
        .controlSize(.regular)
    }

    // MARK: tiles

    private var switchingTile: some View {
        let on = state.cfg.autoSwitch && !state.switchPaused
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Switching").font(.callout.weight(.semibold))
                Spacer(minLength: 2)
                Toggle("Switching", isOn: $state.cfg.autoSwitch).toggleStyle(.switch).controlSize(.mini).labelsHidden()
            }
            Text(state.switchPaused ? "Paused until \(state.switchPausedUntil.formatted(date: .omitted, time: .shortened))"
                 : (state.cfg.autoSwitch ? state.transport : "Off, Take and Release only"))
                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            MethodPicker(title: "Switching link", value: $state.cfg.transport)
            HStack {
                Spacer()
                PauseMenu(paused: state.switchPaused, what: "automatic switching") { until in
                    state.switchPausedUntil = until
                    state.onSwitchPause?(until)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).strokeBorder(on ? Color.green.opacity(0.8) : Color.secondary.opacity(0.35), lineWidth: 1.5))
        .glassCard(cornerRadius: 18)
    }

    private var clipTile: some View {
        let on = state.cfg.clipSync && !state.clipPaused
        let recent = Date().timeIntervalSince(state.clipAt) < 3600 && !state.clipStatus.isEmpty
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Clipboard").font(.callout.weight(.semibold))
                Spacer(minLength: 2)
                Toggle("Clipboard sync", isOn: $state.cfg.clipSync).toggleStyle(.switch).controlSize(.mini).labelsHidden()
            }
            Text(state.clipPaused ? "Paused until \(state.clipPausedUntil.formatted(date: .omitted, time: .shortened))"
                 : (!state.cfg.clipSync ? "Off" : (recent ? state.clipStatus : "Copies sync automatically")))
                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            MethodPicker(title: "Clipboard link", value: $state.cfg.clipTransport)
            HStack(spacing: 6) {
                Button { state.onClipSend?() } label: { Image(systemName: "paperplane") }
                    .buttonStyle(.plain).help("Send this Mac's clipboard now").disabled(!state.cfg.clipSync)
                Menu {
                    Button("Send files…") { pickFiles(to: nil) }
                    ForEach(otherDevices, id: \.self) { d in Button("Send files to \(DeviceName.pretty(d))…") { pickFiles(to: d) } }
                    Divider()
                    ForEach(otherDevices, id: \.self) { d in
                        Button("Open copied link on \(DeviceName.pretty(d))") {
                            if let s = NSPasteboard.general.string(forType: .string) { state.onSendLink?(s.trimmingCharacters(in: .whitespacesAndNewlines), d) }
                        }
                    }
                } label: { Image(systemName: "square.and.arrow.up") }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    .help("Send files, or open the copied link on another device")
                Spacer()
                PauseMenu(paused: state.clipPaused, what: "clipboard sync") { until in
                    state.clipPausedUntil = until
                    state.onClipPause?(until)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).strokeBorder(on ? Color.green.opacity(0.8) : Color.secondary.opacity(0.35), lineWidth: 1.5))
        .glassCard(cornerRadius: 18)
    }

    private var otherDevices: [String] { state.peers.map(\.name).filter { $0 != state.cfg.deviceName }.sorted() }

    private func pickFiles(to dev: String?) {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.canChooseDirectories = false
        p.prompt = "Send"
        p.message = dev.map { "Send to \(DeviceName.pretty($0)) over Wi-Fi" } ?? "Send to your other devices over Wi-Fi"
        NSApp.activate(ignoringOtherApps: true)
        if p.runModal() == .OK, !p.urls.isEmpty { state.onSendFiles?(p.urls, dev) }
    }

    // MARK: devices

    /// Every device, as many as there are: a grid that wraps, devices heard now first. The green outline marks the one
    /// with the earbuds; a dimmed chip is a known device not heard for a while.
    private var deviceNames: [String] {
        let me = state.cfg.deviceName
        let live = state.peers.map(\.name)
        var all = live
        for n in state.cfg.priority + state.cfg.knownDevices where n != me && !all.contains(n) && state.status.devices.contains(where: { $0.name == n }) {
            all.append(n)
        }
        return all
    }

    private var devicesGrid: some View {
        let names = deviceNames
        return VStack(alignment: .leading, spacing: 6) {
            if names.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "laptopcomputer.and.iphone").foregroundStyle(.secondary)
                    Text("No other device yet.").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("Add a Device") { openSettings(.pairing) }.buttonStyle(.link).font(.caption)
                }
                .padding(.horizontal, 4)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], alignment: .leading, spacing: 8) {
                    ForEach(names, id: \.self) { n in deviceChip(n) }
                }
            }
        }
    }

    private func deviceChip(_ n: String) -> some View {
        let p = state.peers.first { $0.name == n }
        let live = p != nil
        return Button { openSettings(.devices) } label: {
            HStack(spacing: 5) {
                Image(systemName: DeviceName.symbol(n)).font(.caption)
                Text(DeviceName.pretty(n)).font(.caption.weight(.medium)).lineLimit(1).truncationMode(.tail)
                if p?.call == true { Image(systemName: "phone.fill").font(.caption2).foregroundStyle(.green) }
                else if p?.playing == true { Image(systemName: "waveform").font(.caption2).foregroundStyle(.secondary) }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 9).padding(.vertical, 5)
            .frame(maxWidth: .infinity)
            .opacity(live ? 1 : 0.5)
            .overlay(Capsule().strokeBorder(p?.owner == true ? Color.green.opacity(0.8) : Color.clear, lineWidth: 1.2))
            .glassCapsule()
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .springy()
        .help(live ? "\(DeviceName.pretty(n)), \(DeviceName.kind(n).title): \(p!.owner ? "has the earbuds, " : "")\(p!.playing ? "playing" : "idle"), over \(p!.via == "ble" ? "Bluetooth LE" : "Wi-Fi")"
                   : "\(DeviceName.pretty(n)), \(DeviceName.kind(n).title): not heard right now")
        .accessibilityLabel("\(DeviceName.pretty(n)), \(live ? (p!.owner ? "has the earbuds" : p!.playing ? "playing" : "idle") : "away")")
    }

    // MARK: last switch and footer

    /// The last switch in a line, with Undo while it is recent (10 minutes).
    @ViewBuilder private var lastSwitch: some View {
        if let last = state.history.first(where: { $0.kind == .claim || $0.kind == .release }), !last.dry,
           Date().timeIntervalSince(last.date) < 600, !switching {
            HStack(spacing: 6) {
                Image(systemName: last.kind == .claim ? "arrow.down.circle" : "arrow.up.circle").foregroundStyle(.secondary)
                Text(last.text).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 4)
                Button("Undo") { state.onUndo?() }.buttonStyle(.link).font(.caption)
                    .help(last.kind == .claim ? "Give the earbuds back" : "Take the earbuds back")
            }
        }
    }

    /// Settings, Copy log and Quit, as a quiet row at the bottom like other menu bar apps.
    private var footer: some View {
        HStack(spacing: 14) {
            Button { state.onOpenSettings?() } label: { Label("Settings", systemImage: "gearshape") }
                .keyboardShortcut(",", modifiers: .command)
            Button { Log.copyToPasteboard(); state.clipStatus = "Log copied"; state.clipAt = Date() } label: { Label("Copy Log", systemImage: "doc.on.doc") }
            Spacer()
            Button { NSApp.terminate(nil) } label: { Label("Quit", systemImage: "power") }
                .keyboardShortcut("q")
        }
        .buttonStyle(.plain)
        .font(.caption)
        .foregroundStyle(.secondary)
        .labelStyle(.titleAndIcon)
        .padding(.horizontal, 6).padding(.top, 2)
    }

    private func openSettings(_ page: SettingsPage) {
        state.settingsPageRequest = page
        state.onOpenSettings?()
    }

    // MARK: pieces

    private func banner(_ symbol: String, _ color: Color, _ text: String, _ button: String?, _ action: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).foregroundStyle(color)
            Text(text).font(.callout).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if let b = button { Button(b, action: action).glassButton() }
        }
        .padding(12)
        .glassCard(cornerRadius: 14)
    }

    private func note(_ symbol: String, _ color: Color, _ text: String) -> some View {
        Label { Text(text).font(.caption).fixedSize(horizontal: false, vertical: true) } icon: { Image(systemName: symbol).foregroundStyle(color) }
    }

    private func chip(_ symbol: String, _ text: String, tint: Color?) -> some View {
        Label(text, systemImage: symbol)
            .font(.caption.weight(.medium))
            .foregroundStyle(tint ?? .primary)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .glassCapsule()
    }

    private func diag(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Text(v).font(.caption.monospaced()).multilineTextAlignment(.trailing).textSelection(.enabled)
        }
    }

    private func symbol(_ k: HandoffEvent.Kind) -> String {
        switch k {
        case .claim: return "arrow.down.circle.fill"
        case .release: return "arrow.up.circle"
        case .pause: return "pause.circle"
        case .resume: return "play.circle"
        case .safe: return "exclamationmark.triangle.fill"
        case .peer: return "person.2"
        case .info: return "info.circle"
        }
    }

    private func color(_ k: HandoffEvent.Kind) -> Color {
        switch k {
        case .claim: return accent
        case .safe: return .orange
        case .pause, .resume: return .secondary
        default: return .secondary
        }
    }
}

private struct ContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Noise control as three round buttons on a line: Noise cancelling, Off, Ambient. The white highlight springs to the
/// chosen one; each button sinks under the pointer and bounces back.
struct NoiseButtons: View {
    let mode: String
    let accent: Color
    let pick: (String) -> Void
    @Namespace private var ns
    private let items: [(String, String)] = [("anc", "Noise cancelling"), ("off", "Off"), ("ambient", "Ambient")]

    var body: some View {
        ZStack(alignment: .top) {
            Capsule().fill(Color.secondary.opacity(0.35)).frame(height: 2).padding(.horizontal, 48).offset(y: 21)
            HStack(spacing: 0) {
                ForEach(items, id: \.0) { m, label in
                    Button { pick(m) } label: {
                        VStack(spacing: 6) {
                            ZStack {
                                // Solid, so the line behind does not show through.
                                Circle().fill(Color(nsColor: .windowBackgroundColor))
                                Circle().fill(Color.secondary.opacity(0.22))
                                if mode == m {
                                    Circle().fill(Color(nsColor: .labelColor).opacity(1))
                                        .shadow(color: accent.opacity(0.6), radius: 8)
                                        .matchedGeometryEffect(id: "pick", in: ns)
                                }
                                NoiseIcon(kind: m).foregroundStyle(mode == m ? Color(nsColor: .windowBackgroundColor) : Color.primary)
                            }
                            .frame(width: 44, height: 44)
                            Text(label).font(.caption2).foregroundStyle(mode == m ? .primary : .secondary).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(SpringButtonStyle())
                    .accessibilityLabel("\(label)\(mode == m ? ", selected" : "")")
                }
            }
        }
        .animation(.spring(response: 0.38, dampingFraction: 0.62), value: mode)
    }
}

/// The six equalizer presets side by side, the same round buttons as the noise control. A custom preset made in
/// Galaxy Wearable selects none and says so.
struct EqButtons: View {
    let preset: String
    let accent: Color
    let pick: (String) -> Void
    @Namespace private var ns
    static let items: [(String, String)] = [("off", "Off"), ("bass", "Bass"), ("soft", "Soft"),
                                            ("dynamic", "Dynamic"), ("clear", "Clear"), ("treble", "Treble")]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 0) {
                ForEach(EqButtons.items, id: \.0) { p, label in
                    Button { pick(p) } label: {
                        VStack(spacing: 5) {
                            ZStack {
                                Circle().fill(Color.secondary.opacity(0.22))
                                if preset == p {
                                    Circle().fill(Color(nsColor: .labelColor))
                                        .shadow(color: accent.opacity(0.6), radius: 7)
                                        .matchedGeometryEffect(id: "eq", in: ns)
                                }
                                EqIcon(preset: p).foregroundStyle(preset == p ? Color(nsColor: .windowBackgroundColor) : Color.primary)
                            }
                            .frame(width: 36, height: 36)
                            Text(label).font(.caption2).foregroundStyle(preset == p ? .primary : .secondary).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(SpringButtonStyle())
                    .accessibilityLabel("Equalizer \(label)\(preset == p ? ", selected" : "")")
                }
            }
            if preset == "custom" {
                Text("A custom equalizer from Galaxy Wearable is on.").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .animation(.spring(response: 0.38, dampingFraction: 0.62), value: preset)
    }
}

/// Five small bars drawing the shape of each preset (low notes on the left).
struct EqIcon: View {
    let preset: String
    private var heights: [CGFloat] {
        switch preset {
        case "bass": return [15, 12, 8, 6, 5]
        case "soft": return [7, 10, 12, 10, 7]
        case "dynamic": return [14, 8, 5, 8, 14]
        case "clear": return [5, 8, 13, 11, 8]
        case "treble": return [5, 6, 8, 12, 15]
        default: return [7, 7, 7, 7, 7]
        }
    }
    var body: some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(Array(heights.enumerated()), id: \.offset) { _, h in
                Capsule().frame(width: 2.5, height: h)
            }
        }
    }
}

/// The three noise icons: rings and a dot (cancelling), a small ring (off), a ring of dots (ambient).
struct NoiseIcon: View {
    let kind: String
    var body: some View {
        ZStack {
            switch kind {
            case "anc":
                Circle().stroke(lineWidth: 2).frame(width: 22, height: 22)
                Circle().frame(width: 8, height: 8)
            case "off":
                Circle().stroke(lineWidth: 2).frame(width: 16, height: 16)
                Circle().frame(width: 6, height: 6)
            default:
                ForEach(0 ..< 8, id: \.self) { i in
                    Circle().frame(width: 3, height: 3)
                        .offset(x: 11 * cos(Double(i) * .pi / 4), y: 11 * sin(Double(i) * .pi / 4))
                }
                Circle().frame(width: 6, height: 6)
            }
        }
    }
}

/// A button that sinks under the pointer and bounces back past its size.
struct SpringButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.45), value: configuration.isPressed)
    }
}

/// Cards and chips answer a press: they sink a little and spring back, without taking clicks from their controls.
struct Springy: ViewModifier {
    @GestureState private var pressed = false
    func body(content: Content) -> some View {
        content
            .scaleEffect(pressed ? 0.975 : 1)
            .animation(.spring(response: 0.32, dampingFraction: 0.5), value: pressed)
            .simultaneousGesture(DragGesture(minimumDistance: 0).updating($pressed) { _, s, _ in s = true })
    }
}

extension View {
    func springy() -> some View { modifier(Springy()) }
}


/// How a feature travels: Bluetooth LE, both, or Wi-Fi.
struct MethodPicker: View {
    let title: String
    @Binding var value: String
    var body: some View {
        Picker(title, selection: $value) {
            Image(systemName: "dot.radiowaves.left.and.right").help("Bluetooth LE").tag("ble")
            Text("Both").tag("both")
            Image(systemName: "wifi").help("Wi-Fi").tag("wifi")
        }
        .pickerStyle(.segmented).labelsHidden().controlSize(.mini)
        .accessibilityLabel(title)
    }
}

