package com.earhop.logger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 3 features: file streams, battery alerts, shared settings. */
class FeaturesTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun fileRecordFromTheMacOpens() {
        // Sealed by CryptoKit on the Mac: "hello file", file id "f1", record 0, nonce 00..0b.
        val rec = hex("000102030405060708090a0b1e589965a78e7f55c48da2ac5359585d3fe780ec850d3fcbd019")
        assertEquals("hello file", String(FileStream.open(rec, "test-secret", "f1", 0)!!))
        assertNull(FileStream.open(rec, "test-secret", "f1", 1))   // another position
        assertNull(FileStream.open(rec, "test-secret", "f2", 0))   // another file
        assertNull(FileStream.open(rec, "other", "f1", 0))
    }

    @Test fun fileStreamRoundTrip() {
        val data = ByteArray(FileStream.CHUNK * 2 + 123) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        FileStream.write(out, byteArrayOf(1, 2, 3), data.inputStream(), "k", "id1")
        val inp = java.io.DataInputStream(out.toByteArray().inputStream())
        assertEquals(FileStream.MAGIC_INT, inp.readInt())
        val hl = inp.readInt(); inp.readFully(ByteArray(hl))
        val got = java.io.ByteArrayOutputStream()
        assertTrue(FileStream.read(inp, got, "k", "id1"))
        assertArrayEquals(data, got.toByteArray())
    }

    @Test fun safeFileNames() {
        assertEquals("passwd", FileStream.safeName("../../etc/passwd"))
        assertEquals("file", FileStream.safeName("..."))
        assertEquals("a-b.txt", FileStream.safeName("a:b.txt"))
    }

    @Test fun batteryAlertsAt20Then10() {
        val a = BatteryAlerts()
        assertNull(a.update("left", 30, false)); assertEquals(20, a.update("left", 20, false))
        assertNull(a.update("left", 19, false)); assertEquals(10, a.update("left", 10, false)); assertNull(a.update("left", 5, false))
        assertNull(a.update("left", 12, true)); assertEquals(20, a.update("left", 18, false))
        val b = BatteryAlerts()
        assertEquals(10, b.update("case", 8, false)); assertNull(b.update("case", -1, false))
    }

    @Test fun sharedSettingsNewestWins() {
        val m = SettingsSync.merge(mapOf("clipSync" to 2000L, "autoSwitch" to 3000L),
            mapOf("clipSync" to Pair(false as Any, 5000L), "autoSwitch" to Pair(true as Any, 1000L), "secret" to Pair("x" as Any, 9000L)), 1000L)
        assertEquals(setOf("clipSync"), m.keys)          // older changes and local-only settings are ignored
        assertEquals(4000L, m["clipSync"]!!.second)     // stamp moved to this device's clock
    }
}
