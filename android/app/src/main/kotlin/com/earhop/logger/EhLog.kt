package com.earhop.logger

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Log mirrored to logcat tag EarHop. The last 400 lines stay in memory for the screen; every line is also appended to
 * files/earhop.log (rotated at 1 MB to earhop.log.1), so Copy log still works after a restart or a crash.
 */
object EhLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var listener: (() -> Unit)? = null
    @Volatile private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Called once from the Application, before anything logs. */
    fun init(ctx: android.content.Context) {
        file = java.io.File(ctx.filesDir, "earhop.log")
        // A copy in EarHop's own folder in shared storage, readable from a computer without root:
        // adb shell cat /sdcard/Android/data/com.earhop.logger/files/earhop.log
        mirror = try { ctx.getExternalFilesDir(null)?.let { java.io.File(it, "earhop.log") } } catch (e: Exception) { null }
    }
    @Volatile private var mirror: java.io.File? = null

    @Synchronized
    fun w(msg: String) {
        Log.i("EarHop", msg)
        val line = "${fmt.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > 400) lines.removeFirst()
        file?.let { f ->
            writer.execute {
                try {
                    if (f.length() > 1_000_000) { val old = java.io.File(f.path + ".1"); old.delete(); f.renameTo(old) }
                    f.appendText(line + "\n")
                    mirror?.let { m -> if (m.length() > 1_000_000) m.writeText(""); m.appendText(line + "\n") }
                } catch (e: Exception) { }
            }
        }
        listener?.invoke()
    }

    /** The recent lines in memory, for the screen. */
    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    /** The last n lines from the files (across a rotation), for Copy log. Falls back to memory. */
    fun dumpAll(n: Int = 1500): String {
        val f = file ?: return dump()
        return try {
            val old = java.io.File(f.path + ".1").takeIf { it.exists() }?.readLines() ?: emptyList()
            val cur = if (f.exists()) f.readLines() else emptyList()
            (old + cur).takeLast(n).joinToString("\n").ifEmpty { dump() }
        } catch (e: Exception) { dump() }
    }
}

/** One line of the Status card: ok true (working), false (not working) or null (just information). */
data class StatusRow(val title: String, val ok: Boolean?, val detail: String)

data class PeerState(
    val name: String, val playing: Boolean, val ear: String, val owner: Boolean,
    val call: Boolean, val via: String, val seenAt: Long,
    val playingSince: Long = 0L, val lastPlayingAt: Long = 0L,
    val seq: Int = 0, val ts: Long = 0L,
    /** The earbuds that device uses ("airpods", "buds", "" unknown), used to follow the owner's pair automatically. */
    val prof: String = "",
    /** Protocol version of the copy taken; with v4, ts is the sender's own clock. */
    val v: Int = 4,
    /** v4: the user was active on that device within the last 30 s. */
    val att: Boolean = false,
)

data class Switch(val at: Long, val kind: String, val reason: String)

/** Live state shared between the service and the screen. */
object EhState {
    @Volatile var running = false
    @Volatile var transport = "starting"
    @Volatile var ear = "unknown"
    @Volatile var playing = false
    @Volatile var owner = false
    @Volatile var call = false
    /** True while EarHop has moved media to this device's speaker because the earbuds are out (speaker fallback). */
    @Volatile var onSpeaker = false
    @Volatile var error: String? = null
    @Volatile var beaconAt = 0L
    @Volatile var beaconInfo = ""
    @Volatile var appleSeen = 0
    @Volatile var scanMode = ""
    @Volatile var aacp = "off"
    @Volatile var buds = "off"
    @Volatile var budsClient: BudsClient? = null
    @Volatile var batA = -1
    @Volatile var batB = -1
    @Volatile var batCase = -1
    @Volatile var batAt = 0L
    @Volatile var controller: HandoffController? = null
    @Volatile var phase = HandoffPhase.IDLE
    /** Switching progress shown on screen, or null when nothing runs. */
    @Volatile var stage: String? = null
    @Volatile var holdReason: String? = null
    @Volatile var lastFailure: String? = null
    @Volatile var safeMode = false
    @Volatile var earReading = EarReading.UNKNOWN
    /** Devices whose packets are refused: name to "secret" or "clock". */
    val rejected = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Device names used by two devices at once, with when it was last seen. */
    val duplicates = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Bluetooth device behind each peer's status advertisements, and when it was seen (for clipboard sync). */
    val bleDevices = java.util.concurrent.ConcurrentHashMap<String, Pair<android.bluetooth.BluetoothDevice, Long>>()
    /** Wi-Fi addresses of the other devices, set by the service. */
    @Volatile var wifiPeers: (Set<String>) -> List<java.net.InetAddress> = { emptyList() }
    /** Wi-Fi addresses of one device. */
    @Volatile var wifiHostsOf: (String) -> List<java.net.InetAddress> = { emptyList() }
    /** The Status card's rows (title, working or not, detail), set by the service. */
    @Volatile var statusRows: () -> List<StatusRow> = { emptyList() }
    fun statusLines(): List<String> = statusRows().map { "${it.title}: ${when (it.ok) { true -> "ok"; false -> "NOT working"; null -> "-" }}, ${it.detail}" }
    val history = java.util.concurrent.CopyOnWriteArrayList<Switch>()
    fun record(kind: String, reason: String) {
        history.add(0, Switch(System.currentTimeMillis(), kind, reason))
        while (history.size > 30) history.removeAt(history.size - 1)
    }
    val peers = java.util.concurrent.ConcurrentHashMap<String, PeerState>()
    /** Sleep timer: when it pauses and releases (0 when off). */
    @Volatile var sleepUntil = 0L
    /** When a device's copies were last refused for being far off its clock offset (for the status page). */
    val clockRefused = java.util.concurrent.ConcurrentHashMap<String, Long>()
}
