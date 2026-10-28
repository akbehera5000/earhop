package com.earhop.logger

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One clipboard item travelling between devices. */
data class ClipItem(val id: String, val dev: String, val kind: String, val mime: String, val ts: Long, val data: ByteArray)

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
            "\"mime\":\"${esc(item.mime)}\",\"ts\":${item.ts},\"v\":1}"
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
            ClipItem(o["id"]!!, o["dev"]!!, o["kind"]!!, o["mime"]!!, o["ts"]!!.toLong(), p.copyOfRange(2 + hl, p.size))
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
