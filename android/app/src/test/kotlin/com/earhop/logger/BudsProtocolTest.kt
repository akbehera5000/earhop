package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudsProtocolTest {
    private fun frame(id: Int, payload: ByteArray): List<Byte> {
        val size = 1 + payload.size + 2
        val l = ArrayList<Byte>()
        l.add(0xFD.toByte()); l.add((size and 0xFF).toByte()); l.add((size shr 8).toByte()); l.add(id.toByte())
        payload.forEach { l.add(it) }
        l.add(0); l.add(0); l.add(0xDD.toByte())
        return l
    }

    private fun ext(placement: Int) = ByteArray(10).also { it[0] = 13; it[2] = 80; it[3] = 70; it[6] = placement.toByte() }

    @Test fun parsesBothWearing() {
        val buf = frame(0x61, ext(0x11)).toMutableList()
        val m = BudsProtocol.extract(buf)
        assertEquals(1, m.size)
        assertEquals(0, buf.size)
        val s = BudsProtocol.status(m[0])!!
        assertEquals("both", s.ear)
        assertEquals(80, s.batteryLeft)
        assertEquals(70, s.batteryRight)
    }

    @Test fun oneAndNone() {
        assertEquals("one", BudsProtocol.status(BudsProtocol.extract(frame(0x61, ext(0x12)).toMutableList())[0])!!.ear)
        assertEquals("one", BudsProtocol.status(BudsProtocol.extract(frame(0x61, ext(0x21)).toMutableList())[0])!!.ear)
        assertEquals("none", BudsProtocol.status(BudsProtocol.extract(frame(0x61, ext(0x22)).toMutableList())[0])!!.ear)
        assertEquals("none", BudsProtocol.status(BudsProtocol.extract(frame(0x61, ext(0x34)).toMutableList())[0])!!.ear)
    }

    @Test fun shortStatusMessage() {
        val p = ByteArray(8).also { it[0] = 5; it[1] = 90; it[2] = 91; it[5] = 0x11; it[6] = 60 }
        val s = BudsProtocol.status(BudsProtocol.extract(frame(0x60, p).toMutableList())[0])!!
        assertEquals("both", s.ear)
        assertEquals(60, s.batteryCase)
    }

    @Test fun partialAndJunk() {
        val all = frame(0x61, ext(0x11))
        val buf = ArrayList<Byte>()
        buf.add(0x00); buf.add(0x55)
        buf.addAll(all.take(7))
        assertEquals(0, BudsProtocol.extract(buf).size)
        buf.addAll(all.drop(7))
        assertEquals(1, BudsProtocol.extract(buf).size)
    }

    @Test fun twoFramesAndOtherIds() {
        val buf = (frame(0x70, ByteArray(2)) + frame(0x61, ext(0x11))).toMutableList()
        val m = BudsProtocol.extract(buf)
        assertEquals(2, m.size)
        assertNull(BudsProtocol.status(m[0]))
        assertEquals("both", BudsProtocol.status(m[1])!!.ear)
    }

    @Test fun badEndByteIsSkipped() {
        val bad = frame(0x61, ext(0x11)).toMutableList()
        bad[bad.size - 1] = 0
        assertEquals(0, BudsProtocol.extract(bad).size)
    }

    @Test fun crcAndEncodeRoundTrip() {
        // CRC16 XModem check value for "123456789" is 0x31C3.
        assertEquals(0x31C3, BudsProtocol.crc16("123456789".toByteArray()))
        val bytes = BudsProtocol.noiseCommand("anc")
        assertEquals(0xFD, bytes[0].toInt() and 0xFF)
        assertEquals(0xDD, bytes.last().toInt() and 0xFF)
        val m = BudsProtocol.extract(bytes.toMutableList())
        assertEquals(1, m.size)
        assertEquals(BudsProtocol.ID_NOISE, m[0].id)
        assertEquals(1, m[0].payload[0].toInt())
    }

    private fun extFull(noise: Int, eq: Int, touch: Int, place: Int): ByteArray {
        val p = ByteArray(40)
        p[0] = 5; p[2] = 80; p[3] = 70; p[6] = place.toByte(); p[7] = 55; p[9] = eq.toByte(); p[10] = touch.toByte()
        p[12] = noise.toByte(); p[19] = 0; p[35] = 1
        return p
    }

    @Test fun parsesFullExtendedStatus() {
        val s = BudsProtocol.State()
        val m = BudsProtocol.Msg(0x61, extFull(noise = 2, eq = 3, touch = 0x8F, place = 0x12))
        assertEquals(true, BudsProtocol.apply(m, s))
        assertEquals("ambient", s.noise)
        assertEquals("dynamic", s.eq)
        assertEquals("wearing", s.placeL)
        assertEquals("idle", s.placeR)
        assertEquals("one", s.ear)
        assertEquals(80, s.battL); assertEquals(70, s.battR); assertEquals(55, s.battCase)
        assertEquals(true, s.touchOn); assertEquals(true, s.hold)
        assertEquals(true, s.seamless)
        assertEquals(true, s.spatial)
        assertEquals(false, BudsProtocol.apply(m, s)) // same message again changes nothing
    }

    @Test fun ackAndNoiseUpdate() {
        val s = BudsProtocol.State()
        BudsProtocol.apply(BudsProtocol.Msg(BudsProtocol.ID_ACK, byteArrayOf(BudsProtocol.ID_EQUALIZER.toByte(), 4)), s)
        assertEquals("clear", s.eq)
        BudsProtocol.apply(BudsProtocol.Msg(BudsProtocol.ID_NOISE_UPDATE, byteArrayOf(1)), s)
        assertEquals("anc", s.noise)
        BudsProtocol.apply(BudsProtocol.Msg(BudsProtocol.ID_ACK, byteArrayOf(BudsProtocol.ID_SPATIAL.toByte(), 1)), s)
        assertEquals(true, s.spatial)
    }

    @Test fun chargingBudCountsAsInCase() {
        val s = BudsProtocol.State()
        val p = ByteArray(8).also { it[1] = 90; it[2] = 90; it[5] = 0x22; it[6] = 40; it[7] = 0x10 }
        BudsProtocol.apply(BudsProtocol.Msg(0x60, p), s)
        assertEquals("case", s.placeL)
        assertEquals("idle", s.placeR)
    }

    @Test fun commandPayloads() {
        val s = BudsProtocol.State()
        assertEquals(1, BudsProtocol.extract(BudsProtocol.eqCommand("bass").toMutableList())[0].payload[0].toInt())
        assertEquals(0, BudsProtocol.extract(BudsProtocol.seamlessCommand(true).toMutableList())[0].payload[0].toInt())
        val on = BudsProtocol.extract(BudsProtocol.touchCommand(true, s).toMutableList())[0].payload
        assertEquals(7, on.size)
        assertEquals(1, on[0].toInt())
        assertEquals(1, on[1].toInt()) // no gestures known, so all are enabled
    }

    @Test fun findSafety() {
        val s = BudsProtocol.State()
        s.placeL = "wearing"; s.placeR = "wearing"
        assertEquals("Both Buds are in your ears", BudsProtocol.findRefusal(s))
        s.placeL = "unknown"; s.placeR = "wearing"
        assertEquals(true, BudsProtocol.findRefusal(s) != null)
        s.placeL = "case"; s.placeR = "case"
        assertNull(BudsProtocol.findRefusal(s))
        s.placeL = "case"; s.placeR = "wearing"
        assertNull(BudsProtocol.findRefusal(s)) // left rings, right is muted
        assertEquals(Pair(false, true), BudsProtocol.findMustMute(s))
        s.placeL = "disconnected"; s.placeR = "wearing"
        assertEquals("Neither bud can ring", BudsProtocol.findRefusal(s))
    }

    // ---- checksum and malformed frame fixtures (synthetic frames built with the documented layout) ----

    private fun goodFrame(id: Int, payload: ByteArray) = BudsProtocol.encode(id, payload).toMutableList()

    @Test fun validChecksumIsCounted() {
        val crc = BudsProtocol.CrcStats()
        val m = BudsProtocol.extract(goodFrame(0x61, ext(0x11)), crc)
        assertEquals(1, m.size); assertEquals(1, crc.good); assertEquals(0, crc.bad)
        assertEquals(true, crc.enforcing)
    }

    @Test fun badChecksumKeptUntilVerifiedThenDropped() {
        val crc = BudsProtocol.CrcStats()
        // Before any frame passed, a bad checksum is kept (the assumption about the checksum may be wrong).
        assertEquals(1, BudsProtocol.extract(frame(0x61, ext(0x22)).toMutableList(), crc).size)
        assertEquals(1, crc.bad)
        assertEquals(1, BudsProtocol.extract(goodFrame(0x61, ext(0x11)), crc).size)
        // Now verified: a bad frame is dropped and the good frame after it still parses.
        val buf = (frame(0x61, ext(0x22)) + goodFrame(0x61, ext(0x12))).toMutableList()
        val m = BudsProtocol.extract(buf, crc)
        assertEquals(1, m.size)
        assertEquals("one", BudsProtocol.status(m[0])!!.ear)
        assertEquals(2, crc.bad)
    }

    @Test fun bigEndianChecksumAccepted() {
        val f = goodFrame(0x61, ext(0x11))
        val lo = f[f.size - 3]; f[f.size - 3] = f[f.size - 2]; f[f.size - 2] = lo
        val crc = BudsProtocol.CrcStats()
        assertEquals(1, BudsProtocol.extract(f, crc).size); assertEquals(1, crc.good)
    }

    @Test fun oversizedLengthIsJunkAndDoesNotStall() {
        val junk = listOf(0xFD, 0xFF, 0x03, 0x61).map { it.toByte() }
        val buf = (junk + goodFrame(0x61, ext(0x11))).toMutableList()
        val m = BudsProtocol.extract(buf, BudsProtocol.CrcStats())
        assertEquals(1, m.size)
        assertEquals(0, buf.size)
    }

    @Test fun tooShortLengthIsSkipped() {
        val junk = listOf(0xFD, 0x02, 0x00, 0x61, 0xDD).map { it.toByte() }
        val buf = (junk + goodFrame(0x60, ByteArray(8).also { it[5] = 0x11 })).toMutableList()
        val m = BudsProtocol.extract(buf)
        assertEquals(1, m.size)
        assertEquals("both", BudsProtocol.status(m[0])!!.ear)
    }

    @Test fun truncatedFrameWaitsForTheRest() {
        val f = goodFrame(0x61, ext(0x11))
        val buf = f.take(f.size - 1).toMutableList()
        assertEquals(0, BudsProtocol.extract(buf).size)
        assertEquals(f.size - 1, buf.size)
        buf.add(f.last())
        assertEquals(1, BudsProtocol.extract(buf).size)
    }
}
