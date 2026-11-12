import AppKit
import IOBluetooth
import Network
import SwiftUI
import ServiceManagement

final class AppDelegate: NSObject, NSApplicationDelegate {
    let state = AppState(cfg: Config.load())
    var item: NSStatusItem!
    var popover = NSPopover()
    var settingsWindow: NSWindow?
    var link: Link!
    var ears: EarMonitor?
    var controller: Controller!
    var seq = 0
    var cachedOwner = false
    var lastSendAt = Date.distantPast
    var lastSentKey = ""
    var earbudsCheckedAt = Date.distantPast
    var appsAt = Date.distantPast
    var cachedApps: [String]?
    var discovery: Discovery!
    var ble: BleLink!
    let startedAt = Date()
    var probing = false
    let budsWear = BudsWear()
    var speaker = SpeakerPolicy()
    var clip: ClipSync!
    var clipPolledAt = Date.distantPast
    let pathMonitor = NWPathMonitor()
    var callDetector = CallDetector()
    var networkKind = ""

    func applicationDidFinishLaunching(_ n: Notification) {
        if !state.cfg.onboarded && !state.cfg.airpodsAddress.isEmpty { state.cfg.onboarded = true }
        let cfg = state.cfg
        Log.write("EarHop start dev=\(cfg.deviceName) live=\(cfg.liveMode)")
        if !Config.readOnly { OutputRestore.run() }
        // If EarHop was moved (for example from ~/Applications to /Applications), point the login item at this copy.
        if SMAppService.mainApp.status == .enabled {
            do { try SMAppService.mainApp.register() } catch { Log.write("login item: \(error.localizedDescription)") }
        }
        Log.onLine = { [weak self] in self?.state.appendLog($0) }

        controller = Controller(cfg: cfg, blue: BlueUtil(path: cfg.blueutilPath, address: cfg.activeAddress))
        link = Link(port: cfg.port, secret: cfg.secret, selfName: cfg.deviceName)
        link.onStatus = { [weak self] s in DispatchQueue.main.async {
            self?.state.rejected[s.dev] = nil
            NameBook.shared.add(s.dev)
            // "Bluetooth" only: Wi-Fi packets (sent for the clipboard) only tell addresses, they do not count for the handoff.
            if self?.state.cfg.transport == "ble" { return }
            self?.controller.receive(s)
        } }
        link.onDuplicate = { [weak self] dev in DispatchQueue.main.async { self?.state.duplicates[dev] = Date() } }
        link.onRejected = { [weak self] dev, reason in DispatchQueue.main.async { self?.state.rejected[dev] = (reason, Date()) } }
        discovery = Discovery(name: cfg.deviceName, port: cfg.port)
        discovery.onChange = { [weak self] n in self?.state.discovered = n }
        link.extraTargets = { [weak self] in self?.discovery.targets ?? [] }
        link.start()
        discovery.start()
        NameBook.shared.setup(secret: cfg.secret, known: cfg.knownDevices)
        DeviceName.update(cfg)
        NameBook.shared.onChange = { [weak self] all in
            DispatchQueue.main.async {
                guard let self else { return }
                let extra = all.filter { !["mac", "phone", "tab"].contains($0) }
                if extra != self.state.cfg.knownDevices { self.state.cfg.knownDevices = extra }
            }
        }
        ble = BleLink(secret: cfg.secret, selfName: cfg.deviceName)
        clip = ClipSync(cfg: cfg)
        clip.wifiPeers = { [weak self] in
            guard let self else { return [] }
            return (self.link.peerHostsByDev() + self.discovery.hostsByDev).reduce(into: [(dev: String, host: String)]()) { acc, p in
                if !acc.contains(where: { $0.host == p.host }) { acc.append(p) }
            }
        }
        clip.tcp.allowed = { [weak self] host in
            guard let self else { return false }
            return (self.link.peerHostsByDev() + self.discovery.hostsByDev).contains { $0.host == host }
        }
        clip.blePeers = { [weak self] in
            (self?.ble.peerIds.filter { Date().timeIntervalSince($0.value.at) < 60 }.map { (dev: $0.key, id: $0.value.id) }) ?? []
        }
        clip.ble.freshId = { [weak self] dev in self?.ble.peerIds[dev]?.id }
        clip.ble.secret = cfg.secret
        clip.ble.selfName = cfg.deviceName
        ble.onUnknownId = { [weak self] id, p in self?.clip.ble.lookupName(id: id, peripheral: p) }
        clip.onEvent = { [weak self] text in DispatchQueue.main.async { self?.state.clipStatus = text; self?.state.clipAt = Date() } }
        state.onClipSend = { [weak self] in self?.clip.sendNow() }
        state.onClipRecopy = { [weak self] r in self?.clip.recopy(r) }
        state.onSwitchPause = { [weak self] until in self?.controller.pausedUntil = until }
        state.onClipPause = { [weak self] until in
            self?.clip.pausedUntil = until
            Log.write(until > Date() ? "clip: paused until \(until.formatted(date: .omitted, time: .shortened))" : "clip: resumed")
        }
        clip.onRecord = { [weak self] r in
            DispatchQueue.main.async {
                guard let self, self.state.cfg.clipHistory > 0 else { return }
                var list = [r] + self.state.clipRecent
                // Keep big images out of memory: only the newest three keep their pixels.
                var images = 0
                for i in list.indices where list[i].image != nil { images += 1; if images > 3 { list[i].image = nil } }
                self.state.clipRecent = Array(list.prefix(self.state.cfg.clipHistory))
            }
        }
        clip.onReceipt = { [weak self] id, words in
            DispatchQueue.main.async {
                guard let self, let i = self.state.clipRecent.firstIndex(where: { $0.clipId == id }) else { return }
                self.state.clipRecent[i].receipt = words
            }
        }
        clip.diagnostics = { [weak self] in self?.diagnosticsPart() ?? "" }
        clip.onDiagnostics = { [weak self] dev, text in
            guard let self, self.diagCollecting else { return }
            self.diagParts[dev] = text
            self.state.diagMessage = "Collecting… received from \(self.diagParts.keys.sorted().map(DeviceName.pretty).joined(separator: ", "))"
        }
        state.onDiagnostics = { [weak self] in self?.collectDiagnostics() }
        clip.onControl = { [weak self] item in self?.control(item) }
        budsWear.onUserChoice = { [weak self] kind, value in
            guard let self else { return }
            guard self.state.cfg.ownBudsSettings else { return }
            if kind == "noise" { self.state.cfg.budsNoise = value } else { self.state.cfg.budsEq = value }
        }
        budsWear.ownSettings = { [weak self] in
            guard let c = self?.state.cfg, c.ownBudsSettings else { return (nil, nil) }
            return (c.budsNoise.isEmpty ? nil : c.budsNoise, c.budsEq.isEmpty ? nil : c.budsEq)
        }
        state.onSendFiles = { [weak self] urls, dev in self?.clip.sendFiles(urls, to: dev.map { [$0] }) }
        state.onSendLink = { [weak self] url, dev in self?.clip.sendLink(url, to: dev) }
        state.clipPinned = PinStore.load(secret: cfg.secret)
        state.onPinsChanged = { [weak self] in
            guard let self else { return }
            PinStore.save(self.state.clipPinned, secret: self.state.cfg.secret)
        }
        clip.start()
        ble.onStatus = { [weak self] s in self?.controller.receive(s, via: "ble") }
        if ProcessInfo.processInfo.environment["EARHOP_NO_BT"] == nil {
            ears = EarMonitor(minRSSI: cfg.minRSSI, allowAnyModel: cfg.allowAnyModel, models: cfg.models)
            ears?.onServiceUUIDs = { [weak self] u, id in self?.ble.handle(u, from: id) }
        }

        controller.onEvent = { [weak self] e in
            guard let self = self else { return }
            self.state.addEvent(e)
            if self.state.cfg.notifications, !e.dry, [.claim, .release, .safe].contains(e.kind) {
                Notifier.post("EarHop", e.text)
            }
        }
        budsWear.onState = { [weak self] st in
            guard let self = self else { return }
            self.state.buds = st
            self.state.budsFindActive = self.budsWear.findActive
            self.state.budsFindMessage = self.budsWear.findMessage
        }
        state.budsLink = budsWear
        controller.autoGate = { [weak self] in
            guard let self = self else { return nil }
            return AutoGate.reason(self.controller.cfg)
        }
        controller.onSafeMode = { [weak self] on in self?.state.safeMode = on }
        controller.onStage = { [weak self] s in self?.state.stage = s }
        MediaControl.isPlaying = { [weak self] in self?.state.playing ?? false }
        MediaControl.useKeyOnly = cfg.mediaControl == "key"
        controller.beforeRelease = { [weak self] in
            guard let self, self.state.cfg.isBuds else { return }
            self.budsWear.suspend(5)
        }
        Notifier.requestAuth()

        HotKey.fire = { [weak self] in
            guard let self = self else { return }
            if self.cachedOwner { self.controller.releaseNow() } else { self.controller.claimNow() }
        }
        if cfg.hotkeyEnabled { HotKey.register() }

        state.onUndo = { [weak self] in self?.controller.undoLast() }
        state.onClearSafe = { [weak self] in self?.controller.clearSafeMode() }
        state.onDetect = { [weak self] in self?.detectAirPods() }
        if cfg.activeAddress.isEmpty { detectAirPods() }

        state.onConfigChange = { [weak self] c in
            guard let self = self else { return }
            self.controller.cfg = c
            self.clip.cfg = c
            DeviceName.update(c)
            MediaControl.useKeyOnly = c.mediaControl == "key"
            self.updateIcon()
            self.applyAppearance(c)
            self.controller.blue = BlueUtil(path: c.blueutilPath, address: c.activeAddress)
            if c.hotkeyEnabled { HotKey.register() } else { HotKey.unregister() }
            Log.write("settings changed live=\(c.liveMode)")
        }
        state.onTake = { [weak self] in self?.controller.claimNow() }
        state.onRelease = { [weak self] in self?.controller.releaseNow() }
        state.onSleep = { [weak self] m in self?.setSleep(minutes: m) }
        state.onOpenSettings = { [weak self] in self?.showSettings() }
        state.onRestart = { [weak self] in self?.restart() }

        popover.behavior = .transient
        popover.contentViewController = NSHostingController(rootView: PopoverView(state: state))

        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.target = self
        item.button?.action = #selector(togglePopover)
        updateIcon()

        applyAppearance(cfg)
        if !AudioMonitor.canPostKeys { AudioMonitor.requestKeyPermission() }
        if !cfg.onboarded { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.showSettings() } }
        Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }
        watchSystem()
    }

    /// Sleep, wake and network changes explain most "peer went quiet" moments, so log them and announce at once.
    func watchSystem() {
        let ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { _ in Log.write("system: going to sleep") }
        ws.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Log.write("system: woke up")
            self?.lastSentKey = ""
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let kind = path.status != .satisfied ? "offline" : (path.usesInterfaceType(.wifi) ? "Wi-Fi" : (path.usesInterfaceType(.wiredEthernet) ? "Ethernet" : "other"))
            DispatchQueue.main.async {
                guard let self else { return }
                if kind != self.networkKind { Log.write("network: \(kind)"); self.networkKind = kind; self.lastSentKey = "" }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "earhop.net"))
    }

    func applyAppearance(_ c: Config) {
        switch c.appearance {
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        default: NSApp.appearance = nil
        }
    }

    func detectAirPods() {
        state.detectMessage = "Looking for connected \(state.cfg.earbudsName)..."
        DispatchQueue.global().async {
            let buds = self.state.cfg.isBuds
            let found = Setup.detectConnectedAirPods(nameContains: buds ? "buds" : "airpods")
            DispatchQueue.main.async {
                guard let f = found else {
                    self.state.detectMessage = "No connected \(self.state.cfg.earbudsName) found. Connect them to this Mac first."
                    return
                }
                if buds { self.state.cfg.budsAddress = f.address } else { self.state.cfg.airpodsAddress = f.address }
                if !buds, let m = f.model, !self.state.cfg.models.contains(m) { self.state.cfg.models.append(m) }
                self.state.detectMessage = "Found \(f.name) (\(f.address))"
                Log.write("setup: detected \(f.name) \(f.address) model=\(f.model.map { String($0, radix: 16) } ?? "?")")
                if self.state.cfg.models.count > 0 { self.state.refreshPaired() }
            }
        }
    }

    /// Bluetooth LE is the main link and Wi-Fi runs alongside it ("both", the default): every status goes out over both
    /// at once and receivers keep the newest copy (PeerOrder), so a failing link costs nothing and there is no failover
    /// gap. "ble" uses Bluetooth LE only (for testing it), "wifi" uses Wi-Fi only.
    func sendStatus(_ status: Status) {
        let mode = state.cfg.transport
        let wantBle = mode != "wifi"
        if wantBle != ble.active {
            ble.setActive(wantBle)
            Log.write("transport: Bluetooth LE \(wantBle ? "on" : "off") (mode \(mode))")
            if wantBle && !ble.supported { Log.write("transport: the device name is not valid (lower case letters, digits and -, up to 20)") }
        }
        // Wi-Fi also runs when the handoff uses Bluetooth only but clipboard sync is on (images and long text always go
        // over Wi-Fi): its packets tell the other devices this Mac's current address, as clips go to it directly. Their
        // handoff ignores them in that mode.
        let clipWifi = state.cfg.clipSync
        if mode != "ble" || clipWifi { link.send(status) }
        if ble.active { ble.advertise(status) }
        // Which links actually bring news from other devices right now.
        let now = Date()
        let viaBle = ble.active && now.timeIntervalSince(ble.lastPeerAt) < 10
        let viaWifi = mode != "ble" && now.timeIntervalSince(link.lastHeard) < 10
        let label = viaBle && viaWifi ? "Bluetooth LE + Wi-Fi" : viaBle ? "Bluetooth LE" : viaWifi ? "Wi-Fi"
            : (mode == "wifi" ? "Wi-Fi (nobody heard)" : mode == "ble" ? "Bluetooth LE (nobody heard)" : "Bluetooth LE + Wi-Fi (nobody heard)")
        if state.bleLive != viaBle { state.bleLive = viaBle }
        if state.wifiLive != viaWifi { state.wifiLive = viaWifi }
        if label != state.transport {
            Log.write("transport: \(label)")
            state.transport = label
        }
    }

    /// Automatic earbuds: the pair connected to this Mac, else the pair the device holding the earbuds reports, else no
    /// change. Switching goes through the normal settings path, the same as picking it in the popover.
    /// Speaker fallback (see SpeakerPolicy): runs whether or not automatic switching is on.
    func speakerTick(playing: Bool, reading: EarReading, owner: Bool, inCall: Bool) {
        let act = speaker.update(now: Date(), enabled: state.cfg.speakerFallback, owner: owner, ear: reading, playing: playing, call: inCall,
                                 earPaused: controller.phase == .ownerPausedByEarRemoval)
        if let act = act {
            Log.write(act == .toSpeaker ? "speaker: earbuds out (\(reading.summary)), sound output to the speakers"
                                        : "speaker: earbuds back in (\(reading.summary)), moving the sound back to them")
            let ok = act == .toSpeaker ? OutputRoute.toSpeakers() : OutputRoute.toEarbuds(state.cfg.activeAddress)
            speaker.routed(act, ok: ok)
        }
        if state.onSpeaker != speaker.onSpeaker { state.onSpeaker = speaker.onSpeaker }
    }

    // MARK: battery, volume and other messages between devices

    /// Battery levels reported by another device (left, right, case and charging), and when.
    private var remoteBattery: (l: Int, r: Int, c: Int, cl: Bool, cr: Bool, cc: Bool, at: Date)?
    private var batteryAlerts = BatteryAlerts()
    private var lastBatteryKey = ""
    private var lastBatterySentAt = Date.distantPast
    private var batteryCheckedAt = Date.distantPast
    /// The volume another device last used while it had the earbuds, for the volume handover.
    private var remoteVolume: (dev: String, v: Float, at: Date)?
    private var lastVolumeSent: Float = -1
    private var lastVolumeSentAt = Date.distantPast
    private var wasOwner = false
    private var ownerAt = Date.distantPast

    func control(_ item: ClipItem) {
        let text = String(data: item.data, encoding: .utf8) ?? ""
        switch item.kind {
        case "batt":
            let p = text.split(separator: ",").map(String.init)
            guard p.count >= 6 else { return }
            remoteBattery = (Int(p[0]) ?? -1, Int(p[1]) ?? -1, Int(p[2]) ?? -1, p[3] == "1", p[4] == "1", p[5] == "1", Date())
            updateIcon()
            if state.remoteBattery?.dev != item.dev || state.remoteBattery?.fresh != true {
                Log.write("battery: levels from \(item.dev): L\(p[0]) R\(p[1]) case \(p[2])")
            }
            state.remoteBattery = RemoteBattery(l: Int(p[0]) ?? -1, r: Int(p[1]) ?? -1, c: Int(p[2]) ?? -1,
                                                cl: p[3] == "1", cr: p[4] == "1", cc: p[5] == "1", dev: item.dev, at: Date())
        case "vol":
            if let v = Float(text) { remoteVolume = (item.dev, v, Date()) }
        case "settings":
            applySharedSettings(text, from: item.dev)
        case "hello":
            // Another device says what it is (phone, tablet, Windows PC, ...), for its icon and name everywhere.
            guard let h = Hello.decode(item.data), DeviceNames.valid(item.dev) else { return }
            NameBook.shared.add(item.dev)
            if state.cfg.deviceKinds[item.dev] != h.kind.rawValue {
                state.cfg.deviceKinds[item.dev] = h.kind.rawValue
                Log.write("devices: \(item.dev) is a \(h.kind.title) (EarHop \(h.app) \(h.version))")
            }
        default: break
        }
    }

    /// The best battery levels known: this Mac's own (earbuds connected here), else another device's from the last 10 min.
    func batteryLevels() -> (l: Int, r: Int, c: Int, cl: Bool, cr: Bool, cc: Bool, local: Bool)? {
        if state.cfg.isBuds, state.budsConnected {
            let b = state.buds
            if b.battL >= 0 || b.battR >= 0 { return (b.battL, b.battR, b.battCase, b.chargeL, b.chargeR, b.chargeCase, true) }
        } else if !state.cfg.isBuds, let b = state.battery {
            return (b.0, b.1, state.caseBattery ?? -1, false, false, false, true)
        }
        if let r = remoteBattery, Date().timeIntervalSince(r.at) < 600 { return (r.l, r.r, r.c, r.cl, r.cr, r.cc, false) }
        return nil
    }

    /// Every 2 s: share this Mac's battery levels when they are its own, and alert at 20 % and 10 %.
    func batteryTick() {
        guard Date().timeIntervalSince(batteryCheckedAt) >= 2 else { return }
        batteryCheckedAt = Date()
        guard let b = batteryLevels() else { return }
        if b.local {
            let key = "\(b.l),\(b.r),\(b.c),\(b.cl ? 1 : 0),\(b.cr ? 1 : 0),\(b.cc ? 1 : 0)"
            if key != lastBatteryKey || Date().timeIntervalSince(lastBatterySentAt) > 60 {
                lastBatteryKey = key; lastBatterySentAt = Date()
                clip.sendControl(kind: "batt", data: Data(key.utf8), to: nil)
            }
        }
        guard state.cfg.lowBatteryAlerts else { return }
        let earbuds = state.cfg.isBuds ? "Galaxy Buds" : "AirPods"
        for (part, level, charging, words) in [("left", b.l, b.cl, "Left earbud"), ("right", b.r, b.cr, "Right earbud"), ("case", b.c, b.cc, "\(earbuds) case")] {
            if let t = batteryAlerts.update(part, level: level, charging: charging) {
                Notifier.post("\(words) battery low", "\(level) % left\(t == 10 ? ", charge soon" : "")")
                Log.write("battery: \(part) at \(level) % (alert at \(t) %)")
            }
        }
    }

    /// While this Mac has the earbuds it tells the others its volume; when they arrive here it takes the last one.
    /// This Mac's own volume profile wins over the handover. It reports only after holding the earbuds 5 s, so a stray
    /// connection of a second or two (the earbuds reconnecting by themselves during a handoff) sets nobody's volume.
    func volumeTick(owner: Bool) {
        defer { wasOwner = owner }
        if owner, !wasOwner { ownerAt = Date() }
        if owner, !wasOwner, state.cfg.volumeHandover, !state.cfg.volumeEnabled, let r = remoteVolume, r.dev != state.cfg.deviceName,
           Date().timeIntervalSince(r.at) < 600 {
            // A moment later, once the sound runs through the earbuds.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                OutputRoute.volume = r.v
                Log.write("volume: set to \(Int(r.v * 100)) %, as on \(r.dev)")
            }
        }
        guard owner, Date().timeIntervalSince(ownerAt) >= 5, let v = OutputRoute.volume else { return }
        if abs(v - lastVolumeSent) > 0.02 || Date().timeIntervalSince(lastVolumeSentAt) > 60 {
            lastVolumeSent = v; lastVolumeSentAt = Date()
            clip.sendControl(kind: "vol", data: Data(String(format: "%.3f", v).utf8), to: nil)
        }
    }

    // MARK: sleep timer and automation

    func setSleep(minutes: Int) {
        if minutes <= 0 { state.sleepUntil = .distantPast; Log.write("sleep: timer cancelled"); return }
        state.sleepUntil = Date().addingTimeInterval(Double(minutes) * 60)
        if (5 ... 240).contains(minutes) { state.cfg.sleepMinutes = minutes }
        Log.write("sleep: pause and release in \(minutes) min")
    }

    func sleepTick(owner: Bool) {
        guard state.sleepUntil > .distantPast, Date() >= state.sleepUntil else { return }
        state.sleepUntil = .distantPast
        Log.write("sleep: timer ended, pausing and releasing the earbuds")
        MediaControl.pausePlayback()
        if owner { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.controller.releaseNow() } }
        Notifier.post("Sleep timer", "Paused and released the earbuds")
    }

    /// earhop:// links, for Shortcuts ("Open URL"), scripts and other apps: take, release, send-clipboard,
    /// sleep?minutes=30, sleep-cancel, diagnostics.
    func application(_ app: NSApplication, open urls: [URL]) {
        for u in urls where u.scheme == "earhop" {
            let q = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems ?? []
            if u.host == "send-files" {
                shareFiles(q.filter { $0.name == "path" }.compactMap(\.value))
                continue
            }
            run(command: u.host ?? "", minutes: q.first { $0.name == "minutes" }?.value.flatMap(Int.init))
        }
    }

    /// Files from the Share menu extension: asks with a small menu at the pointer which device gets them, then sends them
    /// like the "Send file" button (Wi-Fi). Folders are skipped.
    private func shareFiles(_ paths: [String]) {
        var isDir: ObjCBool = false
        let files = paths.map { URL(fileURLWithPath: $0) }
            .filter { FileManager.default.fileExists(atPath: $0.path, isDirectory: &isDir) && !isDir.boolValue }
        Log.write("share: \(files.count) file(s) from the Share menu" + (files.count < paths.count ? ", \(paths.count - files.count) folder(s) or missing item(s) skipped" : ""))
        guard !files.isEmpty else { Notifier.post("EarHop", "Folders cannot be sent, only files."); return }
        let devs = Set(clip.wifiPeers().map(\.dev)).subtracting([state.cfg.deviceName]).sorted()
        guard !devs.isEmpty else { Notifier.post("Not sent", "No other device is on Wi-Fi right now."); return }
        let title = files.count == 1 ? files[0].lastPathComponent : "\(files.count) files"
        // A panel of its own, not a pop-up menu: EarHop has no window, and a menu opened without one did not appear.
        SharePicker.show(title: title, devices: devs) { [weak self] dev in
            Log.write("share: sending to \(dev ?? "all devices")")
            self?.clip.sendFiles(files, to: dev.map { [$0] })
        }
    }

    /// One automation command (earhop:// links and AppleScript).
    @discardableResult
    func run(command: String, minutes: Int? = nil) -> Bool {
        Log.write("automation: \(command)")
        switch command {
        case "take": controller.claimNow()
        case "release": controller.releaseNow()
        case "send-clipboard": clip.sendNow()
        case "sleep": setSleep(minutes: minutes ?? state.cfg.sleepMinutes)
        case "sleep-cancel": setSleep(minutes: 0)
        case "diagnostics": collectDiagnostics()
        default: return false
        }
        return true
    }

    // MARK: shared settings

    private var lastShared: [String: String] = [:]
    private var settingsCheckedAt = Date.distantPast
    private var settingsSentAt = Date.distantPast
    private var helloSentAt = Date.distantPast
    private var settingsPeers = Set<String>()

    /// Once a second: a shared setting changed here is stamped and sent; every 5 minutes, and when a new device appears,
    /// the whole set goes out so devices that were away catch up.
    func settingsTick() {
        let now = Proto.nowMs()
        let vals = SettingsSync.values(state.cfg).mapValues { "\($0)" }
        if lastShared.isEmpty { lastShared = vals; return }
        var changed = false
        for (k, v) in vals where lastShared[k] != v {
            state.cfg.settingsStamps[k] = now
            changed = true
        }
        lastShared = vals
        let peersNow = Set(state.peers.map(\.name))
        let newPeer = !peersNow.subtracting(settingsPeers).isEmpty
        settingsPeers = peersNow
        // Tell the others what this device is: at start, when a device joins, and every 10 minutes.
        if newPeer || Date().timeIntervalSince(helloSentAt) > 600 {
            helloSentAt = Date()
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.4.0"
            clip.sendControl(kind: "hello", data: Hello.encode(kind: .mac, app: "mac", version: version), to: nil)
        }
        if changed || newPeer || Date().timeIntervalSince(settingsSentAt) > 300 {
            settingsSentAt = Date()
            clip.sendControl(kind: "settings", data: SettingsSync.encode(state.cfg, stamps: state.cfg.settingsStamps), to: nil)
        }
    }

    func applySharedSettings(_ text: String, from dev: String) {
        let remote = SettingsSync.decode(Data(text.utf8))
        let apply = SettingsSync.merge(local: state.cfg.settingsStamps, remote: remote, offsetMs: ClockGuard.shared.offset(dev) ?? 0,
                                       localValues: SettingsSync.values(state.cfg), nowMs: Proto.nowMs())
        guard !apply.isEmpty else { return }
        var c = state.cfg
        for (k, r) in apply { SettingsSync.apply(k, r.value, to: &c); c.settingsStamps[k] = r.stamp }
        state.cfg = c
        lastShared = SettingsSync.values(c).mapValues { "\($0)" }
        Log.write("settings: \(apply.keys.sorted().joined(separator: ", ")) changed on \(dev), applied here")
    }

    // MARK: follow my attention

    private var unlockedAt = Date.distantPast
    private var attentionObserver: NSObjectProtocol?

    /// The user unlocked this Mac or pressed a key in the last 30 s. Keys are only counted, never read.
    func attentionNow() -> Bool {
        if attentionObserver == nil {
            attentionObserver = DistributedNotificationCenter.default().addObserver(forName: .init("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in
                self?.unlockedAt = Date()
            }
        }
        let sinceKey = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .keyDown)
        return Date().timeIntervalSince(unlockedAt) < 30 || sinceKey < 30
    }

    // MARK: status page and diagnostics

    private var diagCollecting = false
    private var diagParts: [String: String] = [:]
    private var statusAt = Date.distantPast

    /// The Status page's facts, refreshed once a second from the tick.
    func refreshStatus() {
        guard Date().timeIntervalSince(statusAt) >= 1 else { return }
        statusAt = Date()
        var st = LinkStatus()
        st.bluetoothOn = ears?.poweredOn ?? false
        st.advertising = ble.advertising
        st.scanResultAt = ears.map { $0.lastResultAt > .distantPast ? $0.lastResultAt : nil } ?? nil
        st.bleHeardAt = ble.lastPeerAt > .distantPast ? ble.lastPeerAt : nil
        st.clipService = clip.ble.serviceUp
        st.wifiHeardAt = link.lastHeard > .distantPast ? link.lastHeard : nil
        st.clipPort = clip.tcp.listening
        st.transport = state.transport
        let hosts = clip.wifiPeers()
        let refused = controller.clockRefused
        st.devices = controller.lastHeard().map { p in
            LinkStatus.Device(name: p.name, lastHeard: p.at, via: p.via, bleAt: ble.peerIds[p.name]?.at,
                              wifiHosts: hosts.filter { $0.dev == p.name }.map(\.host),
                              clockOffsetMs: ClockGuard.shared.offset(p.name), clockRefusedAt: refused[p.name])
        }
        if st != state.status { state.status = st }
    }

    /// This Mac's part of a diagnostics file: version, links, devices and the end of the log (no clipboard contents).
    func diagnosticsPart() -> String {
        refreshStatus()
        let s = state.status
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        var out = "EarHop \(v) on macOS \(ProcessInfo.processInfo.operatingSystemVersionString), name \(state.cfg.deviceName)\n"
        out += "bluetooth \(s.bluetoothOn) advertising \(s.advertising) scan result \(s.scanResultAt.map { "\(Int(-$0.timeIntervalSinceNow)) s ago" } ?? "never")"
        out += " clip service \(s.clipService) clip port \(s.clipPort) link \(s.transport)\n"
        out += "transport \(state.cfg.transport) clip method \(state.cfg.clipTransport) clip sync \(state.cfg.clipSync)\n"
        for d in s.devices {
            out += "device \(d.name): heard \(d.lastHeard.map { "\(Int(-$0.timeIntervalSinceNow)) s ago" } ?? "never") over \(d.via)"
            out += ", ble \(d.bleAt.map { "\(Int(-$0.timeIntervalSinceNow)) s ago" } ?? "never"), wifi \(d.wifiHosts.count) address(es)"
            out += ", clock offset \(d.clockOffsetMs.map { "\($0) ms" } ?? "unknown")\n"
        }
        // The end of the log, at most 50 KB, so the reply also fits over Bluetooth LE.
        let log = Log.tail(1500)
        out += "--- log ---\n" + String(log.suffix(50_000))
        return out
    }

    /// Copy diagnostics: asks every device, waits 15 s, saves one file in Downloads and shows it in Finder.
    func collectDiagnostics() {
        guard !diagCollecting else { return }
        diagCollecting = true
        diagParts = [:]
        state.diagMessage = "Collecting from your devices…"
        clip.requestDiagnostics()
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self else { return }
            self.diagCollecting = false
            let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
            var text = "EarHop diagnostics, \(Date())\n\n===== \(self.state.cfg.deviceName) (this Mac) =====\n" + self.diagnosticsPart() + "\n"
            for (dev, part) in self.diagParts.sorted(by: { $0.key < $1.key }) { text += "\n===== \(dev) =====\n" + part + "\n" }
            let missing = self.state.status.devices.map(\.name).filter { self.diagParts[$0] == nil }
            if !missing.isEmpty { text += "\nNo reply from: \(missing.joined(separator: ", "))\n" }
            let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? Config.dir
            let url = dir.appendingPathComponent("EarHop diagnostics \(stamp).txt")
            do {
                try text.write(to: url, atomically: true, encoding: .utf8)
                NSWorkspace.shared.activateFileViewerSelecting([url])
                let got = (["this Mac"] + self.diagParts.keys.sorted().map(DeviceName.pretty)).joined(separator: ", ")
                self.state.diagMessage = "Saved \(url.lastPathComponent) in Downloads (\(got))" + (missing.isEmpty ? "." : ". No reply from \(missing.map(DeviceName.pretty).joined(separator: ", ")).")
                Log.write("diag: saved diagnostics from \(got)")
            } catch {
                self.state.diagMessage = "Could not save the file: \(error.localizedDescription)"
            }
        }
    }

    /// Never leave the sound on the speakers with the earbuds connected when EarHop quits.
    func applicationWillTerminate(_ n: Notification) {
        if speaker.onSpeaker { _ = OutputRoute.toEarbuds(state.cfg.activeAddress) }
    }

    func detectEarbuds() {
        let c = state.cfg
        guard c.earbudsMode == "auto" else { return }
        func connected(_ address: String) -> Bool {
            BlueUtil(path: "", address: address).fastConnected() ?? false
        }
        let want: String
        if connected(c.budsAddress) { want = "buds" }
        else if connected(c.airpodsAddress) { want = "airpods" }
        else if let p = controller.ownerProfile() { want = p }
        else { return }
        guard want != c.earbuds else { return }
        Log.write("earbuds: switching to \(want == "buds" ? "Galaxy Buds2 Pro" : "AirPods") (auto)")
        state.cfg.earbuds = want
    }

    func tick() {
        refreshStatus()
        if Date().timeIntervalSince(clipPolledAt) >= 0.5 {
            clipPolledAt = Date(); clip.poll()
            let b = clip.blePeers().count, w = clip.wifiPeers().count
            if state.clipBlePeers != b { state.clipBlePeers = b }
            if state.clipWifiPeers != w { state.clipWifiPeers = w }
            if state.clipRecent.count > state.cfg.clipHistory { state.clipRecent = Array(state.clipRecent.prefix(state.cfg.clipHistory)) }
        }
        let deviceRunning = AudioMonitor.isPlaying()
        if Date().timeIntervalSince(appsAt) > 1 { cachedApps = AudioMonitor.outputApps(); appsAt = Date() }
        let apps = cachedApps
        let ignored = Set(state.cfg.ignoredApps)
        let active = (apps ?? []).filter { !ignored.contains($0) }
        // When macOS lists which apps are producing sound, trust it: an empty list means nothing is playing,
        // even if the output device stays busy for a few seconds after a pause.
        let playing = deviceRunning && (apps == nil ? true : !active.isEmpty)
        // Galaxy Buds report wear status over Samsung's serial channel. Until that is open they count as in the ears.
        let reading: EarReading = state.cfg.isBuds ? budsWear.reading : (ears?.reading ?? .unknown)
        let ear = reading.ear
        // A meeting app (Zoom, WhatsApp, Teams, Settings, Handoff) producing sound counts as a call at once, so meetings
        // come before music; any other app needs microphone and speaker busy together for 2 s.
        let meeting = (apps ?? []).contains { state.cfg.meetingApps.contains($0) }
        let detected = callDetector.update(now: Date(), mic: AudioMonitor.isMicInUse(), output: deviceRunning)
        let inCall = state.cfg.detectCalls && (detected || meeting)

        // Ownership: instant IOBluetooth check, with an async blueutil probe only as a fallback.
        if let fast = controller.blue.fastConnected() {
            cachedOwner = fast
        } else if controller.blue.available, !probing {
            probing = true
            let blue = controller.blue
            DispatchQueue.global().async {
                let o = blue.isConnected()
                DispatchQueue.main.async { self.cachedOwner = o; self.probing = false }
            }
        } else if !controller.blue.available {
            cachedOwner = false
        }
        let owner = cachedOwner
        budsWear.update(address: state.cfg.budsAddress, owner: owner, wanted: state.cfg.isBuds)
        if state.budsStatus != budsWear.status { state.budsStatus = budsWear.status }

        ears?.setGentle(state.cfg.gentleScan && owner && playing)
        // Speaker fallback first, so when a bud goes back in the sound is on the earbuds before the resume key is sent.
        speakerTick(playing: playing, reading: reading, owner: owner, inCall: inCall)
        batteryTick()
        volumeTick(owner: owner)
        sleepTick(owner: owner)
        if Date().timeIntervalSince(settingsCheckedAt) >= 1 { settingsCheckedAt = Date(); settingsTick() }
        controller.onSpeaker = speaker.onSpeaker
        controller.attention = attentionNow()
        controller.tick(playing: playing, ear: reading, owner: owner, inCall: inCall)
        if state.phase != controller.phase { state.phase = controller.phase }
        if state.holdReason != controller.holdReason { state.holdReason = controller.holdReason }
        if state.lastFailure != controller.lastFailure { state.lastFailure = controller.lastFailure }
        if state.earReading != reading { state.earReading = reading }
        let holder = controller.ownerPeer()
        if state.holder != holder { state.holder = holder }
        if Date().timeIntervalSince(earbudsCheckedAt) >= 2 { earbudsCheckedAt = Date(); detectEarbuds() }
        // Send immediately when something changed, otherwise once a second as a heartbeat.
        let shown = controller.core.advertisedPlaying(playing)
        let key = "\(shown)|\(ear.rawValue)|\(owner)|\(inCall)|\(state.cfg.earbuds)|\(attentionNow())"
        if key != lastSentKey || Date().timeIntervalSince(lastSendAt) >= 1 {
            lastSentKey = key
            lastSendAt = Date()
            seq += 1
            let status = Status(dev: state.cfg.deviceName, ts: Proto.nowMs(), seq: seq, playing: shown, ear: ear, owner: owner,
                                call: inCall, prof: state.cfg.isBuds ? "buds" : "airpods", att: attentionNow())
            sendStatus(status)
        }
        let keys = AudioMonitor.canPostKeys
        if state.keysAllowed != keys { state.keysAllowed = keys }
        if state.playing != playing { state.playing = playing }
        if state.ear != ear { state.ear = ear }
        if state.owner != owner { state.owner = owner }
        if state.inCall != inCall { state.inCall = inCall }
        if state.playingApps != (apps ?? []) { state.playingApps = apps ?? [] }
        let bat = ears?.battery
        if bat?.0 != state.battery?.0 || bat?.1 != state.battery?.1 { state.battery = bat; updateIcon() }
        let cb = ears?.caseBattery
        if cb != state.caseBattery { state.caseBattery = cb; updateIcon() }
        state.peers = controller.peerInfos()
        let holders = state.peers.filter { $0.owner && $0.age < 5 }.map(\.name)
        BlueUtil.setPeerOwners(holders.isEmpty ? nil : holders.joined(separator: ", "))
        updateIcon()
    }

    /// What the menu bar button shows now; the button is only touched when this changes.
    private var iconKey = ""

    func updateIcon() {
        let switching = state.stage != nil
        let name = switching ? "arrow.triangle.2.circlepath" : (state.cfg.isBuds ? "earbuds" : "airpodspro")
        var desc = switching ? "EarHop, switching" : (state.owner ? "EarHop, connected here" : "EarHop")
        if !state.cfg.liveMode { desc += ", dry run" }
        var title = state.safeMode ? " !" : (state.owner ? " \u{25CF}" : "")
        if state.cfg.menuBarBattery, let b = batteryLevels(), let m = [b.l, b.r].filter({ $0 >= 0 }).min() {
            // The lower bud, from this Mac or from the device that has the earbuds.
            title += " \(m)%"
        }
        // This runs four times a second. Setting the button (and above all its tooltip) again and again while macOS
        // showed that tooltip left the "EarHop (live)" tooltip stuck to the pointer after the popover opened
        // (2026-11-13). So: no tooltip at all, and the button is only changed when what it shows changes.
        let key = "\(name)|\(title)|\(desc)"
        guard key != iconKey, let button = item.button else { return }
        iconKey = key
        let img = NSImage(systemSymbolName: name, accessibilityDescription: desc)
        img?.isTemplate = true
        button.image = img
        button.title = title
        button.imagePosition = title.isEmpty ? .imageOnly : .imageLeading
        button.toolTip = nil
        button.setAccessibilityLabel(desc)
    }

    @objc func togglePopover() {
        guard let b = item.button else { return }
        if popover.isShown { popover.performClose(nil) } else {
            popover.show(relativeTo: b.bounds, of: b, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }

    func showSettings() {
        popover.performClose(nil)
        if settingsWindow == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 620),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            // Like System Settings: the sidebar runs under the title bar and the title names the page.
            w.title = "General"
            w.toolbar = NSToolbar(identifier: "earhop.settings")
            w.toolbarStyle = .unified
            w.titlebarAppearsTransparent = true
            w.contentViewController = NSHostingController(rootView: SettingsView(state: state))
            w.setContentSize(NSSize(width: 860, height: 620))
            w.setFrameAutosaveName("EarHopSettings")
            state.onSettingsPage = { [weak w] p in w?.title = p.rawValue }
            w.isReleasedWhenClosed = false
            w.center()
            settingsWindow = w
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: settingsWindow, queue: .main) { _ in
            NSApp.setActivationPolicy(.accessory)
        }
    }

    func restart() {
        let path = Bundle.main.bundlePath
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "sleep 1; /usr/bin/open -n '\(path)'"]
        try? p.run()
        NSApp.terminate(nil)
    }
}

if CommandLine.arguments.contains("--selftest") {
    Log.toFile = false
    Config.readOnly = true
    var failed: [String] = []
    func check(_ name: String, _ ok: Bool) { if !ok { failed.append(name) } }

    let s = Status(dev: "mac", ts: 1700000000000, seq: 7, playing: true, ear: .both, owner: false)
    check("canonical v1", Proto.canonical(s, v: 1) == "1|mac|1700000000000|7|1|both|0")
    check("hmac vector", Proto.sign("test-secret", Proto.canonical(s, v: 1)) == "389cb0b6b95b236ec9f8ee2a76263d87cf0f876d590b14c976960cbe424b4c00")

    let now = Proto.nowMs()
    let fresh = Status(dev: "tab", ts: now, seq: 1, playing: false, ear: .none, owner: true)
    let wire = Proto.encode(fresh, secret: "k")!
    check("roundtrip", Proto.decode(wire, secret: "k", nowMs: now)?.dev == "tab")
    check("wrong secret rejected", Proto.decode(wire, secret: "x", nowMs: now) == nil)
    // v4: the sender's clock is not compared with this one when decoding; ClockGuard checks learned offsets.
    check("v4 decodes with distant clocks", Proto.decode(wire, secret: "k", nowMs: now + 600_000)?.dev == "tab")
    let cg = ClockGuard()
    check("clock 10 min ahead learned", cg.check(dev: "p", senderMs: 601_000, nowMs: 1000, toleranceMs: ClockGuard.wifiTolerance, seq: 1)
          && cg.check(dev: "p", senderMs: 602_000, nowMs: 2000, toleranceMs: ClockGuard.wifiTolerance, seq: 2))
    check("2 minute old recording refused", !cg.check(dev: "p", senderMs: 482_000, nowMs: 2000, toleranceMs: ClockGuard.bleTolerance, seq: 1))
    check("changed clock relearned after 3 rising copies",
          !cg.check(dev: "p", senderMs: 900_000, nowMs: 3000, toleranceMs: ClockGuard.wifiTolerance, seq: 3)
          && !cg.check(dev: "p", senderMs: 901_000, nowMs: 4000, toleranceMs: ClockGuard.wifiTolerance, seq: 4)
          && cg.check(dev: "p", senderMs: 902_000, nowMs: 5000, toleranceMs: ClockGuard.wifiTolerance, seq: 5))
    check("same copy replayed does not relearn",
          !cg.check(dev: "p", senderMs: 100_000, nowMs: 6000, toleranceMs: ClockGuard.wifiTolerance, seq: 9)
          && !cg.check(dev: "p", senderMs: 100_000, nowMs: 7000, toleranceMs: ClockGuard.wifiTolerance, seq: 9)
          && !cg.check(dev: "p", senderMs: 100_000, nowMs: 8000, toleranceMs: ClockGuard.wifiTolerance, seq: 9))
    check("clip fresh by sender clock", cg.fresh(dev: "p", senderMs: 905_000, nowMs: 6000, maxAgeMs: 300_000)
          && !cg.fresh(dev: "p", senderMs: 500_000, nowMs: 6000, maxAgeMs: 300_000))
    check("replay is not a restart", !PeerOrder.accept(lastSeq: 1000, seq: 5, ble: false, ts: 50_000, lastTs: 60_000).take
          && PeerOrder.accept(lastSeq: 1000, seq: 5, ble: false, ts: 70_000, lastTs: 60_000).take)
    check("v4 vectors", Proto.sign("test-secret", Proto.canonical(Status(dev: "mac", ts: 1700000000000, seq: 7, playing: true, ear: .both,
          owner: false, call: true, prof: "buds", att: true), v: 4)) == "7ad20a1aac679b7fcd58e5e67214a1531812fdb6e574eeca406f31ef8af8cf0f"
          && ["mac", "phone", "tab"].map { DeviceNames.id($0, secret: "test-secret") } == [33190, 36699, 48167])
    let b4 = BlePayload.encode4(Status(dev: "mac", ts: 0, seq: 7, playing: true, ear: .both, owner: false, call: true, prof: "buds", att: true),
                                secret: "test-secret", sec: 1_700_000_000)
    check("v4 ble vector", b4.map { String(format: "%02x", $0) }.joined() == "eb81a60007f728611b1ffa426553f100")
    let d4 = BlePayload.decode4(b4, secret: "test-secret")
    check("v4 ble decode", d4.map { BlePayload.status($0, name: "mac") }.map { $0.att && $0.prof == "buds" && $0.ts == 1_700_000_000_000 } == true
          && BlePayload.decode4(b4, secret: "x") == nil && BlePayload.decode4(Array(b4[0 ..< 15]) + [b4[15] ^ 1], secret: "test-secret") == nil)
    // Out of the ears for 2 minutes lets go, except while speaker fallback plays the sound on the speakers.
    func speakerRelease(_ onSpeaker: Bool, playFor: Double, limit: Double = 600) -> Double? {
        var core = HandoffCore(settings: HandoffSettings(deviceName: "mac"))
        var t = Date(timeIntervalSince1970: 1_000_000)
        for _ in 0 ..< 20 {
            _ = core.tick(HandoffInput(now: t, playing: false, ear: EarReading(ear: .both, source: "buds-rfcomm", confidence: .high), owner: true, inCall: false, peers: [], gate: nil))
            t = t.addingTimeInterval(0.25)
        }
        let t0 = t
        while t.timeIntervalSince(t0) < limit {
            let el = t.timeIntervalSince(t0)
            let acts = core.tick(HandoffInput(now: t, playing: el < playFor, ear: EarReading(ear: .none, source: "buds-rfcomm", confidence: .high),
                                              owner: true, inCall: false, peers: [], gate: nil, onSpeaker: onSpeaker && el >= 2))
            if acts.contains(where: { if case .release = $0 { return true }; return false }) { return el }
            t = t.addingTimeInterval(0.25)
        }
        return nil
    }
    check("out 2 min lets go", speakerRelease(false, playFor: 0).map { (120 ... 122).contains($0) } == true)
    check("playing on speakers keeps the earbuds", speakerRelease(true, playFor: 600, limit: 590) == nil)
    let quietRel = speakerRelease(true, playFor: 300)
    check("speakers quiet 2 min lets go \(quietRel.map { String($0) } ?? "never")", quietRel.map { (419 ... 422).contains($0) } == true)
    let a0 = Date(timeIntervalSince1970: 0)
    check("old addresses not used", Link.current(["old", "new"], ["old": a0, "new": a0.addingTimeInterval(3600)], now: a0.addingTimeInterval(3601)) == ["new"])
    check("only the newest when none fresh", Link.current(["a", "b"], ["a": a0, "b": a0.addingTimeInterval(1)], now: a0.addingTimeInterval(9999)) == ["b"])
    check("password-like text", PasswordLike.looks("Hunter2!xyz") && PasswordLike.looks("aB3dEf9hJ") && !PasswordLike.looks("hello world 123")
          && !PasswordLike.looks("short1A") && !PasswordLike.looks("https://example.com/A1b2") && !PasswordLike.looks("Some.One42@example.com")
          && !PasswordLike.looks("onlylowercaseletters"))
    let rc0 = Date(timeIntervalSince1970: 0)
    let rr = Receipts()
    rr.track("c1", targets: ["phone", "tab"], now: rc0)
    check("receipt counted once", rr.ack("c1", from: "phone") && !rr.ack("c1", from: "phone") && !rr.ack("c1", from: "mac"))
    check("no retry before 5 s", rr.due(now: rc0.addingTimeInterval(4)).isEmpty)
    check("retry the silent device at 5 s", rr.due(now: rc0.addingTimeInterval(5)).map(\.devs) == [["tab"]])
    check("second retry at 15 s, then none", rr.due(now: rc0.addingTimeInterval(15)).count == 1 && rr.due(now: rc0.addingTimeInterval(40)).isEmpty)
    check("receipt words", rr.words("c1", now: rc0.addingTimeInterval(10)) == "Delivered to Phone ✓; sending to Tablet"
          && rr.words("c1", now: rc0.addingTimeInterval(61)) == "Delivered to Phone ✓; not delivered to Tablet")
    _ = rr.ack("c1", from: "tab")
    check("all delivered", rr.words("c1", now: rc0.addingTimeInterval(62)) == "Delivered to Phone ✓, Tablet ✓" && rr.due(now: rc0.addingTimeInterval(70)).isEmpty)
    let f = ClipCodec.encode(ClipItem(id: "x", dev: "mac", kind: "ack", mime: "m", ts: 1, data: Data("c1".utf8), to: "phone"), secret: "k")!
    check("recipient field round trip", ClipCodec.decode(f, secret: "k")?.to == "phone")
    // Follow my attention.
    func attClaims(_ follow: Bool, here: Bool, ownerAtt: Bool) -> Bool {
        var st = HandoffSettings(deviceName: "phone"); st.followAttention = follow
        var core = HandoffCore(settings: st)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        var t = t0
        let both = EarReading(ear: .both, source: "buds-rfcomm", confidence: .high)
        while t.timeIntervalSince(t0) < 4 {
            let p = HandoffPeer(name: "mac", playing: true, ear: .both, owner: true, call: false, seenAt: t,
                                playingSince: t0.addingTimeInterval(0.5), lastPlayingAt: t, att: ownerAtt)
            let acts = core.tick(HandoffInput(now: t, playing: true, ear: both, owner: false, inCall: false, peers: [p], gate: nil, attention: here))
            if acts.contains(where: { if case .claim = $0 { return true }; return false }) { return true }
            t = t.addingTimeInterval(0.25)
        }
        return false
    }
    func attYields(_ follow: Bool, here: Bool, theirAtt: Bool) -> Bool {
        var st = HandoffSettings(deviceName: "phone"); st.followAttention = follow
        var core = HandoffCore(settings: st)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        var t = t0
        let both = EarReading(ear: .both, source: "buds-rfcomm", confidence: .high)
        while t.timeIntervalSince(t0) < 4 {
            let p = HandoffPeer(name: "tab", playing: true, ear: .both, owner: false, call: false, seenAt: t,
                                playingSince: t0.addingTimeInterval(-5), lastPlayingAt: t, att: theirAtt)
            let acts = core.tick(HandoffInput(now: t, playing: true, ear: both, owner: true, inCall: false, peers: [p], gate: nil, attention: here))
            if acts.contains(where: { if case .release = $0 { return true }; return false }) { return true }
            t = t.addingTimeInterval(0.25)
        }
        return false
    }
    check("attention: user here wins over a busy owner", attClaims(true, here: true, ownerAtt: false) && !attClaims(true, here: false, ownerAtt: false)
          && !attClaims(false, here: true, ownerAtt: false) && !attClaims(true, here: true, ownerAtt: true))
    check("attention: owner yields when the user moved away", attYields(true, here: false, theirAtt: true) && !attYields(true, here: true, theirAtt: true)
          && !attYields(false, here: false, theirAtt: true) && !attYields(true, here: false, theirAtt: false))
    var ba = BatteryAlerts()
    check("battery alert at 20 then 10, once each", ba.update("left", level: 30, charging: false) == nil && ba.update("left", level: 20, charging: false) == 20
          && ba.update("left", level: 19, charging: false) == nil && ba.update("left", level: 10, charging: false) == 10
          && ba.update("left", level: 9, charging: false) == nil)
    check("battery alert again after charging", ba.update("left", level: 12, charging: true) == nil && ba.update("left", level: 18, charging: false) == 20)
    var bAlerts2 = BatteryAlerts()
    check("battery straight to 10", bAlerts2.update("case", level: 8, charging: false) == 10 && bAlerts2.update("case", level: 7, charging: false) == nil
          && bAlerts2.update("case", level: -1, charging: false) == nil)
    let rem: [String: (value: Any, stamp: Int64)] = ["clipSync": (false, 5000), "autoSwitch": (true, 1000), "secret": ("x", 9000)]
    let mg = SettingsSync.merge(local: ["clipSync": 2000, "autoSwitch": 3000], remote: rem, offsetMs: 1000)
    check("settings: newer change wins, older and local-only keys ignored", mg.keys.sorted() == ["clipSync"] && mg["clipSync"]?.stamp == 4000)
    let echo = SettingsSync.merge(local: ["clipSync": 2000, "sleepMinutes": 2000],
                                  remote: ["clipSync": (NSNumber(value: true), 9000), "sleepMinutes": (NSNumber(value: 45), 9000)],
                                  offsetMs: 0, localValues: ["clipSync": true, "sleepMinutes": 30])
    check("settings: an equal value is not taken again (no echo)", echo.keys.sorted() == ["sleepMinutes"])
    let future = SettingsSync.merge(local: ["clipSync": 2000], remote: ["clipSync": (NSNumber(value: false), 500_000)], offsetMs: 0,
                                    localValues: ["clipSync": true], nowMs: 100_000)
    check("settings: a stamp far in the future is refused", future.isEmpty)
    // Clipboard routing: text over Bluetooth, Wi-Fi only for devices Bluetooth cannot reach; images over Wi-Fi.
    let all: Set<String> = ["phone", "tab"]
    let r1 = ClipRoute.plan(kind: "text", size: 100, mode: "both", wanted: all, ble: ["phone", "tab"], wifi: ["phone", "tab"], maxBle: 65536)
    check("route: text over Bluetooth only when Bluetooth reaches", r1.ble == all && r1.wifi.isEmpty)
    let r2 = ClipRoute.plan(kind: "text", size: 100, mode: "both", wanted: all, ble: ["phone"], wifi: ["phone", "tab"], maxBle: 65536)
    check("route: text over Wi-Fi to a device Bluetooth cannot reach", r2.ble == ["phone"] && r2.wifi == ["tab"])
    let r3 = ClipRoute.plan(kind: "image", size: 20000, mode: "both", wanted: all, ble: ["phone", "tab"], wifi: ["phone"], maxBle: 65536)
    check("route: image over Wi-Fi, Bluetooth only without a Wi-Fi route", r3.wifi == ["phone"] && r3.ble == ["tab"])
    let r4 = ClipRoute.plan(kind: "image", size: 900000, mode: "both", wanted: all, ble: ["phone", "tab"], wifi: [], maxBle: 65536)
    check("route: a large image never goes over Bluetooth", r4.ble.isEmpty && r4.wifi.isEmpty)
    let r5 = ClipRoute.plan(kind: "text", size: 100, mode: "wifi", wanted: all, ble: all, wifi: all, maxBle: 65536)
    check("route: Wi-Fi mode never uses Bluetooth", r5.ble.isEmpty && r5.wifi == all)
    let bh = BleHealth(), bt0 = Date()
    _ = bh.report("tab", ok: false, pairing: false, now: bt0)
    check("ble health: one failure does not rest the device", bh.usable("tab", now: bt0))
    _ = bh.report("tab", ok: false, pairing: false, now: bt0)
    check("ble health: two failures rest it 2 min", !bh.usable("tab", now: bt0.addingTimeInterval(60)) && bh.usable("tab", now: bt0.addingTimeInterval(121)))
    _ = bh.report("phone", ok: false, pairing: true, now: bt0)
    check("ble health: a pairing refusal stops tries for 6 hours", !bh.usable("phone", now: bt0.addingTimeInterval(5 * 3600)) && bh.needsPairing("phone"))
    check("ble health: works again after success", bh.report("tab", ok: true, pairing: false, now: bt0) != nil && bh.usable("tab", now: bt0))
    check("ble health: a device that worked counts as paired", bh.knownGood("tab") && !bh.knownGood("phone"))
    bh.paired("phone")
    check("ble health: Bluetooth from a device clears its pairing note", bh.knownGood("phone") && !bh.needsPairing("phone") && bh.usable("phone", now: bt0))
    let lr = LogRepeat(), lt = Date()
    check("log: a failure line is written once, then dropped for 5 min",
          lr.gate("clip: Bluetooth send to tab failed (x 12)", now: lt) != nil && lr.gate("clip: Bluetooth send to tab failed (x 13)", now: lt.addingTimeInterval(5)) == nil
          && lr.gate("sm: idle -> ownerIdle", now: lt) != nil)
    check("log: the next one after 5 min carries the count",
          lr.gate("clip: Bluetooth send to tab failed (x 14)", now: lt.addingTimeInterval(301))?.contains("and 1 more") == true)
    var sc = Config(secret: "k")
    SettingsSync.apply("clipTransport", "wifi", to: &sc); SettingsSync.apply("clipTransport", "bad", to: &sc)
    check("settings: values checked", sc.clipTransport == "wifi")
    check("settings round trip", SettingsSync.decode(SettingsSync.encode(sc, stamps: ["clipTransport": 7])).keys.sorted() == ["clipTransport"])
    let recHex = "000102030405060708090a0b1e589965a78e7f55c48da2ac5359585d3fe780ec850d3fcbd019"
    let rec = Data((0 ..< recHex.count / 2).map { i -> UInt8 in
        let h = recHex
        let idx = h.index(h.startIndex, offsetBy: i * 2)
        return UInt8(h[idx ..< h.index(idx, offsetBy: 2)], radix: 16)!
    })
    check("file record vector", FileStream.open(rec, secret: "test-secret", id: "f1", index: 0) == Data("hello file".utf8)
          && FileStream.open(rec, secret: "test-secret", id: "f1", index: 1) == nil && FileStream.open(rec, secret: "test-secret", id: "f2", index: 0) == nil)
    check("safe file names", FileStream.safeName("../../etc/passwd") == "passwd" && FileStream.safeName("...") == "file")
    check("device names", DeviceNames.valid("phone2") && DeviceNames.valid("work-mac") && !DeviceNames.valid("Phone")
          && !DeviceNames.valid("a b") && !DeviceNames.valid("-x") && !DeviceNames.valid(String(repeating: "a", count: 21)))
    check("tamper rejected", Proto.decode(String(data: wire, encoding: .utf8)!.replacingOccurrences(of: "\"playing\":0", with: "\"playing\":1").data(using: .utf8)!, secret: "k", nowMs: now) == nil)

    func beacon(_ status: UInt8) -> Data {
        Data([0x4C, 0x00, 0x07, 0x19, 0x01, 0x24, 0x20, status, 0x9A, 0x8F, 0x10, 0x00, 0x04])
    }
    let v2 = Status(dev: "mac", ts: 1700000000000, seq: 7, playing: true, ear: .both, owner: false, call: true)
    check("canonical v2", Proto.canonical(v2) == "2|mac|1700000000000|7|1|both|0|1")
    check("hmac v2 vector", Proto.sign("test-secret", Proto.canonical(v2)) == "8c0b895c231d9329540318f0d015d6cc3d2e1007950972ce05ef20bf82ba2b8f")
    let v2wire = Proto.encode(Status(dev: "phone", ts: now, seq: 3, playing: false, ear: .both, owner: false, call: true), secret: "k")!
    check("call flag roundtrip", Proto.decode(v2wire, secret: "k", nowMs: now)?.call == true)
    check("v1 packet still accepted", Proto.decode(Data("{\"v\":1}".utf8), secret: "k", nowMs: now) == nil)
    let bleMin = BlePayload.minute(1700000000000)
    let bleBytes = BlePayload.encode(v2, secret: "test-secret", minute: bleMin)!
    check("ble vector", bleBytes.map { String(format: "%02x", $0) }.joined() == "ea0000071707e997e90831cf5fb05515")
    check("ble roundtrip", BlePayload.decode(bleBytes, secret: "test-secret", nowMinute: bleMin)?.call == true)
    check("ble wrong secret", BlePayload.decode(bleBytes, secret: "x", nowMinute: bleMin) == nil)
    check("ble recent accepted", BlePayload.decode(bleBytes, secret: "test-secret", nowMinute: bleMin + 2) != nil)
    check("ble replay refused", BlePayload.decode(bleBytes, secret: "test-secret", nowMinute: bleMin + 3) == nil)
    var tampered = bleBytes; tampered[15] ^= 1
    check("ble stamp is signed", BlePayload.decode(tampered, secret: "test-secret", nowMinute: bleMin) == nil)
    var v3 = v2; v3.prof = "buds"
    check("canonical v3", Proto.canonical(v3, v: 3) == "3|mac|1700000000000|7|1|both|0|1|buds")
    check("hmac v3 vector", Proto.sign("test-secret", Proto.canonical(v3, v: 3)) == "e0b6ceded3c4002c40a606404586e70634245c77a4f5aff28eedb1352866659d")
    let v3wire = Proto.encode(Status(dev: "phone", ts: now, seq: 4, playing: false, ear: .both, owner: true, prof: "buds"), secret: "k")!
    check("profile roundtrip", Proto.decode(v3wire, secret: "k", nowMs: now)?.prof == "buds")
    let ble3 = BlePayload.encode(v3, secret: "test-secret", minute: bleMin)!
    check("ble v3 vector", ble3.map { String(format: "%02x", $0) }.joined() == "ea0000077752983dac7ce48782b05515")
    check("ble profile roundtrip", BlePayload.decode(ble3, secret: "test-secret", nowMinute: bleMin)?.prof == "buds")
    check("ble old payload has no profile", BlePayload.decode(bleBytes, secret: "test-secret", nowMinute: bleMin)?.prof == "")
    check("order newer wifi copy taken", PeerOrder.accept(lastSeq: 10, seq: 11, ble: false) == (true, 11))
    check("order duplicate not taken", !PeerOrder.accept(lastSeq: 10, seq: 10, ble: false).take)
    check("order late copy not taken", !PeerOrder.accept(lastSeq: 10, seq: 8, ble: false).take)
    check("order bluetooth widened", PeerOrder.accept(lastSeq: 0x10003, seq: 5, ble: true) == (true, 0x10005))
    check("order older bluetooth not taken", !PeerOrder.accept(lastSeq: 0x10003, seq: 2, ble: true).take)
    check("order bluetooth across wrap", PeerOrder.accept(lastSeq: 0x1FFFF, seq: 1, ble: true) == (true, 0x20001))
    check("order restarted device taken", PeerOrder.accept(lastSeq: 5000, seq: 3, ble: false) == (true, 3))
    check("order first copy taken", PeerOrder.accept(lastSeq: nil, seq: 42, ble: true) == (true, 42))
    // A stale Bluetooth copy arriving after a newer Wi-Fi one must not undo it.
    let mc = Controller(cfg: Config(secret: "k"), blue: BlueUtil(path: "/none", address: ""))
    mc.receive(Status(dev: "phone", ts: now, seq: 70000, playing: true, ear: .both, owner: false), via: "wifi")
    mc.receive(Status(dev: "phone", ts: now, seq: 70000 & 0xFFFF - 3, playing: false, ear: .both, owner: false), via: "ble")
    check("stale bluetooth copy ignored", mc.peerInfos().first?.playing == true)
    mc.receive(Status(dev: "phone", ts: now, seq: (70000 + 2) & 0xFFFF, playing: false, ear: .both, owner: false), via: "ble")
    check("newer bluetooth copy taken", mc.peerInfos().first?.playing == false)
    check("ble seq wrap", BlePayload.isNewer(1, than: 0xFFFF) && !BlePayload.isNewer(0xFFFF, than: 1))
    var qc = Config(secret: "k"); qc.quietEnabled = true; qc.quietStartMinutes = 22 * 60; qc.quietEndMinutes = 7 * 60
    func at(_ h: Int, _ m: Int) -> Date { Calendar.current.date(bySettingHour: h, minute: m, second: 0, of: Date())! }
    check("quiet wraps midnight", AutoGate.reason(qc, now: at(23, 30)) != nil && AutoGate.reason(qc, now: at(3, 0)) != nil)
    check("quiet ends", AutoGate.reason(qc, now: at(12, 0)) == nil && AutoGate.reason(qc, now: at(7, 0)) == nil)
    qc.quietEnabled = false
    check("quiet off", AutoGate.reason(qc, now: at(23, 30)) == nil)
    func bframe(_ id: UInt8, _ p: [UInt8]) -> [UInt8] {
        let size = 1 + p.count + 2
        return [0xFD, UInt8(size & 0xFF), UInt8(size >> 8), id] + p + [0, 0, 0xDD]
    }
    func bext(_ pl: UInt8) -> [UInt8] { var p = [UInt8](repeating: 0, count: 40); p[6] = pl; return p }
    func bstate(_ id: UInt8, _ p: [UInt8]) -> BudsProtocol.State {
        var f = bframe(id, p); var st = BudsProtocol.State()
        for m in BudsProtocol.extract(&f) { BudsProtocol.apply(m, &st) }
        return st
    }
    var bb: [UInt8] = bframe(0x61, bext(0x11)); let bm = BudsProtocol.extract(&bb)
    check("buds frame", bm.count == 1 && bb.isEmpty)
    check("buds both", bstate(0x61, bext(0x11)).ear == .both)
    check("buds one", bstate(0x61, bext(0x12)).ear == .one && bstate(0x61, bext(0x21)).ear == .one)
    check("buds none", bstate(0x61, bext(0x22)).ear == Ear.none)
    check("buds case is none", bstate(0x61, bext(0x34)).ear == Ear.none)
    var part = Array(bframe(0x61, bext(0x11)).prefix(6)); check("buds partial", BudsProtocol.extract(&part).isEmpty)
    check("buds crc check value", BudsProtocol.crc16(Array("123456789".utf8)) == 0x31C3)
    var enc = BudsProtocol.noiseCommand("anc")
    let encMsgs = BudsProtocol.extract(&enc)
    check("buds encode roundtrip", encMsgs.count == 1 && encMsgs[0].id == BudsProtocol.idNoise && encMsgs[0].payload == [1])
    var full = [UInt8](repeating: 0, count: 40)
    full[0] = 5; full[2] = 80; full[3] = 70; full[6] = 0x12; full[7] = 55; full[9] = 3; full[10] = 0x8F; full[12] = 2; full[19] = 0; full[35] = 1
    let fs = bstate(0x61, full)
    check("buds full status", fs.noise == "ambient" && fs.eq == "dynamic" && fs.battL == 80 && fs.battR == 70 && fs.battCase == 55
          && fs.touchOn == true && fs.seamless == true && fs.spatial == true && fs.ear == .one && fs.placeR == "idle")
    var as1 = BudsProtocol.State()
    BudsProtocol.apply(BudsProtocol.Msg(id: BudsProtocol.idAck, payload: [UInt8(BudsProtocol.idEqualizer), 4]), &as1)
    BudsProtocol.apply(BudsProtocol.Msg(id: BudsProtocol.idNoiseUpdate, payload: [1]), &as1)
    check("buds ack and noise update", as1.eq == "clear" && as1.noise == "anc")
    var fsafe = BudsProtocol.State()
    fsafe.placeL = "wearing"; fsafe.placeR = "wearing"
    check("find refuses both worn", BudsProtocol.findRefusal(fsafe) == "Both Buds are in your ears")
    fsafe.placeL = "case"; fsafe.placeR = "case"
    check("find allowed in case", BudsProtocol.findRefusal(fsafe) == nil)
    fsafe.placeL = "case"; fsafe.placeR = "wearing"
    check("find mutes worn bud", BudsProtocol.findRefusal(fsafe) == nil && BudsProtocol.mustMute(fsafe).r && !BudsProtocol.mustMute(fsafe).l)
    var tcb = BudsProtocol.touchCommand(true, BudsProtocol.State())
    let tc = BudsProtocol.extract(&tcb)
    check("buds touch command", tc.count == 1 && tc[0].payload == [1, 1, 1, 1, 1, 0, 0])
    check("beacon both", Beacon.parse(beacon(0x0B))?.ear == .both)
    check("beacon one", Beacon.parse(beacon(0x03))?.ear == .one)
    check("beacon none", Beacon.parse(beacon(0x01))?.ear == .some(.none))
    check("beacon model", Beacon.parse(beacon(0x0B))?.model == 0x2024)
    check("beacon case level", Beacon.parse(Data([0x4C, 0x00, 0x07, 0x19, 0x01, 0x24, 0x20, 0x0B, 0x9A, 0x85, 0x10, 0x00, 0x04]))?.caseLevel == 5)
    check("beacon case unknown", Beacon.parse(beacon(0x0B))?.caseLevel == nil)
    check("beacon battery", Beacon.parse(beacon(0x0B))?.budA == 9 && Beacon.parse(beacon(0x0B))?.budB == 10)
    check("beacon rejects short", Beacon.parse(Data([0x4C, 0x00, 0x07])) == nil)
    check("weaker pair nearby is ignored", Beacon.pick([(2, -45), (0, -70)]) == 2)
    check("own second address still counts", Beacon.pick([(2, -45), (1, -50)]) == 1)
    check("no sources gives no reading", Beacon.pick([]) == nil)

    let c = Controller(cfg: Config(secret: "k"), blue: BlueUtil(path: "/none", address: ""))
    c.receive(fresh)
    c.receive(Status(dev: "tab", ts: now, seq: 1, playing: true, ear: .both, owner: false)) // replay
    check("replay ignored", c.peerInfos().first?.playing == false)
    c.receive(Status(dev: "tab", ts: now + 1000, seq: 2, playing: true, ear: .both, owner: false))
    check("newer accepted", c.peerInfos().first?.playing == true)

    // Call priority: a peer in a call makes this Mac (the owner) yield.
    var cfgCall = Config(secret: "k"); cfgCall.deviceName = "mac"
    let cc = Controller(cfg: cfgCall, blue: BlueUtil(path: "/none", address: ""))
    var yielded = false
    cc.onEvent = { if $0.kind == .release { yielded = true } }
    cc.receive(Status(dev: "phone", ts: now, seq: 1, playing: false, ear: .both, owner: false, call: true))
    cc.tick(playing: false, ear: EarReading(ear: .both, source: "test", confidence: .high), owner: true, inCall: false)
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    check("call peer makes owner release", yielded)

    // Device kinds: the hello message round trip, guesses from names, and Apple's spelling of iPhone.
    let hello = Hello.decode(Hello.encode(kind: .windows, app: "windows", version: "1.4.0"))
    check("hello round trip", hello?.kind == .windows && hello?.app == "windows")
    check("hello refuses junk", Hello.decode(Data("{\"type\":\"toaster\"}".utf8)) == nil && Hello.decode(Data("x".utf8)) == nil)
    check("kind guesses", DeviceKind.guess("phone2") == .phone && DeviceKind.guess("tab") == .tablet && DeviceKind.guess("work-pc") == .windows
          && DeviceKind.guess("iphone") == .iphone && DeviceKind.guess("mac-mini") == .mac && DeviceKind.guess("kitchen") == .other)
    check("pretty names", DeviceName.pretty("iphone-2") == "iPhone-2" && DeviceName.pretty("tab") == "Tablet" && DeviceName.pretty("work-pc") == "Work-pc")
    runHandoffSimTests(check)
    // Fix 6: the same timelines with the real connect and disconnect times seen on the Buds (7 s and 1.5 s).
    HandoffSim.defaultConnect = 7; HandoffSim.defaultDisconnect = 1.5
    runHandoffSimTests { name, ok in check("real timing: " + name, ok) }
    HandoffSim.defaultConnect = 1.5; HandoffSim.defaultDisconnect = 0.5

    // Clipboard: encrypted frame, wrong secret, tampering, chunks out of order.
    let clipItem = ClipItem(id: "0011223344556677", dev: "mac", kind: "text", mime: "text/plain", ts: 1700000000000, data: Data("hello EarHop".utf8))
    let clipFrame = ClipCodec.encode(clipItem, secret: "test-secret", nonce: Data(0 ..< 12))!
    check("clip roundtrip", ClipCodec.decode(clipFrame, secret: "test-secret").map { String(data: $0.data, encoding: .utf8) == "hello EarHop" && $0.id == "0011223344556677" } == true)
    // A frame encrypted by the Android app (printed by ClipCodecTest.printAndroidVector).
    let androidHex = "45484331000102030405060708090a0b765f8e2baccb6f1e92ca8c15cd9b54707dbfebaee2d2b733ca6147f74e7301c066ef4645451e0d295719f773cbfdff89df58072a1683548a75e16904a903226984dbe42346cdcac2d11b30f3c220036152bba9ec65820b3ea9ee210521ada68b68b697fc13a5312d0fda7ac7e62fbaf3862fce7382946c5fce347a00ed13e7fa021a4a"
    let androidFrame = Data(stride(from: 0, to: androidHex.count, by: 2).map { i -> UInt8 in
        UInt8(androidHex[androidHex.index(androidHex.startIndex, offsetBy: i) ..< androidHex.index(androidHex.startIndex, offsetBy: i + 2)], radix: 16)! })
    check("clip reads the Android frame", ClipCodec.decode(androidFrame, secret: "test-secret").map { String(data: $0.data, encoding: .utf8) == "hi from Android" && $0.dev == "phone" } == true)
    check("clip wrong secret refused", ClipCodec.decode(clipFrame, secret: "other") == nil)
    var clipBad = clipFrame; clipBad[clipBad.count - 20] ^= 1
    check("clip tampering refused", ClipCodec.decode(clipBad, secret: "test-secret") == nil)
    let big = ClipCodec.encode(ClipItem(id: "a", dev: "mac", kind: "text", mime: "text/plain", ts: 1, data: Data(repeating: 65, count: 5000)), secret: "k")!
    let chunks = ClipChunks.split(big, payload: 172, tag: 7)
    let asm = ClipChunks.Assembler()
    var assembled: Data?
    for c in chunks.reversed() { if let w = asm.add(c, from: "x") { assembled = w } }
    // Wi-Fi transport over loopback on a spare port: the frame must arrive whole.
    let loop = ClipTcp(port: 47499)
    var gotFrame: Data?
    loop.onFrame = { gotFrame = $0 }
    loop.start()
    RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    loop.send(big, to: "127.0.0.1")
    let until = Date().addingTimeInterval(3)
    while gotFrame == nil && Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    check("clip Wi-Fi loopback", gotFrame == big)
    check("clip chunks reassemble out of order", assembled == big && chunks.count == (big.count + 171) / 172)

    // Calls: microphone alone (dictation) is not a call; microphone and speaker together for 2 s is.
    var cd = CallDetector()
    let t0 = Date(timeIntervalSince1970: 1000)
    var dictation = false
    for i in 0 ..< 40 { dictation = dictation || cd.update(now: t0.addingTimeInterval(Double(i) * 0.25), mic: true, output: false) }
    check("microphone alone is not a call", !dictation)
    var cd2 = CallDetector()
    _ = cd2.update(now: t0, mic: true, output: true)
    let early = cd2.update(now: t0.addingTimeInterval(1), mic: true, output: true)
    let started = cd2.update(now: t0.addingTimeInterval(2.1), mic: true, output: true)
    let silentMoment = cd2.update(now: t0.addingTimeInterval(3), mic: true, output: false)
    _ = cd2.update(now: t0.addingTimeInterval(4), mic: false, output: false)
    let ended = !cd2.update(now: t0.addingTimeInterval(6.1), mic: false, output: false)
    check("mic and speaker for 2 s is a call", !early && started && silentMoment && ended)

    // Galaxy Buds frame fixtures: checksums, malformed lengths, truncation (synthetic frames, documented layout).
    func ext(_ place: UInt8) -> [UInt8] { var p = [UInt8](repeating: 0, count: 13); p[0] = 5; p[2] = 80; p[3] = 70; p[6] = place; return p }
    func zeroCrc(_ id: Int, _ p: [UInt8]) -> [UInt8] { var f = BudsProtocol.encode(id, p); f[f.count - 3] = 0; f[f.count - 2] = 0; return f }
    let crcA = BudsProtocol.CrcStats()
    var fa = BudsProtocol.encode(0x61, ext(0x11))
    check("buds good checksum counted", BudsProtocol.extract(&fa, crc: crcA).count == 1 && crcA.good == 1 && crcA.enforcing)
    let crcB = BudsProtocol.CrcStats()
    var fb = zeroCrc(0x61, ext(0x22))
    check("buds bad checksum kept before verified", BudsProtocol.extract(&fb, crc: crcB).count == 1 && crcB.bad == 1)
    var fc = BudsProtocol.encode(0x61, ext(0x11)); _ = BudsProtocol.extract(&fc, crc: crcB)
    var fd = zeroCrc(0x61, ext(0x22)) + BudsProtocol.encode(0x61, ext(0x12))
    let md = BudsProtocol.extract(&fd, crc: crcB)
    var sd = BudsProtocol.State(); if let m = md.first { BudsProtocol.apply(m, &sd) }
    check("buds bad checksum dropped once verified", md.count == 1 && sd.ear == .one)
    var fe = BudsProtocol.encode(0x61, ext(0x11)); fe.swapAt(fe.count - 3, fe.count - 2)
    let crcE = BudsProtocol.CrcStats()
    check("buds big endian checksum accepted", BudsProtocol.extract(&fe, crc: crcE).count == 1 && crcE.good == 1)
    var ff: [UInt8] = [0xFD, 0xFF, 0x03, 0x61] + BudsProtocol.encode(0x61, ext(0x11))
    check("buds oversized length is junk", BudsProtocol.extract(&ff, crc: BudsProtocol.CrcStats()).count == 1 && ff.isEmpty)
    let whole = BudsProtocol.encode(0x61, ext(0x11))
    var fg = Array(whole.dropLast())
    let g0 = BudsProtocol.extract(&fg).count
    fg.append(whole.last!)
    check("buds truncated frame waits", g0 == 0 && BudsProtocol.extract(&fg).count == 1)

    // Packet security: bad HMAC, stale and replayed packets.
    let sec = Proto.encode(Status(dev: "phone", ts: now, seq: 5, playing: true, ear: .both, owner: false), secret: "k")!
    check("bad hmac rejected", Proto.decode(sec, secret: "other", nowMs: now) == nil)
    check("v4 packet from a clock behind decodes", Proto.decode(sec, secret: "k", nowMs: now - 31000) != nil)
    let rc = Controller(cfg: Config(secret: "k"), blue: BlueUtil(path: "/none", address: ""))
    rc.receive(Status(dev: "phone", ts: now, seq: 5, playing: false, ear: .both, owner: false))
    rc.receive(Status(dev: "phone", ts: now - 500, seq: 4, playing: true, ear: .both, owner: false))
    check("older sequence ignored", rc.peerInfos().first?.playing == false)
    check("wrong secret is reported", Proto.rejection(sec, secret: "other", nowMs: now).map { $0.dev == "phone" && $0.reason == "secret" } == true)
    check("v4 clock never reported as wrong secret", Proto.decode(sec, secret: "k", nowMs: now + 60000) != nil)
    check("junk is not reported", Proto.rejection(Data("hello".utf8), secret: "k", nowMs: now) == nil)

    // Speaker fallback: a start with the earbuds out goes to the speakers, a bud back in returns it; nothing else moves.
    func ears(_ e: Ear) -> EarReading { EarReading(ear: e, source: "buds-rfcomm", confidence: .high) }
    var sp = SpeakerPolicy()
    var st = Date()
    func spStep(_ e: Ear, _ playing: Bool, owner: Bool = true, enabled: Bool = true, call: Bool = false, dt: Double = 0.25) -> SpeakerPolicy.Action? {
        st = st.addingTimeInterval(dt)
        return sp.update(now: st, enabled: enabled, owner: owner, ear: ears(e), playing: playing, call: call)
    }
    for _ in 0 ..< 8 { _ = spStep(.both, true) }
    check("speaker: playing when taken out is not moved before the pause", (0 ..< 8).allSatisfy { _ in spStep(.none, true) == nil })
    var sp2 = SpeakerPolicy()
    var t2 = Date()
    for _ in 0 ..< 4 { t2 += 0.25; _ = sp2.update(now: t2, enabled: true, owner: true, ear: ears(.both), playing: true, call: false) }
    t2 += 0.25; let first = sp2.update(now: t2, enabled: true, owner: true, ear: ears(.none), playing: true, call: false, earPaused: true)
    t2 += 1.2; let later = sp2.update(now: t2, enabled: true, owner: true, ear: ears(.none), playing: true, call: false, earPaused: true)
    check("speaker: after the ear pause the output moves while silent", first == nil && later == .toSpeaker)
    for _ in 0 ..< 8 { _ = spStep(.none, false) }
    check("speaker: start with buds out goes to speakers", spStep(.none, true) == .toSpeaker)
    sp.routed(.toSpeaker, ok: true)
    check("speaker: stays while out", spStep(.none, true) == nil && sp.onSpeaker)
    check("speaker: bud back in returns", spStep(.one, true) == .toEarbuds)
    sp.routed(.toEarbuds, ok: true)
    check("speaker: back on earbuds", !sp.onSpeaker && spStep(.both, true) == nil)
    sp = SpeakerPolicy()
    for _ in 0 ..< 8 { _ = spStep(.none, false) }
    check("speaker: off does nothing", spStep(.none, true, enabled: false) == nil)
    _ = spStep(.none, false)
    check("speaker: calls do nothing", spStep(.none, true, call: true) == nil)
    _ = spStep(.none, false)
    check("speaker: other device's earbuds do nothing", spStep(.none, true, owner: false) == nil)

    print(failed.isEmpty ? "selftest OK" : "selftest FAILED: \(failed.joined(separator: ", "))")
    exit(failed.isEmpty ? 0 : 1)
}

/// Prints the shared clipboard test vector (used by the Android unit test): --clip-vector
if CommandLine.arguments.contains("--clip-vector") {
    let item = ClipItem(id: "0011223344556677", dev: "mac", kind: "text", mime: "text/plain", ts: 1700000000000, data: Data("hello EarHop".utf8))
    print(ClipCodec.encode(item, secret: "test-secret", nonce: Data(0 ..< 12))!.map { String(format: "%02x", $0) }.joined())
    exit(0)
}

/// Renders the popover with sample data to PNG files, to check the layout without a menu bar: --snapshot <folder>
if let i = CommandLine.arguments.firstIndex(of: "--snapshot"), i + 1 < CommandLine.arguments.count {
    Log.toFile = false
    Config.readOnly = true
    let dir = URL(fileURLWithPath: CommandLine.arguments[i + 1])
    NSApplication.shared.setActivationPolicy(.accessory)
    func shot(_ name: String, dark: Bool, _ setup: (AppState) -> Void) {
        var c = Config(secret: "x"); c.liveMode = true; c.budsAddress = "x"; c.airpodsAddress = "x"
        let st = AppState(cfg: c)
        setup(st)
        let view = PopoverView(state: st).environment(\.colorScheme, dark ? .dark : .light)
            .background(dark ? Color.black : Color(white: 0.93))
        // Glass and native controls only draw inside a real window, so show one briefly and capture just that window.
        let host = NSHostingView(rootView: view)
        let size = host.fittingSize
        let w = NSWindow(contentRect: NSRect(x: 40, y: 40, width: size.width, height: size.height), styleMask: [.borderless], backing: .buffered, defer: false)
        w.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        w.contentView = host
        w.level = .floating
        w.orderFrontRegardless()
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))
        let out = dir.appendingPathComponent("\(name).png")
        if let img = CGWindowListCreateImage(.null, .optionIncludingWindow, CGWindowID(w.windowNumber), [.boundsIgnoreFraming, .bestResolution]) {
            try? NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])?.write(to: out)
        } else { print("capture failed for \(name)") }
        w.orderOut(nil)
    }
    let peers = [Controller.PeerInfo(name: "phone", playing: false, ear: .both, owner: false, call: false, age: 0, via: "wifi"),
                 Controller.PeerInfo(name: "tab", playing: false, ear: .unknown, owner: false, call: false, age: 1, via: "wifi")]
    let ev = [HandoffEvent(date: Date(), kind: .claim, text: "Buds connected (playing here)", dry: false),
              HandoffEvent(date: Date().addingTimeInterval(-60), kind: .pause, text: "pause (earbud removed)", dry: false)]
    for dark in [false, true] {
        let tag = dark ? "dark" : "light"
        shot("owner-buds-\(tag)", dark: dark) { s in
            s.cfg.earbuds = "buds"; s.owner = true; s.playing = true; s.ear = .both; s.phase = .ownerPlaying
            s.budsStatus = "connected"; s.buds.battL = 82; s.buds.battR = 79; s.buds.battCase = 45; s.buds.noise = "anc"
            s.peers = peers; s.history = ev; s.earReading = EarReading(ear: .both, source: "buds-rfcomm", confidence: .high)
            s.bleLive = true; s.wifiLive = true
            s.clipBlePeers = 2; s.clipWifiPeers = 2
            s.clipRecent = [ClipRecent(sent: false, dev: "Phone", kind: "text", text: "Meeting moved to 4 pm, room 2B"),
                            ClipRecent(sent: true, dev: "Phone, Tablet", kind: "text", text: "https://example.com/notes"),
                            ClipRecent(sent: false, dev: "Tablet", kind: "image")]
        }
        shot("switching-airpods-\(tag)", dark: dark) { s in
            s.playing = true; s.ear = .both; s.phase = .claimPending; s.stage = "Waiting for phone to let go"; s.holder = "phone"
            s.battery = (90, 80); s.caseBattery = 60
            s.peers = [Controller.PeerInfo(name: "phone", playing: false, ear: .both, owner: true, call: false, age: 0, via: "wifi")]
        }
        shot("held-\(tag)", dark: dark) { s in
            s.playing = true; s.holder = "phone"; s.phase = .playingWithoutEarbuds; s.holdReason = "phone is still playing"
            s.lastFailure = nil; s.safeMode = true
            s.cfg.transport = "ble"; s.bleLive = false
            s.peers = [Controller.PeerInfo(name: "phone", playing: true, ear: .both, owner: true, call: false, age: 0, via: "wifi")]
        }
    }
    // Many devices of every kind: the popover grid and every Settings page.
    func sample(_ s: AppState) {
        s.cfg.earbuds = "buds"; s.owner = true; s.playing = true; s.phase = .ownerPlaying; s.budsStatus = "off"
        s.cfg.knownDevices = ["work-pc", "iphone", "linux-box", "tab2"]
        s.cfg.deviceKinds = ["work-pc": "windows", "iphone": "iphone", "linux-box": "linux", "tab2": "tablet", "phone": "phone", "tab": "tablet"]
        s.cfg.priority = ["mac", "tab", "phone", "work-pc"]
        DeviceName.update(s.cfg)
        s.peers = [Controller.PeerInfo(name: "phone", playing: true, ear: .both, owner: false, call: false, age: 0, via: "ble"),
                   Controller.PeerInfo(name: "tab", playing: false, ear: .unknown, owner: false, call: false, age: 1, via: "ble"),
                   Controller.PeerInfo(name: "work-pc", playing: false, ear: .unknown, owner: false, call: false, age: 2, via: "wifi"),
                   Controller.PeerInfo(name: "iphone", playing: false, ear: .unknown, owner: false, call: true, age: 0, via: "wifi")]
        s.status.devices = ["phone", "tab", "work-pc", "iphone", "linux-box"].map {
            LinkStatus.Device(name: $0, lastHeard: Date().addingTimeInterval($0 == "linux-box" ? -900 : -1), via: "ble", bleAt: Date(), wifiHosts: [], clockOffsetMs: 300)
        }
        s.history = ev
    }
    for dark in [false, true] { shot("many-devices-\(dark ? "dark" : "light")", dark: dark, sample) }
    for page in SettingsPage.allCases {
        var c = Config(secret: "0123456789abcdef0123456789abcdef"); c.liveMode = true; c.budsAddress = "aa-bb-cc-dd-ee-ff"
        let st = AppState(cfg: c)
        sample(st)
        st.settingsPageRequest = page
        let w = NSWindow(contentRect: NSRect(x: 60, y: 60, width: 860, height: 620),
                         styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        w.title = page.rawValue
        w.toolbar = NSToolbar(identifier: "snap")
        w.toolbarStyle = .unified
        w.titlebarAppearsTransparent = true
        w.appearance = NSAppearance(named: .darkAqua)
        w.contentViewController = NSHostingController(rootView: SettingsView(state: st))
        w.setContentSize(NSSize(width: 860, height: 620))
        w.orderFrontRegardless()
        RunLoop.current.run(until: Date().addingTimeInterval(1.2))
        if let img = CGWindowListCreateImage(.null, .optionIncludingWindow, CGWindowID(w.windowNumber), [.boundsIgnoreFraming, .bestResolution]) {
            try? NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])?.write(to: dir.appendingPathComponent("settings-\(page.rawValue.lowercased()).png"))
        }
        w.orderOut(nil)
    }
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
