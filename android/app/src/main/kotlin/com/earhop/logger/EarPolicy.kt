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
