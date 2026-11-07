package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
