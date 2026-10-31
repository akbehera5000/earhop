package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Galaxy Buds link over Samsung's serial channel. While the Buds are connected to this device it reads their status
 * (wear, battery, noise mode, equalizer and more) and sends control commands. Only works on the device that currently
 * holds the Buds, and may be refused if Galaxy Wearable already holds the channel. Every outcome is logged.
 */
class BudsClient(private val ctx: Context, private val filter: () -> String, private val isOwner: () -> Boolean) {
    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var at = 0L
    @Volatile private var failures = 0
    @Volatile private var holdUntil = 0L
    @Volatile private var closedAt = 0L
    private val lock = Any()
    private val st = BudsProtocol.State()
    private val writer = Executors.newSingleThreadExecutor()

    // find my earbuds
    @Volatile var findActive = false; private set
    @Volatile var findMessage = ""; private set
    @Volatile private var findDeadline = 0L
    @Volatile private var findConfirmBy = 0L
    @Volatile private var findWant = Pair(false, false)

    val connected get() = socket != null

    fun snapshot(): BudsProtocol.State = synchronized(lock) { st.copy() }

    /** Current wear state, or "unknown" when nothing fresh has been heard. */
    fun ear(): String = reading().ear

    /**
     * Wear state with its source: live from the channel (high), the last report for 20 s after the channel closed
     * (low, never pauses or resumes), or unknown. The Buds only report placement when it changes, so while the channel
     * is open the last report stays true however old it is (it used to expire after 2 minutes of sitting still, which
     * turned the wear state "unknown" and broke switching).
     */
    fun reading(): EarReading {
        val now = System.currentTimeMillis()
        val e = synchronized(lock) { st.ear }
        if (socket != null && at != 0L) return EarReading(e, "buds-rfcomm", "high")
        if (at != 0L && closedAt != 0L && now - closedAt < 20_000) return EarReading(e, "last-known", "low")
        return EarReading.UNKNOWN
    }

    /** Closes the channel before EarHop releases the Buds and keeps it closed for a moment, so the disconnect is quick. */
    fun suspend(ms: Long) {
        holdUntil = System.currentTimeMillis() + ms
        if (socket != null) { EhLog.w("buds: closing wear channel before release"); close() }
    }

    fun start() {
        if (running) return
        running = true
        EhState.budsClient = this
        thread = Thread({ loop() }, "earhop-buds").also { it.start() }
    }

    fun stop() {
        running = false
        if (EhState.budsClient === this) EhState.budsClient = null
        close()
        thread?.interrupt()
    }

    private fun close() {
        if (findActive) { findActive = false; findMessage = "Search ended" }
        try { socket?.close() } catch (e: Exception) { }
        if (socket != null) closedAt = System.currentTimeMillis()
        socket = null
        EhState.buds = "off"
    }

    // ---- commands ----

    fun send(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        writer.execute { try { s.outputStream.write(bytes); s.outputStream.flush() } catch (e: Exception) { EhLog.w("buds: send failed ${e.message}") ; close() } }
        return true
    }

    fun setNoise(mode: String) = send(BudsProtocol.noiseCommand(mode))
    fun setEq(preset: String) = send(BudsProtocol.eqCommand(preset))
    fun setSpatial(on: Boolean) = send(BudsProtocol.spatialCommand(on))
    fun setSeamless(on: Boolean) = send(BudsProtocol.seamlessCommand(on))
    fun setTouch(on: Boolean) = send(BudsProtocol.touchCommand(on, snapshot()))

    /** Rings the Buds. Refuses unless it is safe: a bud that may be in an ear is muted at once, and the search stops if the mute is not confirmed. */
    fun findStart() {
        if (findActive) return
        if (socket == null) { findMessage = "Buds are not connected to this device"; return }
        val s = snapshot()
        val why = BudsProtocol.findRefusal(s)
        if (why != null) { findMessage = why; return }
        val must = BudsProtocol.findMustMute(s)
        findWant = must
        findActive = true
        findMessage = "Ringing"
        findDeadline = System.currentTimeMillis() + 60_000
        send(BudsProtocol.encode(BudsProtocol.ID_FIND_START))
        if (must.first || must.second) {
            send(BudsProtocol.muteCommand(must.first, must.second))
            findConfirmBy = System.currentTimeMillis() + 2000
        } else findConfirmBy = 0
        Thread({ findWatch() }, "earhop-find").start()
    }

    fun findStop(why: String = "Search stopped") {
        if (!findActive) return
        findActive = false
        findMessage = why
        send(BudsProtocol.encode(BudsProtocol.ID_FIND_STOP))
    }

    private fun findWatch() {
        while (findActive) {
            try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            val now = System.currentTimeMillis()
            val s = snapshot()
            when {
                socket == null -> { findActive = false; findMessage = "Search ended" }
                now > findDeadline -> findStop("Search timed out")
                BudsProtocol.findRefusal(s) != null -> findStop("A bud went into an ear, search stopped")
                findConfirmBy != 0L -> {
                    val ok = (!findWant.first || s.muteL) && (!findWant.second || s.muteR)
                    if (ok) findConfirmBy = 0 else if (now > findConfirmBy) findStop("The Buds did not confirm the safety mute, search stopped")
                }
            }
            val now2 = BudsProtocol.findMustMute(s)
            if (findActive && (now2.first && !findWant.first || now2.second && !findWant.second)) {
                findWant = Pair(findWant.first || now2.first, findWant.second || now2.second)
                send(BudsProtocol.muteCommand(findWant.first, findWant.second))
                findConfirmBy = System.currentTimeMillis() + 1000
            }
        }
    }

    // ---- connection ----

    @SuppressLint("MissingPermission")
    private fun loop() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        while (running) {
            try {
                if (!isOwner()) { if (socket != null) close(); failures = 0; Thread.sleep(1000); continue }
                if (System.currentTimeMillis() < holdUntil) { Thread.sleep(500); continue }
                if (socket == null) {
                    // Back off 2, 4, 8, 15 s so a refused channel never becomes a busy loop, but the controls come back
                    // quickly once Galaxy Wearable or Quick connect lets go (it used to wait up to a minute).
                    val wait = minOf(15_000L, 2000L shl minOf(failures, 3))
                    val dev = adapter?.bondedDevices?.firstOrNull { it.name?.contains(filter(), true) == true }
                    if (dev == null) { EhState.buds = "no paired Buds"; Thread.sleep(wait); continue }
                    EhState.buds = "connecting"
                    try {
                        adapter.cancelDiscovery()
                        val s = dev.createRfcommSocketToServiceRecord(UUID.fromString(BudsProtocol.SERVICE_UUID))
                        // connect() has no timeout of its own and can hang while the Buds are busy switching; closing
                        // the socket from a watchdog ends it so the loop can try again.
                        val watchdog = Thread({
                            try { Thread.sleep(12_000); EhLog.w("buds: wear channel connect timed out"); s.close() } catch (e: InterruptedException) { } catch (e: Exception) { }
                        }, "earhop-buds-watchdog").also { it.isDaemon = true; it.start() }
                        try { s.connect() } finally { watchdog.interrupt() }
                        at = 0L
                        socket = s
                        failures = 0
                        EhState.buds = "connected"
                        EhLog.w("buds: wear channel open")
                    } catch (e: Exception) {
                        failures++
                        EhState.buds = "refused (${e.message ?: e.javaClass.simpleName})" +
                            if (failures >= 3) ", Galaxy Wearable may hold the channel" else ""
                        if (failures <= 2 || failures % 10 == 0) EhLog.w("buds: wear channel failed ($failures, next try in ${wait / 1000} s): ${e.message}")
                        Thread.sleep(wait)
                        continue
                    }
                }
                readLoop(socket!!)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                EhLog.w("buds: error ${e.message}")
                close()
                try { Thread.sleep(3000) } catch (x: InterruptedException) { break }
            }
        }
        close()
    }

    private fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buf = ArrayList<Byte>()
        val chunk = ByteArray(512)
        var lastId = -1
        var lastEar = ""
        val crc = BudsProtocol.CrcStats()
        while (running && isOwner()) {
            val n = try { input.read(chunk) } catch (e: Exception) { -1 }
            if (n < 0) { EhLog.w("buds: wear channel closed"); close(); return }
            for (i in 0 until n) buf.add(chunk[i])
            val hadGood = crc.good; val hadBad = crc.bad
            val msgs = BudsProtocol.extract(buf, crc)
            if (hadGood == 0 && crc.good > 0) EhLog.w("buds: frame checksums verified, bad frames are now dropped")
            if (crc.bad > hadBad && (crc.bad == 1 || crc.bad % 20 == 0))
                EhLog.w("buds: ${crc.bad} frames with a bad checksum (${if (crc.enforcing) "dropped" else "kept, checksum not confirmed yet"})")
            for (m in msgs) {
                val known = m.id in setOf(BudsProtocol.ID_STATUS, BudsProtocol.ID_EXTENDED_STATUS, BudsProtocol.ID_ACK,
                    BudsProtocol.ID_NOISE_UPDATE, BudsProtocol.ID_MUTE_UPDATED, BudsProtocol.ID_FIND_STOP)
                if (m.id != lastId && !known) { lastId = m.id; EhLog.w("buds: message id 0x%02x size ${m.payload.size}".format(m.id)) }
                val now = System.currentTimeMillis()
                val ended: Boolean
                val ear: String
                synchronized(lock) {
                    BudsProtocol.apply(m, st)
                    ended = st.findEnded
                    st.findEnded = false
                    ear = st.ear
                    EhState.batA = st.battL; EhState.batB = st.battR; EhState.batCase = st.battCase
                    if (st.battL >= 0 || st.battR >= 0) EhState.batAt = now
                }
                if (m.id == BudsProtocol.ID_STATUS || m.id == BudsProtocol.ID_EXTENDED_STATUS) {
                    at = now
                    if (ear != lastEar) {
                        val sn = snapshot()
                        EhLog.w("buds: wear $lastEar -> $ear (L=${sn.placeL} R=${sn.placeR})")
                        lastEar = ear
                    }
                }
                if (ended && findActive) { findActive = false; findMessage = "The Buds stopped the search" }
            }
        }
    }
}
