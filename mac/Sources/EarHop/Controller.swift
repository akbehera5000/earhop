import Foundation

struct HandoffEvent: Identifiable {
    enum Kind: String { case claim, release, pause, resume, peer, safe, info }
    let id = UUID()
    let date: Date
    let kind: Kind
    let text: String
    let dry: Bool
}

/// Runs the HandoffCore decisions on this Mac: Bluetooth connect and release, media keys, events and logging.
/// Decisions are only acted on when liveMode is true; otherwise they are logged as a dry run.
final class Controller {
    struct Peer { var status: Status; var seenAt: Date; var lastPlayingAt: Date?; var playingSince: Date?; var via: String }
    struct PeerInfo: Identifiable {
        let name: String; let playing: Bool; let ear: Ear; let owner: Bool; let call: Bool; let age: Int; let via: String
        var id: String { name }
    }

    var cfg: Config { didSet { core.settings = Controller.settings(cfg) } }
    var blue: BlueUtil
    var onEvent: ((HandoffEvent) -> Void)?
    var onSafeMode: ((Bool) -> Void)?
    /// Switching progress for the UI: a short stage name while a claim or release runs, nil otherwise.
    var onStage: ((String?) -> Void)?
    /// Returns a reason when automatic claiming should hold off (quiet hours, wrong Wi-Fi). Manual actions ignore it.
    var autoGate: () -> String? = { nil }
    /// Runs on the main thread just before a disconnect, so the Galaxy Buds status channel does not hold the link open.
    var beforeRelease: (() -> Void)?

    let core: HandoffCore
    private var peers: [String: Peer] = [:]
    private let lock = NSLock()
    private let actions = DispatchQueue(label: "earhop.actions")
    private var knownPeers = Set<String>()
    private var lastPhase: HandoffPhase?
    private var lastHold: String?
    private(set) var lastClaimKind: HandoffEvent.Kind?
    /// The reason the last switch failed, shown until the next success.
    private(set) var lastFailure: String?

    var safeMode: Bool { core.safeMode }
    var phase: HandoffPhase { core.phase }
    var holdReason: String? { autoOff ? "automatic switching is \(cfg.autoSwitch ? "paused" : "off")" : core.holdReason }
    /// Automatic switching paused until then from the popover (not saved). Take and Release still work.
    var pausedUntil = Date.distantPast
    var autoOff: Bool { !cfg.autoSwitch || pausedUntil > Date() }
    private var wasOff = false

    init(cfg: Config, blue: BlueUtil) {
        self.cfg = cfg
        self.blue = blue
        core = HandoffCore(settings: Controller.settings(cfg))
    }

    static func settings(_ c: Config) -> HandoffSettings {
        var s = HandoffSettings()
        s.deviceName = c.deviceName
        s.priority = c.priority
        s.live = c.liveMode
        s.cooldown = c.cooldownSeconds
        s.minPlay = max(c.debounceSeconds, c.minPlaySeconds)
        s.quiet = c.quietSeconds
        // With speaker fallback the earbuds stay connected while they are out, so putting them back in is noticed and
        // the sound can move back to them; otherwise this Mac lets go of them after releaseAfterOutSeconds out.
        s.releaseAfterOut = c.speakerFallback ? 1e9 : c.releaseAfterOutSeconds
        s.resumeWithin = c.resumeWithinSeconds
        s.pauseWhenOneOut = c.pauseWhenOneOut
        s.safeModeAfter = c.safeModeAfterFailures
        s.requireEar = true
        return s
    }

    private var buds: String { cfg.earbudsShort }

    // MARK: peers

    func receive(_ incoming: Status, via: String = "wifi") {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        // The same status arrives over Bluetooth LE and Wi-Fi, often several times. Only a newer copy changes anything;
        // an older or repeated copy still shows the device is alive.
        let order = PeerOrder.accept(lastSeq: peers[incoming.dev]?.status.seq, seq: incoming.seq, ble: via == "ble")
        guard order.take else { peers[incoming.dev]?.seenAt = now; return }
        var s = incoming
        s.seq = order.seq
        var last = peers[s.dev]?.lastPlayingAt
        var since = peers[s.dev]?.playingSince
        if s.playing { last = now; if since == nil { since = now } } else { since = nil }
        if let old = peers[s.dev]?.status, old.playing != s.playing || old.owner != s.owner || old.ear != s.ear || old.call != s.call {
            Log.write("peer \(s.dev) (\(via)): playing=\(s.playing ? 1 : 0) ear=\(s.ear.rawValue) owner=\(s.owner ? 1 : 0) call=\(s.call ? 1 : 0)")
        }
        peers[s.dev] = Peer(status: s, seenAt: now, lastPlayingAt: last, playingSince: since, via: via)
    }

    func peerInfos() -> [PeerInfo] {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        return peers.values.filter { now.timeIntervalSince($0.seenAt) < 15 }
            .sorted { $0.status.dev < $1.status.dev }
            .map { PeerInfo(name: $0.status.dev, playing: $0.status.playing, ear: $0.status.ear,
                            owner: $0.status.owner, call: $0.status.call, age: Int(now.timeIntervalSince($0.seenAt)), via: $0.via) }
    }

    private func handoffPeers() -> [HandoffPeer] {
        lock.lock(); defer { lock.unlock() }
        return peers.values.map {
            HandoffPeer(name: $0.status.dev, playing: $0.status.playing, ear: $0.status.ear, owner: $0.status.owner,
                        call: $0.status.call, seenAt: $0.seenAt, playingSince: $0.playingSince, lastPlayingAt: $0.lastPlayingAt)
        }
    }

    /// The fresh peer that holds the earbuds, if any.
    /// The earbud profile reported by the device that has the earbuds now, if it says.
    func ownerProfile() -> String? {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        return peers.values.first { $0.status.owner && !$0.status.prof.isEmpty && now.timeIntervalSince($0.seenAt) < 5 }?.status.prof
    }

    func ownerPeer() -> String? {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        return peers.values.first { $0.status.owner && now.timeIntervalSince($0.seenAt) < 5 }?.status.dev
    }

    // MARK: events

    private func emit(_ kind: HandoffEvent.Kind, _ text: String, dry: Bool = false) {
        Log.write("\(dry ? "DRY RUN " : "")\(kind.rawValue): \(text)")
        let e = HandoffEvent(date: Date(), kind: kind, text: text, dry: dry)
        DispatchQueue.main.async { self.onEvent?(e) }
    }

    private func stage(_ s: String?) { DispatchQueue.main.async { self.onStage?(s) } }

    private func setSafe(_ on: Bool) {
        DispatchQueue.main.async { self.onSafeMode?(on) }
        if on { emit(.safe, "Safe mode: auto claiming paused after \(core.failures) failed switches") }
        else { emit(.info, "Auto claiming resumed") }
    }

    func clearSafeMode() { core.clearSafeMode(); setSafe(false) }

    // MARK: actions

    private func run(_ list: [HandoffAction]) {
        for a in list {
            switch a {
            case .claim(let reason, let manual): performClaim(reason: reason, manual: manual)
            case .release(let reason): performRelease(reason: reason)
            case .pause(let why): mediaKey("pause (\(why))", kind: .pause)
            case .resume(let why): mediaKey("resume (\(why))", kind: .resume)
            case .info(let text): emit(.info, text)
            case .safeMode(let on): setSafe(on)
            }
        }
    }

    private func mediaKey(_ why: String, kind: HandoffEvent.Kind) {
        if cfg.liveMode {
            emit(kind, why)
            if kind == .resume { MediaControl.resumePlayback() } else { MediaControl.pausePlayback() }
        } else {
            emit(kind, "would \(why)", dry: true)
        }
    }

    /// Connect with one retry and verification. Runs off the main thread.
    private func performClaim(reason: String, manual: Bool) {
        guard cfg.liveMode else { emit(.claim, "would claim \(buds) (\(reason))", dry: true); return }
        guard blue.available else {
            _ = core.claimFinished(ok: false, manual: manual, failure: "no earbud address set", now: Date())
            emit(.info, "Claim skipped: connect the earbuds to this Mac once, then press Detect")
            return
        }
        let blue = self.blue
        let waits = cfg.isBuds ? 150 : 60 // checks every 0.1 s; Galaxy Buds can take longer to accept a connection
        let name = buds
        emit(.info, "claiming \(name) (\(reason))")
        actions.async {
            var ok = false
            // Connecting while another device still holds the earbuds is slow and often fails, so give it up to 8 s to let go.
            if !manual, let holder = self.ownerPeer() {
                self.stage("Waiting for \(holder) to let go")
                var waited = 0.0
                while waited < 8, self.ownerPeer() != nil, !(blue.fastConnected() ?? false) { Thread.sleep(forTimeInterval: 0.15); waited += 0.15 }
                Log.write("claim: waited \(String(format: "%.1f", waited)) s for \(holder) to let go")
            }
            let started = Date()
            for attempt in 1 ... 2 {
                if blue.fastConnected() ?? false { ok = true; break }
                self.stage("Connecting here")
                // First try inside the app; the second try uses blueutil when it is installed.
                var proc: Process?
                if attempt == 2, blue.hasTool { proc = blue.startConnect() } else { blue.startConnectInApp() }
                for i in 0 ..< waits {
                    Thread.sleep(forTimeInterval: 0.1)
                    if i == 10 { self.stage("Confirming connection") }
                    if blue.fastConnected() ?? false { ok = true; break }
                }
                if ok { break }
                if proc?.isRunning == true { proc?.terminate() }
                if attempt == 1 { self.emit(.info, "Claim did not connect, retrying once") }
            }
            if ok { Log.write("claim: link up \(String(format: "%.1f", Date().timeIntervalSince(started))) s after connect started") }
            let holder = self.ownerPeer()
            DispatchQueue.main.async {
                self.stage(nil)
                let failure = ok ? nil : (holder.map { "\($0) is still connected to the \(name)" }
                    ?? "the \(name) did not accept the connection. Connect them from Bluetooth settings")
                self.lastFailure = failure
                self.run(self.core.claimFinished(ok: ok, manual: manual, failure: failure, now: Date()))
                if ok {
                    self.lastClaimKind = .claim
                    self.emit(.claim, "\(name) connected (\(reason))")
                    self.applyVolume()
                }
            }
        }
    }

    private func performRelease(reason: String) {
        guard cfg.liveMode else { emit(.release, "would release \(buds) (\(reason))", dry: true); return }
        guard blue.available else { core.releaseFinished(now: Date()); return }
        beforeRelease?()
        let blue = self.blue
        let name = buds
        stage("Releasing \(name)")
        actions.async {
            let started = Date()
            blue.disconnect()
            Thread.sleep(forTimeInterval: 0.3)
            let took = Date().timeIntervalSince(started)
            let still = blue.fastConnected() ?? false
            DispatchQueue.main.async {
                self.stage(nil)
                self.core.releaseFinished(now: Date())
                self.lastClaimKind = .release
                if still {
                    self.lastFailure = "the \(name) are still connected to this Mac"
                    self.emit(.info, "release: \(name) still connected after \(String(format: "%.1f", took)) s")
                } else {
                    self.lastFailure = nil
                    self.emit(.release, "\(name) disconnected (\(reason)) in \(String(format: "%.1f", took)) s")
                }
            }
        }
    }

    /// Per-device volume profile: set the output volume when the earbuds arrive.
    private func applyVolume() {
        guard cfg.volumeEnabled else { return }
        let pct = min(100, max(0, cfg.volumePercent))
        DispatchQueue.global().async {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            p.arguments = ["-e", "set volume output volume \(pct)"]
            try? p.run(); p.waitUntilExit()
            Log.write("volume profile: set to \(pct)%")
        }
    }

    func claimNow() { run(core.manualClaim(now: Date())) }
    func releaseNow() { run(core.manualRelease(now: Date())) }

    func undoLast() {
        switch lastClaimKind {
        case .claim: releaseNow()
        case .release: claimNow()
        default: break
        }
    }

    // MARK: tick

    /// Called four times a second on the main thread.
    func tick(playing: Bool, ear: EarReading, owner: Bool, inCall: Bool) {
        let names = Set(peerInfos().map { $0.name })
        for n in names.subtracting(knownPeers) { emit(.peer, "\(n) joined") }
        for n in knownPeers.subtracting(names) { emit(.peer, "\(n) went quiet") }
        knownPeers = names

        // Off or paused: no automatic decisions at all, like the Android app (Take and Release still work).
        if autoOff != wasOff {
            wasOff = autoOff
            Log.write(autoOff ? "handoff: automatic switching \(cfg.autoSwitch ? "paused" : "off")" : "handoff: automatic switching on")
        }
        if autoOff { return }

        let input = HandoffInput(now: Date(), playing: playing, ear: ear, owner: owner, inCall: inCall,
                                 peers: handoffPeers(), gate: autoGate())
        let list = core.tick(input)
        if core.phase != lastPhase || !list.isEmpty {
            let acts = list.map { "\($0)" }.joined(separator: "; ")
            Log.write("sm: \(lastPhase?.rawValue ?? "start") -> \(core.phase.rawValue) \(core.describe(input, profile: cfg.earbuds))"
                + (acts.isEmpty ? "" : " actions=[\(acts)]"))
            lastPhase = core.phase
        }
        if core.holdReason != lastHold {
            if let h = core.holdReason { Log.write("hold: not taking the \(buds): \(h)") }
            lastHold = core.holdReason
        }
        if owner { lastFailure = nil }
        run(list)
    }
}
