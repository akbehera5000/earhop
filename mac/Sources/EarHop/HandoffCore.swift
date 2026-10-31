import Foundation

/// How much an ear reading can be trusted. Only medium or high readings may pause or resume playback.
enum EarConfidence: String { case high, medium, low }

/// One ear reading plus where it came from: buds-rfcomm, airpods-beacon, airpods-direct, last-known, assumed or none.
struct EarReading: Equatable {
    var ear: Ear
    var source: String
    var confidence: EarConfidence
    static let unknown = EarReading(ear: .unknown, source: "none", confidence: .low)
    var trusted: Bool { confidence != .low && ear != .unknown }
    var summary: String { "\(ear.rawValue)/\(source)/\(confidence.rawValue)" }
}

/// The explicit handoff states. Exactly one is current after every tick.
enum HandoffPhase: String {
    case idle, ownerIdle, playingWithoutEarbuds, claimPending, ownerPlaying, ownerPausedByEarRemoval
    case releasePending, lostOwnership, callPriority, safeMode

    var label: String {
        switch self {
        case .idle: return "Ready"
        case .ownerIdle: return "Connected, paused"
        case .playingWithoutEarbuds: return "Playing without earbuds"
        case .claimPending: return "Switching here"
        case .ownerPlaying: return "Playing"
        case .ownerPausedByEarRemoval: return "Paused, earbud out"
        case .releasePending: return "Letting go"
        case .lostOwnership: return "Earbuds moved away"
        case .callPriority: return "Call priority"
        case .safeMode: return "Safe mode"
        }
    }
}

enum HandoffAction: Equatable {
    case claim(reason: String, manual: Bool)
    case release(reason: String)
    case pause(reason: String)
    case resume(reason: String)
    case info(String)
    case safeMode(Bool)
}

struct HandoffPeer {
    var name: String
    var playing: Bool
    var ear: Ear
    var owner: Bool
    var call: Bool
    var seenAt: Date
    var playingSince: Date?
    var lastPlayingAt: Date?
}

struct HandoffSettings {
    var deviceName = "mac"
    var priority = ["mac", "tab", "phone"]
    var live = true
    var cooldown: TimeInterval = 2
    var minPlay: TimeInterval = 1
    var quiet: TimeInterval = 2
    var releaseAfterOut: TimeInterval = 120
    var resumeWithin: TimeInterval = 120
    var pauseWhenOneOut = true
    var safeModeAfter = 3
    /// Safe mode ends by itself after this long, or earlier when any device has the earbuds connected.
    var safeModeSeconds: TimeInterval = 120
    /// When false, an unknown ear state counts as "in" for claims (Android without a working ear source).
    var requireEar = true
    /// The Mac treats a busy microphone as a call only while it also plays; Android knows real call states.
    var callNeedsPlaying = true
}

struct HandoffInput {
    var now: Date
    var playing: Bool
    var ear: EarReading
    var owner: Bool
    var inCall: Bool
    var peers: [HandoffPeer]
    /// Why automatic claims are held right now (quiet hours, wrong Wi-Fi), or nil.
    var gate: String?
}

/// Pause and resume on ear removal, the same rules as Android's EarPolicy. Only trusted readings count.
struct EarPolicy {
    enum Action: Equatable { case none, pause, removedAlreadyStopped, removedNotPlaying, resume, alreadyPlaying, expired }

    var lastKnown: Ear = .unknown
    var lastKnownAt = Date.distantPast
    var lastPlayingAt = Date.distantPast
    var pausedAt: Date?
    var pausedFromNone = true
    var pausedByKey = false

    var waiting: Bool { pausedAt != nil }
    mutating func clear() { pausedAt = nil }

    static func isOut(_ e: Ear, pauseOnOne: Bool) -> Bool { e == .none || (pauseOnOne && e == .one) }

    mutating func update(now: Date, reading: EarReading, playing: Bool, owner: Bool, pauseOnOne: Bool, resumeWithin: TimeInterval) -> Action {
        if playing { lastPlayingAt = now }
        let ear = reading.trusted ? reading.ear : .unknown
        let was = lastKnown, wasAt = lastKnownAt
        if ear != .unknown { lastKnown = ear; lastKnownAt = now }
        guard owner else { return .none }
        let out = { EarPolicy.isOut($0, pauseOnOne: pauseOnOne) }
        // Both buds out always counts as a removal, even if the last reading was a late "one".
        let becameNone = ear == .none && was != .none && was != .unknown
        if was != .unknown, ear != .unknown, now.timeIntervalSince(wasAt) < 30,
           (!out(was) && out(ear)) || (becameNone && pausedAt == nil) {
            let playedRecently = playing || now.timeIntervalSince(lastPlayingAt) < 6
            guard playedRecently else { return .removedNotPlaying }
            pausedAt = now
            pausedFromNone = ear == .none
            pausedByKey = playing
            return playing ? .pause : .removedAlreadyStopped
        }
        if let at = pausedAt {
            if now.timeIntervalSince(at) > resumeWithin { pausedAt = nil; return .expired }
            if ear == .none { pausedFromNone = true }
            // Paused with both out: one bud back is enough. Paused with one out: wait for both.
            let back = pausedFromNone ? (ear == .one || ear == .both) : ear == .both
            if back {
                pausedAt = nil
                // After our own pause, players such as Music keep their output open, so "playing" stays true for as
                // long as they are paused. Trust our pause until the buds are back: the resume is an explicit play
                // command, never a toggle, so it cannot stop audio that the user started meanwhile.
                let trustKey = pausedByKey
                pausedByKey = false
                return (!playing || trustKey) ? .resume : .alreadyPlaying
            }
        }
        return .none
    }
}

/// The handoff rule as a pure state machine: no Bluetooth, no keys, no timers. The app feeds it one snapshot
/// per tick and carries out the actions it returns, so every timeline can be replayed in tests.
/// Newest playback wins; priority only breaks a near tie; calls override; a device that let go or lost the
/// earbuds may not take them back until its own audio has really stopped.
final class HandoffCore {
    enum Pending: String { case claim, release }

    var settings: HandoffSettings
    private(set) var phase: HandoffPhase = .idle
    private(set) var pending: Pending?
    private(set) var safeMode = false
    private(set) var failures = 0
    /// Why this device is not taking the earbuds while it plays, or nil.
    private(set) var holdReason: String?
    private(set) var claimBlocked = false
    private(set) var ears = EarPolicy()

    private var playingSince: Date?
    private var lastLocalPlayingAt = Date.distantPast
    private var earOutSince: Date?
    private var lastHandoff = Date.distantPast
    private var prevOwner = false
    private var blockedAt = Date.distantPast
    private var lastPauseAt = Date.distantPast
    private var lastReleaseAt = Date.distantPast
    private var lostAt = Date.distantPast
    /// When this device last became the owner. An idle owner only lets go for playback that started after this.
    private var ownerSince: Date?
    private var yieldedForCall: String?
    private var gateLoggedAt = Date.distantPast
    private var inCallPhase = false

    init(settings: HandoffSettings) { self.settings = settings }

    private func prio(_ n: String) -> Int { settings.priority.firstIndex(of: n) ?? Int.max }

    private func earIn(_ r: EarReading) -> Bool {
        r.ear == .both || r.ear == .one || (!settings.requireEar && r.ear == .unknown)
    }

    /// After a failed claim the wait doubles (2, 4, 8 s) so a failing claim cannot loop quickly.
    private var cooldownNow: TimeInterval { settings.cooldown * pow(2, Double(min(failures, 3))) }

    private func block(_ now: Date) { claimBlocked = true; blockedAt = now }

    private func startClaim(_ reason: String, manual: Bool, _ now: Date) -> [HandoffAction] {
        guard pending == nil else { return [] }
        lastHandoff = now
        if settings.live { pending = .claim }
        return [.claim(reason: reason, manual: manual)]
    }

    private func startRelease(_ reason: String, _ now: Date) -> [HandoffAction] {
        guard pending == nil else { return [] }
        lastHandoff = now
        if settings.live { pending = .release; block(now); lastReleaseAt = now }
        return [.release(reason: reason)]
    }

    private func pause(_ reason: String, _ now: Date) -> HandoffAction {
        lastPauseAt = now
        return .pause(reason: reason)
    }

    /// A pause before letting go, skipped when EarHop paused a moment ago (a second toggle would restart the audio).
    private func pauseBeforeRelease(_ reason: String, _ now: Date) -> [HandoffAction] {
        if now.timeIntervalSince(lastPauseAt) < 6 { return [.info("already paused by EarHop, letting go without a second key")] }
        return [pause(reason, now)]
    }

    // MARK: results from the app

    func manualClaim(now: Date) -> [HandoffAction] { startClaim("manual", manual: true, now) }
    func manualRelease(now: Date) -> [HandoffAction] { startRelease("manual", now) }

    private var safeSince = Date.distantPast

    func claimFinished(ok: Bool, manual: Bool, failure: String?, now: Date) -> [HandoffAction] {
        if pending == .claim { pending = nil }
        lastHandoff = now
        if ok {
            failures = 0
            claimBlocked = false
            if manual && safeMode { safeMode = false; return [.safeMode(false)] }
            return []
        }
        failures += 1
        var out: [HandoffAction] = [.info("Claim failed (\(failures) in a row): \(failure ?? "the earbuds did not connect")")]
        if failures >= settings.safeModeAfter && !manual && !safeMode {
            safeMode = true
            safeSince = now
            out.append(.safeMode(true))
        }
        return out
    }

    func releaseFinished(now: Date) {
        if pending == .release { pending = nil }
        lastHandoff = now
        lastReleaseAt = now
    }

    func clearSafeMode() { safeMode = false; failures = 0 }

    /// What this device tells the others. Right after it let go or lost the earbuds its audio is stale (still fading,
    /// or moving to the speakers), so it does not announce "playing": the others must not take that as a new start.
    func advertisedPlaying(_ playing: Bool) -> Bool { playing && !claimBlocked }

    // MARK: tick

    func tick(_ i: HandoffInput) -> [HandoffAction] {
        let now = i.now
        var out: [HandoffAction] = []
        defer {
            prevOwner = i.owner
            phase = computePhase(i)
        }

        // Read before this tick updates it: the output can drop out for a moment when the earbuds leave.
        let playedJustNow = i.playing || now.timeIntervalSince(lastLocalPlayingAt) < 1.5
        // Unblock only after a real stop: quiet for 2 s, counted from at least 4 s after the block, so the short
        // flicker while the sound moves from the earbuds to the speakers does not count as a new start.
        if claimBlocked, !i.playing, now.timeIntervalSince(lastLocalPlayingAt) >= 2, now.timeIntervalSince(blockedAt) >= 4 {
            claimBlocked = false
        }
        if i.playing { lastLocalPlayingAt = now; if playingSince == nil { playingSince = now } } else { playingSince = nil }
        if i.ear.ear == .none { if earOutSince == nil { earOutSince = now } } else { earOutSince = nil }
        if i.owner { if ownerSince == nil { ownerSince = now } } else { ownerSince = nil }
        let fresh = i.peers.filter { now.timeIntervalSince($0.seenAt) < 5 }
        // Safe mode is a pause, not a stop: it ends after safeModeSeconds, or as soon as some device has the earbuds,
        // which shows they work again (they were often just in the case while a call tried to take them).
        if safeMode, now.timeIntervalSince(safeSince) >= settings.safeModeSeconds || i.owner || fresh.contains(where: { $0.owner }) {
            safeMode = false
            failures = 0
            out.append(.safeMode(false))
        }
        let cooled = now.timeIntervalSince(lastHandoff) >= cooldownNow
        inCallPhase = false

        // A call anywhere gets the earbuds. The device with the call has priority.
        if let cp = fresh.first(where: { $0.call }), !i.inCall {
            inCallPhase = true
            ears.clear()
            holdReason = "call on \(cp.name)"
            if i.owner, yieldedForCall != cp.name, pending == nil {
                yieldedForCall = cp.name
                if i.playing { out += pauseBeforeRelease("call on \(cp.name)", now) }
                out += startRelease("call on \(cp.name)", now)
            }
            return out
        }
        yieldedForCall = nil
        if i.inCall {
            inCallPhase = true
            ears.clear()
            holdReason = nil
            if !i.owner, i.playing || !settings.callNeedsPlaying, cooled, pending == nil, !safeMode, !claimBlocked, i.gate == nil, earIn(i.ear) {
                out += startClaim("call here", manual: false, now)
            }
            return out
        }

        // Ear removal pause and resume.
        switch ears.update(now: now, reading: i.ear, playing: i.playing, owner: i.owner,
                           pauseOnOne: settings.pauseWhenOneOut, resumeWithin: settings.resumeWithin) {
        case .pause: out.append(pause("earbud removed (\(i.ear.summary))", now))
        case .removedAlreadyStopped: out.append(.info("earbud removed, playback already stopped, will resume when back"))
        case .resume: out.append(.resume(reason: "earbuds back in (\(i.ear.summary))"))
        case .alreadyPlaying: out.append(.info("earbuds back in, audio is already playing"))
        case .expired: out.append(.info("resume window expired"))
        case .removedNotPlaying, .none: break
        }

        // Lost the earbuds without asking for it (another device took them, Quick Connect, out of range).
        if prevOwner && !i.owner {
            lostAt = now
            // Play/Pause is a toggle on the Mac. After our own pause or release the audio is already paused and the
            // output only looks busy; a second key would start the music again on the speakers.
            let ours = pending == .release || now.timeIntervalSince(lastReleaseAt) < 15
            let pausedRecently = now.timeIntervalSince(lastPauseAt) < 6
            // The output often drops out for a moment as the sound moves to the speakers, so "playing a moment ago" counts.
            if playedJustNow && !ours && !pausedRecently {
                out.append(pause("lost the earbuds", now))
            } else if playedJustNow {
                out.append(.info("earbuds left, audio was already paused by EarHop, no second key"))
            }
            block(now)
        }

        // The current owner blocks a claim only while it still plays and started after us, or played a moment ago.
        let ownerBusy = fresh.first { p in
            guard p.owner else { return false }
            if p.playing {
                guard let theirs = p.playingSince, let mine = playingSince else { return true }
                return !(mine > theirs.addingTimeInterval(1))
            }
            if let l = p.lastPlayingAt, now.timeIntervalSince(l) < settings.quiet { return true }
            return false
        }
        // Priority only breaks a near tie (both started within 2.5 s). Otherwise the newest playback wins.
        let outranker = fresh.first { p in
            guard !p.owner, p.playing, p.ear == .both || p.ear == .one, prio(p.name) < prio(settings.deviceName),
                  let theirs = p.playingSince, let mine = playingSince else { return false }
            return abs(theirs.timeIntervalSince(mine)) <= 2.5
        }
        // Reliability over speed: the newcomer must have played steadily for the full minimum before the owner yields.
        let relNeed = settings.minPlay
        let playedLongEnough = playingSince.map { now.timeIntervalSince($0) >= settings.minPlay } ?? false

        holdReason = nil
        if !i.owner && i.playing && pending == nil {
            if safeMode { holdReason = "safe mode after repeated failures" }
            else if claimBlocked { holdReason = "just let go, waiting for this device's audio to stop" }
            else if let p = ownerBusy { holdReason = "\(p.name) is still playing" }
            else if let p = outranker { holdReason = "\(p.name) has priority" }
            else if !earIn(i.ear) { holdReason = "earbuds are not in your ears" }
            else if let g = i.gate { holdReason = "held: \(g)" }
            else if !cooled { holdReason = "cooling down after the last switch" }
            else if !playedLongEnough { holdReason = "waiting for playback to settle" }
        }

        if !i.owner, i.playing, cooled, pending == nil, !safeMode, !claimBlocked, ownerBusy == nil, outranker == nil,
           earIn(i.ear), playedLongEnough {
            if let g = i.gate {
                if now.timeIntervalSince(gateLoggedAt) > 60 { gateLoggedAt = now; out.append(.info("auto claim held: \(g)")) }
            } else {
                out += startClaim("playing here", manual: false, now)
            }
        } else if i.owner, cooled, pending == nil, let eo = earOutSince, now.timeIntervalSince(eo) >= settings.releaseAfterOut {
            out += startRelease("out of ears", now)
        } else if i.owner, cooled, pending == nil, i.playing, let mine = playingSince,
                  let taker = fresh.first(where: { p in
                      !p.owner && p.playing && p.ear != .none
                          && (p.playingSince.map { now.timeIntervalSince($0) >= relNeed && $0 > mine.addingTimeInterval(1)
                              && $0 > (ownerSince ?? .distantPast) } ?? false)
                  }) {
            // Another device started after this one: pause first, then let go, so the two never stream together.
            out += pauseBeforeRelease("\(taker.name) started playing", now)
            out += startRelease("\(taker.name) started playing", now)
        } else if i.owner, cooled, pending == nil, !i.playing, now.timeIntervalSince(lastLocalPlayingAt) >= settings.quiet,
                  let taker = fresh.first(where: { p in
                      guard !p.owner, p.playing, p.ear != .none, let since = p.playingSince else { return false }
                      // Audio that was already playing when this device got the earbuds (Quick Connect, a manual take)
                      // is not a new start, so it does not pull them away.
                      return now.timeIntervalSince(since) >= relNeed && since > (ownerSince ?? .distantPast)
                  }) {
            out += startRelease("\(taker.name) is playing", now)
        }
        return out
    }

    private func computePhase(_ i: HandoffInput) -> HandoffPhase {
        if pending == .claim { return .claimPending }
        if pending == .release { return .releasePending }
        if inCallPhase { return .callPriority }
        if i.owner {
            if ears.waiting { return .ownerPausedByEarRemoval }
            return i.playing ? .ownerPlaying : .ownerIdle
        }
        if safeMode { return .safeMode }
        if i.now.timeIntervalSince(lostAt) < 10 { return .lostOwnership }
        return i.playing ? .playingWithoutEarbuds : .idle
    }

    /// One line describing the inputs, for the transition log.
    func describe(_ i: HandoffInput, profile: String) -> String {
        let peer = i.peers.filter { i.now.timeIntervalSince($0.seenAt) < 5 }.map { p -> String in
            let since = p.playingSince.map { String(format: "%.1fs", i.now.timeIntervalSince($0)) } ?? "-"
            return "\(p.name)(owner=\(p.owner ? 1 : 0) playing=\(p.playing ? 1 : 0) since=\(since))"
        }.joined(separator: ",")
        let playing = i.playing ? 1 : 0, owner = i.owner ? 1 : 0, call = i.inCall ? 1 : 0
        let head = "dev=\(settings.deviceName) profile=\(profile) playing=\(playing) owner=\(owner)"
        return head + " ear=\(i.ear.summary) call=\(call) peers=[\(peer)]"
    }
}
