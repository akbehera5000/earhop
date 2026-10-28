import AppKit
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
    var appsAt = Date.distantPast
    var cachedApps: [String]?
    var discovery: Discovery!
    var ble: BleLink!
    let startedAt = Date()
    var probing = false
    let budsWear = BudsWear()
    var clip: ClipSync!
    var clipPolledAt = Date.distantPast
    let pathMonitor = NWPathMonitor()
    var callDetector = CallDetector()
    var networkKind = ""

    func applicationDidFinishLaunching(_ n: Notification) {
        if !state.cfg.onboarded && !state.cfg.airpodsAddress.isEmpty { state.cfg.onboarded = true }
        let cfg = state.cfg
        Log.write("EarHop start dev=\(cfg.deviceName) live=\(cfg.liveMode)")
        // If EarHop was moved (for example from ~/Applications to /Applications), point the login item at this copy.
        if SMAppService.mainApp.status == .enabled {
            do { try SMAppService.mainApp.register() } catch { Log.write("login item: \(error.localizedDescription)") }
        }
        Log.onLine = { [weak self] in self?.state.appendLog($0) }

        controller = Controller(cfg: cfg, blue: BlueUtil(path: cfg.blueutilPath, address: cfg.activeAddress))
        link = Link(port: cfg.port, secret: cfg.secret, selfName: cfg.deviceName)
        link.onStatus = { [weak self] s in DispatchQueue.main.async {
            self?.controller.receive(s)
            self?.state.rejected[s.dev] = nil
        } }
        link.onDuplicate = { [weak self] dev in DispatchQueue.main.async { self?.state.duplicates[dev] = Date() } }
        link.onRejected = { [weak self] dev, reason in DispatchQueue.main.async { self?.state.rejected[dev] = (reason, Date()) } }
        discovery = Discovery(name: cfg.deviceName, port: cfg.port)
        discovery.onChange = { [weak self] n in self?.state.discovered = n }
        link.extraTargets = { [weak self] in self?.discovery.targets ?? [] }
        link.start()
        discovery.start()
        ble = BleLink(secret: cfg.secret, selfName: cfg.deviceName)
        clip = ClipSync(cfg: cfg)
        clip.wifiPeers = { [weak self] in
            guard let self else { return [] }
            return (self.link.peerHostsByDev() + self.discovery.hostsByDev).reduce(into: [(dev: String, host: String)]()) { acc, p in
                if !acc.contains(where: { $0.host == p.host }) { acc.append(p) }
            }
        }
        clip.blePeers = { [weak self] in
            (self?.ble.peerIds.filter { Date().timeIntervalSince($0.value.at) < 60 }.map { (dev: $0.key, id: $0.value.id) }) ?? []
        }
        clip.ble.freshId = { [weak self] dev in self?.ble.peerIds[dev]?.id }
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
            MediaControl.useKeyOnly = c.mediaControl == "key"
            self.updateIcon()
            self.applyAppearance(c)
            self.controller.blue = BlueUtil(path: c.blueutilPath, address: c.activeAddress)
            if c.hotkeyEnabled { HotKey.register() } else { HotKey.unregister() }
            Log.write("settings changed live=\(c.liveMode)")
        }
        state.onTake = { [weak self] in self?.controller.claimNow() }
        state.onRelease = { [weak self] in self?.controller.releaseNow() }
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
            if wantBle && !ble.supported { Log.write("transport: Bluetooth LE needs the device name mac, phone or tab") }
        }
        if mode != "ble" { link.send(status) }
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

    func tick() {
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
        let inCall = state.cfg.detectCalls && callDetector.update(now: Date(), mic: AudioMonitor.isMicInUse(), output: deviceRunning)

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
        controller.tick(playing: playing, ear: reading, owner: owner, inCall: inCall)
        if state.phase != controller.phase { state.phase = controller.phase }
        if state.holdReason != controller.holdReason { state.holdReason = controller.holdReason }
        if state.lastFailure != controller.lastFailure { state.lastFailure = controller.lastFailure }
        if state.earReading != reading { state.earReading = reading }
        let holder = controller.ownerPeer()
        if state.holder != holder { state.holder = holder }
        // Send immediately when something changed, otherwise once a second as a heartbeat.
        let shown = controller.core.advertisedPlaying(playing)
        let key = "\(shown)|\(ear.rawValue)|\(owner)|\(inCall)"
        if key != lastSentKey || Date().timeIntervalSince(lastSendAt) >= 1 {
            lastSentKey = key
            lastSendAt = Date()
            seq += 1
            let status = Status(dev: state.cfg.deviceName, ts: Proto.nowMs(), seq: seq, playing: shown, ear: ear, owner: owner, call: inCall)
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
        updateIcon()
    }

    func updateIcon() {
        let switching = state.stage != nil
        let name = switching ? "arrow.triangle.2.circlepath" : (state.cfg.isBuds ? "earbuds" : "airpodspro")
        let desc = switching ? "EarHop, switching" : (state.owner ? "EarHop, connected here" : "EarHop")
        let img = NSImage(systemSymbolName: name, accessibilityDescription: desc)
        img?.isTemplate = true
        item.button?.image = img
        var title = state.safeMode ? " !" : (state.owner ? " \u{25CF}" : "")
        if state.cfg.menuBarBattery {
            if state.cfg.isBuds {
                let b = state.buds
                let m = [b.battL, b.battR].filter { $0 >= 0 }.min()
                if state.budsConnected, let m { title += " \(m)%" }
            } else if let b = state.battery { title += " \(min(b.0, b.1))%" }
        }
        item.button?.title = title
        item.button?.imagePosition = title.isEmpty ? .imageOnly : .imageLeading
        item.button?.toolTip = state.cfg.liveMode ? "EarHop (live)" : "EarHop (dry run)"
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
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 520),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "EarHop Settings"
            w.titlebarAppearsTransparent = true
            w.contentViewController = NSHostingController(rootView: SettingsView(state: state))
            w.setContentSize(NSSize(width: 760, height: 520))
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
    check("stale rejected", Proto.decode(wire, secret: "k", nowMs: now + 31000) == nil)
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

    runHandoffSimTests(check)

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
    check("future packet rejected", Proto.decode(sec, secret: "k", nowMs: now - 31000) == nil)
    let rc = Controller(cfg: Config(secret: "k"), blue: BlueUtil(path: "/none", address: ""))
    rc.receive(Status(dev: "phone", ts: now, seq: 5, playing: false, ear: .both, owner: false))
    rc.receive(Status(dev: "phone", ts: now - 500, seq: 4, playing: true, ear: .both, owner: false))
    check("older sequence ignored", rc.peerInfos().first?.playing == false)
    check("wrong secret is reported", Proto.rejection(sec, secret: "other", nowMs: now).map { $0.dev == "phone" && $0.reason == "secret" } == true)
    check("clock skew is reported", Proto.rejection(sec, secret: "k", nowMs: now + 60000).map { $0.reason == "clock" } == true)
    check("junk is not reported", Proto.rejection(Data("hello".utf8), secret: "k", nowMs: now) == nil)

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
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
