package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic simulator for HandoffCore, the same timelines as SimTests.swift on the Mac. It models a toggle
 * Play/Pause key, audio output that looks busy for a moment after a pause, slow disconnects, and connects that fail
 * while another device still holds the earbuds.
 */
class HandoffSimTest {
    class Dev(val name: String, val toggle: Boolean, settings: HandoffSettings) {
        val core = HandoffCore(settings)
        var playerOn = false
        var staleUntil = 0L
        var stuck = false
        var ignoresPause = false
        /** While audio moves from the earbuds to the speakers the output drops for a moment, then starts again. */
        var dropUntil = 0L
        var ear = EarReading("both", "sim", "high")
        var inCall = false
        var gate: String? = null
        var online = true
        var pauses = 0; var resumes = 0; var claims = 0; var releases = 0
        var speakerMs = 0L
        val seen = HashMap<String, Pair<Long, Long>>() // since, last
    }

    class World {
        var now = 1_700_000_000_000L
        var holder: String? = null
        val devs = mutableListOf<Dev>()
        var connectMs = 1500L
        var disconnectMs = 500L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()

        fun add(name: String, toggle: Boolean = false, tweak: (HandoffSettings) -> Unit = {}): Dev {
            val s = HandoffSettings(deviceName = name, requireEar = true)
            tweak(s)
            return Dev(name, toggle, s).also { devs += it }
        }

        private var lastHolder: String? = null

        fun playing(d: Dev) = now >= d.dropUntil && (d.stuck || d.playerOn || now < d.staleUntil)
        private fun stop(d: Dev) { if (d.playerOn) { d.playerOn = false; d.staleUntil = now + 2000 } }
        fun play(d: Dev) { d.playerOn = true }
        fun pause(d: Dev) = stop(d)
        private fun after(ms: Long, f: () -> Unit) { timers += Pair(now + ms, f) }

        private fun apply(d: Dev, acts: List<HandoffAction>) {
            for (a in acts) when (a) {
                is HandoffAction.Pause -> { d.pauses++; if (!d.ignoresPause) { if (d.toggle && !d.playerOn) d.playerOn = true else stop(d) } }
                is HandoffAction.Resume -> { d.resumes++; if (d.toggle && d.playerOn) stop(d) else d.playerOn = true }
                is HandoffAction.Claim -> {
                    d.claims++
                    val start = now
                    fun attempt() {
                        val holderOnline = devs.firstOrNull { it.name == holder }?.online ?: false
                        if (holder == null || holder == d.name || !holderOnline || now - start >= 8000 || a.manual) {
                            after(connectMs) {
                                val ok = holder == null || holder == d.name
                                if (ok) holder = d.name
                                apply(d, d.core.claimFinished(ok, a.manual, if (ok) null else "held", now))
                            }
                        } else after(250) { attempt() }
                    }
                    attempt()
                }
                is HandoffAction.Release -> {
                    d.releases++
                    after(disconnectMs) { if (holder == d.name) holder = null; d.core.releaseFinished(now) }
                }
                else -> {}
            }
        }

        fun step() {
            val due = timers.filter { it.first <= now }
            timers.removeAll { it.first <= now }
            due.forEach { it.second() }
            if (holder != lastHolder) devs.firstOrNull { it.name == lastHolder && it.playerOn }?.dropUntil = now + 750
            lastHolder = holder
            val snap = devs.map { Triple(it, it.core.advertisedPlaying(playing(it)), holder == it.name) }
            for (d in devs) {
                if (!d.online) continue
                val peers = snap.filter { it.first !== d && it.first.online }.map { (o, pl, own) ->
                    val prev = d.seen[o.name] ?: Pair(0L, 0L)
                    val rec = if (pl) Pair(if (prev.first == 0L) now else prev.first, now) else Pair(0L, prev.second)
                    d.seen[o.name] = rec
                    PeerState(o.name, pl, o.ear.ear, own, o.inCall, "sim", now, rec.first, rec.second)
                }
                apply(d, d.core.tick(HandoffInput(now, playing(d), d.ear, holder == d.name, d.inCall, peers, d.gate)))
                if (d.playerOn && holder != d.name) d.speakerMs += 250
            }
            now += 250
        }

        fun run(seconds: Double) = repeat((seconds * 4).toInt()) { step() }
    }

    @Test fun macOwnsPhoneStartsPlaying() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        w.play(phone); w.run(15.0)
        assertEquals("phone", w.holder)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
        assertTrue(mac.speakerMs < 1000)
        w.pause(phone); w.run(30.0)
        assertEquals("phone", w.holder); assertEquals(0, mac.claims)
    }

    @Test fun phoneOwnsMacStartsPlaying() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "phone"; w.play(phone); w.run(5.0)
        w.play(mac); w.run(15.0)
        assertEquals("mac", w.holder)
        assertEquals(1, phone.pauses); assertFalse(phone.playerOn); assertTrue(mac.playerOn)
    }

    @Test fun ownerPausesAndAnotherStartsWithinTwoSeconds() {
        val w = World(); val mac = w.add("mac", false); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        w.pause(mac); w.run(1.0); w.play(phone); w.run(15.0)
        assertEquals("phone", w.holder); assertFalse(mac.playerOn)
    }

    @Test fun stuckPlayingOwnerDoesNotBlockOrReclaim() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "phone"; w.play(phone); w.run(5.0)
        phone.stuck = true
        w.play(mac); w.run(15.0)
        assertEquals("mac", w.holder)
        w.run(60.0)
        assertEquals("mac", w.holder); assertEquals(0, phone.claims)
    }

    @Test fun slowDisconnectStillHandsOver() {
        val w = World(); w.disconnectMs = 7000
        val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        w.play(phone); w.run(25.0)
        assertEquals("phone", w.holder)
        assertEquals(0, phone.core.failures); assertFalse(phone.core.safeMode)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
    }

    @Test fun silentHolderEndsInSafeModeWithoutLooping() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "mac"; mac.online = false
        w.play(phone); w.run(90.0)
        assertTrue(phone.core.safeMode); assertEquals(3, phone.claims)
        phone.core.manualClaim(w.now)
        assertEquals(HandoffCore.Pending.CLAIM, phone.core.pending)
    }

    @Test fun callOnPhoneTakesEarbuds() {
        val w = World(); val mac = w.add("mac", true) { it.callNeedsPlaying = true }; val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        phone.inCall = true; w.run(15.0)
        assertEquals("phone", w.holder)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
    }

    @Test fun manualTakeWorksWhileGated() {
        val w = World(); val phone = w.add("phone")
        phone.gate = "quiet hours"; w.play(phone); w.run(5.0)
        assertEquals(0, phone.claims)
        phone.core.manualClaim(w.now)
        assertEquals(HandoffCore.Pending.CLAIM, phone.core.pending)
    }

    @Test fun earRemovalDuringHandoffPausesOnce() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        w.play(phone); w.run(0.25)
        mac.ear = mac.ear.copy(ear = "none"); w.run(15.0)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
        assertEquals("phone", w.holder)
    }

    @Test fun quickConnectMoveIsNotUndone() {
        val w = World(); val mac = w.add("mac", true); w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        w.holder = "phone"; w.run(20.0)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
        assertEquals("phone", w.holder); assertEquals(0, mac.claims)
    }

    @Test fun earPauseResumeAndLowConfidenceNeverActs() {
        val w = World(); val phone = w.add("phone")
        w.holder = "phone"; w.play(phone); w.run(3.0)
        phone.ear = phone.ear.copy(ear = "one"); w.run(2.0)
        assertEquals(1, phone.pauses); assertFalse(phone.playerOn)
        phone.ear = phone.ear.copy(ear = "both"); w.run(2.0)
        assertEquals(1, phone.resumes); assertTrue(phone.playerOn)
        phone.ear = EarReading("none", "last-known", "low"); w.run(3.0)
        assertEquals(1, phone.pauses); assertTrue(phone.playerOn)
    }

    @Test fun noSecondPauseAfterLosingEarbuds() {
        val w = World(); val mac = w.add("mac", true)
        w.holder = "mac"; w.play(mac); w.run(3.0)
        mac.ear = mac.ear.copy(ear = "none"); w.run(0.5)
        w.holder = null; w.run(5.0)
        assertEquals(1, mac.pauses); assertFalse(mac.playerOn)
    }

    @Test fun oldOwnerAudioThatIgnoresPauseDoesNotPingPong() {
        val w = World(); val mac = w.add("mac", false); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        mac.ignoresPause = true
        w.play(phone); w.run(30.0)
        assertEquals("phone", w.holder); assertEquals(0, phone.releases); assertEquals(0, mac.claims)
    }

    @Test fun flickeringPeerIsNotASteadyStart() {
        val w = World(); val mac = w.add("mac", false); val phone = w.add("phone")
        w.holder = "mac"; w.play(mac); w.run(5.0)
        repeat(20) { w.play(phone); w.run(0.5); phone.playerOn = false; phone.staleUntil = 0L; w.run(0.5) }
        assertEquals("mac", w.holder); assertEquals(0, mac.releases)
    }

    @Test fun priorityBreaksNearTie() {
        val w = World(); val mac = w.add("mac", true); val phone = w.add("phone")
        w.play(mac); w.play(phone); w.run(10.0)
        assertEquals("mac", w.holder)
    }
}
