package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clipboard rules added in 1.3: echo prevention, fresh addresses, Bluetooth recovery and password-like text. */
class ClipRulesTest {
    // ---- echo prevention ----
    @Test fun receivedClipIsNotSentBack() {
        // Labelled EarHop (put there by EarHop), within 3 s of its own write, or the same content as the last received one.
        assertTrue(EchoGuard.skip(true, "EarHop", 100_000, 0, null, 0, 0))
        assertTrue(EchoGuard.skip(true, "EarHop image", 100_000, 0, null, 0, 0))
        assertTrue(EchoGuard.skip(true, "copy", 10_000, 8_000, null, 0, 0))
        assertTrue(EchoGuard.skip(true, null, 100_000, 0, 42, 42, 0))
        assertTrue(EchoGuard.skip(true, null, 100_000, 0, 42, 0, 42))
    }

    @Test fun newCopiesAndManualSendsGo() {
        assertFalse(EchoGuard.skip(true, "copy", 100_000, 0, 7, 42, 43))
        assertFalse(EchoGuard.skip(false, "EarHop", 100, 99, 42, 42, 42))
    }

    // ---- fresh addresses ----
    @Test fun oldAddressesFromOtherNetworksAreNotUsed() {
        val b = AddressBook<String>()
        b.learn("mac", "198.51.100.7", 0)
        b.learn("mac", "192.0.2.40", 3_600_000)
        assertEquals(listOf("192.0.2.40"), b.current("mac", 3_601_000))
    }

    @Test fun onlyTheNewestWhenNoneIsFresh() {
        val b = AddressBook<String>()
        b.learn("phone", "a", 0); b.learn("phone", "b", 1000)
        assertEquals(listOf("b"), b.current("phone", 10_000_000))
    }

    @Test fun freshAddressesNewestFirstAndAtMostFour() {
        val b = AddressBook<String>()
        for (i in 1..6) b.learn("tab", "a$i", i * 1000L)
        assertEquals(listOf("a6", "a5", "a4", "a3"), b.current("tab", 10_000))
        assertTrue(b.learn("tab", "a7", 11_000))
        assertFalse(b.learn("tab", "a7", 12_000))
    }

    // ---- Bluetooth recovery ----
    @Test fun watchdogBacksOffWhileNobodyIsAround() {
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 600_000L, 600_000L), (0..4).map { BleWatchdog.interval(it) })
        assertFalse(BleWatchdog.due(100_000, 0, 0, 0))
        assertTrue(BleWatchdog.due(121_000, 0, 0, 0))
        assertFalse(BleWatchdog.due(500_000, 0, 0, 3))
        assertTrue(BleWatchdog.due(601_000, 0, 0, 3))
    }

    @Test fun hearingADeviceKeepsTheWatchdogQuiet() {
        // Heard 10 s ago: no restart, however long the scan has run.
        assertFalse(BleWatchdog.due(1_000_000, 990_000, 0, 0))
    }

    // ---- password-like text ----
    @Test fun passwordLikeText() {
        assertTrue(PasswordLike.looks("Hunter2!xyz"))
        assertTrue(PasswordLike.looks("aB3dEf9hJ"))
        assertFalse(PasswordLike.looks("hello world 123"))
        assertFalse(PasswordLike.looks("short1A"))
        assertFalse(PasswordLike.looks("https://example.com/A1b2"))
        assertFalse(PasswordLike.looks("Some.One42@example.com"))
        assertFalse(PasswordLike.looks("onlylowercaseletters"))
        assertFalse(PasswordLike.looks("A".repeat(30) + "1" + "b".repeat(40)))
    }

    // ---- delivery receipts ----
    private fun pretty(n: String) = when (n) { "mac" -> "Mac"; "tab" -> "Tablet"; "phone" -> "Phone"; else -> n }

    @Test fun receiptsRetryTheSilentDeviceAndGiveUp() {
        val r = Receipts()
        r.track("c1", setOf("mac", "tab"), 0)
        assertTrue(r.ack("c1", "mac")); assertFalse(r.ack("c1", "mac")); assertFalse(r.ack("c1", "phone"))
        assertTrue(r.due(4_000).isEmpty())
        assertEquals(listOf(Pair("c1", listOf("tab"))), r.due(5_000))
        assertEquals(1, r.due(15_000).size)
        assertTrue(r.due(40_000).isEmpty())
        assertEquals("Delivered to Mac ✓; sending to Tablet", r.words("c1", 10_000, ::pretty))
        assertEquals("Delivered to Mac ✓; not delivered to Tablet", r.words("c1", 61_000, ::pretty))
        assertTrue(r.ack("c1", "tab"))
        assertEquals("Delivered to Mac ✓, Tablet ✓", r.words("c1", 62_000, ::pretty))
    }

    @Test fun recipientFieldRoundTrip() {
        val f = ClipCodec.encode(ClipItem("x", "phone", "ack", "m", 1, "c1".toByteArray(), to = "mac"), "k")!!
        assertEquals("mac", ClipCodec.decode(f, "k")!!.to)
        val g = ClipCodec.encode(ClipItem("y", "phone", "text", "text/plain", 1, "hi".toByteArray()), "k")!!
        assertEquals("", ClipCodec.decode(g, "k")!!.to)
    }

    @Test fun routeTextOverBluetoothImagesOverWifi() {
        val all = setOf("mac", "tab")
        val r1 = ClipRoute.plan("text", 100, "both", all, all, all, 65536)
        assertEquals(all, r1.first); assertEquals(emptySet<String>(), r1.second)
        val r2 = ClipRoute.plan("text", 100, "both", all, setOf("mac"), all, 65536)
        assertEquals(setOf("mac"), r2.first); assertEquals(setOf("tab"), r2.second)
        val r3 = ClipRoute.plan("image", 20000, "both", all, all, setOf("mac"), 65536)
        assertEquals(setOf("tab"), r3.first); assertEquals(setOf("mac"), r3.second)
        val r4 = ClipRoute.plan("image", 900000, "both", all, all, emptySet(), 65536)
        assertTrue(r4.first.isEmpty() && r4.second.isEmpty())
        val r5 = ClipRoute.plan("text", 100, "wifi", all, all, all, 65536)
        assertTrue(r5.first.isEmpty()); assertEquals(all, r5.second)
    }

    @Test fun bleHealthBacksOffAndStopsPairingRequests() {
        val h = BleHealth(); val t = 1_000_000L
        h.report("tab", false, false, t); assertTrue(h.usable("tab", t))
        h.report("tab", false, false, t); assertFalse(h.usable("tab", t + 60_000)); assertTrue(h.usable("tab", t + 121_000))
        h.report("mac", false, true, t); assertFalse(h.usable("mac", t + 5 * 3600_000L)); assertTrue(h.needsPairing("mac"))
        h.paired("mac"); assertTrue(h.usable("mac", t))
        assertNotNull(h.report("tab", true, false, t)); assertTrue(h.usable("tab", t))
    }

    @Test fun androidHourlyClearIsNotACopy() {
        val t = 1_000_000_000L
        assertTrue(ClipRules.isAutoClear(t + 3_600_090L, t))   // 60 min after the last copy, as seen on the tablet
        assertFalse(ClipRules.isAutoClear(t + 120_000L, t))
        assertFalse(ClipRules.isAutoClear(t + 3_600_000L, 0L))
    }

    @Test fun repeatedFailureLinesAreThinnedOut() {
        val r = LogRepeat(); val t = 1_000_000L
        assertNotNull(r.gate("clip: Wi-Fi send to 10.0.0.1 failed after 5000ms", t))
        assertEquals(null, r.gate("clip: Wi-Fi send to 10.0.0.1 failed after 5001ms", t + 5000))
        assertNotNull(r.gate("sm: idle -> owner_idle", t + 5000))
        assertTrue(r.gate("clip: Wi-Fi send to 10.0.0.1 failed after 5000ms", t + 301_000)!!.contains("and 1 more"))
    }
}
