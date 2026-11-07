package com.earhop.logger

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Out of the ears for 2 minutes lets go of the earbuds, except while speaker fallback plays the sound on the speaker. */
class SpeakerHoldTest {
    private val out = EarReading("none", "buds-rfcomm", "high")

    /** Owner with the buds in, then out at t0; plays for [playMs] after that. Returns ms from t0 to the release. */
    private fun releaseAfter(onSpeaker: Boolean, playMs: Long, limitMs: Long = 600_000): Long? {
        val core = HandoffCore(HandoffSettings(deviceName = "phone"))
        var t = 1_000_000L
        repeat(20) { core.tick(HandoffInput(t, false, EarReading("both", "buds-rfcomm", "high"), true, false, emptyList(), null)); t += 250 }
        val t0 = t
        while (t - t0 < limitMs) {
            val playing = t - t0 < playMs
            val acts = core.tick(HandoffInput(t, playing, out, true, false, emptyList(), null, onSpeaker && t - t0 >= 2000))
            if (acts.any { it is HandoffAction.Release }) return t - t0
            t += 250
        }
        return null
    }

    @Test fun withoutSpeakerLetsGoAfterTwoMinutes() {
        val r = releaseAfter(onSpeaker = false, playMs = 0)
        assertNotNull(r); assertTrue(r!! in 120_000..122_000)
    }

    @Test fun playingOnSpeakerKeepsTheEarbuds() {
        assertNull(releaseAfter(onSpeaker = true, playMs = 600_000, limitMs = 590_000))
    }

    @Test fun speakerQuietForTwoMinutesLetsGo() {
        val r = releaseAfter(onSpeaker = true, playMs = 300_000)
        assertNotNull(r); assertTrue(r!! in 419_000..422_000)
    }
}
