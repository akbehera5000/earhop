package com.earhop.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow my attention: audio started on the device the user is using wins over a busy owner they are not using. */
class AttentionTest {
    private val both = EarReading("both", "buds-rfcomm", "high")

    private fun peer(name: String, owner: Boolean, since: Long, att: Boolean, now: Long) =
        PeerState(name, true, "both", owner, false, "ble", now, since, now, 1, now, "buds", 4, att)

    /** This device plays from t0; the owner [mac] started after it. True when this device claims within 4 s. */
    private fun claims(follow: Boolean, here: Boolean, ownerAtt: Boolean): Boolean {
        val core = HandoffCore(HandoffSettings(deviceName = "phone", followAttention = follow))
        val t0 = 1_000_000L
        var t = t0
        while (t < t0 + 4000) {
            val acts = core.tick(HandoffInput(t, true, both, false, false, listOf(peer("mac", true, t0 + 500, ownerAtt, t)), null, attention = here))
            if (acts.any { it is HandoffAction.Claim }) return true
            t += 250
        }
        return false
    }

    /** This device owns and plays from t0; [tab] started earlier. True when this device lets go within 4 s. */
    private fun yields(follow: Boolean, here: Boolean, theirAtt: Boolean): Boolean {
        val core = HandoffCore(HandoffSettings(deviceName = "phone", followAttention = follow))
        val t0 = 1_000_000L
        var t = t0
        while (t < t0 + 4000) {
            val acts = core.tick(HandoffInput(t, true, both, true, false, listOf(peer("tab", false, t0 - 5000, theirAtt, t)), null, attention = here))
            if (acts.any { it is HandoffAction.Release }) return true
            t += 250
        }
        return false
    }

    @Test fun userHereWinsOverABusyOwner() {
        assertTrue(claims(follow = true, here = true, ownerAtt = false))
        assertFalse(claims(follow = true, here = false, ownerAtt = false)) // not used here: the owner keeps them
        assertFalse(claims(follow = false, here = true, ownerAtt = false)) // the option is off
        assertFalse(claims(follow = true, here = true, ownerAtt = true))   // the user is on the owner too
    }

    @Test fun ownerYieldsWhenTheUserMovedAway() {
        assertTrue(yields(follow = true, here = false, theirAtt = true))
        assertFalse(yields(follow = true, here = true, theirAtt = true))
        assertFalse(yields(follow = false, here = false, theirAtt = true))
        assertFalse(yields(follow = true, here = false, theirAtt = false))
    }
}
