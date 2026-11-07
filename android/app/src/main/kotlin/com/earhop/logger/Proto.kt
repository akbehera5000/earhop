package com.earhop.logger

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Status(
    val dev: String,
    val ts: Long,
    val seq: Int,
    val playing: Boolean,
    val ear: String,
    val owner: Boolean,
    val call: Boolean = false,
    /** Which earbuds this device uses: "airpods", "buds", or "" when unknown (v1, v2 and older Bluetooth LE packets). */
    val prof: String = "",
    /** v4: the user was active on this device (unlocked it or typed) within the last 30 s. */
    val att: Boolean = false,
    /** Protocol version it arrived in. v4 carries the sender's own clock in ts (checked by ClockGuard). */
    val v: Int = 4,
)

/**
 * Device names are free: 1 to 20 characters, lower case letters, digits and "-", starting with a letter or digit.
 * Bluetooth LE carries a 16 bit id instead: the first two bytes of HMAC(secret, "earhop dev v4|" + name).
 */
object DeviceNames {
    private val re = Regex("^[a-z0-9][a-z0-9-]{0,19}$")
    fun valid(n: String) = re.matches(n)
    fun id(name: String, secret: String): Int {
        val m = Proto.signRaw(secret, "earhop dev v4|$name".toByteArray(Charsets.UTF_8))
        return ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
    }
}

/** Every device name known here and its Bluetooth LE id (same as NameBook in Protocol.swift). */
object NameBook {
    private val names = java.util.concurrent.CopyOnWriteArrayList(listOf("mac", "phone", "tab"))
    private val ids = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var secret = ""
    /** Called when a new name was learned (saved in the settings by the service). */
    @Volatile var onChange: ((List<String>) -> Unit)? = null

    @Synchronized fun setup(secret: String, known: Collection<String>) {
        this.secret = secret
        for (n in known) if (DeviceNames.valid(n) && n !in names) names.add(n)
        ids.clear()
        for (n in names) ids.putIfAbsent(DeviceNames.id(n, secret), n)
    }

    @Synchronized fun add(n: String): Boolean {
        if (!DeviceNames.valid(n) || n in names) return false
        names.add(n); ids[DeviceNames.id(n, secret)] = n
        onChange?.invoke(names.toList())
        return true
    }

    fun name(id: Int): String? = ids[id]
    val all: List<String> get() = names.toList()
}

/**
 * Learns how far each device's clock is from this one and refuses status copies and clips far off that offset (the
 * same rule as ClockGuard in Protocol.swift; see there). Devices with clocks minutes apart work; an old recording
 * replayed later is refused; a device whose clock was changed is relearned after three copies with rising numbers.
 */
class ClockGuard {
    companion object {
        val shared = ClockGuard()
        const val WIFI_TOLERANCE = 15_000L
        const val BLE_TOLERANCE = 90_000L
    }
    private class Entry(var offset: Long, var cand: Long? = null, var candCount: Int = 0, var candSeq: Int = 0)
    private val map = HashMap<String, Entry>()

    @Synchronized
    fun check(dev: String, senderMs: Long, nowMs: Long, toleranceMs: Long, seq: Int): Boolean {
        val seen = senderMs - nowMs
        val e = map[dev] ?: run { map[dev] = Entry(seen); return true }
        if (Math.abs(seen - e.offset) <= toleranceMs) {
            if (seen > e.offset) e.offset = seen
            e.cand = null; e.candCount = 0
            return true
        }
        val c = e.cand
        if (c != null && Math.abs(seen - c) <= toleranceMs && BlePayload.isNewer(seq and 0xFFFF, e.candSeq and 0xFFFF)) {
            e.candCount++; e.candSeq = seq
            if (e.candCount >= 3) { e.offset = maxOf(c, seen); e.cand = null; e.candCount = 0; return true }
        } else if (c == null || Math.abs(seen - c) > toleranceMs) {
            e.cand = seen; e.candCount = 1; e.candSeq = seq
        }
        return false
    }

    @Synchronized fun offset(dev: String): Long? = map[dev]?.offset

    /** For clips: within maxAgeMs of now by that device's clock; accepted before its first status copy. */
    fun fresh(dev: String, senderMs: Long, nowMs: Long, maxAgeMs: Long): Boolean {
        val o = offset(dev) ?: return true
        return Math.abs(senderMs - nowMs - o) <= maxAgeMs
    }
}

object Proto {
    /** v1 has no call field, v2 appends it, v3 also appends the earbud profile. */
    fun canonical(s: Status, v: Int = 2): String {
        val base = "$v|${s.dev}|${s.ts}|${s.seq}|${if (s.playing) 1 else 0}|${s.ear}|${if (s.owner) 1 else 0}"
        return when (v) {
            1 -> base
            2 -> base + "|${if (s.call) 1 else 0}"
            3 -> base + "|${if (s.call) 1 else 0}|${s.prof}"
            else -> base + "|${if (s.call) 1 else 0}|${s.prof}|${if (s.att) 1 else 0}"
        }
    }

    fun signRaw(secret: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sign(secret: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun encode(s: Status, secret: String): ByteArray {
        val o = JSONObject()
        o.put("v", 4)
        o.put("dev", s.dev)
        o.put("ts", s.ts)
        o.put("seq", s.seq)
        o.put("playing", if (s.playing) 1 else 0)
        o.put("ear", s.ear)
        o.put("owner", if (s.owner) 1 else 0)
        o.put("call", if (s.call) 1 else 0)
        o.put("prof", s.prof)
        o.put("att", if (s.att) 1 else 0)
        o.put("sig", sign(secret, canonical(s, 4)))
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * For a packet decode() refused: if it is a well formed EarHop packet, the sending device and why ("secret" or
     * "clock"). Only used to tell the user; the packet itself is never accepted.
     */
    fun rejection(data: ByteArray, len: Int, secret: String, nowMs: Long): Pair<String, String>? = try {
        val o = JSONObject(String(data, 0, len, Charsets.UTF_8))
        val dev = o.getString("dev")
        o.getString("sig")
        val ts = o.getLong("ts")
        // v1 to v3 only; v4 has no clock window (offsets are learned).
        if (o.optInt("v", 4) < 4 && Math.abs(nowMs - ts) > 30000 && decode(data, len, secret, ts) != null) Pair(dev, "clock") else Pair(dev, "secret")
    } catch (e: Exception) {
        null
    }

    fun decode(data: ByteArray, len: Int, secret: String, nowMs: Long): Status? {
        return try {
            val o = JSONObject(String(data, 0, len, Charsets.UTF_8))
            val v = o.getInt("v")
            if (v !in 1..4) return null
            val prof = if (v >= 3) o.optString("prof", "") else ""
            if (prof !in setOf("", "airpods", "buds")) return null
            val ear = o.getString("ear")
            if (ear !in setOf("both", "one", "none", "unknown")) return null
            val s = Status(
                o.getString("dev"), o.getLong("ts"), o.getInt("seq"),
                o.getInt("playing") == 1, ear, o.getInt("owner") == 1,
                call = v >= 2 && o.getInt("call") == 1, prof = prof,
                att = v >= 4 && o.getInt("att") == 1, v = v,
            )
            if (s.dev.contains("|")) return null
            if (v >= 4 && !DeviceNames.valid(s.dev)) return null
            val expected = sign(secret, canonical(s, v)).toByteArray()
            val got = o.getString("sig").toByteArray()
            if (!MessageDigest.isEqual(expected, got)) return null
            if (v < 4 && Math.abs(nowMs - s.ts) > 30000) return null
            s
        } catch (e: Exception) {
            null
        }
    }

    /** Must print the same hash as the project brief test vector. */
    fun selfTest(): Boolean {
        val s = Status("mac", 1700000000000L, 7, true, "both", false, call = true)
        return canonical(s, 1) == "1|mac|1700000000000|7|1|both|0" &&
            sign("test-secret", canonical(s, 1)) ==
            "389cb0b6b95b236ec9f8ee2a76263d87cf0f876d590b14c976960cbe424b4c00" &&
            canonical(s) == "2|mac|1700000000000|7|1|both|0|1" &&
            sign("test-secret", canonical(s)) ==
            "8c0b895c231d9329540318f0d015d6cc3d2e1007950972ce05ef20bf82ba2b8f" &&
            BlePayload.encode(s, "test-secret", BlePayload.minute(1700000000000L))?.joinToString("") { "%02x".format(it) } ==
            "ea0000071707e997e90831cf5fb05515" &&
            canonical(s.copy(prof = "buds"), 3) == "3|mac|1700000000000|7|1|both|0|1|buds" &&
            sign("test-secret", canonical(s.copy(prof = "buds"), 3)) ==
            "e0b6ceded3c4002c40a606404586e70634245c77a4f5aff28eedb1352866659d" &&
            BlePayload.encode(s.copy(prof = "buds"), "test-secret", BlePayload.minute(1700000000000L))?.joinToString("") { "%02x".format(it) } ==
            "ea0000077752983dac7ce48782b05515" &&
            sign("test-secret", canonical(s.copy(prof = "buds", att = true), 4)) ==
            "7ad20a1aac679b7fcd58e5e67214a1531812fdb6e574eeca406f31ef8af8cf0f" &&
            listOf("mac", "phone", "tab").map { DeviceNames.id(it, "test-secret") } == listOf(33190, 36699, 48167) &&
            BlePayload.encode4(s.copy(prof = "buds", att = true), "test-secret", 1_700_000_000L).joinToString("") { "%02x".format(it) } ==
            "eb81a60007f728611b1ffa426553f100"
    }
}
