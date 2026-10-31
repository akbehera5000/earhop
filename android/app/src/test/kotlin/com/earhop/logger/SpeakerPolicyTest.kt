package com.earhop.logger

import com.earhop.logger.SpeakerPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerPolicyTest {
    private var t = 1_000_000L
    private fun ear(e: String) = EarReading(e, "buds-rfcomm", "high")
    private fun step(p: SpeakerPolicy, e: String, playing: Boolean, owner: Boolean = true, enabled: Boolean = true,
                     call: Boolean = false, advance: Long = 250): Action? {
        t += advance
        return p.update(t, enabled, owner, ear(e), playing, call)
    }
    private fun settle(p: SpeakerPolicy, e: String, playing: Boolean) { repeat(8) { assertNull(step(p, e, playing)) } }

    @Test fun startWithBudsOutGoesToSpeakerAndBackWhenIn() {
        val p = SpeakerPolicy()
        settle(p, "both", true)
        settle(p, "none", false) // taken out, EarPolicy paused it
        assertEquals(Action.TO_SPEAKER, step(p, "none", true))
        p.routed(Action.TO_SPEAKER, true)
        assertTrue(p.onSpeaker)
        settle(p, "none", true)
        assertEquals(Action.TO_EARBUDS, step(p, "one", true))
        p.routed(Action.TO_EARBUDS, true)
        assertFalse(p.onSpeaker)
        settle(p, "both", true)
    }

    @Test fun audioPlayingWhenTakenOutIsNotMoved() {
        val p = SpeakerPolicy()
        settle(p, "both", true)
        // Still playing for a moment after removal, until the pause lands: never a start, never moved.
        repeat(8) { assertNull(step(p, "none", true)) }
    }

    @Test fun startRightAfterRemovalWaitsForTheBudsToBeOutAMoment() {
        val p = SpeakerPolicy()
        settle(p, "both", false)
        assertNull(step(p, "none", true, advance = 100))
    }

    @Test fun offCallsAndOtherDevicesDoNothing() {
        val off = SpeakerPolicy(); settle(off, "none", false); assertNull(step(off, "none", true, enabled = false))
        val call = SpeakerPolicy(); settle(call, "none", false); assertNull(step(call, "none", true, call = true))
        val away = SpeakerPolicy(); settle(away, "none", false); assertNull(step(away, "none", true, owner = false))
    }

    @Test fun failedRouteLeavesSoundWhereItWas() {
        val p = SpeakerPolicy()
        settle(p, "none", false)
        assertEquals(Action.TO_SPEAKER, step(p, "none", true))
        p.routed(Action.TO_SPEAKER, false)
        assertFalse(p.onSpeaker)
        assertNull(step(p, "both", true))
    }

    @Test fun turningItOffOrLosingTheBudsEndsSpeaker() {
        val p = SpeakerPolicy()
        settle(p, "none", false)
        step(p, "none", true); p.routed(Action.TO_SPEAKER, true)
        assertEquals(Action.TO_EARBUDS, step(p, "none", true, enabled = false))
        val q = SpeakerPolicy()
        settle(q, "none", false)
        step(q, "none", true); q.routed(Action.TO_SPEAKER, true)
        assertNull(step(q, "none", true, owner = false))
        assertFalse(q.onSpeaker)
    }
}
