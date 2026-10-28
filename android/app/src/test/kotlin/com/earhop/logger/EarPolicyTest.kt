package com.earhop.logger

import com.earhop.logger.EarPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class EarPolicyTest {
    private var t = 1_000_000L
    private fun step(p: EarPolicy, ear: String, playing: Boolean, owner: Boolean = true, advance: Long = 250): Action {
        t += advance
        return p.update(t, ear, playing, owner)
    }
    private fun settle(p: EarPolicy, ear: String, playing: Boolean) { repeat(4) { step(p, ear, playing) } }

    @Test fun oneBudOutPausesAndBothBackResumes() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "one", true))
        assertEquals(Action.NONE, step(p, "one", false))
        assertEquals(Action.RESUME, step(p, "both", false, advance = 20_000))
    }

    @Test fun bothOutThenOneThenOtherLaterStillResumes() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "none", true))
        assertEquals(Action.NONE, step(p, "none", false, advance = 5_000))
        assertEquals(Action.RESUME, step(p, "one", false, advance = 10_000))  // one bud back after both were out: resume
        assertEquals(Action.NONE, step(p, "both", false, advance = 40_000))  // second bud later: nothing more to do
    }

    @Test fun bothOutQuickBothBackResumes() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "none", true))
        assertEquals(Action.RESUME, step(p, "both", false, advance = 2_000))
    }

    @Test fun oneThenNoneOnlyPausesOnce() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "one", true))
        assertEquals(Action.NONE, step(p, "none", false))
        assertEquals(Action.RESUME, step(p, "one", false, advance = 8_000)) // both were out meanwhile, one back is enough
    }

    @Test fun pausedByOneBudWaitsForBoth() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "one", true))
        assertEquals(Action.NONE, step(p, "one", false, advance = 5_000))
        assertEquals(Action.RESUME, step(p, "both", false, advance = 5_000))
    }

    @Test fun oneBudOutIgnoredWhenSettingOff() {
        val p = EarPolicy({ false })
        settle(p, "both", true)
        assertEquals(Action.NONE, step(p, "one", true))
        assertEquals(Action.PAUSE, step(p, "none", true))
    }

    @Test fun beaconGapDoesNotHideRemoval() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.NONE, step(p, "unknown", true, advance = 3_000))
        assertEquals(Action.PAUSE, step(p, "one", true))
    }

    @Test fun playbackStoppedJustBeforeRemovalStillResumes() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        step(p, "both", false, advance = 2_000)
        assertEquals(Action.REMOVED_ALREADY_STOPPED, step(p, "one", false, advance = 500))
        assertEquals(Action.RESUME, step(p, "both", false, advance = 15_000))
    }

    @Test fun nothingPlayingMeansNoResume() {
        val p = EarPolicy({ true })
        settle(p, "both", false)
        step(p, "both", false, advance = 30_000)
        assertEquals(Action.REMOVED_NOT_PLAYING, step(p, "one", false))
        assertEquals(Action.NONE, step(p, "both", false, advance = 5_000))
    }

    @Test fun windowExpires() {
        val p = EarPolicy({ true }, resumeWindowMs = 120_000)
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "none", true))
        assertEquals(Action.EXPIRED, step(p, "both", false, advance = 130_000))
    }

    @Test fun otherDeviceOwnsTheBuds() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.NONE, step(p, "none", true, owner = false))
    }

    @Test fun userStartedPlayingAgainSoNoResume() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "none", true))
        assertEquals(Action.ALREADY_PLAYING, step(p, "both", true, advance = 3_000))
    }

    @Test fun staleOneThenNonePausesWhilePlaying() {
        val p = EarPolicy({ true })
        settle(p, "both", true)
        assertEquals(Action.PAUSE, step(p, "none", true)) // pause on both out
        assertEquals(Action.RESUME, step(p, "one", false, advance = 5_000)) // first bud back: resume
        settle(p, "one", true) // the beacon still says "one" although both buds are in
        assertEquals(Action.PAUSE, step(p, "none", true)) // both removed: must pause
    }

    @Test fun weakerPairNearbyIsIgnored() {
        assertEquals(2, BeaconPick.pick(listOf(Pair(2, -45.0), Pair(0, -70.0))))
        assertEquals(1, BeaconPick.pick(listOf(Pair(2, -45.0), Pair(1, -50.0))))
        assertEquals(null, BeaconPick.pick(emptyList()))
    }
}
