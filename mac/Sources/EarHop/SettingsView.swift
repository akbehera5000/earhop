import SwiftUI
import CoreBluetooth

enum SettingsPage: String, CaseIterable, Identifiable {
    case general = "General", airpods = "AirPods", buds = "Buds", handoff = "Handoff", connection = "Earbud Link", clipboard = "Clipboard", apps = "Apps", history = "History", network = "Network", log = "Log"
    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .airpods: return "airpodspro"
        case .buds: return "earbuds"
        case .handoff: return "arrow.left.arrow.right"
        case .connection: return "antenna.radiowaves.left.and.right"
        case .clipboard: return "doc.on.clipboard"
        case .apps: return "app.badge"
        case .history: return "clock.arrow.circlepath"
        case .network: return "network"
        case .log: return "text.alignleft"
        }
    }
}

/// Tiny observable box, used instead of @State (the SwiftUI macro plugin needs full Xcode).
final class Box<T>: ObservableObject {
    @Published var value: T
    init(_ v: T) { value = v }
}

struct SettingsView: View {
    @ObservedObject var state: AppState
    @StateObject private var pageBox = Box<SettingsPage?>(.general)
    private var page: Binding<SettingsPage?> { Binding(get: { pageBox.value }, set: { pageBox.value = $0 }) }

    var body: some View {
        NavigationSplitView {
            List(SettingsPage.allCases, selection: page) { p in
                Label(p.rawValue, systemImage: p.symbol).tag(p)
            }
            .navigationSplitViewColumnWidth(min: 160, ideal: 170, max: 200)
        } detail: {
            VStack(spacing: 0) {
                if state.needsRestart { restartBanner }
                Group {
                    switch page.wrappedValue ?? .general {
                    case .general: GeneralPage(state: state)
                    case .airpods: AirPodsPage(state: state)
                    case .buds: BudsPage(state: state)
                    case .handoff: HandoffPage(state: state)
                    case .connection: ConnectionPage(state: state)
                    case .clipboard: ClipboardPage(state: state)
                    case .apps: AppsPage(state: state)
                    case .history: HistoryPage(state: state)
                    case .network: NetworkPage(state: state)
                    case .log: LogPage(state: state)
                    }
                }
                .formStyle(.grouped)
            }
            .navigationTitle(page.wrappedValue?.rawValue ?? "General")
        }
        .frame(minWidth: 680, minHeight: 480)
        .tint(state.cfg.accentColor)
    }

    private var restartBanner: some View {
        HStack {
            Image(systemName: "arrow.clockwise.circle.fill").foregroundStyle(.orange)
            Text("Some changes apply after a restart.")
            Spacer()
            Button("Restart EarHop") { state.onRestart?() }.glassButton(prominent: true)
        }
        .padding(12)
        .glassCard(cornerRadius: 14)
        .padding([.horizontal, .top], 12)
    }
}

private struct GeneralPage: View {
    @ObservedObject var state: AppState
    private func permission(_ ok: Bool) -> some View {
        Label(ok ? "Allowed" : "Not allowed", systemImage: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
            .foregroundStyle(ok ? .green : .orange)
    }
    private func step(_ done: Bool, _ title: String, _ body: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle").foregroundStyle(done ? .green : .secondary)
            VStack(alignment: .leading, spacing: 2) { Text(title).fontWeight(.medium); Text(body).font(.footnote).foregroundStyle(.secondary) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(done ? "done" : "to do")")
    }
    var body: some View {
        Form {
            if !state.cfg.onboarded {
                Section {
                    step(!state.cfg.activeAddress.isEmpty, "Find your \(state.cfg.earbudsName)", "Connect them to this Mac, then detect them.")
                    if state.cfg.activeAddress.isEmpty { Button("Detect \(state.cfg.earbudsName)") { state.onDetect?() } }
                    step(state.ear != .unknown, "Check ear detection", state.ear == .unknown ? "Put the \(state.cfg.earbudsShort) in your ears and keep them close. Allow Bluetooth for EarHop if asked." : "Seen: \(state.ear.label)")
                    step(state.peers.count > 0, "Pair your phone or tablet", "Open Network in this window and scan the QR code with the phone camera.")
                    step(state.owner, "Test a handoff", state.owner ? "The \(state.cfg.earbudsShort) are connected to this Mac." : "Press Take \(state.cfg.earbudsShort), then play something.")
                    if !state.owner { Button("Take \(state.cfg.earbudsShort)") { state.onTake?() } }
                    Button(state.owner && !state.cfg.activeAddress.isEmpty ? "Finish setup" : "Skip for now") { state.cfg.onboarded = true }
                } header: { Text("Set up EarHop") }
            }
            if !state.keysAllowed {
                Section {
                    Label("EarHop cannot pause or resume yet", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    Text("macOS blocks the Play/Pause key until EarHop is allowed under Privacy & Security, Accessibility (called Device Control and Data Access on newer macOS). If EarHop is already switched on and this still shows, remove it with the minus button, then press Ask again.").font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button("Ask again") { AudioMonitor.requestKeyPermission() }
                        Button("Open settings") { AudioMonitor.openKeyPermissionSettings() }
                        Button("Check now") { state.keysAllowed = AudioMonitor.canPostKeys }
                    }
                } header: { Text("Permission needed") }
            }
            Section {
                Picker("Appearance", selection: $state.cfg.appearance) {
                    Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark")
                }.pickerStyle(.segmented)
                HStack {
                    Text("Accent colour")
                    Spacer()
                    Button { state.cfg.accent = "auto" } label: {
                        Circle().fill(LinearGradient(colors: [Config.airpodsAccent, Config.budsAccent], startPoint: .leading, endPoint: .trailing))
                            .frame(width: 20, height: 20)
                            .overlay(Circle().stroke(Color.primary, lineWidth: state.cfg.accent == "auto" ? 2.5 : 0).padding(-3))
                    }
                    .buttonStyle(.plain)
                    .help("Follow the earbuds: blue for AirPods, violet for Galaxy Buds")
                    .accessibilityLabel("Accent follows the earbuds\(state.cfg.accent == "auto" ? ", selected" : "")")
                    ForEach(Config.accents, id: \.id) { a in
                        Button { state.cfg.accent = a.id } label: {
                            Circle().fill(a.color).frame(width: 20, height: 20)
                                .overlay(Circle().stroke(Color.primary, lineWidth: state.cfg.accent == a.id ? 2.5 : 0).padding(-3))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(a.name) accent\(state.cfg.accent == a.id ? ", selected" : "")")
                    }
                }
            } header: { Text("Appearance") }
            Section {
                TextField("Device name", text: $state.cfg.deviceName)
                Toggle("Live mode", isOn: $state.cfg.liveMode)
            } header: { Text("This device") } footer: {
                Text("The name must differ on every device and must not contain a vertical bar. Live mode acts on handoff decisions; off is a dry run that only logs.")
            }
            Section {
                LabeledContent("Accessibility (pause and play keys)") { permission(state.keysAllowed) }
                LabeledContent("Bluetooth") { permission(CBManager.authorization == .allowedAlways) }
                LabeledContent("Local network") {
                    if state.peers.isEmpty { Text("No peer heard yet").foregroundStyle(.secondary) } else { permission(true) }
                }
                Picker("Pause and play with", selection: $state.cfg.mediaControl) {
                    Text("Pause and Play commands (recommended)").tag("auto")
                    Text("Play/Pause key only").tag("key")
                }
                LabeledContent("Currently") { Text(MediaControl.mode).foregroundStyle(.secondary) }
            } header: { Text("Permissions and control") } footer: {
                Text("Pause and Play commands never restart audio that is already paused. If they have no effect on this Mac, EarHop notices and falls back to the Play/Pause key. Local network access is confirmed once another device is heard.")
            }
            Section("Behavior") {
                Toggle("Open EarHop at login", isOn: $state.launchAtLogin)
                Toggle("Notify on handoffs", isOn: $state.cfg.notifications)
                Toggle("Show earbud battery in the menu bar", isOn: $state.cfg.menuBarBattery)
                Toggle("Global hotkey (Control Option Command E) takes or releases the earbuds", isOn: $state.cfg.hotkeyEnabled)
                Toggle("Stay hands off during calls (microphone in use)", isOn: $state.cfg.detectCalls)
            }
            Section("Files") {
                LabeledContent("Config") {
                    Button("Reveal in Finder") { NSWorkspace.shared.activateFileViewerSelecting([Config.file]) }
                }
                LabeledContent("Log") {
                    Button("Open log") { NSWorkspace.shared.open(Log.file) }
                }
            }
        }
    }
}

private struct AirPodsPage: View {
    @ObservedObject var state: AppState
    private var modelsText: Binding<String> {
        Binding(
            get: { state.cfg.models.map { String($0, radix: 16) }.joined(separator: ", ") },
            set: { text in
                let parsed = text.split(whereSeparator: { ", ".contains($0) }).compactMap { Int($0, radix: 16) }
                if !parsed.isEmpty { state.cfg.models = parsed }
            })
    }

    var body: some View {
        Form {
            Section {
                Picker("Active earbuds", selection: $state.cfg.earbuds) {
                    Text("AirPods").tag("airpods"); Text("Galaxy Buds2 Pro").tag("buds")
                }.pickerStyle(.segmented)
            } header: { Text("Earbud profile") } footer: {
                Text(state.cfg.isBuds
                     ? "Galaxy Buds: connect, release and play based switching work. In-ear pause and resume is experimental: it reads Samsung's status channel while the Buds are connected to this Mac. Wear channel: \(state.budsStatus). Until it is open the Buds count as in your ears."
                     : "AirPods: full in-ear detection from the Bluetooth beacon. One pair is active at a time, each keeps its own address.")
            }
            Section {
                HStack {
                    Button("Detect connected \(state.cfg.earbudsName)") { state.onDetect?() }.glassButton(prominent: true)
                    Text(state.detectMessage).font(.callout).foregroundStyle(.secondary)
                }
            } header: { Text("Setup") } footer: {
                Text("Connect your AirPods to this Mac first. EarHop reads their address and model automatically.")
            }
            Section {
                TextField("Address", text: state.cfg.isBuds ? $state.cfg.budsAddress : $state.cfg.airpodsAddress, prompt: Text("aa-bb-cc-dd-ee-ff"))
                    .font(.system(.body, design: .monospaced))
                Menu("Choose from paired devices") {
                    if state.paired.isEmpty { Text("No paired devices found. Pair the earbuds in System Settings, Bluetooth first.") }
                    ForEach(state.paired) { d in
                        Button("\(d.name)  \(d.address)") { if state.cfg.isBuds { state.cfg.budsAddress = d.address } else { state.cfg.airpodsAddress = d.address } }
                    }
                }
                .onAppear { state.refreshPaired() }
            } header: { Text("Earbuds") } footer: {
                Text("EarHop connects and disconnects this address itself.")
            }
            Section {
                TextField("blueutil path", text: $state.cfg.blueutilPath)
                    .font(.system(.body, design: .monospaced))
                LabeledContent("Status") {
                    let ok = FileManager.default.isExecutableFile(atPath: state.cfg.blueutilPath)
                    Label(ok ? "Found" : "Not found", systemImage: ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                        .foregroundStyle(ok ? .green : .red)
                }
            } header: { Text("blueutil (optional)") } footer: { Text("Not needed. When installed (brew install blueutil) it is used as a second try if a connect does not work.") }
            Section {
                TextField("Model ids (hex)", text: modelsText)
                Toggle("Accept any AirPods model", isOn: $state.cfg.allowAnyModel)
                Toggle("Pause when only one bud is out", isOn: $state.cfg.pauseWhenOneOut)
                Toggle("Scan gently while audio is playing (reduces crackling)", isOn: $state.cfg.gentleScan)
                Stepper("Minimum signal: \(state.cfg.minRSSI) dBm", value: $state.cfg.minRSSI, in: -100 ... -30, step: 5)
            } header: { Text("In-ear detection") } footer: {
                Text("Ignores other people's AirPods. Model 2024 is AirPods Pro 2 (USB-C), 2014 is Lightning. Needs a restart.")
            }
        }
    }
}

private struct HandoffPage: View {
    @ObservedObject var state: AppState
    private func move(_ i: Int, _ d: Int) {
        var p = state.cfg.priority
        let j = i + d
        guard p.indices.contains(i), p.indices.contains(j) else { return }
        p.swapAt(i, j)
        state.cfg.priority = p
    }
    private func minutesBinding(_ kp: WritableKeyPath<Config, Int>) -> Binding<Date> {
        Binding(get: {
            let m = state.cfg[keyPath: kp]
            return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
        }, set: {
            let c = Calendar.current
            state.cfg[keyPath: kp] = c.component(.hour, from: $0) * 60 + c.component(.minute, from: $0)
        })
    }
    private func slider(_ title: String, _ value: Binding<Double>, _ range: ClosedRange<Double>, step: Double, unit: String = "s") -> some View {
        VStack(alignment: .leading) {
            HStack { Text(title); Spacer(); Text("\(value.wrappedValue, specifier: "%g") \(unit)").foregroundStyle(.secondary) }
            Slider(value: value, in: range, step: step)
        }
    }
    var body: some View {
        Form {
            Section {
                LabeledContent("State") { Text(state.phase.label) }
                LabeledContent("Earbuds") { Text(state.owner ? "on this Mac" : (state.holder.map { "on \(DeviceName.pretty($0))" } ?? "not connected")) }
                LabeledContent("Ear reading") { Text("\(state.earReading.ear.label), \(state.earReading.source), \(state.earReading.confidence.rawValue) confidence") }
                if let h = state.holdReason { LabeledContent("Not switching because") { Text(h).foregroundStyle(.orange) } }
                if let f = state.lastFailure { LabeledContent("Last failure") { Text(f).foregroundStyle(.orange) } }
                if state.safeMode { Button("Resume automatic switching") { state.onClearSafe?() } }
            } header: { Text("Now") } footer: {
                Text("Only medium or high confidence ear readings pause or resume playback. A reading kept from a closed Buds channel is low confidence.")
            }
            Section {
                slider("Playing before claim", $state.cfg.debounceSeconds, 0.5 ... 10, step: 0.5)
                slider("Ignore sounds shorter than", $state.cfg.minPlaySeconds, 0 ... 10, step: 0.5)
                slider("Cooldown between handoffs", $state.cfg.cooldownSeconds, 1 ... 30, step: 1)
                slider("Wait for owner to go quiet", $state.cfg.quietSeconds, 1 ... 15, step: 1)
            } header: { Text("Claiming") } footer: {
                Text("This Mac takes the earbuds after playing audio for the first duration, once the current owner has been quiet.")
            }
            Section {
                slider("Resume playback within", $state.cfg.resumeWithinSeconds, 5 ... 120, step: 5)
                Stepper("Safe mode after \(state.cfg.safeModeAfterFailures) failed handoffs", value: $state.cfg.safeModeAfterFailures, in: 1 ... 10)
            } header: { Text("Safety") } footer: {
                Text("Playback paused by EarHop resumes only when the buds return in time.")
            }
            Section {
                ForEach(Array(state.cfg.priority.enumerated()), id: \.element) { i, name in
                    HStack {
                        Text("\(i + 1)").foregroundStyle(.secondary).frame(width: 20)
                        Text(name)
                        Spacer()
                        Button { move(i, -1) } label: { Image(systemName: "chevron.up") }.disabled(i == 0).buttonStyle(.borderless)
                        Button { move(i, 1) } label: { Image(systemName: "chevron.down") }.disabled(i == state.cfg.priority.count - 1).buttonStyle(.borderless)
                    }
                }
            } header: { Text("Device priority") } footer: {
                Text("If two devices start playing together, the one higher in this list wins. This list applies on this Mac.")
            }
            Section {
                Toggle("Quiet hours (no automatic claiming)", isOn: $state.cfg.quietEnabled)
                if state.cfg.quietEnabled {
                    DatePicker("From", selection: minutesBinding(\.quietStartMinutes), displayedComponents: .hourAndMinute)
                    DatePicker("Until", selection: minutesBinding(\.quietEndMinutes), displayedComponents: .hourAndMinute)
                }
                Toggle("Only switch automatically on these Wi-Fi networks", isOn: $state.cfg.wifiOnly)
                if state.cfg.wifiOnly {
                    ForEach(state.cfg.wifiNames, id: \.self) { n in
                        HStack { Text(n); Spacer(); Button("Remove") { state.cfg.wifiNames.removeAll { $0 == n } }.buttonStyle(.borderless) }
                    }
                    if let now = WifiInfo.ssid() {
                        Button("Add current network (\(now))") { if !state.cfg.wifiNames.contains(now) { state.cfg.wifiNames.append(now) } }
                    } else {
                        Text("macOS does not reveal the Wi-Fi name to EarHop. Allow Location for EarHop in Privacy settings. Until then every network is allowed.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("When to switch") } footer: {
                Text("Quiet hours and Wi-Fi limits stop automatic claiming only. Take and Release always work. Focus and Do Not Disturb cannot be read on macOS without Full Disk Access, so they are not used here.")
            }
            Section {
                Toggle("Set the volume when the earbuds arrive here", isOn: $state.cfg.volumeEnabled)
                if state.cfg.volumeEnabled {
                    Stepper("Volume \(state.cfg.volumePercent)%", value: $state.cfg.volumePercent, in: 0 ... 100, step: 5)
                }
            } header: { Text("Volume profile") } footer: {
                Text("Each device keeps its own volume, for example quieter on the phone and louder on the Mac.")
            }
            Section {
                slider("Release after out of ears", $state.cfg.releaseAfterOutSeconds, 15 ... 600, step: 15)
            } header: { Text("Releasing") } footer: {
                Text("Disconnects the earbuds from this Mac after they have been out of both ears this long.")
            }
            Section {
                Button("Reset to defaults") {
                    let d = Config(secret: state.cfg.secret)
                    state.cfg.debounceSeconds = d.debounceSeconds
                    state.cfg.cooldownSeconds = d.cooldownSeconds
                    state.cfg.quietSeconds = d.quietSeconds
                    state.cfg.releaseAfterOutSeconds = d.releaseAfterOutSeconds
                }
            }
        }
    }
}

private struct NetworkPage: View {
    @ObservedObject var state: AppState
    @StateObject private var revealBox = Box(false)
    private var reveal: Bool { revealBox.value }
    var body: some View {
        Form {
            Section {
                LabeledContent("Shared secret") {
                    HStack {
                        if reveal {
                            Text(state.cfg.secret).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                        } else {
                            Text(String(repeating: "\u{2022}", count: 16))
                        }
                        Button(reveal ? "Hide" : "Show") { revealBox.value.toggle() }
                    }
                }
                HStack {
                    Button("Copy secret") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(state.cfg.secret, forType: .string)
                    }
                    Button("Generate new secret") {
                        state.cfg.secret = (0 ..< 16).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
                    }
                }
            } header: { Text("Security") } footer: {
                Text("Every device must use the same secret. Messages are signed with HMAC-SHA256. Only play, ear and owner flags cross the network. Needs a restart.")
            }
            Section {
                HStack {
                    Spacer()
                    if let img = QR.image("earhop://pair?secret=\(state.cfg.secret)&port=\(state.cfg.port)", size: 170) {
                        Image(nsImage: img).interpolation(.none).resizable().frame(width: 170, height: 170)
                            .padding(10).background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                    }
                    Spacer()
                }
            } header: { Text("Pair an Android device") } footer: {
                Text("Scan with the phone camera. It opens the EarHop Android app with the secret filled in. Treat this code like a password.")
            }
            Section("Link") {
                LabeledContent("UDP port") {
                    TextField("", value: $state.cfg.port, format: .number.grouping(.never)).frame(width: 90).multilineTextAlignment(.trailing)
                }
            }
            Section {
                LabeledContent("Working now", value: state.transport)
                LabeledContent("Found by Bonjour", value: state.discovered.isEmpty ? "none yet" : state.discovered.joined(separator: ", "))
            } header: { Text("Links") } footer: {
                Text("Choose Bluetooth LE, Wi-Fi or both on the Connection page.")
            }
            Section("Peers") {
                if state.peers.isEmpty {
                    Text("No other devices seen yet").foregroundStyle(.secondary)
                } else {
                    ForEach(state.peers) { p in
                        LabeledContent(p.name) { Text("\(p.call ? "in call, " : "")\(p.playing ? "playing" : "idle"), ear \(p.ear.rawValue), via \(p.via), \(p.age)s ago") }
                    }
                }
            }
        }
    }
}

private struct LogPage: View {
    @ObservedObject var state: AppState
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { Log.copyToPasteboard() } label: { Label("Copy log", systemImage: "doc.on.doc") }.glassButton(prominent: true)
                Button("Open log file") { NSWorkspace.shared.open(Log.file) }.glassButton()
                Spacer()
            }
            .padding([.horizontal, .top], 16)
            logList
        }
    }
    private var logList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(state.logLines.enumerated()), id: \.offset) { i, line in
                        Text(line).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).id(i)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
            }
            .glassCard(cornerRadius: 14)
            .padding(16)
            .onChange(of: state.logLines.count) { _ in proxy.scrollTo(state.logLines.count - 1, anchor: .bottom) }
        }
    }
}

private struct AppsPage: View {
    @ObservedObject var state: AppState
    private var ignoredText: Binding<String> {
        Binding(get: { state.cfg.ignoredApps.joined(separator: "\n") },
                set: { state.cfg.ignoredApps = $0.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } })
    }
    var body: some View {
        Form {
            Section {
                if state.playingApps.isEmpty {
                    Text("Nothing is playing audio right now").foregroundStyle(.secondary)
                }
                ForEach(state.playingApps, id: \.self) { app in
                    LabeledContent(app) {
                        Button(state.cfg.ignoredApps.contains(app) ? "Ignored" : "Ignore") {
                            if !state.cfg.ignoredApps.contains(app) { state.cfg.ignoredApps.append(app) }
                        }.disabled(state.cfg.ignoredApps.contains(app))
                    }
                }
            } header: { Text("Playing now") } footer: {
                Text("Audio from ignored apps never makes this Mac claim the AirPods.")
            }
            Section("Ignored apps (bundle ids, one per line)") {
                TextEditor(text: ignoredText)
                    .font(.system(.body, design: .monospaced))
                    .frame(minHeight: 110)
            }
        }
    }
}

private struct HistoryPage: View {
    @ObservedObject var state: AppState
    private let fmt: DateFormatter = { let f = DateFormatter(); f.timeStyle = .medium; return f }()
    var body: some View {
        Form {
            Section {
                if state.history.isEmpty { Text("No handoffs yet").foregroundStyle(.secondary) }
                ForEach(state.history) { e in
                    HStack(alignment: .top) {
                        Text(fmt.string(from: e.date)).foregroundStyle(.secondary).monospacedDigit()
                        Text(e.dry ? "dry" : e.kind.rawValue).font(.caption).padding(.horizontal, 6).padding(.vertical, 1)
                            .glassCapsule(tint: e.kind == .safe ? .orange : nil)
                        Text(e.text)
                        Spacer()
                    }
                }
            } header: {
                HStack {
                    Text("Why did it switch?")
                    Spacer()
                    Button("Undo last handoff") { state.onUndo?() }
                }
            }
        }
    }
}


private struct BudsPage: View {
    @ObservedObject var state: AppState

    private func word(_ p: String, _ charging: Bool) -> String {
        switch p { case "wearing": return "in ear"; case "idle": return "out"; case "case": return charging ? "charging" : "in case"; case "disconnected": return "off"; default: return "?" }
    }
    private func pct(_ v: Int) -> String { v >= 0 ? "\(v)%" : "-" }

    private func choice(_ title: String, _ names: [(id: String, label: String)], _ current: String, _ pick: @escaping (String) -> Void) -> some View {
        Picker(title, selection: Binding(get: { current }, set: { pick($0) })) {
            ForEach(names, id: \.id) { Text($0.label).tag($0.id) }
            if !names.contains(where: { $0.id == current }) { Text(current == "custom" ? "Custom (Galaxy Wearable)" : "-").tag(current).disabled(true) }
        }
    }

    var body: some View {
        Form {
            if !state.cfg.isBuds {
                Section { Text("Switch the active earbuds to Galaxy Buds2 Pro in the popover or on the AirPods page to use these controls.").foregroundStyle(.secondary) }
            } else if !state.budsConnected || state.budsLink == nil {
                Section {
                    Text("Connect the Buds to this Mac to control them. Wear channel: \(state.budsStatus).")
                    Text("Noise control, equalizer, battery and Find only work on the device that holds the Buds, and only while Galaxy Wearable is not holding their control channel.").font(.footnote).foregroundStyle(.secondary)
                } header: { Text("Galaxy Buds2 Pro") }
            } else {
                let b = state.buds
                let link = state.budsLink!
                Section("Battery") {
                    LabeledContent("Left") { Text("\(pct(b.battL)), \(word(b.placeL, b.chargeL))") }
                    LabeledContent("Right") { Text("\(pct(b.battR)), \(word(b.placeR, b.chargeR))") }
                    LabeledContent("Case") { Text(pct(b.battCase)) }
                }
                Section {
                    choice("Noise control", [("off", "Off"), ("anc", "Noise cancelling"), ("ambient", "Ambient sound")], b.noise) { link.setNoise($0) }
                    choice("Equalizer", [("off", "Off"), ("bass", "Bass boost"), ("soft", "Soft"), ("dynamic", "Dynamic"), ("clear", "Clear"), ("treble", "Treble boost")], b.eq) { link.setEq($0) }
                } header: { Text("Sound") } footer: {
                    Text("Noise cancelling needs both Buds in your ears unless Noise controls with one earbud is on in Galaxy Wearable.")
                }
                Section {
                    if let v = b.spatial { Toggle("360 Audio", isOn: Binding(get: { v }, set: { link.setSpatial($0) })) }
                    if let v = b.touchOn { Toggle("Touch controls", isOn: Binding(get: { v }, set: { link.setTouch($0) })) }
                    if let v = b.seamless { Toggle("Quick connect (Samsung auto switch)", isOn: Binding(get: { v }, set: { link.setSeamless($0) })) }
                } header: { Text("Options") } footer: {
                    Text("If Samsung's own switching fights EarHop, turn Quick connect off.")
                }
                Section {
                    HStack {
                        if state.budsFindActive { Button("Stop ringing") { link.findStop() }.glassButton(prominent: true) }
                        else { Button("Ring the Buds") { link.findStart() }.glassButton() }
                        Text(state.budsFindMessage).font(.callout).foregroundStyle(.secondary)
                    }
                } header: { Text("Find my Buds") } footer: {
                    Text("Rings quietly, then loud. Refused while a bud may be in your ear, and it stops itself after a minute.")
                }
            }
        }
    }
}

private struct ConnectionPage: View {
    @ObservedObject var state: AppState
    private func pauseSwitch(_ minutes: Int) {
        let until = Date().addingTimeInterval(Double(minutes) * 60)
        state.switchPausedUntil = until
        state.onSwitchPause?(until)
    }
    private var mode: LinkMode { LinkMode(rawValue: state.cfg.transport) ?? .both }
    var body: some View {
        Form {
            Section {
                ConnectionControl(state: state, showExplanation: true)
                    .padding(.vertical, 4)
            } header: { Text("How this Mac talks to your phone and tablet about the earbuds") } footer: {
                Text("Each device has its own setting, so set the same mode on the phone and tablet (EarHop, Automatic switching, Talk to other devices over). Both is safest: a device using only one link still hears this Mac on that link.")
            }
            Section {
                Toggle("Automatic switching", isOn: $state.cfg.autoSwitch)
                Toggle("Calls take the earbuds", isOn: $state.cfg.detectCalls)
                Toggle("Pause when an earbud comes out", isOn: $state.cfg.pauseWhenOneOut)
                LabeledContent("Pause") {
                    HStack {
                        if state.switchPaused {
                            Text("until \(state.switchPausedUntil.formatted(date: .omitted, time: .shortened))").foregroundStyle(.orange)
                            Button("Resume") { state.switchPausedUntil = .distantPast; state.onSwitchPause?(.distantPast) }
                        } else {
                            Button("15 minutes") { pauseSwitch(15) }
                            Button("1 hour") { pauseSwitch(60) }
                        }
                    }
                }
                .disabled(!state.cfg.autoSwitch)
            } header: { Text("Automatic switching") } footer: {
                Text("Off or paused, the earbuds stay where they are until you press Take or Release. A pause ends by itself; a restart of EarHop also ends it.")
            }
            Section {
                LabeledContent("Working now", value: state.transport)
                ForEach(state.peers) { p in
                    LabeledContent(DeviceName.pretty(p.name)) {
                        Text("last heard \(p.age) s ago over \(p.via == "ble" ? "Bluetooth LE" : "Wi-Fi")")
                            .foregroundStyle(p.age > 5 ? .orange : .secondary)
                    }
                }
                if state.peers.isEmpty { Text("No other device heard yet").foregroundStyle(.secondary) }
            } header: { Text("Status") }
            Section {
                Label("Bluetooth LE needs the device names mac, phone and tab.", systemImage: "info.circle")
                Label("Bluetooth LE reaches about one room; Wi-Fi works across the network.", systemImage: "info.circle")
                if mode != .wifi && state.cfg.deviceName != "mac" {
                    Label("This Mac is named \(state.cfg.deviceName), so it cannot use Bluetooth LE. Rename it to mac on the General page.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } header: { Text("Good to know") }
        }
    }
}
