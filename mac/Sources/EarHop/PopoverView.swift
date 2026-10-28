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
        if state.owner { return state.playing ? "Playing" : (state.phase == .ownerPausedByEarRemoval ? "Paused" : "Ready") }
        if state.playing { return "Playing here" }
        return state.holder == nil ? "Idle" : "Ready"
    }

    var body: some View {
        GlassGroup {
            VStack(spacing: 12) {
                header
                banners
                hero
                if state.cfg.isBuds, state.budsConnected, let link = state.budsLink { noisePicker(link) }
                actions
                connection
                ClipCard(state: state)
                if !state.peers.isEmpty { devices }
                if !state.history.isEmpty { timeline }
                diagnostics
                footer
            }
        }
        .padding(14)
        .frame(width: 360)
        .tint(accent)
    }

    // MARK: header

    private var header: some View {
        HStack(spacing: 10) {
            Picker("Earbuds", selection: $state.cfg.earbuds) {
                Text("AirPods Pro").tag("airpods"); Text("Galaxy Buds2 Pro").tag("buds")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Active earbuds")
            Toggle("Live", isOn: $state.cfg.liveMode)
                .toggleStyle(.switch)
                .controlSize(.small)
                .help("Live: act on handoff decisions. Off: dry run, log only.")
        }
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
            banner("person.2.slash", .orange,
                   "Two devices are both named \(dev), so EarHop mixes them up. In the EarHop app on the tablet, set the name to tab (and on the phone to phone), then press Start again.", nil) {}
        }
        ForEach(state.rejected.filter { Date().timeIntervalSince($0.value.at) < 180 }.map { $0.key }.sorted(), id: \.self) { dev in
            let clock = state.rejected[dev]?.reason == "clock"
            banner("lock.trianglebadge.exclamationmark.fill", .orange,
                   clock ? "\(DeviceName.pretty(dev)) is ignored: its clock differs from this Mac by more than 30 seconds. Set both to automatic time."
                         : "\(DeviceName.pretty(dev)) uses a different secret, so it is ignored. Pair it again with the QR code.",
                   clock ? nil : "Pair") { state.onOpenSettings?() }
        }
        if !state.cfg.liveMode {
            banner("eye", .secondary, "Dry run: EarHop only logs what it would do.", "Go live") { state.cfg.liveMode = true }
        }
    }

    // MARK: hero card

    private var hero: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(accent.opacity(state.owner ? 0.22 : 0.08)).frame(width: 64, height: 64)
                    if switching {
                        ProgressView().controlSize(.large).tint(accent)
                    } else {
                        Image(systemName: state.cfg.isBuds ? "earbuds" : "airpodspro")
                            .font(.system(size: 30, weight: .regular))
                            .symbolRenderingMode(.hierarchical)
                            .foregroundStyle(state.owner ? accent : .secondary)
                    }
                }
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(state.cfg.isBuds ? "Galaxy Buds2 Pro" : "AirPods Pro 2").font(.headline)
                    Text(whereText).font(.subheadline).foregroundStyle(.secondary)
                    Text(stateWord)
                        .font(.system(.title2, design: .rounded).weight(.semibold))
                        .foregroundStyle(state.safeMode ? .orange : (state.owner || switching ? accent : .primary))
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(state.cfg.earbudsName), \(whereText), \(stateWord)")

            if switching { stages }
            if let f = state.lastFailure, !switching {
                note("exclamationmark.circle.fill", .orange, "Last switch failed: \(f).")
            } else if let h = state.holdReason, !state.owner, !switching {
                note("pause.circle", .secondary, "Not switching: \(h).")
            }

            HStack(spacing: 8) {
                chip(state.ear.symbol, state.ear.label, tint: state.ear == .none ? .orange : nil)
                batteryChips
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .glassCard()
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: switching)
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

    @ViewBuilder private var batteryChips: some View {
        if state.cfg.isBuds {
            let b = state.buds
            if state.budsConnected, b.battL >= 0 || b.battR >= 0 {
                chip("battery.75percent", "L \(pct(b.battL)) R \(pct(b.battR))", tint: low(b.battL, b.battR) ? .red : nil)
                if b.battCase >= 0 { chip("earbuds.case", pct(b.battCase), tint: b.battCase <= 20 ? .red : nil) }
            }
        } else if let b = state.battery {
            chip("battery.75percent", "L \(b.0)% R \(b.1)%", tint: min(b.0, b.1) <= 20 ? .red : nil)
            if let c = state.caseBattery { chip("airpodspro.chargingcase.wireless", "\(c)%", tint: c <= 20 ? .red : nil) }
        }
    }

    private func pct(_ v: Int) -> String { v >= 0 ? "\(v)%" : "-" }
    private func low(_ a: Int, _ b: Int) -> Bool { min(a < 0 ? 100 : a, b < 0 ? 100 : b) <= 20 }

    private func noisePicker(_ link: BudsWear) -> some View {
        Picker("Noise control", selection: Binding(get: { state.buds.noise }, set: { link.setNoise($0) })) {
            Text("Off").tag("off"); Text("Noise cancelling").tag("anc"); Text("Ambient").tag("ambient")
            if !["off", "anc", "ambient"].contains(state.buds.noise) { Text("-").tag(state.buds.noise).disabled(true) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .accessibilityLabel("Noise control")
    }

    // MARK: actions

    private var actions: some View {
        HStack(spacing: 10) {
            Button { state.onTake?() } label: {
                Label(state.owner ? "Connected here" : "Take \(state.cfg.earbudsShort)", systemImage: "arrow.down.circle.fill")
                    .frame(maxWidth: .infinity)
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
        }
        .controlSize(.large)
    }

    private var connection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Earbud switching").font(.caption).foregroundStyle(.secondary)
                if state.cfg.autoSwitch, state.switchPaused {
                    Text("paused until \(state.switchPausedUntil.formatted(date: .omitted, time: .shortened))")
                        .font(.caption2).padding(.horizontal, 6).padding(.vertical, 1).glassCapsule(tint: .orange)
                }
                Spacer()
            }
            ConnectionControl(state: state)
            HStack(spacing: 8) {
                Toggle("Auto", isOn: $state.cfg.autoSwitch)
                    .toggleStyle(.switch).controlSize(.mini)
                    .help("Move the \(state.cfg.earbudsShort) automatically to the device that starts playing. Off: only Take and Release.")
                Toggle("Calls", isOn: $state.cfg.detectCalls)
                    .toggleStyle(.switch).controlSize(.mini)
                    .help("A call on any device takes the \(state.cfg.earbudsShort)")
                Toggle("Ear pause", isOn: $state.cfg.pauseWhenOneOut)
                    .toggleStyle(.switch).controlSize(.mini)
                    .help("Pause when you take an earbud out, resume when you put it back")
                Spacer(minLength: 0)
                PauseMenu(paused: state.switchPaused, what: "automatic switching") { until in
                    state.switchPausedUntil = until
                    state.onSwitchPause?(until)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassCard()
    }

    private var devices: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Devices").font(.caption).foregroundStyle(.secondary)
            ForEach(state.peers) { p in
                HStack(spacing: 8) {
                    Image(systemName: DeviceName.symbol(p.name)).frame(width: 22).foregroundStyle(p.owner ? accent : .secondary)
                    Text(DeviceName.pretty(p.name)).fontWeight(.medium)
                    if p.call { Image(systemName: "phone.fill").foregroundStyle(.green).accessibilityLabel("in a call") }
                    Spacer()
                    if p.owner { Text("has the earbuds").font(.caption).padding(.horizontal, 8).padding(.vertical, 2).glassCapsule(tint: accent) }
                    Text(p.playing ? "Playing" : "Idle").font(.caption).foregroundStyle(p.playing ? .green : .secondary)
                    Image(systemName: p.via == "ble" ? "dot.radiowaves.left.and.right" : "wifi")
                        .font(.caption).foregroundStyle(p.age > 3 ? .orange : .secondary)
                        .help(p.via == "ble" ? "Bluetooth LE, \(p.age) s ago" : "Wi-Fi, \(p.age) s ago")
                }
                .accessibilityElement(children: .combine)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassCard()
    }

    private var timeline: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recent").font(.caption).foregroundStyle(.secondary)
            ForEach(state.history.filter { $0.kind != .peer }.prefix(4)) { e in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: symbol(e.kind)).font(.caption).foregroundStyle(color(e.kind)).frame(width: 16)
                    Text(e.text).font(.caption).lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 4)
                    Text(e.date, style: .time).font(.caption2).foregroundStyle(.secondary)
                }
                .opacity(e.dry ? 0.6 : 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .glassCard()
    }

    private var diagnostics: some View {
        DisclosureGroup(isExpanded: Binding(get: { diagBox.value }, set: { diagBox.value = $0 })) {
            VStack(spacing: 6) {
                diag("State", state.phase.label)
                diag("Ear source", "\(state.earReading.source), \(state.earReading.confidence.rawValue) confidence")
                diag("This Mac", state.playing ? "playing (\(state.playingApps.map { $0.split(separator: ".").last.map(String.init) ?? $0 }.joined(separator: ", ")))" : "idle")
                diag("Link", state.transport + (state.discovered.isEmpty ? "" : ", \(state.discovered.count) found"))
                diag("Pause and play", MediaControl.mode)
                if state.cfg.isBuds { diag("Buds channel", state.budsStatus) }
            }
            .padding(.top, 6)
        } label: {
            Label("Diagnostics", systemImage: "stethoscope").font(.callout)
        }
        .padding(.horizontal, 4)
    }

    private var footer: some View {
        HStack(spacing: 14) {
            if let last = state.history.first(where: { $0.kind == .claim || $0.kind == .release }), !last.dry {
                Button { state.onUndo?() } label: { Label("Undo", systemImage: "arrow.uturn.backward") }
                    .buttonStyle(.plain).help("Undo: \(last.text)")
            }
            Button { state.onOpenSettings?() } label: { Label("Settings", systemImage: "gearshape") }
                .buttonStyle(.plain).keyboardShortcut(",", modifiers: .command)
            Button { Log.copyToPasteboard() } label: { Label("Copy log", systemImage: "doc.on.doc") }
                .buttonStyle(.plain).help("Copy the last 400 log lines")
            Spacer()
            Button("Quit") { NSApp.terminate(nil) }.buttonStyle(.plain).foregroundStyle(.secondary).keyboardShortcut("q")
        }
        .font(.callout)
        .padding(.horizontal, 4)
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

/// Friendly names and symbols for the protocol device names.
enum DeviceName {
    static func pretty(_ n: String) -> String {
        switch n { case "mac": return "Mac"; case "phone": return "Phone"; case "tab": return "Tablet"; default: return n }
    }
    static func symbol(_ n: String) -> String {
        switch n { case "phone": return "iphone"; case "tab": return "ipad.landscape"; default: return "laptopcomputer" }
    }
}
