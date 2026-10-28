package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoGateTest {
    private val quiet = AutoGate.Rules(quietEnabled = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60)

    @Test fun quietWrapsMidnight() {
        assertEquals("quiet hours", AutoGate.reason(quiet, 23 * 60 + 30, null, false))
        assertEquals("quiet hours", AutoGate.reason(quiet, 3 * 60, null, false))
        assertNull(AutoGate.reason(quiet, 12 * 60, null, false))
        assertNull(AutoGate.reason(quiet, 7 * 60, null, false))
    }

    @Test fun quietSameDayWindow() {
        val r = AutoGate.Rules(quietEnabled = true, quietStartMin = 13 * 60, quietEndMin = 14 * 60)
        assertEquals("quiet hours", AutoGate.reason(r, 13 * 60 + 10, null, false))
        assertNull(AutoGate.reason(r, 14 * 60, null, false))
    }

    @Test fun wifiRules() {
        val r = AutoGate.Rules(wifiOnly = true, wifiNames = setOf("Home"))
        assertNull(AutoGate.reason(r, 0, "Home", false))
        assertNull(AutoGate.reason(r, 0, null, false)) // unknown name never blocks
        assertEquals("not on a listed Wi-Fi (Cafe)", AutoGate.reason(r, 0, "Cafe", false))
        assertNull(AutoGate.reason(AutoGate.Rules(wifiOnly = true), 0, "Cafe", false)) // empty list never blocks
    }

    @Test fun dnd() {
        val r = AutoGate.Rules(respectDnd = true)
        assertEquals("Do Not Disturb is on", AutoGate.reason(r, 0, null, true))
        assertNull(AutoGate.reason(r, 0, null, false))
        assertNull(AutoGate.reason(AutoGate.Rules(), 0, null, true))
    }
}
