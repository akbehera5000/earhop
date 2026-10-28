package com.earhop.logger

/**
 * Galaxy Buds Bluetooth serial messages, only the parts EarHop needs: framing and the wear status.
 * Frame: 0xFD, two length bytes (little endian, low 10 bits = id + payload + crc), id, payload, 2 crc bytes, 0xDD.
 * Status messages 0x60 and 0x61 carry the placement of each bud as two nibbles: 1 wearing, 2 out, 3 in case, 4 closed case.
 */
object BudsProtocol {
    const val SOM = 0xFD
    const val EOM = 0xDD
    const val ID_STATUS = 0x60
    const val ID_EXTENDED_STATUS = 0x61
    const val SERVICE_UUID = "2e73a4ad-332d-41fc-90e2-16bef06523f2"

    class Msg(val id: Int, val payload: ByteArray)
    class Status(val left: Int, val right: Int, val batteryLeft: Int, val batteryRight: Int, val batteryCase: Int) {
        val wearing get() = (if (left == 1) 1 else 0) + (if (right == 1) 1 else 0)
        val ear get() = when (wearing) { 2 -> "both"; 1 -> "one"; else -> "none" }
    }

    /** Takes complete frames out of the front of buf and returns them. Partial data stays in buf. Junk before a frame is dropped. */
    /** Largest frame EarHop accepts. Real status frames are well under 100 bytes; a bigger length is junk. */
    const val MAX_SIZE = 600

    /**
     * Checksum bookkeeping for one connection. Every frame is checked, but bad checksums are only dropped once at least
     * one frame on this connection has passed, so a wrong assumption about the checksum can never silence the Buds.
     */
    class CrcStats {
        var good = 0
        var bad = 0
        val enforcing get() = good > 0
    }

    /** True when the two checksum bytes match id + payload, in either byte order. */
    fun crcOk(id: Int, payload: ByteArray, lo: Int, hi: Int): Boolean {
        val c = crc16(byteArrayOf(id.toByte()) + payload)
        return c == (lo or (hi shl 8)) || c == (hi or (lo shl 8))
    }

    /** Takes complete frames off the front of buf. With crc set, frames are checked as described on CrcStats. */
    fun extract(buf: MutableList<Byte>, crc: CrcStats? = null): List<Msg> {
        val out = ArrayList<Msg>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == SOM }
            if (start < 0) { buf.clear(); break }
            repeat(start) { buf.removeAt(0) }
            if (buf.size < 4) break
            val size = ((buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (size < 3 || size > MAX_SIZE) { buf.removeAt(0); continue }
            val total = 4 + size
            if (buf.size < total) break
            if ((buf[total - 1].toInt() and 0xFF) != EOM) { buf.removeAt(0); continue }
            val id = buf[3].toInt() and 0xFF
            val payload = ByteArray(size - 3) { buf[4 + it] }
            if (crc != null) {
                if (crcOk(id, payload, buf[total - 3].toInt() and 0xFF, buf[total - 2].toInt() and 0xFF)) crc.good++
                else {
                    crc.bad++
                    if (crc.enforcing) { buf.removeAt(0); continue }
                }
            }
            repeat(total) { buf.removeAt(0) }
            out.add(Msg(id, payload))
        }
        return out
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun status(m: Msg): Status? = when {
        m.id == ID_EXTENDED_STATUS && m.payload.size > 6 ->
            Status(u(m.payload, 6) shr 4, u(m.payload, 6) and 15, u(m.payload, 2), u(m.payload, 3), -1)
        m.id == ID_STATUS && m.payload.size > 6 ->
            Status(u(m.payload, 5) shr 4, u(m.payload, 5) and 15, u(m.payload, 1), u(m.payload, 2), u(m.payload, 6))
        else -> null
    }

    // ---- commands and full state (Buds2 Pro layout; byte positions follow the open source
    // omarchy-galaxy-buds-pro project, MIT licensed, which documents them per model) ----

    const val ID_ACK = 66
    const val ID_NOISE_UPDATE = 119
    const val ID_NOISE = 120
    const val ID_SPATIAL = 124
    const val ID_EQUALIZER = 134
    const val ID_LOCK_TOUCHPAD = 144
    const val ID_FIND_START = 160
    const val ID_FIND_STOP = 161
    const val ID_MUTE = 162
    const val ID_MUTE_UPDATED = 163
    const val ID_SEAMLESS = 175

    val NOISE_MODES = listOf("off", "anc", "ambient")
    val EQ_PRESETS = listOf("off", "bass", "soft", "dynamic", "clear", "treble")

    private val CRC_TABLE = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    /** CRC16 CCITT (XModem) over message id and payload. */
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = ((crc shl 8) xor CRC_TABLE[((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        return crc
    }

    fun encode(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 1 + payload.size + 2
        val body = ByteArray(1 + payload.size).also { it[0] = id.toByte(); payload.copyInto(it, 1) }
        val crc = crc16(body)
        val out = java.io.ByteArrayOutputStream()
        out.write(SOM); out.write(size and 0xFF); out.write((size shr 8) and 0xFF)
        out.write(body)
        out.write(crc and 0xFF); out.write((crc shr 8) and 0xFF)
        out.write(EOM)
        return out.toByteArray()
    }

    fun noiseCommand(mode: String) = encode(ID_NOISE, byteArrayOf(NOISE_MODES.indexOf(mode).coerceAtLeast(0).toByte()))
    fun eqCommand(preset: String) = encode(ID_EQUALIZER, byteArrayOf(EQ_PRESETS.indexOf(preset).coerceAtLeast(0).toByte()))
    fun spatialCommand(on: Boolean) = encode(ID_SPATIAL, byteArrayOf(if (on) 1 else 0))
    /** Quick connect is inverted on the wire: 0 means on. */
    fun seamlessCommand(on: Boolean) = encode(ID_SEAMLESS, byteArrayOf(if (on) 0 else 1))
    fun muteCommand(left: Boolean, right: Boolean) = encode(ID_MUTE, byteArrayOf(if (left) 1 else 0, if (right) 1 else 0))

    /** Touch on or off. Re-sends the gesture bits last read so turning it back on restores them. */
    fun touchCommand(on: Boolean, s: State): ByteArray {
        var g = listOf(s.tap, s.double, s.triple, s.hold)
        if (on && g.none { it }) g = listOf(true, true, true, true)
        val p = ByteArray(7)
        p[0] = if (on) 1 else 0
        g.forEachIndexed { i, v -> p[1 + i] = if (v) 1 else 0 }
        p[5] = if (s.doubleCall) 1 else 0
        p[6] = if (s.holdCall) 1 else 0
        return encode(ID_LOCK_TOUCHPAD, p)
    }

    /** Everything the earbuds report. -1 or null means not reported yet. */
    class State {
        var revision = -1
        var battL = -1; var battR = -1; var battCase = -1
        var placeL = "unknown"; var placeR = "unknown"
        var chargeL = false; var chargeR = false; var chargeCase = false
        var noise = "unknown"
        var eq = "unknown"
        var spatial: Boolean? = null
        var touchOn: Boolean? = null
        var seamless: Boolean? = null
        var tap = false; var double = false; var triple = false; var hold = false
        var doubleCall = false; var holdCall = false
        var muteL = false; var muteR = false
        var findEnded = false

        val wearing get() = (if (placeL == "wearing") 1 else 0) + (if (placeR == "wearing") 1 else 0)
        val ear get() = when (wearing) { 2 -> "both"; 1 -> "one"; else -> "none" }

        fun copy(): State = State().also {
            it.revision = revision; it.battL = battL; it.battR = battR; it.battCase = battCase
            it.placeL = placeL; it.placeR = placeR; it.chargeL = chargeL; it.chargeR = chargeR; it.chargeCase = chargeCase
            it.noise = noise; it.eq = eq; it.spatial = spatial; it.touchOn = touchOn; it.seamless = seamless
            it.tap = tap; it.double = double; it.triple = triple; it.hold = hold; it.doubleCall = doubleCall; it.holdCall = holdCall
            it.muteL = muteL; it.muteR = muteR
        }
    }

    private fun place(n: Int) = when (n) { 0 -> "disconnected"; 1 -> "wearing"; 2 -> "idle"; 3, 4 -> "case"; else -> "unknown" }
    private fun battery(v: Int) = if (v in 1..100) v else -1

    /** Applies one incoming message to the state. Returns true when something changed. */
    fun apply(m: Msg, s: State): Boolean {
        val p = m.payload
        val before = snapshot(s)
        when (m.id) {
            ID_EXTENDED_STATUS -> if (p.size > 12) {
                s.revision = u(p, 0)
                s.battL = battery(u(p, 2)); s.battR = battery(u(p, 3))
                s.placeL = place(u(p, 6) shr 4); s.placeR = place(u(p, 6) and 15)
                s.battCase = battery(u(p, 7))
                s.eq = EQ_PRESETS.getOrNull(u(p, 9)) ?: if (u(p, 9) == 6) "custom" else "unknown"
                val t = u(p, 10)
                s.touchOn = t and 0x80 != 0
                s.tap = t and 0x08 != 0; s.double = t and 0x04 != 0; s.triple = t and 0x02 != 0; s.hold = t and 0x01 != 0
                s.doubleCall = t and 0x10 != 0; s.holdCall = t and 0x20 != 0
                s.noise = NOISE_MODES.getOrNull(u(p, 12)) ?: "unknown"
                if (p.size > 19) s.seamless = u(p, 19) == 0
                if (p.size > 35 && s.revision >= 1) s.spatial = u(p, 35) == 1
            }
            ID_STATUS -> if (p.size > 6) {
                s.battL = battery(u(p, 1)); s.battR = battery(u(p, 2))
                s.placeL = place(u(p, 5) shr 4); s.placeR = place(u(p, 5) and 15)
                s.battCase = battery(u(p, 6))
                if (p.size > 7) {
                    val c = u(p, 7)
                    s.chargeL = c and 0x10 != 0; s.chargeR = c and 0x04 != 0; s.chargeCase = c and 0x01 != 0
                    // A bud only charges in the case, whatever its placement says.
                    if (s.chargeL) s.placeL = "case"
                    if (s.chargeR) s.placeR = "case"
                }
            }
            ID_NOISE_UPDATE -> if (p.isNotEmpty()) s.noise = NOISE_MODES.getOrNull(u(p, 0)) ?: "unknown"
            ID_MUTE_UPDATED -> if (p.size >= 2) { s.muteL = u(p, 0) != 0; s.muteR = u(p, 1) != 0 }
            ID_FIND_STOP -> s.findEnded = true
            ID_ACK -> if (p.size >= 2) {
                val v = u(p, 1)
                when (u(p, 0)) {
                    ID_NOISE -> NOISE_MODES.getOrNull(v)?.let { s.noise = it }
                    ID_SPATIAL -> s.spatial = v == 1
                    ID_SEAMLESS -> s.seamless = v == 0
                    ID_LOCK_TOUCHPAD -> s.touchOn = v == 1
                    ID_EQUALIZER -> s.eq = EQ_PRESETS.getOrNull(v) ?: if (v == 6) "custom" else "unknown"
                    ID_MUTE -> if (p.size >= 3) { s.muteL = v != 0; s.muteR = u(p, 2) != 0 }
                }
            }
        }
        return snapshot(s) != before
    }

    private fun snapshot(s: State) = listOf(s.battL, s.battR, s.battCase, s.placeL, s.placeR, s.chargeL, s.chargeR, s.chargeCase,
        s.noise, s.eq, s.spatial, s.touchOn, s.seamless, s.muteL, s.muteR, s.findEnded)

    // ---- find my earbuds safety, same rules as the project: never ring a bud that may be in an ear ----
    private val OUT_OF_EAR = setOf("idle", "case", "disconnected")

    /** Per side: true when that bud may be in an ear and so must not ring. */
    fun findMustMute(s: State) = Pair(s.placeL !in OUT_OF_EAR, s.placeR !in OUT_OF_EAR)

    /** Reason a search may not start, or null. */
    fun findRefusal(s: State): String? {
        val (l, r) = findMustMute(s)
        if (l && r) return if (s.placeL == "wearing" && s.placeR == "wearing") "Both Buds are in your ears" else "Buds position unknown, take them out first"
        if (!(!l && s.placeL != "disconnected") && !(!r && s.placeR != "disconnected")) return "Neither bud can ring"
        return null
    }
}
