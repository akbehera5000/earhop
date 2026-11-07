package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol v4: free names, sender clocks with learned offsets, replay-safe restarts. Vectors match the Mac and Python. */
class ProtocolV4Test {
    private val s = Status("mac", 1700000000000L, 7, true, "both", false, call = true, prof = "buds", att = true)

    @Test fun vectors() {
        assertEquals("4|mac|1700000000000|7|1|both|0|1|buds|1", Proto.canonical(s, 4))
        assertEquals("7ad20a1aac679b7fcd58e5e67214a1531812fdb6e574eeca406f31ef8af8cf0f", Proto.sign("test-secret", Proto.canonical(s, 4)))
        assertEquals(listOf(33190, 36699, 48167), listOf("mac", "phone", "tab").map { DeviceNames.id(it, "test-secret") })
        assertEquals("eb81a60007f728611b1ffa426553f100", BlePayload.encode4(s, "test-secret", 1_700_000_000L).joinToString("") { "%02x".format(it) })
        assertTrue(Proto.selfTest())
    }

    @Test fun bleDecodeAndTamper() {
        val b = BlePayload.encode4(s, "test-secret", 1_700_000_000L)
        val m = BlePayload.decode4(b, "test-secret")!!
        val st = BlePayload.status(m, "mac")
        assertTrue(st.att && st.call && st.prof == "buds" && st.ts == 1_700_000_000_000L && st.seq == 7)
        assertNull(BlePayload.decode4(b, "other"))
        val t = b.copyOf(); t[15] = (t[15].toInt() xor 1).toByte()
        assertNull(BlePayload.decode4(t, "test-secret"))
    }

    // The Wi-Fi JSON packet itself is tested by the Mac self test and tools/earhop_tool.py (org.json is not available
    // in plain unit tests).

    @Test fun clockOffsetsAreLearned() {
        val g = ClockGuard()
        assertTrue(g.check("p", 601_000, 1000, ClockGuard.WIFI_TOLERANCE, 1))          // 10 minutes ahead
        assertTrue(g.check("p", 602_000, 2000, ClockGuard.WIFI_TOLERANCE, 2))
        assertFalse(g.check("p", 482_000, 2000, ClockGuard.BLE_TOLERANCE, 1))         // 2 minute old recording
        assertFalse(g.check("p", 900_000, 3000, ClockGuard.WIFI_TOLERANCE, 3))
        assertFalse(g.check("p", 901_000, 4000, ClockGuard.WIFI_TOLERANCE, 4))
        assertTrue(g.check("p", 902_000, 5000, ClockGuard.WIFI_TOLERANCE, 5))         // clock changed: relearned
        assertTrue(g.fresh("p", 905_000, 6000, 300_000))
        assertFalse(g.fresh("p", 500_000, 6000, 300_000))
    }

    @Test fun replayedCopyDoesNotRelearn() {
        val g = ClockGuard()
        g.check("p", 1000, 1000, ClockGuard.WIFI_TOLERANCE, 1)
        repeat(5) { assertFalse(g.check("p", -200_000, 2000L + it, ClockGuard.WIFI_TOLERANCE, 9)) }
    }

    @Test fun replayIsNotARestart() {
        assertFalse(PeerOrder.accept(1000, 5, false, 50_000, 60_000).first)
        assertTrue(PeerOrder.accept(1000, 5, false, 70_000, 60_000).first)
    }

    @Test fun deviceNames() {
        assertTrue(DeviceNames.valid("phone2") && DeviceNames.valid("work-mac") && DeviceNames.valid("tab"))
        assertFalse(DeviceNames.valid("Phone") || DeviceNames.valid("a b") || DeviceNames.valid("-x") || DeviceNames.valid("a".repeat(21)))
        NameBook.setup("test-secret", listOf("phone2"))
        assertEquals("phone2", NameBook.name(DeviceNames.id("phone2", "test-secret")))
        assertEquals("tab", NameBook.name(48167))
    }
}
