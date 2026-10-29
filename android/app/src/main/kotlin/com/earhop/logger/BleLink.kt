package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Bluetooth LE payload, 16 bytes carried as one 128 bit service UUID.
 * [0]=0xEA [1]=dev code [2..3]=seq [4]=flags [5..12]=truncated HMAC of bytes 0..4 and 13..15
 * [13..15]=minutes since 1970 (low 24 bits). The signed minute stamp refuses recordings older than 2 minutes.
 * Flags: bit0 playing, bits1-2 ear (0 unknown 1 none 2 one 3 both), bit3 owner, bit4 call, bit5 earbud profile known,
 * bit6 profile is Galaxy Buds (else AirPods).
 */
object BlePayload {
    val devCodes = mapOf("mac" to 0, "phone" to 1, "tab" to 2)
    private val ears = listOf("unknown", "none", "one", "both")

    fun minute(ms: Long): Int = ((ms / 60000) and 0xFFFFFF).toInt()

    fun encode(s: Status, secret: String, minute: Int = minute(System.currentTimeMillis())): ByteArray? {
        val dev = devCodes[s.dev] ?: return null
        val flags = (if (s.playing) 1 else 0) or (ears.indexOf(s.ear).coerceAtLeast(0) shl 1) or
            (if (s.owner) 8 else 0) or (if (s.call) 16 else 0) or
            when (s.prof) { "airpods" -> 32; "buds" -> 96; else -> 0 }
        val head = byteArrayOf(0xEA.toByte(), dev.toByte(), ((s.seq shr 8) and 0xFF).toByte(), (s.seq and 0xFF).toByte(), flags.toByte())
        val stamp = byteArrayOf(((minute shr 16) and 0xFF).toByte(), ((minute shr 8) and 0xFF).toByte(), (minute and 0xFF).toByte())
        return head + Proto.signRaw(secret, head + stamp).copyOfRange(0, 8) + stamp
    }

    fun decode(b: ByteArray, secret: String, nowMinute: Int = minute(System.currentTimeMillis())): Status? {
        if (b.size != 16 || b[0] != 0xEA.toByte()) return null
        val mac = Proto.signRaw(secret, b.copyOfRange(0, 5) + b.copyOfRange(13, 16)).copyOfRange(0, 8)
        if (!java.security.MessageDigest.isEqual(mac, b.copyOfRange(5, 13))) return null
        val m = ((b[13].toInt() and 0xFF) shl 16) or ((b[14].toInt() and 0xFF) shl 8) or (b[15].toInt() and 0xFF)
        val d = (nowMinute - m) and 0xFFFFFF
        if (minOf(d, 0x1000000 - d) > 2) return null
        val name = devCodes.entries.firstOrNull { it.value == (b[1].toInt() and 0xFF) }?.key ?: return null
        val f = b[4].toInt() and 0xFF
        val seq = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val prof = if (f and 32 == 0) "" else if (f and 64 != 0) "buds" else "airpods"
        return Status(name, System.currentTimeMillis(), seq, f and 1 != 0, ears[(f shr 1) and 3], f and 8 != 0, f and 16 != 0, prof)
    }

    fun isNewer(new: Int, old: Int): Boolean {
        val d = (new - old) and 0xFFFF
        return d != 0 && d < 0x8000
    }

    fun toUuid(b: ByteArray): UUID {
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (b[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (b[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    fun fromUuid(u: UUID): ByteArray {
        val b = ByteArray(16)
        for (i in 0 until 8) b[i] = (u.mostSignificantBits shr (56 - 8 * i)).toByte()
        for (i in 0 until 8) b[8 + i] = (u.leastSignificantBits shr (56 - 8 * i)).toByte()
        return b
    }
}

/** Fallback transport: advertises this device status and listens for the others. */
class BleLink(private val ctx: Context, private val secret: String, private val selfName: String,
              private val onStatus: (Status) -> Unit) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    /** Per device: the last advertisement passed on and when, so the steady stream of repeats is thinned out. */
    private val lastForward = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    /** When a device was last heard over Bluetooth LE. */
    @Volatile var lastPeerAt = 0L
        private set
    @Volatile private var activatedAt = 0L
    /** With no device heard over Bluetooth LE for a minute, scan and advertise more gently (still often enough to be seen). */
    @Volatile private var lowPower = false
    private var lastKey = ""
    private var lastAdvertAt = 0L
    @Volatile var active = false
        private set

    val supported: Boolean get() = BlePayload.devCodes.containsKey(selfName)

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val uuids = r.scanRecord?.serviceUuids ?: return
            for (pu in uuids) {
                val st = BlePayload.decode(BlePayload.fromUuid(pu.uuid), secret) ?: continue
                if (st.dev == selfName) continue
                val now = System.currentTimeMillis()
                lastPeerAt = now
                r.device?.let { EhState.bleDevices[st.dev] = Pair(it, now) }
                // The scan reports the same advertisement many times a second. Pass on a new one at once and a repeat
                // every 0.5 s; the service decides which copy is newest and treats repeats as "still here".
                val last = lastForward[st.dev]
                if (last != null && last.first == st.seq && now - last.second < 500) continue
                lastForward[st.dev] = Pair(st.seq, now)
                onStatus(st)
            }
        }
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { EhLog.w("ble: advertise failed code=$errorCode") }
    }

    @SuppressLint("MissingPermission")
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (on) {
            advertiser = adapter?.bluetoothLeAdvertiser
            scanner = adapter?.bluetoothLeScanner
            activatedAt = System.currentTimeMillis()
            lowPower = false
            startScan()
        } else {
            try { scanner?.stopScan(scanCb) } catch (e: Exception) { }
            try { advertiser?.stopAdvertising(advCb) } catch (e: Exception) { }
            lastKey = ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val magic = UUID.fromString("ea000000-0000-0000-0000-000000000000")
        val mask = UUID.fromString("ff000000-0000-0000-0000-000000000000")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(magic), ParcelUuid(mask)).build()
        // Balanced in low power, so a device that appears is still noticed within a few seconds.
        val mode = if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        try {
            scanner?.stopScan(scanCb)
            scanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(mode).build(), scanCb)
        } catch (e: Exception) { EhLog.w("ble: scan failed ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun advertise(s: Status) {
        if (!active || !supported) return
        val quiet = System.currentTimeMillis() - maxOf(lastPeerAt, activatedAt) > 60000
        if (quiet != lowPower) {
            lowPower = quiet
            EhLog.w("ble: ${if (quiet) "no device heard for a minute, low power" else "device heard, normal rate"}")
            startScan()
            lastKey = "" // restart the advertisement with the new rate
        }
        // Same sequence number as Wi-Fi (low 16 bits), so receivers can tell which copy is newer.
        val bytes = BlePayload.encode(s.copy(seq = s.seq and 0xFFFF), secret) ?: return
        val key = "${bytes[1]}${bytes[4]}"
        val now = System.currentTimeMillis()
        // Restart only when the state changed (or once a minute). Some devices give every restart a new
        // random Bluetooth address, which breaks connections other devices are opening to send clipboard text.
        if (key == lastKey && now - lastAdvertAt < 60_000) return
        lastKey = key
        lastAdvertAt = now
        val adv = advertiser ?: return
        adv.stopAdvertising(advCb)
        adv.startAdvertising(
            // Balanced (about 4 a second) is quick enough and leaves the radio room for the earbuds' audio.
            AdvertiseSettings.Builder().setAdvertiseMode(if (lowPower) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                // Connectable, so other devices can open a short connection to send clipboard text.
                .setConnectable(true).setTimeout(0).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(BlePayload.toUuid(bytes))).build(),
            advCb,
        )
    }
}
