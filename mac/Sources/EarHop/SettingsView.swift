import SwiftUI
import CoreBluetooth

/// The Settings pages. Every setting lives on exactly one of them.
enum SettingsPage: String, CaseIterable, Identifiable {
    case general = "General", devices = "Devices", earbuds = "Earbuds", switching = "Switching", clipboard = "Clipboard"
    case pairing = "Pairing", connection = "Connection", history = "History", log = "Log"
    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .devices: return "macbook.and.iphone"
        case .earbuds: return "earbuds"
        case .switching: return "arrow.left.arrow.right"
        case .clipboard: return "doc.on.clipboard"
        case .pairing: return "qrcode"
        case .connection: return "antenna.radiowaves.left.and.right"
        case .history: return "clock.arrow.circlepath"
        case .log: return "doc.text"
        }
    }
    /// The tile colour behind the sidebar icon: Apple's system palette, slightly deepened, as in System Settings.
    var tile: Color {
        switch self {
        case .general: return Color(red: 0.53, green: 0.54, blue: 0.57)
        case .devices: return Color(red: 0.04, green: 0.46, blue: 0.96)
        case .earbuds: return Color(red: 0.55, green: 0.33, blue: 0.86)
        case .switching: return Color(red: 0.96, green: 0.55, blue: 0.05)
        case .clipboard: return Color(red: 0.13, green: 0.62, blue: 0.72)
        case .pairing: return Color(red: 0.19, green: 0.69, blue: 0.34)
        case .connection: return Color(red: 0.36, green: 0.36, blue: 0.85)
        case .history: return Color(red: 0.93, green: 0.27, blue: 0.36)
        case .log: return Color(red: 0.42, green: 0.43, blue: 0.46)
        }
    }
    /// The groups in the sidebar, separated by a little space.
    static let groups: [[SettingsPage]] = [[.general], [.devices, .earbuds, .switching, .clipboard], [.pairing, .connection], [.history, .log]]
}

/// Tiny observable box, used instead of @State (the SwiftUI macro plugin needs full Xcode).
final class Box<T>: ObservableObject {
    @Published var value: T
    init(_ v: T) { value = v }
}

/// A white symbol on a rounded colour tile, the System Settings sidebar look: a soft top-to-bottom gradient, a hairline
/// inner highlight, and every symbol fitted to the same box so wide and tall glyphs look the same size.
struct SettingsIcon: View {
    let symbol: String
    let color: Color
    var size: CGFloat = 20
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.235, style: .continuous)
        ZStack {
            shape.fill(LinearGradient(colors: [color.opacity(0.82), color], startPoint: .top, endPoint: .bottom))
            shape.fill(Color.black.opacity(0.04))
            shape.strokeBorder(LinearGradient(colors: [Color.white.opacity(0.28), Color.white.opacity(0.04)], startPoint: .top, endPoint: .bottom), lineWidth: 0.5)
            Image(systemName: symbol)
                .resizable()
                .scaledToFit()
                .fontWeight(.medium)
                .foregroundStyle(.white)
                .frame(width: size * 0.62, height: size * 0.62)
                .shadow(color: .black.opacity(0.18), radius: 0.5, y: 0.5)
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.12), radius: 0.5, y: 0.5)
        .accessibilityHidden(true)
    }
}

struct SettingsView: View {
    @ObservedObject var state: AppState
    @StateObject private var pageBox = Box<SettingsPage?>(.general)
    private var page: Binding<SettingsPage?> { Binding(get: { pageBox.value }, set: { pageBox.value = $0; state.onSettingsPage?($0 ?? .general) }) }

    var body: some View {
        NavigationSplitView {
            List(selection: page) {
                ForEach(Array(SettingsPage.groups.enumerated()), id: \.offset) { _, group in
                    Section {
                        ForEach(group) { p in
                            Label { Text(p.rawValue) } icon: { SettingsIcon(symbol: p.symbol, color: p.tile) }
                                .tag(p)
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 190, ideal: 200, max: 240)
        } detail: {
            VStack(spacing: 0) {
                if state.needsRestart { restartBanner }
                Group {
                    switch page.wrappedValue ?? .general {
                    case .general: GeneralPage(state: state)
                    case .devices: DevicesPage(state: state)
                    case .earbuds: EarbudsPage(state: state)
                    case .switching: SwitchingPage(state: state)
                    case .clipboard: ClipboardPage(state: state)
                    case .pairing: PairingPage(state: state)
                    case .connection: ConnectionPage(state: state)
                    case .history: HistoryPage(state: state)
                    case .log: LogPage(state: state)
                    }
                }
                .formStyle(.grouped)
            }
        }
        .frame(minWidth: 760, minHeight: 540)
        .tint(state.cfg.accentColor)
        .onAppear { if let p = state.settingsPageRequest { pageBox.value = p; state.settingsPageRequest = nil } }
        .onReceive(state.$settingsPageRequest) { p in if let p { pageBox.value = p; state.settingsPageRequest = nil } }
    }

    private var restartBanner: some View {
        HStack {
            Image(systemName: "arrow.clockwise.circle.fill").foregroundStyle(.orange)
            Text("Some changes apply after a restart.")
            Spacer()
            Button("Restart EarHop") { state.onRestart?() }.buttonStyle(.borderedProminent)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.orange.opacity(0.08)))
        .padding([.horizontal, .top], 16)
    }
}

/// A row with a coloured icon tile, a title and a grey line under it, as in System Settings lists.
struct IconRow<Trailing: View>: View {
    let symbol: String
    let color: Color
    let title: String
    let subtitle: String
    var outline: Color = .clear
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(color)
                .frame(width: 32, height: 32)
                .background(Circle().fill(color.opacity(0.12)))
                // State on the outline only: green when the device was heard just now, none otherwise.
                .overlay(Circle().strokeBorder(outline, lineWidth: 2))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fontWeight(.medium)
                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

// MARK: General

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
                    step(state.peers.count > 0, "Pair your other devices", "On the Pairing page, scan the QR code with each phone or tablet.")
                    step(state.owner, "Test a handoff", state.owner ? "The \(state.cfg.earbudsShort) are connected to this Mac." : "Press Take, then play something.")
                    if !state.owner { Button("Take \(state.cfg.earbudsShort)") { state.onTake?() } }
                    Button(state.owner && !state.cfg.activeAddress.isEmpty ? "Finish setup" : "Skip for now") { state.cfg.onboarded = true }
                } header: { Text("Set up EarHop") }
            }
            if !state.keysAllowed {
                Section {
                    Label("EarHop cannot pause or resume yet", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    Text("Allow EarHop under Privacy & Security, Accessibility. If it is already on and this still shows, remove it with the minus button, then press Ask again.")
                        .font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button("Ask again") { AudioMonitor.requestKeyPermission() }
                        Button("Open Settings") { AudioMonitor.openKeyPermissionSettings() }
                        Button("Check now") { state.keysAllowed = AudioMonitor.canPostKeys }
                    }
                } header: { Text("Permission needed") }
            }
            Section("Startup and menu bar") {
                Toggle("Open EarHop at login", isOn: $state.launchAtLogin)
                Toggle("Show earbud battery in the menu bar", isOn: $state.cfg.menuBarBattery)
                Toggle("Control Option Command E takes or releases the earbuds", isOn: $state.cfg.hotkeyEnabled)
            }
            Section {
                Toggle("Notify when the earbuds switch", isOn: $state.cfg.notifications)
                Toggle("Low battery alerts at 20 % and 10 %", isOn: $state.cfg.lowBatteryAlerts)
                Picker("Sleep timer", selection: $state.cfg.sleepMinutes) {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { Text("\($0) minutes").tag($0) }
                }
            } header: { Text("Notifications and timers") } footer: { FootText("The sleep timer (the moon in the popover) pauses and lets go of the earbuds after this long.")
            }
            Section("Appearance") {
                Picker("Appearance", selection: $state.cfg.appearance) {
                    Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark")
                }.pickerStyle(.segmented)
                LabeledContent("Accent colour") {
                    HStack(spacing: 10) {
                        swatch(id: "auto", fill: AnyShapeStyle(LinearGradient(colors: [Config.airpodsAccent, Config.budsAccent], startPoint: .leading, endPoint: .trailing)), name: "Follow the earbuds")
                        ForEach(Config.accents, id: \.id) { a in swatch(id: a.id, fill: AnyShapeStyle(a.color), name: a.name) }
                    }
                }
            }
            Section {
                LabeledContent("Accessibility") { permission(state.keysAllowed) }
                LabeledContent("Bluetooth") { permission(CBManager.authorization == .allowedAlways) }
                LabeledContent("Local network") {
                    if state.peers.isEmpty { Text("Confirmed when a device is heard").foregroundStyle(.secondary) } else { permission(true) }
                }
                Picker("Pause and play with", selection: $state.cfg.mediaControl) {
                    Text("Pause and Play commands").tag("auto")
                    Text("Play/Pause key only").tag("key")
                }
            } header: { Text("Permissions") } footer: { FootText("Pause and Play commands never restart audio that is already paused. Now using: \(MediaControl.mode).")
            }
            Section("Files") {
                LabeledContent("Settings file") { Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([Config.file]) } }
                LabeledContent("Log") { Button("Open") { NSWorkspace.shared.open(Log.file) } }
            }
        }
    }

    private func swatch(id: String, fill: AnyShapeStyle, name: String) -> some View {
        Button { state.cfg.accent = id } label: {
            Circle().fill(fill).frame(width: 18, height: 18)
                .overlay(Circle().stroke(Color.primary, lineWidth: state.cfg.accent == id ? 2 : 0).padding(-3))
        }
        .buttonStyle(.plain)
        .help(name)
        .accessibilityLabel("\(name)\(state.cfg.accent == id ? ", selected" : "")")
    }
}

// MARK: Devices

/// Every device EarHop knows, in priority order, with its icon, kind, link and clipboard choice. New devices appear by
/// themselves the first time they are heard.
private struct DevicesPage: View {
    @ObservedObject var state: AppState
    @StateObject private var detail = Box<String?>(nil)

    /// Every other device: the priority list first (in its order), then the others by name.
    static func names(_ state: AppState) -> [String] {
        let me = state.cfg.deviceName
        var all: [String] = []
        for n in state.cfg.priority + NameBook.shared.all + state.peers.map(\.name) + state.cfg.knownDevices where n != me && !all.contains(n) {
            all.append(n)
        }
        let ranked = state.cfg.priority.filter { all.contains($0) }
        return ranked + all.filter { !ranked.contains($0) }.sorted()
    }

    private func move(_ name: String, _ d: Int) {
        var order = DevicesPage.names(state)
        // The priority list holds every device, this Mac in its old place, so the order survives restarts.
        guard let i = order.firstIndex(of: name), order.indices.contains(i + d) else { return }
        order.swapAt(i, i + d)
        let mePos = state.cfg.priority.firstIndex(of: state.cfg.deviceName) ?? 0
        order.insert(state.cfg.deviceName, at: min(mePos, order.count))
        state.cfg.priority = order
    }

    var body: some View {
        let names = DevicesPage.names(state)
        Form {
            Section {
                IconRow(symbol: DeviceKind.mac.symbol, color: .accentColor, title: DeviceName.pretty(state.cfg.deviceName),
                        subtitle: "This Mac" + (state.owner ? ", has the \(state.cfg.earbudsShort)" : "")) {
                    Button("Rename…") { state.settingsPageRequest = .pairing }.buttonStyle(.borderless)
                }
            } header: { Text("This Mac") }
            Section {
                ForEach(Array(names.enumerated()), id: \.element) { i, name in
                    row(name, rank: i + 1, count: names.count)
                }
            } header: { Text("Your devices") } footer: { FootText("Devices appear here by themselves once they are paired and heard. When two devices start playing within 2.5 s of each other, the one higher in this list gets the earbuds. Click Details to change a device's type or stop sending it clips.")
            }
        }
        .sheet(isPresented: Binding(get: { detail.value != nil }, set: { if !$0 { detail.value = nil } })) {
            if let n = detail.value { DeviceDetail(state: state, name: n) { detail.value = nil } }
        }
    }

    private func row(_ name: String, rank: Int, count: Int) -> some View {
        let peer = state.peers.first { $0.name == name }
        let live = (peer?.age ?? 99) < 15
        let heard = state.status.devices.first { $0.name == name }?.lastHeard
        var parts = [DeviceName.kind(name).title]
        if let p = peer, live {
            parts.append(p.call ? "in a call" : p.playing ? "playing" : "idle")
            if p.owner { parts.append("has the \(state.cfg.earbudsShort)") }
            parts.append(p.via == "ble" ? "Bluetooth LE" : "Wi-Fi")
        } else {
            parts.append(heard.map { "last heard \(Self.ago($0))" } ?? "not heard yet")
        }
        if state.cfg.clipExclude.contains(name) { parts.append("no clips") }
        return IconRow(symbol: DeviceName.symbol(name), color: live ? .accentColor : .secondary, title: DeviceName.pretty(name),
                       subtitle: parts.joined(separator: " · "), outline: live ? .green : .clear) {
            HStack(spacing: 2) {
                Text("\(rank)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 16)
                Button { move(name, -1) } label: { Image(systemName: "chevron.up") }.disabled(rank == 1).help("Higher priority")
                Button { move(name, 1) } label: { Image(systemName: "chevron.down") }.disabled(rank == count).help("Lower priority")
                Button("Details…") { detail.value = name }.padding(.leading, 6)
            }
            .buttonStyle(.borderless)
        }
    }

    static func ago(_ d: Date) -> String {
        let s = Int(Date().timeIntervalSince(d))
        return s < 60 ? "\(s) s ago" : s < 3600 ? "\(s / 60) min ago" : d.formatted(date: .abbreviated, time: .shortened)
    }
}

/// One device in detail: its type, clipboard choice, links and clock, and Forget.
private struct DeviceDetail: View {
    @ObservedObject var state: AppState
    let name: String
    let done: () -> Void

    private var kindChoice: Binding<String> {
        Binding(get: { state.cfg.deviceKindOverrides[name] ?? "auto" }, set: { v in
            if v == "auto" { state.cfg.deviceKindOverrides[name] = nil } else { state.cfg.deviceKindOverrides[name] = v }
        })
    }

    var body: some View {
        let st = state.status.devices.first { $0.name == name }
        let announced = DeviceName.announcedKind(name)
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                Image(systemName: DeviceName.symbol(name)).font(.system(size: 40, weight: .light)).foregroundStyle(Color.accentColor)
                Text(DeviceName.pretty(name)).font(.title2.weight(.semibold))
                Text(DeviceName.kind(name).title).foregroundStyle(.secondary)
            }
            .padding(.top, 24).padding(.bottom, 8)
            Form {
                Section {
                    Picker("Type", selection: kindChoice) {
                        Text("Automatic (\((announced ?? DeviceKind.guess(name)).title))").tag("auto")
                        Divider()
                        ForEach(DeviceKind.allCases) { k in Label(k.title, systemImage: k.symbol).tag(k.rawValue) }
                    }
                    Toggle("Send clipboard and files to it", isOn: Binding(
                        get: { !state.cfg.clipExclude.contains(name) },
                        set: { on in state.cfg.clipExclude.removeAll { $0 == name }; if !on { state.cfg.clipExclude.append(name) } }))
                } footer: { FootText(announced == nil ? "This device has not said what it is (older EarHop), so the type is a guess from its name."
                                          : "The device said it is a \(announced!.title).")
                }
                Section("Connection") {
                    LabeledContent("Last heard", value: st?.lastHeard.map { DevicesPage.ago($0) } ?? "never")
                    LabeledContent("Bluetooth LE", value: st?.bleAt.map { DevicesPage.ago($0) } ?? "never")
                    LabeledContent("Wi-Fi address", value: st?.wifiHosts.joined(separator: ", ").nonEmpty ?? "none")
                    if let o = st?.clockOffsetMs {
                        LabeledContent("Clock", value: abs(o) < 2000 ? "in step" : "\(o > 0 ? "ahead" : "behind") by \(abs(o) / 1000) s")
                    }
                }
            }
            .formStyle(.grouped)
            HStack {
                if !["mac", "phone", "tab"].contains(name) {
                    Button("Forget This Device", role: .destructive) {
                        NameBook.shared.remove(name)
                        state.cfg.knownDevices.removeAll { $0 == name }
                        state.cfg.priority.removeAll { $0 == name }
                        state.cfg.deviceKinds[name] = nil
                        state.cfg.deviceKindOverrides[name] = nil
                        done()
                    }
                }
                Spacer()
                Button("Done", action: done).keyboardShortcut(.defaultAction)
            }
            .padding(16)
        }
        .frame(width: 440, height: 520)
    }
}

extension String { var nonEmpty: String? { isEmpty ? nil : self } }

/// A section footer aligned to the leading edge, like the notes under groups in System Settings.
struct FootText: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: Earbuds

/// One page for the earbuds: which pair, where they are, finding them, and that pair's own controls.
private struct EarbudsPage: View {
    @ObservedObject var state: AppState

    private var choice: Binding<String> {
        Binding(get: { state.cfg.earbudsMode == "auto" ? "auto" : state.cfg.earbuds }, set: { v in
            var c = state.cfg
            if v == "auto" { c.earbudsMode = "auto" } else { c.earbudsMode = "manual"; c.earbuds = v }
            state.cfg = c
        })
    }
    private var modelsText: Binding<String> {
        Binding(get: { state.cfg.models.map { String($0, radix: 16) }.joined(separator: ", ") }, set: { text in
            let parsed = text.split(whereSeparator: { ", ".contains($0) }).compactMap { Int($0, radix: 16) }
            if !parsed.isEmpty { state.cfg.models = parsed }
        })
    }
    private var whereText: String {
        if state.owner { return "Connected to this Mac" }
        if let h = state.holder { return "Connected to \(DeviceName.pretty(h))" }
        return "Not connected"
    }
    private func pct(_ v: Int) -> String { v >= 0 ? "\(v) %" : "unknown" }
    private func word(_ p: String, _ charging: Bool) -> String {
        switch p { case "wearing": return "in ear"; case "idle": return "out"; case "case": return charging ? "charging in the case" : "in the case"; case "disconnected": return "off"; default: return "unknown" }
    }

    var body: some View {
        Form {
            Section {
                Picker("Earbuds", selection: choice) {
                    Text("Automatic").tag("auto"); Text("AirPods").tag("airpods"); Text("Galaxy Buds2 Pro").tag("buds")
                }
                .pickerStyle(.segmented)
            } footer: { FootText("Automatic follows the pair connected to this Mac, or the pair the device holding the earbuds reports. One pair is active at a time; each keeps its own address.")
            }
            Section {
                IconRow(symbol: state.cfg.isBuds ? "earbuds" : "airpodspro", color: state.cfg.accentColor, title: state.cfg.earbudsName,
                        subtitle: whereText, outline: state.owner ? .green : .clear) { EmptyView() }
                LabeledContent("Address") {
                    HStack {
                        TextField("Address", text: state.cfg.isBuds ? $state.cfg.budsAddress : $state.cfg.airpodsAddress, prompt: Text("aa-bb-cc-dd-ee-ff"))
                            .labelsHidden().font(.system(.body, design: .monospaced)).multilineTextAlignment(.trailing)
                        Menu("Choose") {
                            if state.paired.isEmpty { Text("No paired devices. Pair the earbuds in System Settings, Bluetooth first.") }
                            ForEach(state.paired) { d in
                                Button(d.name) { if state.cfg.isBuds { state.cfg.budsAddress = d.address } else { state.cfg.airpodsAddress = d.address } }
                            }
                        }
                        .fixedSize()
                        .onAppear { state.refreshPaired() }
                    }
                }
                HStack {
                    Button("Detect Connected \(state.cfg.earbudsShort)") { state.onDetect?() }
                    Text(state.detectMessage).font(.callout).foregroundStyle(.secondary)
                }
            } header: { Text("This pair") } footer: { FootText("Connect the earbuds to this Mac once and press Detect. EarHop then connects and disconnects them itself.")
            }
            if state.cfg.isBuds { budsSections } else { airpodsSections }
            Section {
                LabeledContent("blueutil") {
                    TextField("blueutil", text: $state.cfg.blueutilPath).labelsHidden().font(.system(.body, design: .monospaced)).multilineTextAlignment(.trailing)
                }
                LabeledContent("Status") {
                    let ok = FileManager.default.isExecutableFile(atPath: state.cfg.blueutilPath)
                    Text(ok ? "Found" : "Not installed").foregroundStyle(.secondary)
                }
            } header: { Text("Advanced") } footer: { FootText("blueutil is optional. When installed (brew install blueutil) it is a second try if a connect does not work.")
            }
        }
    }

    @ViewBuilder private var budsSections: some View {
        if !state.budsConnected || state.budsLink == nil {
            Section {
                Text("Battery, noise control, equalizer and Find work while the Buds are connected to this Mac, and only while Galaxy Wearable is not holding their control channel. Control channel: \(state.budsStatus).")
                    .foregroundStyle(.secondary)
            } header: { Text("Controls") }
        } else {
            let b = state.buds
            let link = state.budsLink!
            Section("Battery") {
                LabeledContent("Left", value: "\(pct(b.battL)), \(word(b.placeL, b.chargeL))")
                LabeledContent("Right", value: "\(pct(b.battR)), \(word(b.placeR, b.chargeR))")
                LabeledContent("Case", value: pct(b.battCase))
            }
            Section {
                Picker("Noise control", selection: Binding(get: { b.noise }, set: { link.setNoise($0) })) {
                    Text("Off").tag("off"); Text("Noise cancelling").tag("anc"); Text("Ambient sound").tag("ambient")
                }
                Picker("Equalizer", selection: Binding(get: { b.eq }, set: { link.setEq($0) })) {
                    ForEach(EqButtons.items, id: \.0) { Text($0.1).tag($0.0) }
                    if b.eq == "custom" { Text("Custom (Galaxy Wearable)").tag("custom") }
                }
                Toggle("Keep this Mac's own noise control and equalizer", isOn: $state.cfg.ownBudsSettings)
            } header: { Text("Sound") } footer: { FootText("With this on, what you choose here is applied whenever the Buds connect to this Mac; each device keeps its own.")
            }
            Section {
                if let v = b.touchOn { Toggle("Touch controls", isOn: Binding(get: { v }, set: { link.setTouch($0) })) }
                if let v = b.seamless { Toggle("Quick connect (Samsung auto switch)", isOn: Binding(get: { v }, set: { link.setSeamless($0) })) }
            } header: { Text("Options") } footer: { FootText("If Samsung's own switching fights EarHop, turn Quick connect off.")
            }
            Section {
                HStack {
                    if state.budsFindActive { Button("Stop Ringing") { link.findStop() }.buttonStyle(.borderedProminent) }
                    else { Button("Ring the Buds") { link.findStart() } }
                    Text(state.budsFindMessage).font(.callout).foregroundStyle(.secondary)
                }
            } header: { Text("Find my Buds") } footer: { FootText("Rings quietly, then loud. Refused while a bud may be in your ear; stops by itself after a minute.")
            }
        }
    }

    @ViewBuilder private var airpodsSections: some View {
        Section("Battery") {
            LabeledContent("Left", value: pct(state.battery?.0 ?? -1))
            LabeledContent("Right", value: pct(state.battery?.1 ?? -1))
            LabeledContent("Case", value: pct(state.caseBattery ?? -1))
        }
        Section {
            LabeledContent("Now", value: "\(state.earReading.ear.label), \(state.earReading.confidence.rawValue) confidence")
            Stepper("Ignore AirPods weaker than \(state.cfg.minRSSI) dBm", value: $state.cfg.minRSSI, in: -100 ... -30, step: 5)
            Toggle("Scan gently while audio plays (less crackling)", isOn: $state.cfg.gentleScan)
            Toggle("Accept any AirPods model", isOn: $state.cfg.allowAnyModel)
            if !state.cfg.allowAnyModel { TextField("Model ids (hex)", text: modelsText) }
        } header: { Text("In-ear detection") } footer: { FootText("Read from the AirPods' Bluetooth signal. The signal limit ignores other people's AirPods nearby. 2024 is AirPods Pro 2 (USB-C), 2014 the Lightning version. Changes here need a restart.")
        }
    }
}

// MARK: Switching

private struct SwitchingPage: View {
    @ObservedObject var state: AppState
    private func minutesBinding(_ kp: WritableKeyPath<Config, Int>) -> Binding<Date> {
        Binding(get: {
            let m = state.cfg[keyPath: kp]
            return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
        }, set: {
            let c = Calendar.current
            state.cfg[keyPath: kp] = c.component(.hour, from: $0) * 60 + c.component(.minute, from: $0)
        })
    }
    private func slider(_ title: String, _ value: Binding<Double>, _ range: ClosedRange<Double>, step: Double) -> some View {
        LabeledContent(title) {
            HStack {
                Slider(value: value, in: range, step: step).frame(width: 180)
                Text(value.wrappedValue < 60 ? "\(value.wrappedValue, specifier: "%g") s" : "\(Int(value.wrappedValue / 60)) min \(Int(value.wrappedValue) % 60) s")
                    .monospacedDigit().foregroundStyle(.secondary).frame(width: 70, alignment: .trailing)
            }
        }
    }
    private func pause(_ minutes: Int) {
        let until = Date().addingTimeInterval(Double(minutes) * 60)
        state.switchPausedUntil = until
        state.onSwitchPause?(until)
    }
    private var ignoredText: Binding<String> {
        Binding(get: { state.cfg.ignoredApps.joined(separator: "\n") },
                set: { state.cfg.ignoredApps = $0.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } })
    }

    var body: some View {
        Form {
            Section {
                Toggle("Switch the earbuds automatically", isOn: $state.cfg.autoSwitch)
                LabeledContent("Pause switching") {
                    HStack {
                        if state.switchPaused {
                            Text("until \(state.switchPausedUntil.formatted(date: .omitted, time: .shortened))").foregroundStyle(.secondary)
                            Button("Resume") { state.switchPausedUntil = .distantPast; state.onSwitchPause?(.distantPast) }
                        } else {
                            Button("15 Minutes") { pause(15) }
                            Button("1 Hour") { pause(60) }
                        }
                    }
                }
                .disabled(!state.cfg.autoSwitch)
                LabeledContent("Now", value: state.phase.label)
                if let h = state.holdReason { LabeledContent("Not switching because") { Text(h).foregroundStyle(.secondary) } }
                if let f = state.lastFailure { LabeledContent("Last failure") { Text(f).foregroundStyle(.orange) } }
                if state.safeMode { Button("Resume Automatic Switching") { state.onClearSafe?() } }
            } header: { Text("Automatic switching") } footer: { FootText("The earbuds follow the device where audio starts. Off or paused, they stay where they are until you press Take or Release.")
            }
            Section {
                Toggle("Pause when an earbud comes out", isOn: $state.cfg.pauseWhenOneOut)
                slider("Resume if back within", $state.cfg.resumeWithinSeconds, 5 ... 120, step: 5)
                Toggle(isOn: $state.cfg.speakerFallback) {
                    Text("Use the speakers while the earbuds are out")
                    Text("Audio you start with the earbuds out plays on this Mac's speakers and moves back when you put one in.")
                }
                slider("Let go after out of ears for", $state.cfg.releaseAfterOutSeconds, 15 ... 600, step: 15)
            } header: { Text("Earbuds out of your ears") }
            Section {
                Toggle(isOn: $state.cfg.detectCalls) {
                    Text("Calls and meetings come first")
                    Text("A call on any device takes the earbuds. Zoom, Teams and WhatsApp count at once; other calls when the microphone and speaker are busy for 2 s.")
                }
                Toggle(isOn: $state.cfg.followAttention) {
                    Text("Follow my attention")
                    Text("When you used this Mac in the last 30 s and audio starts here, the earbuds come here even if another device still plays.")
                }
            } header: { Text("Who gets the earbuds") }
            Section {
                Toggle("Keep the volume of the device they come from", isOn: $state.cfg.volumeHandover)
                Toggle("Set a fixed volume instead", isOn: $state.cfg.volumeEnabled)
                if state.cfg.volumeEnabled {
                    Stepper("Volume \(state.cfg.volumePercent) %", value: $state.cfg.volumePercent, in: 0 ... 100, step: 5)
                }
            } header: { Text("When the earbuds arrive here") }
            Section {
                Toggle("Quiet hours", isOn: $state.cfg.quietEnabled)
                if state.cfg.quietEnabled {
                    DatePicker("From", selection: minutesBinding(\.quietStartMinutes), displayedComponents: .hourAndMinute)
                    DatePicker("Until", selection: minutesBinding(\.quietEndMinutes), displayedComponents: .hourAndMinute)
                }
                Toggle("Only on these Wi-Fi networks", isOn: $state.cfg.wifiOnly)
                if state.cfg.wifiOnly {
                    ForEach(state.cfg.wifiNames, id: \.self) { n in
                        LabeledContent(n) { Button("Remove") { state.cfg.wifiNames.removeAll { $0 == n } }.buttonStyle(.borderless) }
                    }
                    if let now = WifiInfo.ssid() {
                        Button("Add \(now)") { if !state.cfg.wifiNames.contains(now) { state.cfg.wifiNames.append(now) } }
                    } else {
                        Text("macOS does not tell EarHop the Wi-Fi name until Location is allowed for it. Until then every network counts.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("When to switch") } footer: { FootText("These hold automatic switching only. Take and Release always work.")
            }
            Section {
                ForEach(state.playingApps, id: \.self) { app in
                    LabeledContent(app) {
                        Button(state.cfg.ignoredApps.contains(app) ? "Ignored" : "Ignore") {
                            if !state.cfg.ignoredApps.contains(app) { state.cfg.ignoredApps.append(app) }
                        }
                        .disabled(state.cfg.ignoredApps.contains(app))
                    }
                }
                if state.playingApps.isEmpty { Text("Nothing plays audio right now.").foregroundStyle(.secondary) }
                TextEditor(text: ignoredText).font(.system(.body, design: .monospaced)).frame(minHeight: 60)
            } header: { Text("Apps that never switch") } footer: { FootText("Audio from these apps (bundle ids, one per line) never brings the earbuds to this Mac.")
            }
            Section {
                slider("Play this long before switching", $state.cfg.minPlaySeconds, 0 ... 10, step: 0.5)
                slider("Wait between switches", $state.cfg.cooldownSeconds, 1 ... 30, step: 1)
                slider("The other device must be quiet for", $state.cfg.quietSeconds, 1 ... 15, step: 1)
                Stepper("Safe mode after \(state.cfg.safeModeAfterFailures) failed switches", value: $state.cfg.safeModeAfterFailures, in: 1 ... 10)
                Button("Restore Defaults") {
                    let d = Config(secret: state.cfg.secret)
                    state.cfg.minPlaySeconds = d.minPlaySeconds
                    state.cfg.cooldownSeconds = d.cooldownSeconds
                    state.cfg.quietSeconds = d.quietSeconds
                    state.cfg.releaseAfterOutSeconds = d.releaseAfterOutSeconds
                    state.cfg.resumeWithinSeconds = d.resumeWithinSeconds
                    state.cfg.safeModeAfterFailures = d.safeModeAfterFailures
                }
            } header: { Text("Timing") } footer: { FootText("Shorter times switch faster but can be fooled by short sounds. The defaults favour reliability.")
            }
            Section {
                Toggle("Act on switching decisions", isOn: $state.cfg.liveMode)
            } header: { Text("Testing") } footer: { FootText("Off is a dry run: EarHop only writes in the log what it would do. Leave it on for normal use.")
            }
        }
    }
}

// MARK: Pairing

private struct PairingPage: View {
    @ObservedObject var state: AppState
    @StateObject private var reveal = Box(false)
    @StateObject private var scanning = Box(false)
    @StateObject private var confirmNew = Box(false)
    @StateObject private var message = Box("")

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $state.cfg.deviceName)
                if !DeviceNames.valid(state.cfg.deviceName) {
                    Label("Use 1 to 20 lower case letters, digits or -, starting with a letter or digit.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } header: { Text("This Mac") } footer: { FootText("Every device needs its own name, for example mac or work-mac. A new name applies after a restart.")
            }
            Section {
                LabeledContent("Secret") {
                    HStack {
                        Text(reveal.value ? state.cfg.secret : String(repeating: "\u{2022}", count: 16))
                            .font(.system(.body, design: .monospaced)).textSelection(.enabled)
                        Button(reveal.value ? "Hide" : "Show") { reveal.value.toggle() }
                    }
                }
                HStack {
                    Button("Copy Secret") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(state.cfg.secret, forType: .string)
                        message.value = "Copied."
                    }
                    Button("Copy Pairing Link") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(pairLink, forType: .string)
                        message.value = "Copied. Paste it into Settings on the other device."
                    }
                    Spacer()
                    Button("New Secret…") { confirmNew.value = true }
                }
                if !message.value.isEmpty { Text(message.value).font(.caption).foregroundStyle(.secondary) }
            } header: { Text("Shared secret") } footer: { FootText("Every device uses the same secret; it signs and encrypts everything they send each other. Treat it like a password.")
            }
            Section {
                HStack {
                    Spacer()
                    if let img = QR.image(pairLink, size: 180) {
                        Image(nsImage: img).interpolation(.none).resizable().frame(width: 180, height: 180)
                            .padding(10).background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityLabel("Pairing QR code")
                    }
                    Spacer()
                }
            } header: { Text("Add a phone or tablet") } footer: { FootText("Scan this code with the phone's camera, or with Scan QR code in the EarHop app. It fills in the secret.")
            }
            Section {
                HStack {
                    Button { scanning.value = true } label: { Label("Scan With Camera…", systemImage: "camera.viewfinder") }
                    Button("Paste Pairing Link") { usePasted() }
                }
            } header: { Text("Join another device's pairing") } footer: { FootText("Takes the secret from another device's pairing QR code (shown by EarHop on that device) or from its copied pairing link. This Mac then joins that device's group, and the devices paired with this Mac's old secret must be paired again.")
            }
        }
        .sheet(isPresented: Binding(get: { scanning.value }, set: { scanning.value = $0 })) {
            QRScanSheet { text in
                scanning.value = false
                if let text { use(text) }
            }
        }
        .alert("Make a new secret?", isPresented: Binding(get: { confirmNew.value }, set: { confirmNew.value = $0 })) {
            Button("New Secret", role: .destructive) {
                state.cfg.secret = (0 ..< 16).map { _ in String(format: "%02x", UInt8.random(in: 0 ... 255)) }.joined()
                message.value = "New secret made. Restart EarHop, then pair every other device again."
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every other device stops working with this Mac until you pair it again with the new QR code.")
        }
    }

    private var pairLink: String { "earhop://pair?secret=\(state.cfg.secret)&port=\(state.cfg.port)" }

    private func usePasted() {
        guard let s = NSPasteboard.general.string(forType: .string) else { message.value = "The clipboard has no text."; return }
        use(s)
    }

    /// Takes a secret from a pairing link (earhop://pair?secret=...) or from plain text.
    private func use(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var secret = t
        if let u = URLComponents(string: t), u.scheme == "earhop", u.host == "pair" {
            guard let s = u.queryItems?.first(where: { $0.name == "secret" })?.value, !s.isEmpty else { message.value = "That pairing link has no secret."; return }
            secret = s
        } else if t.contains("://") || t.contains(where: { $0.isWhitespace }) || t.count < 8 || t.count > 128 {
            message.value = "That is not an EarHop pairing code."
            return
        }
        if secret == state.cfg.secret { message.value = "This Mac already uses that secret."; return }
        state.cfg.secret = secret
        message.value = "Secret taken. Restart EarHop to join."
        Log.write("pairing: took a new secret from a pairing code")
    }
}

// MARK: Connection

private struct ConnectionPage: View {
    @ObservedObject var state: AppState
    var body: some View {
        let s = state.status
        let now = Date()
        Form {
            Section {
                ConnectionControl(state: state, showExplanation: true).padding(.vertical, 4)
            } header: { Text("Switching link") } footer: { FootText("How this Mac tells your other devices what it plays. Use the same choice on every device; Both is safest. The clipboard has its own choice on the Clipboard page.")
            }
            Section {
                LinkRow(title: "Bluetooth", ok: s.bluetoothOn, detail: s.bluetoothOn ? "On" : "Off or not allowed")
                LinkRow(title: "Bluetooth LE advertising", ok: s.advertising, detail: s.advertising ? "Other devices can hear this Mac" : "Not advertising")
                LinkRow(title: "Bluetooth LE scan", ok: LinkRow.recent(s.scanResultAt, 30, now), detail: LinkRow.ago("Last result", s.scanResultAt, now))
                LinkRow(title: "Heard over Bluetooth LE", ok: LinkRow.recent(s.bleHeardAt, 15, now), detail: LinkRow.ago("Last device", s.bleHeardAt, now))
                LinkRow(title: "Bluetooth clipboard service", ok: s.clipService, detail: s.clipService ? "Ready" : "Not registered")
                LinkRow(title: "Wi-Fi link", ok: LinkRow.recent(s.wifiHeardAt, 15, now), detail: LinkRow.ago("Last device", s.wifiHeardAt, now))
                LinkRow(title: "Wi-Fi clipboard port", ok: s.clipPort, detail: s.clipPort ? "Listening" : "Not listening")
                LabeledContent("Working now", value: s.transport)
            } header: { Text("This Mac") }
            Section {
                LabeledContent("Found with Bonjour", value: state.discovered.isEmpty ? "none yet" : state.discovered.map(DeviceName.pretty).joined(separator: ", "))
                LabeledContent("Status port (UDP)") {
                    TextField("", value: $state.cfg.port, format: .number.grouping(.never)).labelsHidden().frame(width: 80).multilineTextAlignment(.trailing)
                }
            } header: { Text("Network") } footer: { FootText("Every device must use the same port. A new port applies after a restart.")
            }
            Section {
                Label("Bluetooth LE reaches about one room; Wi-Fi works across the network.", systemImage: "info.circle")
                Label("The first Bluetooth clipboard send between two devices asks once to pair them. Accept it on both.", systemImage: "info.circle")
                Label("Each device's own link is on the Devices page.", systemImage: "info.circle")
            } header: { Text("Good to know") }
        }
    }
}

// MARK: History and Log

private struct HistoryPage: View {
    @ObservedObject var state: AppState
    private let fmt: DateFormatter = { let f = DateFormatter(); f.timeStyle = .medium; return f }()
    var body: some View {
        Form {
            Section {
                if state.history.isEmpty { Text("No switches yet").foregroundStyle(.secondary) }
                ForEach(state.history) { e in
                    HStack(alignment: .firstTextBaseline) {
                        Text(fmt.string(from: e.date)).foregroundStyle(.secondary).monospacedDigit()
                        Text(e.dry ? "dry run" : e.kind.rawValue).font(.caption).foregroundStyle(.secondary)
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .overlay(Capsule().strokeBorder(e.kind == .safe ? Color.orange : Color.secondary.opacity(0.4)))
                        Text(e.text)
                        Spacer()
                    }
                }
            } header: {
                HStack {
                    Text("Why did it switch?")
                    Spacer()
                    Button("Undo Last Switch") { state.onUndo?() }.disabled(state.history.isEmpty)
                }
            }
        }
    }
}

private struct LogPage: View {
    @ObservedObject var state: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button { Log.copyToPasteboard() } label: { Label("Copy Log", systemImage: "doc.on.doc") }
                Button("Open Log File") { NSWorkspace.shared.open(Log.file) }
                Spacer()
                Button { state.onDiagnostics?() } label: { Label("Copy Diagnostics", systemImage: "stethoscope") }
                    .help("Asks every device for its status and the end of its log, and saves everything in one file in Downloads. Logs never contain clipboard contents.")
            }
            if !state.diagMessage.isEmpty { Text(state.diagMessage).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(state.logLines.enumerated()), id: \.offset) { i, line in
                            Text(line).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).id(i)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                }
                .background(RoundedRectangle(cornerRadius: 8).fill(Color(nsColor: .textBackgroundColor)))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.secondary.opacity(0.2)))
                .onChange(of: state.logLines.count) { _ in proxy.scrollTo(state.logLines.count - 1, anchor: .bottom) }
            }
        }
        .padding(20)
    }
}
