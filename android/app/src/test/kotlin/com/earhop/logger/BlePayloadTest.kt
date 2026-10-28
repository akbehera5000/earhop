package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The Bluetooth LE fallback payload, with the same vector as the Mac and tools/earhop_tool.py. */
class BlePayloadTest {
    private val s = Status("mac", 1700000000000L, 7, true, "both", false, call = true)
    private val m = BlePayload.minute(1700000000000L)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun matchesSharedVector() {
        assertEquals("ea0000071707e997e90831cf5fb05515", hex(BlePayload.encode(s, "test-secret", m)!!))
    }

    @Test fun roundTripAndWrongSecret() {
        val b = BlePayload.encode(s, "test-secret", m)!!
        assertEquals(true, BlePayload.decode(b, "test-secret", m)!!.call)
        assertNull(BlePayload.decode(b, "x", m))
    }

    @Test fun recordingOlderThanTwoMinutesIsRefused() {
        val b = BlePayload.encode(s, "test-secret", m)!!
        assertNotNull(BlePayload.decode(b, "test-secret", m + 2))
        assertNull(BlePayload.decode(b, "test-secret", m + 3))
    }

    @Test fun minuteStampIsSigned() {
        val b = BlePayload.encode(s, "test-secret", m)!!
        b[15] = (b[15].toInt() xor 1).toByte()
        assertNull(BlePayload.decode(b, "test-secret", m))
    }
}
