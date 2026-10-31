package com.earhop.logger

/**
 * Pause and resume rules for earbud removal, free of Android classes so they can be unit tested.
 * Pause when the buds go from "in" to "out" while playing, resume once they are back in, within a window.
 * "Out" is none, or one bud too when pauseOnOne is on.
 */
class EarPolicy(
    private val pauseOnOne: () -> Boolean,
    private val resumeWindowMs: Long = 120_000,
    private val recentPlayMs: Long = 6_000,
) {
    enum class Action { NONE, PAUSE, REMOVED_ALREADY_STOPPED, REMOVED_NOT_PLAYING, RESUME, ALREADY_PLAYING, EXPIRED }

    private var lastKnownEar = "unknown"
    private var lastKnownAt = 0L
    private var lastPlayingAt = 0L
    private var pausedAt = 0L
    private var pausedFrom = "none" // worst state seen since the pause: "none" or "one"
    private var pausedByKey = false

    val waitingToResume get() = pausedAt != 0L

    fun isOut(ear: String) = ear == "none" || (pauseOnOne() && ear == "one")

    fun clear() { pausedAt = 0L }

    fun update(now: Long, ear: String, playing: Boolean, owner: Boolean): Action {
        if (playing) lastPlayingAt = now
        val wasEar = lastKnownEar
        val wasAt = lastKnownAt
        if (ear != "unknown") { lastKnownEar = ear; lastKnownAt = now }
        if (!owner) return Action.NONE

        // Both buds out always counts as a removal, even if the beacon was still showing "one" because
        // it reports the second bud late.
        val becameNone = ear == "none" && wasEar != "none" && wasEar != "unknown"
        if (wasEar != "unknown" && now - wasAt < 30_000 && ((!isOut(wasEar) && isOut(ear)) || (becameNone && pausedAt == 0L))) {
            val playedRecently = playing || (lastPlayingAt != 0L && now - lastPlayingAt < recentPlayMs)
            if (!playedRecently) return Action.REMOVED_NOT_PLAYING
            pausedAt = now
            pausedFrom = if (ear == "none") "none" else "one"
            pausedByKey = playing
            return if (playing) Action.PAUSE else Action.REMOVED_ALREADY_STOPPED
        }
        if (pausedAt != 0L) {
            if (now - pausedAt > resumeWindowMs) { pausedAt = 0L; return Action.EXPIRED }
            if (ear == "none") pausedFrom = "none"
            // Paused with both buds out: resume as soon as a bud is back. Paused with one bud out: wait for both.
            val back = if (pausedFrom == "none") (ear == "one" || ear == "both") else ear == "both"
            if (back) {
                // Right after our own pause the player can still look active, so trust the pause for 3 seconds.
                val trustKey = pausedByKey && now - pausedAt < 3000
                pausedAt = 0L
                pausedByKey = false
                return if (!playing || trustKey) Action.RESUME else Action.ALREADY_PLAYING
            }
        }
        return Action.NONE
    }
}

/** Chooses which AirPods beacons count. Pure, so it can be unit tested. */
object BeaconPick {
    /**
     * Your own AirPods are the strongest signal nearby. Only sources within 8 dB of the strongest (smoothed) signal
     * count, so a weaker pair (someone else's) cannot change the reading. Among those the lowest in-ear count wins,
     * because your earbuds can broadcast from more than one address and a removal shows on one first.
     */
    fun pick(sources: List<Pair<Int, Double>>): Int? {
        val top = sources.maxOfOrNull { it.second } ?: return null
        return sources.filter { it.second >= top - 8 }.minOfOrNull { it.first }
    }
}

/**
 * Speaker fallback, free of Android classes so it can be unit tested. With the earbuds connected but out of your ears,
 * audio that you start sends the sound to this device's own speaker; putting a bud back in sends it to the earbuds
 * again, without stopping playback. Only a start while the buds are out counts: audio that was already playing when
 * they came out is paused by EarPolicy, not moved.
 */
class SpeakerPolicy {
    enum class Action { TO_SPEAKER, TO_EARBUDS }

    /** True while EarHop has sent the sound to the speaker. */
    var onSpeaker = false; private set
    private var wasPlaying = false
    private var outSince = 0L

    /** Records what the service did: a route that failed leaves the sound where it was. */
    @Synchronized fun routed(action: Action, ok: Boolean) {
        onSpeaker = when (action) { Action.TO_SPEAKER -> ok; Action.TO_EARBUDS -> !ok && onSpeaker }
    }

    @Synchronized fun update(now: Long, enabled: Boolean, owner: Boolean, ear: EarReading, playing: Boolean, call: Boolean): Action? {
        val out = ear.trusted && ear.ear == "none"
        val inEar = ear.trusted && (ear.ear == "both" || ear.ear == "one")
        if (out) { if (outSince == 0L) outSince = now } else if (ear.ear != "unknown") outSince = 0L
        val started = playing && !wasPlaying
        wasPlaying = playing
        if (onSpeaker) {
            // The earbuds left this device: the sound is on the speaker anyway, nothing to undo.
            if (!owner) { onSpeaker = false; return null }
            return if (inEar || !enabled) Action.TO_EARBUDS else null
        }
        // Out for at least 1.5 s, so the pause after taking them out is never mistaken for a new start.
        return if (enabled && owner && !call && out && started && now - outSince >= 1500) Action.TO_SPEAKER else null
    }
}
