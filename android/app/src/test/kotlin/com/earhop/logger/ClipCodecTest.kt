package com.earhop.logger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipCodecTest {
    /** Printed by the Mac app with `EarHop --clip-vector` (same item, secret and nonce). */
    private val macVector = "45484331000102030405060708090a0b765c8e2baccb6f1e92ca911cc1d71d7038f9a0f0e2d8a53ac36a4ca51c25579630bd1417175a47291052f27e87a3ffc7985416704e8315c175ed265ba94d656595ffbf7c5ac0c2c59d153ea5c5711b6a54bca9ec65820b3ea9ee21053da3f2df70bddbe91ea07d245df07695ef2eae3173d9af3e584b2a61d20cf57e0fdcb8"
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun readsTheMacsFrame() {
        val item = ClipCodec.decode(unhex(macVector), "test-secret")!!
        assertEquals("hello EarHop", String(item.data))
        assertEquals("0011223344556677", item.id)
        assertEquals("mac", item.dev)
        assertEquals("text/plain", item.mime)
        assertEquals(1700000000000L, item.ts)
    }

    @Test fun roundTrip() {
        val item = ClipItem("abc", "phone", "text", "text/plain", 42L, "Grüße \"quoted\" \\ /".toByteArray())
        val back = ClipCodec.decode(ClipCodec.encode(item, "k")!!, "k")!!
        assertEquals("Grüße \"quoted\" \\ /", String(back.data))
        assertEquals("phone", back.dev)
    }

    @Test fun wrongSecretAndTamperingAreRefused() {
        val f = unhex(macVector)
        assertNull(ClipCodec.decode(f, "other"))
        val bad = f.copyOf(); bad[bad.size - 20] = (bad[bad.size - 20].toInt() xor 1).toByte()
        assertNull(ClipCodec.decode(bad, "test-secret"))
    }

    @Test fun chunksReassembleOutOfOrder() {
        val frame = ByteArray(5000) { (it % 251).toByte() }
        val chunks = ClipChunks.split(frame, 172, 7)
        val asm = ClipChunks.Assembler()
        var whole: ByteArray? = null
        for (c in chunks.reversed()) asm.add(c, "x")?.let { whole = it }
        assertArrayEquals(frame, whole)
    }

    @Test fun flatJsonWithEscapedSlash() {
        val o = ClipCodec.parseFlat("{\"mime\":\"text\\/plain\",\"ts\":17,\"id\":\"a\\u0041\"}")
        assertEquals("text/plain", o["mime"]); assertEquals("17", o["ts"]); assertEquals("aA", o["id"])
    }

    @Test fun wifiTransportLoopback() {
        val got = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val tcp = ClipTcp(47498) { got.put(it) }
        tcp.start()
        Thread.sleep(300)
        val frame = ByteArray(200_000) { (it % 253).toByte() }
        tcp.send(frame, java.net.InetAddress.getLoopbackAddress())
        assertArrayEquals(frame, got.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        tcp.stop()
    }

    @Test fun printAndroidVector() {
        val f = ClipCodec.encode(ClipItem("8899aabbccddeeff", "phone", "text", "text/plain", 1700000000000L, "hi from Android".toByteArray()),
            "test-secret", ByteArray(12) { it.toByte() })!!
        println("ANDROID_CLIP_VECTOR " + f.joinToString("") { "%02x".format(it) })
    }
}
