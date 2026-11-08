package com.earhop.logger

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One clipboard item travelling between devices. */
/**
 * One item travelling between devices. kind "text" and "image" are clips; "ack" (delivery receipt, data = clip id),
 * "diag-req" and "diag" (diagnostics) are control messages. [to] is the one device it is for, or "" for all.
 */
data class ClipItem(val id: String, val dev: String, val kind: String, val mime: String, val ts: Long, val data: ByteArray, val to: String = "")

/**
 * The encrypted clipboard frame, identical to ClipCodec in Clipboard.swift on the Mac:
 * "EHC1" | nonce (12) | AES-256-GCM ciphertext | tag (16), with "EHC1" as associated data.
 * Plaintext: header length (2 bytes, big endian) | header JSON | data. Key: HMAC-SHA256(secret, "earhop clip v1").
 * Pure (no Android classes), so it is unit tested against the Mac's vector.
 */
object ClipCodec {
    private val MAGIC = "EHC1".toByteArray()
    /** Clips older than this are refused, so a recording cannot be replayed later. */
    const val MAX_AGE_MS = 300_000L

    fun key(secret: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal("earhop clip v1".toByteArray())
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    fun encode(item: ClipItem, secret: String, nonce: ByteArray? = null): ByteArray? = try {
        val header = "{\"dev\":\"${esc(item.dev)}\",\"id\":\"${esc(item.id)}\",\"kind\":\"${esc(item.kind)}\"," +
            "\"mime\":\"${esc(item.mime)}\"," + (if (item.to.isNotEmpty()) "\"to\":\"${esc(item.to)}\"," else "") + "\"ts\":${item.ts},\"v\":1}"
        val h = header.toByteArray()
        val plain = byteArrayOf((h.size shr 8).toByte(), (h.size and 0xFF).toByte()) + h + item.data
        val n = nonce ?: ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(secret), "AES"), GCMParameterSpec(128, n))
        c.updateAAD(MAGIC)
        MAGIC + n + c.doFinal(plain)
    } catch (e: Exception) { null }

    fun decode(frame: ByteArray, secret: String): ClipItem? = try {
        if (frame.size <= 4 + 12 + 16 || !frame.copyOfRange(0, 4).contentEquals(MAGIC)) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(secret), "AES"), GCMParameterSpec(128, frame.copyOfRange(4, 16)))
            c.updateAAD(MAGIC)
            val p = c.doFinal(frame, 16, frame.size - 16)
            val hl = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
            val o = parseFlat(String(p, 2, hl, Charsets.UTF_8))
            ClipItem(o["id"]!!, o["dev"]!!, o["kind"]!!, o["mime"]!!, o["ts"]!!.toLong(), p.copyOfRange(2 + hl, p.size), o["to"] ?: "")
        }
    } catch (e: Exception) { null }

    /** Reads a flat JSON object of strings and numbers (the clip header). The Mac escapes "/" as "\/". */
    fun parseFlat(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = json.indexOf('{') + 1
        fun skip() { while (i < json.length && json[i] in " \t\r\n,") i++ }
        fun str(): String {
            val sb = StringBuilder(); i++ // opening quote
            while (json[i] != '"') {
                if (json[i] == '\\') {
                    i++
                    when (val e = json[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(json.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(json[i])
                i++
            }
            i++ // closing quote
            return sb.toString()
        }
        while (true) {
            skip()
            if (i >= json.length || json[i] == '}') break
            val k = str()
            skip(); i++ // colon
            skip()
            val v = if (json[i] == '"') str() else {
                val start = i
                while (i < json.length && json[i] !in ",} \t\r\n") i++
                json.substring(start, i)
            }
            out[k] = v
        }
        return out
    }
}

/**
 * Splits a frame into Bluetooth LE writes and puts them back together, the same format as ClipChunks on the Mac.
 * Each chunk: tag (4 bytes) | index (2) | count (2) | payload.
 */
object ClipChunks {
    fun split(frame: ByteArray, payload: Int, tag: Int): List<ByteArray> {
        val size = maxOf(1, payload)
        val count = (frame.size + size - 1) / size
        if (count <= 0 || count > 0xFFFF) return emptyList()
        return (0 until count).map { i ->
            byteArrayOf((tag ushr 24).toByte(), (tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte(),
                (i shr 8).toByte(), i.toByte(), (count shr 8).toByte(), count.toByte()) +
                frame.copyOfRange(i * size, minOf(frame.size, (i + 1) * size))
        }
    }

    class Assembler {
        private class Entry(val count: Int, val got: HashMap<Int, ByteArray>, var at: Long)
        private val parts = HashMap<String, Entry>()

        /** Adds one chunk from a sender; returns the whole frame once every chunk has arrived. */
        @Synchronized
        fun add(chunk: ByteArray, from: String, now: Long = System.currentTimeMillis()): ByteArray? {
            parts.entries.removeIf { now - it.value.at > 30_000 }
            if (chunk.size < 8) return null
            val tag = "$from/${chunk[0]}.${chunk[1]}.${chunk[2]}.${chunk[3]}"
            val index = ((chunk[4].toInt() and 0xFF) shl 8) or (chunk[5].toInt() and 0xFF)
            val count = ((chunk[6].toInt() and 0xFF) shl 8) or (chunk[7].toInt() and 0xFF)
            if (count <= 0 || index >= count) return null
            val e = parts.getOrPut(tag) { Entry(count, HashMap(), now) }
            e.got[index] = chunk.copyOfRange(8, chunk.size)
            e.at = now
            if (e.got.size < e.count) return null
            parts.remove(tag)
            val out = java.io.ByteArrayOutputStream()
            for (k in 0 until e.count) out.write(e.got[k] ?: ByteArray(0))
            return out.toByteArray()
        }
    }
}

/**
 * Text that looks like a password: one word (no spaces or line breaks), 8 to 64 characters, with at least 3 of lower
 * case letters, upper case letters, digits and symbols. Links and email addresses never count. Used only when the
 * owner switches on "Skip password-like text" (off by default). Same rule as PasswordLike in Clipboard.swift.
 */
object PasswordLike {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun looks(t: String): Boolean {
        if (t.length !in 8..64 || t.any { it.isWhitespace() }) return false
        val low = t.lowercase()
        if ("://" in t || low.startsWith("www.") || email.matches(t)) return false
        val kinds = listOf(t.any { it.isLowerCase() }, t.any { it.isUpperCase() }, t.any { it.isDigit() },
            t.any { !it.isLetterOrDigit() }).count { it }
        return kinds >= 3
    }
}

/**
 * Whether an automatic send must be skipped because the clip is EarHop's own: one that just arrived from another
 * device and was put on this clipboard. Android shows its clipboard preview for that too, which the copy triggers take
 * for a new copy, and without this the clip would bounce straight back. Manual sends are never skipped. Pure, tested.
 */
object EchoGuard {
    fun skip(auto: Boolean, label: String?, now: Long, ownWriteAt: Long, hash: Int?, lastReceivedHash: Int, lastSentHash: Int): Boolean {
        if (!auto) return false
        if (label?.startsWith("EarHop") == true) return true
        if (now - ownWriteAt < 3000) return true
        return hash != null && (hash == lastReceivedHash || hash == lastSentHash)
    }
}

/**
 * Where each device can be reached on Wi-Fi: the addresses its signed packets came from, with when each was last heard.
 * [current] gives the addresses heard within 10 minutes, newest first, or else only the newest one, so clips never go
 * to networks the device left long ago. At most 4 addresses per device are kept. Pure, unit tested.
 */
class AddressBook<A : Any> {
    private val map = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<A, Long>>()

    /** Records [dev] heard at [addr]; true when the address is new for it. */
    fun learn(dev: String, addr: A, now: Long): Boolean {
        val m = map.getOrPut(dev) { java.util.concurrent.ConcurrentHashMap() }
        val isNew = m.put(addr, now) == null
        while (m.size > 4) m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
        return isNew
    }

    fun current(dev: String, now: Long): List<A> {
        val m = map[dev] ?: return emptyList()
        val sorted = m.entries.sortedByDescending { it.value }
        val fresh = sorted.filter { now - it.value < 600_000 }.map { it.key }
        return fresh.ifEmpty { sorted.take(1).map { it.key } }
    }

    val devices: Set<String> get() = map.keys.toSet()
}

/**
 * Delivery receipts for sent clips (same rule as Receipts in Clipboard.swift): every device that applies a clip answers
 * with an "ack". Devices that did not answer get the clip again after 5 and 15 s, and after 60 s count as not
 * delivered. Pure, unit tested.
 */
class Receipts {
    class Entry(val targets: Set<String>, val sentAt: Long) { val delivered = HashSet<String>(); var retries = 0 }
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    companion object { val RETRY_AFTER = longArrayOf(5_000, 15_000); const val GIVE_UP = 60_000L }

    fun track(id: String, targets: Set<String>, now: Long) {
        entries.entries.removeIf { now - it.value.sentAt > 600_000 }
        entries[id] = Entry(targets, now)
    }

    /** Records a receipt; true when it was new. */
    @Synchronized fun ack(id: String, dev: String): Boolean {
        val e = entries[id] ?: return false
        if (dev !in e.targets || dev in e.delivered) return false
        e.delivered += dev
        return true
    }

    /** Clips to send again now, and to whom. */
    @Synchronized fun due(now: Long): List<Pair<String, List<String>>> = entries.mapNotNull { (id, e) ->
        val waiting = e.targets - e.delivered
        if (waiting.isEmpty() || e.retries >= RETRY_AFTER.size || now - e.sentAt < RETRY_AFTER[e.retries]) null
        else { e.retries++; Pair(id, waiting.sorted()) }
    }

    fun sentAt(id: String): Long? = entries[id]?.sentAt

    /** "Delivered to Mac ✓, Tablet ✓", plus who is still waited for or did not answer. */
    @Synchronized fun words(id: String, now: Long, pretty: (String) -> String): String {
        val e = entries[id] ?: return ""
        val rest = (e.targets - e.delivered).sorted()
        val over = now - e.sentAt >= GIVE_UP
        val parts = ArrayList<String>()
        if (e.delivered.isNotEmpty()) parts += "Delivered to " + e.delivered.sorted().joinToString(", ") { pretty(it) + " ✓" }
        if (rest.isNotEmpty()) parts += (if (over) "not delivered to " else "sending to ") + rest.joinToString(", ") { pretty(it) }
        if (parts.isEmpty()) return ""
        parts[0] = parts[0].replaceFirstChar { it.uppercase() }
        return parts.joinToString("; ")
    }
}

/**
 * Which link carries a clipboard message to which device (same rule as ClipRoute in Clipboard.swift). Pure, tested.
 * "both" (the default): text and small control messages over Bluetooth, Wi-Fi only for devices Bluetooth cannot reach
 * right now; images and large replies over Wi-Fi, Bluetooth only for devices with no Wi-Fi route (when small enough).
 * "ble": Bluetooth only for what fits, Wi-Fi only when Bluetooth reaches nobody. "wifi": Wi-Fi only.
 */
object ClipRoute {
    private val large = setOf("image", "diag")
    fun plan(kind: String, size: Int, mode: String, wanted: Set<String>, ble: Set<String>, wifi: Set<String>, maxBle: Int): Pair<Set<String>, Set<String>> {
        val fits = size <= maxBle
        val b = wanted intersect ble
        val w = wanted intersect wifi
        return when {
            mode == "wifi" -> Pair(emptySet(), w)
            kind in large && mode == "ble" -> if (fits && b.isNotEmpty()) Pair(b, emptySet()) else Pair(emptySet(), w)
            kind in large -> Pair(if (fits) b - w else emptySet(), w)
            !fits -> Pair(emptySet(), w)
            mode == "ble" -> Pair(b, if (b.isEmpty()) w else emptySet())
            else -> Pair(b, w - b)
        }
    }
}

/**
 * Bluetooth health per device (same rule as BleHealth in Clipboard.swift). A device that refused for lack of pairing
 * gets no Bluetooth tries, so no pairing requests, for 6 hours or until it is paired; other failures wait 2, 4, 8 and
 * at most 10 minutes from the second in a row. Pure, tested.
 */
class BleHealth {
    private val until = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val auth = HashSet<String>()
    @Synchronized fun usable(dev: String, now: Long) = now >= (until[dev] ?: 0L)
    @Synchronized fun needsPairing(dev: String) = dev in auth
    /** Records a result; returns a line for the log when the state of that device changed. */
    @Synchronized fun report(dev: String, ok: Boolean, pairing: Boolean, now: Long): String? {
        if (ok) {
            val was = until.remove(dev) != null || (fails.remove(dev) ?: 0) >= 2 || auth.remove(dev)
            return if (was) "clip: Bluetooth to $dev works again" else null
        }
        if (pairing) {
            auth += dev; until[dev] = now + 6 * 3600_000L
            return "clip: Bluetooth to $dev needs pairing again (remove each from the other's Bluetooth list, then pair); no Bluetooth tries to it for 6 hours, Wi-Fi carries its clips"
        }
        val n = (fails[dev] ?: 0) + 1
        fails[dev] = n
        if (n < 2) return null
        val wait = minOf(10 * 60_000L, 2 * 60_000L shl minOf(n - 2, 3))
        until[dev] = now + wait
        return "clip: Bluetooth to $dev failed $n times in a row; next try in ${wait / 60_000} min, Wi-Fi carries its clips"
    }
    /** Pairing finished (Android's bond state), so try again at once. */
    @Synchronized fun paired(dev: String) { until.remove(dev); fails.remove(dev); auth.remove(dev) }
}

object ClipRules {
    /** Android clears the clipboard one hour after the last copy; a "copy" seen right then is that clear. Pure, tested. */
    fun isAutoClear(now: Long, lastChange: Long) = lastChange > 0 && now - lastChange in 3_590_000L..3_630_000L
}
