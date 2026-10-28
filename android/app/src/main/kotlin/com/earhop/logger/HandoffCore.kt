package com.earhop.logger

/** One ear reading plus where it came from: buds-rfcomm, airpods-beacon, airpods-direct, last-known, assumed or none. */
data class EarReading(val ear: String, val source: String, val confidence: String) {
    /** Only medium or high readings may pause or resume playback. */
    val trusted get() = confidence != "low" && ear != "unknown"
    val summary get() = "$ear/$source/$confidence"
    companion object { val UNKNOWN = EarReading("unknown", "none", "low") }
}

/** The explicit handoff states. Exactly one is current after every tick. Same names as the Mac. */
enum class HandoffPhase(val label: String) {
    IDLE("Ready"), OWNER_IDLE("Connected, paused"), PLAYING_WITHOUT_EARBUDS("Playing without earbuds"),
    CLAIM_PENDING("Switching here"), OWNER_PLAYING("Playing"), OWNER_PAUSED_BY_EAR_REMOVAL("Paused, earbud out"),
    RELEASE_PENDING("Letting go"), LOST_OWNERSHIP("Earbuds moved away"), CALL_PRIORITY("Call priority"), SAFE_MODE("Safe mode"),
}

sealed class HandoffAction {
    data class Claim(val reason: String, val manual: Boolean) : HandoffAction()
    data class Release(val reason: String) : HandoffAction()
    data class Pause(val reason: String) : HandoffAction()
    data class Resume(val reason: String) : HandoffAction()
    data class Info(val text: String) : HandoffAction()
    data class SafeMode(val on: Boolean) : HandoffAction()
}

data class HandoffSettings(
    var deviceName: String = "phone",
    var priority: List<String> = listOf("mac", "tab", "phone"),
    var live: Boolean = true,
    var cooldownMs: Long = 2000,
    var minPlayMs: Long = 1000,
    var quietMs: Long = 2000,
    var releaseAfterOutMs: Long = 120_000,
    var resumeWithinMs: Long = 120_000,
    var pauseWhenOneOut: Boolean = true,
    var safeModeAfter: Int = 3,
    /** When false, an unknown ear state counts as "in" for claims. */
    var requireEar: Boolean = false,
    /** Android knows real call states, so a call claims even when no music plays. */
    var callNeedsPlaying: Boolean = false,
)

class HandoffInput(
    val now: Long, val playing: Boolean, val ear: EarReading, val owner: Boolean, val inCall: Boolean,
    val peers: Collection<PeerState>, val gate: String?,
)

/**
 * The handoff rule as a pure state machine, the same as HandoffCore.swift on the Mac: no Bluetooth, no keys, no
 * threads. The service feeds it one snapshot per tick and carries out the actions it returns, so every timeline can be
 * replayed in unit tests. Newest playback wins; priority only breaks a near tie; calls override; a device that let go
 * or lost the earbuds may not take them back until its own audio has really stopped.
 */
class HandoffCore(var settings: HandoffSettings) {
    enum class Pending { CLAIM, RELEASE }

    var phase = HandoffPhase.IDLE; private set
    var pending: Pending? = null; private set
    var safeMode = false; private set
    var failures = 0; private set
    /** Why this device is not taking the earbuds while it plays, or null. */
    var holdReason: String? = null; private set
    var claimBlocked = false; private set
    val ears = EarPolicy({ settings.pauseWhenOneOut }, settings.resumeWithinMs)

    private var playingSince = 0L
    private var lastLocalPlayingAt = 0L
    private var earOutSince = 0L
    private var lastHandoff = 0L
    private var prevOwner = false
    private var blockedAt = 0L
    private var lastPauseAt = -1_000_000L
    private var lastReleaseAt = -1_000_000L
    private var lostAt = -1_000_000L
    private var ownerSince = 0L
    private var yieldedForCall: String? = null
    private var gateLoggedAt = -1_000_000L
    private var inCallPhase = false

    private fun prio(n: String) = settings.priority.indexOf(n).let { if (it < 0) 99 else it }
    private fun earIn(r: EarReading) = r.ear == "both" || r.ear == "one" || (!settings.requireEar && r.ear == "unknown")

    /** After a failed claim the wait doubles (2, 4, 8 s) so a failing claim cannot loop quickly. */
    private val cooldownNow get() = settings.cooldownMs shl minOf(failures, 3)

    private fun block(now: Long) { claimBlocked = true; blockedAt = now }

    private fun startClaim(reason: String, manual: Boolean, now: Long): List<HandoffAction> {
        if (pending != null) return emptyList()
        lastHandoff = now
        if (settings.live) pending = Pending.CLAIM
        return listOf(HandoffAction.Claim(reason, manual))
    }

    private fun startRelease(reason: String, now: Long): List<HandoffAction> {
        if (pending != null) return emptyList()
        lastHandoff = now
        if (settings.live) { pending = Pending.RELEASE; block(now); lastReleaseAt = now }
        return listOf(HandoffAction.Release(reason))
    }

    private fun pause(reason: String, now: Long): HandoffAction { lastPauseAt = now; return HandoffAction.Pause(reason) }

    /** A pause before letting go, skipped when EarHop paused a moment ago. */
    private fun pauseBeforeRelease(reason: String, now: Long): List<HandoffAction> =
        if (now - lastPauseAt < 6000) listOf(HandoffAction.Info("already paused by EarHop, letting go without a second key"))
        else listOf(pause(reason, now))

    // ---- results from the service ----

    fun manualClaim(now: Long) = startClaim("manual", true, now)
    fun manualRelease(now: Long) = startRelease("manual", now)

    fun claimFinished(ok: Boolean, manual: Boolean, failure: String?, now: Long): List<HandoffAction> {
        if (pending == Pending.CLAIM) pending = null
        lastHandoff = now
        if (ok) {
            failures = 0
            claimBlocked = false
            if (manual && safeMode) { safeMode = false; return listOf(HandoffAction.SafeMode(false)) }
            return emptyList()
        }
        failures++
        val out = mutableListOf<HandoffAction>(HandoffAction.Info("Claim failed ($failures in a row): ${failure ?: "the earbuds did not connect"}"))
        if (failures >= settings.safeModeAfter && !manual && !safeMode) { safeMode = true; out += HandoffAction.SafeMode(true) }
        return out
    }

    fun releaseFinished(now: Long) {
        if (pending == Pending.RELEASE) pending = null
        lastHandoff = now
        lastReleaseAt = now
    }

    fun clearSafeMode() { safeMode = false; failures = 0 }

    /**
     * What this device tells the others. Right after it let go or lost the earbuds its audio is stale, so it does not
     * announce "playing": the others must not take that as a new start.
     */
    fun advertisedPlaying(playing: Boolean) = playing && !claimBlocked

    // ---- tick ----

    fun tick(i: HandoffInput): List<HandoffAction> {
        val now = i.now
        val out = mutableListOf<HandoffAction>()
        try {
            // Read before this tick updates it: the output can drop out for a moment when the earbuds leave.
            val playedJustNow = i.playing || now - lastLocalPlayingAt < 1500
            // Unblock only after a real stop: quiet for 2 s, counted from at least 4 s after the block.
            if (claimBlocked && !i.playing && now - lastLocalPlayingAt >= 2000 && now - blockedAt >= 4000) claimBlocked = false
            if (i.playing) { lastLocalPlayingAt = now; if (playingSince == 0L) playingSince = now } else playingSince = 0L
            if (i.ear.ear == "none") { if (earOutSince == 0L) earOutSince = now } else earOutSince = 0L
            if (i.owner) { if (ownerSince == 0L) ownerSince = now } else ownerSince = 0L
            val fresh = i.peers.filter { now - it.seenAt < 5000 }
            val cooled = now - lastHandoff >= cooldownNow
            inCallPhase = false

            // A call anywhere gets the earbuds. The device with the call has priority.
            val cp = fresh.firstOrNull { it.call }
            if (cp != null && !i.inCall) {
                inCallPhase = true
                ears.clear()
                holdReason = "call on ${cp.name}"
                if (i.owner && yieldedForCall != cp.name && pending == null) {
                    yieldedForCall = cp.name
                    if (i.playing) out += pauseBeforeRelease("call on ${cp.name}", now)
                    out += startRelease("call on ${cp.name}", now)
                }
                return out
            }
            yieldedForCall = null
            if (i.inCall) {
                inCallPhase = true
                ears.clear()
                holdReason = null
                if (!i.owner && (i.playing || !settings.callNeedsPlaying) && cooled && pending == null && !safeMode && !claimBlocked &&
                    i.gate == null && earIn(i.ear)) out += startClaim("call here", false, now)
                return out
            }

            // Ear removal pause and resume. Low confidence readings never act.
            when (ears.update(now, if (i.ear.trusted) i.ear.ear else "unknown", i.playing, i.owner)) {
                EarPolicy.Action.PAUSE -> out += pause("earbud removed (${i.ear.summary})", now)
                EarPolicy.Action.REMOVED_ALREADY_STOPPED -> out += HandoffAction.Info("earbud removed, playback already stopped, will resume when back")
                EarPolicy.Action.RESUME -> out += HandoffAction.Resume("earbuds back in (${i.ear.summary})")
                EarPolicy.Action.ALREADY_PLAYING -> out += HandoffAction.Info("earbuds back in, audio is already playing")
                EarPolicy.Action.EXPIRED -> out += HandoffAction.Info("resume window expired")
                else -> {}
            }

            // Lost the earbuds without asking for it (another device took them, Quick Connect, out of range).
            if (prevOwner && !i.owner) {
                lostAt = now
                val ours = pending == Pending.RELEASE || now - lastReleaseAt < 15000
                val pausedRecently = now - lastPauseAt < 6000
                // The output often drops out for a moment as the sound moves to the speakers, so "playing a moment ago" counts.
                if (playedJustNow && !ours && !pausedRecently) out += pause("lost the earbuds", now)
                else if (playedJustNow) out += HandoffAction.Info("earbuds left, audio was already paused by EarHop")
                block(now)
            }

            // The owner blocks a claim only while it still plays and started after us, or played a moment ago.
            val ownerBusy = fresh.firstOrNull {
                it.owner && if (it.playing) !(it.playingSince != 0L && playingSince != 0L && playingSince > it.playingSince + 1000)
                else now - it.lastPlayingAt < settings.quietMs
            }
            // Priority only breaks a near tie (both started within 2.5 s). Otherwise the newest playback wins.
            val outranker = fresh.firstOrNull {
                !it.owner && it.playing && (it.ear == "both" || it.ear == "one") && prio(it.name) < prio(settings.deviceName) &&
                    it.playingSince != 0L && playingSince != 0L && Math.abs(it.playingSince - playingSince) <= 2500
            }
            // Reliability over speed: the newcomer must have played steadily for the full minimum before the owner yields.
            val relNeed = settings.minPlayMs
            val playedLongEnough = playingSince != 0L && now - playingSince >= settings.minPlayMs

            holdReason = null
            if (!i.owner && i.playing && pending == null) holdReason = when {
                safeMode -> "safe mode after repeated failures"
                claimBlocked -> "just let go, waiting for this device's audio to stop"
                ownerBusy != null -> "${ownerBusy.name} is still playing"
                outranker != null -> "${outranker.name} has priority"
                !earIn(i.ear) -> "earbuds are not in your ears"
                i.gate != null -> "held: ${i.gate}"
                !cooled -> "cooling down after the last switch"
                !playedLongEnough -> "waiting for playback to settle"
                else -> null
            }

            if (!i.owner && i.playing && cooled && pending == null && !safeMode && !claimBlocked && ownerBusy == null &&
                outranker == null && earIn(i.ear) && playedLongEnough) {
                if (i.gate != null) {
                    if (now - gateLoggedAt > 60000) { gateLoggedAt = now; out += HandoffAction.Info("auto claim held: ${i.gate}") }
                } else out += startClaim("playing here", false, now)
            } else if (i.owner && cooled && pending == null && earOutSince != 0L && now - earOutSince >= settings.releaseAfterOutMs) {
                out += startRelease("out of ears", now)
            } else if (i.owner && cooled && pending == null && i.playing && playingSince != 0L) {
                // Another device started after this one: pause first, then let go, so the two never stream together.
                val taker = fresh.firstOrNull {
                    !it.owner && it.playing && it.ear != "none" && it.playingSince != 0L &&
                        now - it.playingSince >= relNeed && it.playingSince > playingSince + 1000 && it.playingSince > ownerSince
                }
                if (taker != null) {
                    out += pauseBeforeRelease("${taker.name} started playing", now)
                    out += startRelease("${taker.name} started playing", now)
                }
            } else if (i.owner && cooled && pending == null && !i.playing && now - lastLocalPlayingAt >= settings.quietMs) {
                // Audio that was already playing when this device got the earbuds is not a new start.
                val taker = fresh.firstOrNull {
                    !it.owner && it.playing && it.ear != "none" && it.playingSince != 0L &&
                        now - it.playingSince >= relNeed && it.playingSince > ownerSince
                }
                if (taker != null) out += startRelease("${taker.name} is playing", now)
            }
            return out
        } finally {
            prevOwner = i.owner
            phase = computePhase(i)
        }
    }

    private fun computePhase(i: HandoffInput): HandoffPhase = when {
        pending == Pending.CLAIM -> HandoffPhase.CLAIM_PENDING
        pending == Pending.RELEASE -> HandoffPhase.RELEASE_PENDING
        inCallPhase -> HandoffPhase.CALL_PRIORITY
        i.owner -> if (ears.waitingToResume) HandoffPhase.OWNER_PAUSED_BY_EAR_REMOVAL else if (i.playing) HandoffPhase.OWNER_PLAYING else HandoffPhase.OWNER_IDLE
        safeMode -> HandoffPhase.SAFE_MODE
        i.now - lostAt < 10000 -> HandoffPhase.LOST_OWNERSHIP
        i.playing -> HandoffPhase.PLAYING_WITHOUT_EARBUDS
        else -> HandoffPhase.IDLE
    }

    /** One line describing the inputs, for the transition log. */
    fun describe(i: HandoffInput, profile: String): String {
        val peers = i.peers.filter { i.now - it.seenAt < 5000 }.joinToString(",") {
            val since = if (it.playingSince != 0L) "%.1fs".format((i.now - it.playingSince) / 1000.0) else "-"
            "${it.name}(owner=${if (it.owner) 1 else 0} playing=${if (it.playing) 1 else 0} since=$since)"
        }
        return "dev=${settings.deviceName} profile=$profile playing=${if (i.playing) 1 else 0} owner=${if (i.owner) 1 else 0} " +
            "ear=${i.ear.summary} call=${if (i.inCall) 1 else 0} peers=[$peers]"
    }
}
