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
)

object Proto {
    /** v1 has no call field, v2 appends it. */
    fun canonical(s: Status, v: Int = 2): String {
        val base = "$v|${s.dev}|${s.ts}|${s.seq}|${if (s.playing) 1 else 0}|${s.ear}|${if (s.owner) 1 else 0}"
        return if (v == 1) base else base + "|${if (s.call) 1 else 0}"
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
        o.put("v", 2)
        o.put("dev", s.dev)
        o.put("ts", s.ts)
        o.put("seq", s.seq)
        o.put("playing", if (s.playing) 1 else 0)
        o.put("ear", s.ear)
        o.put("owner", if (s.owner) 1 else 0)
        o.put("call", if (s.call) 1 else 0)
        o.put("sig", sign(secret, canonical(s)))
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
        if (Math.abs(nowMs - ts) > 30000 && decode(data, len, secret, ts) != null) Pair(dev, "clock") else Pair(dev, "secret")
    } catch (e: Exception) {
        null
    }

    fun decode(data: ByteArray, len: Int, secret: String, nowMs: Long): Status? {
        return try {
            val o = JSONObject(String(data, 0, len, Charsets.UTF_8))
            val v = o.getInt("v")
            if (v != 1 && v != 2) return null
            val ear = o.getString("ear")
            if (ear !in setOf("both", "one", "none", "unknown")) return null
            val s = Status(
                o.getString("dev"), o.getLong("ts"), o.getInt("seq"),
                o.getInt("playing") == 1, ear, o.getInt("owner") == 1,
                call = v == 2 && o.getInt("call") == 1,
            )
            if (s.dev.contains("|")) return null
            val expected = sign(secret, canonical(s, v)).toByteArray()
            val got = o.getString("sig").toByteArray()
            if (!MessageDigest.isEqual(expected, got)) return null
            if (Math.abs(nowMs - s.ts) > 30000) return null
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
            "ea0000071707e997e90831cf5fb05515"
    }
}
