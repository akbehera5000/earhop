import Foundation

/// A deterministic simulator for HandoffCore: several devices, one pair of earbuds, a fake clock.
/// It models what made real handoffs fail: the Mac's Play/Pause key is a toggle, audio output looks busy for a
/// moment after a pause, disconnects can be slow, and a connect fails while another device still holds the earbuds.
final class HandoffSim {
    final class Dev {
        let name: String
        let core: HandoffCore
        var toggleKeys: Bool
        var playerOn = false
        var staleUntil = Date.distantPast
        var stuckPlaying = false
        /// A player that ignores EarHop's pause (the audio carries on, on the speakers once the earbuds are gone).
        var ignoresPause = false
        /// While audio moves from the earbuds to the speakers the output drops for a moment, then starts again.
        var dropUntil = Date.distantPast
        var ear = EarReading(ear: .both, source: "sim", confidence: .high)
        var inCall = false
        var gate: String?
        var online = true
        var pauses = 0, resumes = 0, claims = 0, releases = 0
        var speakerSeconds = 0.0
        var seen: [String: (since: Date?, last: Date?)] = [:]
        init(_ name: String, toggle: Bool, settings: HandoffSettings) { self.name = name; toggleKeys = toggle; core = HandoffCore(settings: settings) }
    }

    var now = Date(timeIntervalSince1970: 1_700_000_000)
    var holder: String?
    var devs: [Dev] = []
    var connectLatency = 1.5
    var disconnectLatency = 0.5
    private var timers: [(at: Date, run: () -> Void)] = []

    func add(_ name: String, toggle: Bool = false, _ tweak: (inout HandoffSettings) -> Void = { _ in }) -> Dev {
        var s = HandoffSettings()
        s.deviceName = name
        tweak(&s)
        let d = Dev(name, toggle: toggle, settings: s)
        devs.append(d)
        return d
    }

    private var lastHolder: String?

    func playing(_ d: Dev) -> Bool {
        if now < d.dropUntil { return false }
        return d.stuckPlaying || d.playerOn || now < d.staleUntil
    }

    private func stop(_ d: Dev) { if d.playerOn { d.playerOn = false; d.staleUntil = now.addingTimeInterval(2) } }

    func userPlay(_ d: Dev) { d.playerOn = true }
    func userPause(_ d: Dev) { stop(d) }

    private func after(_ s: Double, _ f: @escaping () -> Void) { timers.append((now.addingTimeInterval(s), f)) }

    private func apply(_ d: Dev, _ actions: [HandoffAction]) {
        for a in actions {
            switch a {
            case .pause:
                d.pauses += 1
                if d.ignoresPause { continue }
                if d.toggleKeys && !d.playerOn { d.playerOn = true } else { stop(d) }
            case .resume:
                d.resumes += 1
                if d.toggleKeys && d.playerOn { stop(d) } else { d.playerOn = true }
            case .claim(_, let manual):
                d.claims += 1
                let start = now
                // Like the app: wait up to 8 s for the holder to let go, then connect.
                func attempt() {
                    let holderOnline = devs.first { $0.name == self.holder }?.online ?? false
                    if holder == nil || holder == d.name || !holderOnline || now.timeIntervalSince(start) >= 8 || manual {
                        after(connectLatency) {
                            let ok = self.holder == nil || self.holder == d.name
                            if ok { self.holder = d.name }
                            self.apply(d, d.core.claimFinished(ok: ok, manual: manual, failure: ok ? nil : "held", now: self.now))
                        }
                    } else { after(0.25, attempt) }
                }
                attempt()
            case .release:
                d.releases += 1
                after(disconnectLatency) {
                    if self.holder == d.name { self.holder = nil }
                    d.core.releaseFinished(now: self.now)
                }
            case .info, .safeMode: break
            }
        }
    }

    func step() {
        let due = timers.filter { $0.at <= now }
        timers.removeAll { $0.at <= now }
        due.forEach { $0.run() }
        if holder != lastHolder, let old = devs.first(where: { $0.name == lastHolder }), old.playerOn {
            old.dropUntil = now.addingTimeInterval(0.75)
        }
        lastHolder = holder
        let statuses = devs.map { (d: $0, playing: $0.core.advertisedPlaying(playing($0)), owner: holder == $0.name) }
        for d in devs where d.online {
            var peers: [HandoffPeer] = []
            for s in statuses where s.d !== d && s.d.online {
                var rec = d.seen[s.d.name] ?? (nil, nil)
                if s.playing { rec.last = now; if rec.since == nil { rec.since = now } } else { rec.since = nil }
                d.seen[s.d.name] = rec
                peers.append(HandoffPeer(name: s.d.name, playing: s.playing, ear: s.d.ear.ear, owner: s.owner, call: s.d.inCall,
                                         seenAt: now, playingSince: rec.since, lastPlayingAt: rec.last))
            }
            let input = HandoffInput(now: now, playing: playing(d), ear: d.ear, owner: holder == d.name, inCall: d.inCall, peers: peers, gate: d.gate)
            apply(d, d.core.tick(input))
            if d.playerOn && holder != d.name { d.speakerSeconds += 0.25 }
        }
        now = now.addingTimeInterval(0.25)
    }

    func run(_ seconds: Double) { for _ in 0 ..< Int(seconds * 4) { step() } }
}

/// The handoff timelines from the integration brief, replayed without hardware.
func runHandoffSimTests(_ check: (String, Bool) -> Void) {
    // 1. Mac owns and plays, the phone starts playing.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        w.userPlay(phone); w.run(15)
        check("sim1 phone gets the earbuds", w.holder == "phone")
        check("sim1 mac paused exactly once", mac.pauses == 1 && !mac.playerOn)
        check("sim1 no mac speaker playback", mac.speakerSeconds < 1)
        w.userPause(phone); w.run(30)
        check("sim1 earbuds stay on the phone after its pause", w.holder == "phone" && mac.claims == 0)
    }
    // 2. Phone owns and plays, the Mac starts playing.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "phone"; w.userPlay(phone); w.run(5)
        w.userPlay(mac); w.run(15)
        check("sim2 mac gets the earbuds", w.holder == "mac")
        check("sim2 phone paused", phone.pauses == 1 && !phone.playerOn)
        check("sim2 mac still playing", mac.playerOn)
    }
    // 3. The owner pauses and another device starts within two seconds. The Mac still looks busy for a moment, so
    // EarHop sends a pause; with explicit Pause commands (the default on the Mac) that is harmless.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: false), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        w.userPause(mac); w.run(1); w.userPlay(phone); w.run(15)
        check("sim3 phone gets the earbuds", w.holder == "phone")
        check("sim3 mac stays paused", !mac.playerOn && mac.speakerSeconds < 1)
    }
    // 4. The old owner keeps reporting playing (an app holds audio focus).
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "phone"; w.userPlay(phone); w.run(5)
        phone.stuckPlaying = true
        w.userPlay(mac); w.run(15)
        check("sim4 mac gets the earbuds despite the stuck phone", w.holder == "mac")
        w.run(60)
        check("sim4 stuck phone never takes them back", w.holder == "mac" && phone.claims == 0)
    }
    // 5 and 6. Bluetooth disconnect takes 7 s; the new claim starts while the old owner is still connected.
    do {
        let w = HandoffSim()
        w.disconnectLatency = 7
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        w.userPlay(phone); w.run(25)
        check("sim5 slow disconnect still hands over", w.holder == "phone")
        check("sim5 no failed claim on the phone", phone.core.failures == 0 && !phone.core.safeMode)
        check("sim5 mac paused once", mac.pauses == 1 && !mac.playerOn)
    }
    // 7. The holder disappears from the network and never lets go: the claim must not loop.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; mac.online = false
        w.userPlay(phone); w.run(90)
        check("sim7 failed claims stop in safe mode", phone.core.safeMode && phone.claims == 3)
        _ = phone.core.manualClaim(now: w.now)
        check("sim7 manual take still allowed in safe mode", phone.core.pending == .claim)
    }
    // 7b. Safe mode ends by itself after two minutes (the earbuds were only in the case), so switching resumes.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; mac.online = false
        w.userPlay(phone); w.run(90)
        check("sim7b in safe mode first", phone.core.safeMode)
        w.run(120)
        check("sim7b safe mode ends by itself and claims resume", phone.claims > 3)
    }
    // 7c. Safe mode ends at once when a device has the earbuds again.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; mac.online = false
        w.userPlay(phone); w.run(90)
        mac.online = true
        w.run(3)
        check("sim7c safe mode ends when the earbuds are seen again", !phone.core.safeMode)
    }
    // 9. A call on the phone while the Mac owns the earbuds.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone") { $0.callNeedsPlaying = false }
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        phone.inCall = true; w.run(15)
        check("sim9 the call takes the earbuds", w.holder == "phone")
        check("sim9 mac paused for the call", mac.pauses == 1 && !mac.playerOn)
    }
    // 10. Manual Take while automatic switching is gated.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true)
        mac.gate = "quiet hours"; w.userPlay(mac); w.run(5)
        check("sim10 gate holds automatic claims", mac.claims == 0)
        _ = mac.core.manualClaim(now: w.now)
        check("sim10 manual take works while gated", mac.core.pending == .claim)
    }
    // 11. An earbud comes out at the same moment the phone takes over.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        w.userPlay(phone); w.run(0.25)
        mac.ear.ear = .none; w.run(15)
        check("sim11 single pause during removal and handoff", mac.pauses == 1 && !mac.playerOn)
        check("sim11 phone gets the earbuds", w.holder == "phone")
    }
    // 12. Quick Connect moves the earbuds without EarHop.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), _ = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        w.holder = "phone"; w.run(20)
        check("sim12 mac pauses once when the earbuds vanish", mac.pauses == 1 && !mac.playerOn)
        check("sim12 mac does not grab them back", w.holder == "phone" && mac.claims == 0)
    }
    // Ear removal pause and resume, and low confidence readings never act.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true)
        w.holder = "mac"; w.userPlay(mac); w.run(3)
        mac.ear.ear = .one; w.run(2)
        check("ear pause on one out", mac.pauses == 1 && !mac.playerOn)
        mac.ear.ear = .both; w.run(2)
        check("ear resume when back", mac.resumes == 1 && mac.playerOn)
        mac.ear = EarReading(ear: .none, source: "last-known", confidence: .low); w.run(3)
        check("low confidence reading does not pause", mac.pauses == 1 && mac.playerOn)
        mac.ear = EarReading(ear: .both, source: "assumed", confidence: .low); w.run(3)
        check("low confidence reading does not resume", mac.resumes == 1)
    }
    // A pause right before the earbuds leave must not be followed by a second toggle.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true)
        w.holder = "mac"; w.userPlay(mac); w.run(3)
        mac.ear.ear = .none; w.run(0.5)
        w.holder = nil; w.run(5)
        check("no second pause after losing earbuds", mac.pauses == 1 && !mac.playerOn)
    }
    // The 23:22 ping-pong: the old owner's pause has no effect, so its audio carries on after it lets go. The new owner
    // must keep the earbuds, and the old one must not take them back.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: false), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        mac.ignoresPause = true
        w.userPlay(phone); w.run(30)
        check("pingpong phone keeps the earbuds", w.holder == "phone" && phone.releases == 0)
        check("pingpong mac does not take them back", mac.claims == 0)
    }
    // A flickering peer (on, off, on) never counts as a steady new start.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: false), phone = w.add("phone")
        w.holder = "mac"; w.userPlay(mac); w.run(5)
        for _ in 0 ..< 20 { w.userPlay(phone); w.run(0.5); phone.playerOn = false; phone.staleUntil = .distantPast; w.run(0.5) }
        check("flickering peer does not take the earbuds", w.holder == "mac" && mac.releases == 0)
    }
    // Priority breaks a near tie only.
    do {
        let w = HandoffSim()
        let mac = w.add("mac", toggle: true), phone = w.add("phone")
        w.userPlay(mac); w.userPlay(phone); w.run(10)
        check("tie goes to the higher priority device", w.holder == "mac")
        _ = phone
    }
}
