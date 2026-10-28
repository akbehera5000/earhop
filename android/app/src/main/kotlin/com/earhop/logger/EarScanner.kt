package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Reads the AirPods proximity pairing beacon (see project brief).
 * Manufacturer payload after the company id: [0]=0x07 [1]=len [2]=0x01 [3..4]=model (little endian) [5]=status.
 * Starts with a filtered scan, and falls back to an unfiltered scan if no beacon arrives, because some
 * phones drop manufacturer data filters.
 */
class EarScanner(private val ctx: Context, private val minRssi: Int, private val allowAnyModel: Boolean) {
    private val knownModels = setOf(0x2014, 0x2924, 0x2024, 0x200E, 0x2013)
    private var lastIgnoredLog = 0L
    /** One entry per broadcasting address; rssi is smoothed so one weak packet does not move it. */
    private class Source(var status: Int, var count: Int, var rssi: Double, var at: Long)
    private val sources = java.util.concurrent.ConcurrentHashMap<String, Source>()
    @Volatile private var aacpEar: String? = null
    private var scanner: BluetoothLeScanner? = null
    private val main = Handler(Looper.getMainLooper())
    private var gentle = false
    private var started = false

    private var beaconVal: String? = null
    private var beaconChangedAt = 0L
    private var beaconSeenAt = 0L
    private var candidate: String? = null
    private var candidateSince = 0L
    private var aacpChangedAt = 0L
    private var resolved = ""

    /** Instant in-ear state from the direct control channel, or null when it is not connected. */
    @Synchronized
    fun setAacp(ear: String?) {
        if (ear != aacpEar) aacpChangedAt = System.currentTimeMillis()
        aacpEar = ear
    }

    /**
     * Beacon state: the earbuds may broadcast from more than one address, so each source is tracked
     * separately and the lowest in-ear count among fresh sources wins. A new value must hold for
     * 300 ms before it is adopted, which filters out single stray packets.
     */
    private fun beaconNow(now: Long) {
        val fresh = sources.values.filter { now - it.at < 2500 }
        if (fresh.isEmpty()) return
        beaconSeenAt = fresh.maxOf { it.at }
        val n = BeaconPick.pick(fresh.map { Pair(it.count, it.rssi) }) ?: return
        val raw = when (n) { 2 -> "both"; 1 -> "one"; else -> "none" }
        if (beaconVal == null) { beaconVal = raw; beaconChangedAt = now; return }
        if (raw == beaconVal) { candidate = null; return }
        if (raw != candidate) { candidate = raw; candidateSince = now }
        // Both out is reliable on this beacon; a later "one" is often the second bud reporting late, so it must hold
        // for a second before it can replace "none". Other changes need 300 ms.
        else if (now - candidateSince >= (if (beaconVal == "none" && raw == "one") 1000L else 300L)) {
            beaconVal = raw; beaconChangedAt = now; candidate = null
        }
    }

    /**
     * The final ear state. Two signals can exist, the beacon and the direct channel. Whichever one
     * changed most recently wins, so a signal that stopped updating can never stay stuck and override
     * a fresh change from the other.
     */
    /** Where the last value came from: direct, beacon or none. */
    @Volatile var via = "none"; private set

    /**
     * Ear state with confidence. The direct channel is exact. On the beacon, "none" is reliable, while the second bud's
     * removal can arrive 24 to 33 s late, so "one" and "both" are medium.
     */
    val reading: EarReading
        get() {
            val e = ear
            return when {
                e == "unknown" -> EarReading.UNKNOWN
                via == "direct" -> EarReading(e, "airpods-direct", "high")
                e == "none" -> EarReading(e, "airpods-beacon", "high")
                else -> EarReading(e, "airpods-beacon", "medium")
            }
        }

    val ear: String
        @Synchronized get() {
            val now = System.currentTimeMillis()
            beaconNow(now)
            val b = if (beaconVal != null && now - beaconSeenAt < 6000) beaconVal else null
            val a = aacpEar
            val (value, via) = when {
                a != null && b != null -> if (aacpChangedAt >= beaconChangedAt) Pair(a, "direct") else Pair(b, "beacon")
                a != null -> Pair(a, "direct")
                b != null -> Pair(b, "beacon")
                else -> Pair("unknown", "none")
            }
            this.via = via
            val key = "$value/$via"
            if (key != resolved) {
                resolved = key
                if (value != "unknown") EhLog.w("ear: CHANGE -> $value (via $via, sources=${sources.size})")
            }
            return value
        }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val md = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
            EhState.appleSeen++
            if (md.size < 6 || md[0] != 0x07.toByte()) return
            val rssi = r.rssi
            val model = ((md[4].toInt() and 0xFF) shl 8) or (md[3].toInt() and 0xFF)
            val status = md[5].toInt() and 0xFF
            if (rssi < minRssi) return
            if (!allowAnyModel && model !in knownModels) {
                val t = System.currentTimeMillis()
                if (t - lastIgnoredLog > 30000) { lastIgnoredLog = t; EhLog.w("ear: ignored other AirPods model=%04x rssi=$rssi".format(model)) }
                return
            }
            var n = 0
            if (status and 0x02 != 0) n++
            if (status and 0x08 != 0) n++
            val now = System.currentTimeMillis()
            val addr = r.device?.address ?: "?"
            val src = sources.getOrPut(addr) { Source(-1, -1, rssi.toDouble(), now) }
            if (src.status != status) {
                EhLog.w("ear: src ${addr.takeLast(5)} status=0x%02x (in-ear count $n) rssi=$rssi".format(status))
            }
            src.status = status; src.count = n; src.rssi = src.rssi * 0.7 + rssi * 0.3; src.at = now
            sources.entries.removeIf { now - it.value.at > 30000 }
            EhState.beaconAt = now
            // Battery only from the strongest source: your own AirPods, not a pair nearby.
            val strongest = sources.values.filter { now - it.at < 10000 }.maxByOrNull { it.rssi }
            if (md.size > 6 && strongest === src) {
                val pods = md[6].toInt() and 0xFF
                val a = pods shr 4; val b = pods and 0x0F
                EhState.batA = if (a <= 10) a * 10 else -1
                EhState.batB = if (b <= 10) b * 10 else -1
                val cs = if (md.size > 7) (md[7].toInt() and 0x0F) else 15
                EhState.batCase = if (cs <= 10) cs * 10 else -1
                EhState.batAt = now
            }
            EhState.beaconInfo = "status=0x%02x model=%04x rssi=$rssi sources=${sources.size}".format(status, model)
        }

        override fun onScanFailed(errorCode: Int) {
            EhLog.w("ear: scan failed code=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(filtered: Boolean) {
        val sc = scanner ?: return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(if (gentle) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            if (filtered) {
                val filter = ScanFilter.Builder()
                    .setManufacturerData(0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build()
                sc.startScan(listOf(filter), settings, callback)
            } else {
                sc.startScan(null, settings, callback)
            }
            EhState.scanMode = if (filtered) "filtered" else "unfiltered"
            EhLog.w("ear: ${EhState.scanMode} scan started${if (gentle) " (gentle)" else ""}")
        } catch (e: SecurityException) {
            EhLog.w("ear: scan blocked, grant location and Bluetooth permission")
        } catch (e: Exception) {
            EhLog.w("ear: scan error ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = bm.adapter?.bluetoothLeScanner
        if (scanner == null) {
            EhLog.w("ear: bluetooth off or unavailable")
            return
        }
        started = true
        startScan(true)
        // If the filter delivers nothing, switch to an unfiltered scan.
        main.postDelayed({
            if (EhState.beaconAt == 0L && scanner != null) {
                EhLog.w("ear: no beacon with filter after 10s, trying an unfiltered scan")
                try { scanner?.stopScan(callback) } catch (e: Exception) { }
                startScan(false)
            }
        }, 10000)
    }

    /** While audio streams to the AirPods, scan less aggressively to avoid crackling. */
    @SuppressLint("MissingPermission")
    fun setGentle(on: Boolean) {
        if (on == gentle || !started) return
        gentle = on
        try { scanner?.stopScan(callback) } catch (e: Exception) { }
        startScan(EhState.scanMode != "unfiltered")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(callback) } catch (e: Exception) { }
        scanner = null
    }
}
