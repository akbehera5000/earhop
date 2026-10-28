package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build

/**
 * EXPERIMENTAL. Opens the AirPods control channel (L2CAP PSM 0x1001) and reads their in-ear packets, which
 * report each bud instantly. The beacon cannot see one bud being removed on some pairs, this can.
 * Packet layout from community reverse engineering: 04 00 04 00 06 00 <primary> <secondary>,
 * where 0x00 = in ear, 0x01 = out of ear, 0x02 = in case. If the phone or the AirPods refuse the
 * connection, every failure is logged and the beacon stays in use.
 */
class AacpClient(
    private val ctx: Context,
    private val filter: String,
    private val isOwner: () -> Boolean,
    private val onEar: (String?) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: BluetoothSocket? = null

    private val handshake = byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val features = byteArrayOf(4, 0, 4, 0, 0x4D, 0, 0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0)
    private val notifications = byteArrayOf(4, 0, 4, 0, 0x0F, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    fun start() {
        if (running) return
        if (ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).getBoolean("aacp_unsupported", false)) {
            EhState.aacp = "not supported"
            return
        }
        if (Build.VERSION.SDK_INT < 29) { EhState.aacp = "needs Android 10"; return }
        running = true
        thread = Thread({ loop() }, "earhop-aacp").also { it.start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        thread?.interrupt()
        onEar(null)
    }

    @SuppressLint("MissingPermission")
    private fun device(): BluetoothDevice? {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bm.adapter?.bondedDevices?.firstOrNull { it.name?.contains(filter, ignoreCase = true) == true }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var failures = 0
        var everConnected = false
        var logged = 0
        while (running) {
            try {
                if (!isOwner()) { onEar(null); Thread.sleep(3000); continue }
                val dev = device()
                if (dev == null) { Thread.sleep(5000); continue }
                val s = dev.createL2capChannel(0x1001)
                socket = s
                s.connect()
                everConnected = true
                failures = 0
                EhState.aacp = "connected"
                EhLog.w("aacp: connected")
                val out = s.outputStream
                val input = s.inputStream
                out.write(handshake); out.flush()
                Thread.sleep(300)
                out.write(features); out.flush()
                Thread.sleep(100)
                out.write(notifications); out.flush()
                val buf = ByteArray(512)
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n >= 8 && buf[0] == 4.toByte() && buf[1] == 0.toByte() && buf[2] == 4.toByte() &&
                        buf[3] == 0.toByte() && buf[4] == 6.toByte() && buf[5] == 0.toByte()) {
                        val a = buf[6].toInt() and 0xFF
                        val b = buf[7].toInt() and 0xFF
                        val inEar = (if (a == 0) 1 else 0) + (if (b == 0) 1 else 0)
                        val ear = when (inEar) { 2 -> "both"; 1 -> "one"; else -> "none" }
                        EhLog.w("aacp: ear packet %02x %02x -> $ear".format(a, b))
                        onEar(ear)
                    } else if (logged < 6) {
                        logged++
                        EhLog.w("aacp: packet " + buf.take(minOf(n, 16)).joinToString(" ") { "%02x".format(it) })
                    }
                }
                onEar(null)
                EhState.aacp = "disconnected"
                EhLog.w("aacp: disconnected")
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                failures++
                onEar(null)
                EhState.aacp = "unavailable"
                if (failures <= 3) EhLog.w("aacp: connect failed (${e.javaClass.simpleName}: ${e.message})")
                if (failures >= 6 && !everConnected) {
                    EhLog.w("aacp: giving up, this phone or these AirPods do not allow it; using the beacon only")
                    EhState.aacp = "not supported"
                    ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).edit().putBoolean("aacp_unsupported", true).apply()
                    break
                }
                try { Thread.sleep(minOf(5000L * failures, 30000L)) } catch (x: InterruptedException) { break }
            } finally {
                try { socket?.close() } catch (e: Exception) { }
            }
        }
    }
}
