package com.earhop.logger

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.widget.Toast
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

// Clipboard sync, the same design as Clipboard.swift on the Mac: text goes over Bluetooth LE and Wi-Fi at the same
// time (the first copy wins, the other is recognised by its id), images over Wi-Fi only, everything AES-256-GCM
// encrypted with a key derived from the shared secret (ClipCodec).

/** Wi-Fi: a direct TCP connection to the peer, port 47475, one length prefixed frame per connection. */
class ClipTcp(private val port: Int = PORT, private val onFrame: (ByteArray) -> Unit) {
    companion object { const val PORT = 47475; const val MAX_FRAME = 16 * 1024 * 1024 }
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        Thread({
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                server = s
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    pool.execute { serve(c) }
                }
            } catch (e: Exception) { EhLog.w("clip: Wi-Fi listener not started: ${e.message}") }
        }, "earhop-clip-tcp").start()
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 30_000
            val inp = DataInputStream(c.getInputStream())
            val len = inp.readInt()
            if (len in 1..MAX_FRAME) { val b = ByteArray(len); inp.readFully(b); onFrame(b) }
        } catch (e: Exception) { } finally { try { c.close() } catch (e: Exception) { } }
    }

    fun send(frame: ByteArray, host: java.net.InetAddress) {
        pool.execute {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.soTimeout = 20_000
                    val out = java.io.DataOutputStream(s.getOutputStream())
                    out.writeInt(frame.size); out.write(frame); out.flush()
                }
            } catch (e: Exception) { EhLog.w("clip: Wi-Fi send to ${host.hostAddress} failed: ${e.message}") }
        }
    }

    fun stop() { try { server?.close() } catch (e: Exception) { }; pool.shutdownNow() }
}

/**
 * Bluetooth LE: a small GATT service other devices write clips into, and a client that connects to a peer for a
 * moment, writes the chunks and disconnects. Peers are known from their status advertisements (EhState.bleDevices).
 */
@SuppressLint("MissingPermission")
class ClipGatt(private val ctx: Context, private val onFrame: (ByteArray) -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("eac1b000-5eed-4c1e-8a00-000000000001")
        val INBOX: UUID = UUID.fromString("eac1b001-5eed-4c1e-8a00-000000000001")
        const val MAX_FRAME = 64 * 1024
    }
    private var server: BluetoothGattServer? = null
    private val assembler = ClipChunks.Assembler()
    private val worker = Executors.newSingleThreadExecutor()

    fun start() {
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val s = bm.openGattServer(ctx, object : BluetoothGattServerCallback() {
                override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
                                                          prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                    if (responseNeeded) try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (e: Exception) { }
                    if (ch.uuid == INBOX && value != null) assembler.add(value, device.address)?.let(onFrame)
                }
            }) ?: run { EhLog.w("clip: Bluetooth server not available"); return }
            val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(BluetoothGattCharacteristic(INBOX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE))
            s.addService(svc)
            server = s
        } catch (e: Exception) { EhLog.w("clip: Bluetooth server failed: ${e.message}") }
    }

    /** Sends a frame to each device (name to Bluetooth device), one after the other, each with a short connection. */
    fun send(frame: ByteArray, devices: List<Pair<String, BluetoothDevice>>) {
        if (frame.size > MAX_FRAME) return
        val tag = java.util.Random().nextInt()
        for ((name, d) in devices) worker.execute {
            if (!sendTo(frame, tag, name, d)) {
                // One retry with the device's newest address: some Android devices change it whenever they restart advertising.
                val fresh = EhState.bleDevices[name]?.first
                if (fresh != null) {
                    EhLog.w("clip: retrying $name with its newest Bluetooth address${if (fresh.address == d.address) " (unchanged)" else ""}")
                    sendTo(frame, tag, name, fresh)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendTo(frame: ByteArray, tag: Int, name: String, device: BluetoothDevice): Boolean {
        var stage = "connecting"
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val chunks = ClipChunks.split(frame, 172, tag)
        var next = 0
        var gatt: BluetoothGatt? = null
        val cb = object : BluetoothGattCallback() {
            fun writeNext(g: BluetoothGatt) {
                val ch = g.getService(SERVICE)?.getCharacteristic(INBOX) ?: run { done.countDown(); return }
                if (next >= chunks.size) { ok = true; done.countDown(); return }
                stage = "writing"
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = chunks[next]
                if (!g.writeCharacteristic(ch)) done.countDown()
            }
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) { stage = "setting up"; g.requestMtu(185) } else done.countDown()
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { stage = "finding the clipboard service"; g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { writeNext(g) }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { done.countDown(); return }
                next++; writeNext(g)
            }
        }
        try {
            gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            done.await(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { }
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        EhLog.w("clip: Bluetooth send to $name ${if (ok) "done" else "failed while $stage"}")
        return ok
    }

    fun stop() { try { server?.close() } catch (e: Exception) { }; worker.shutdownNow() }
}

/** Serves received clipboard images to other apps (the clipboard grants them read access when they paste). */
class ClipImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = File(context!!.cacheDir, "clip/" + (uri.lastPathSegment ?: return null))
        return if (f.exists()) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = File(context!!.cacheDir, "clip/" + (uri.lastPathSegment ?: return null))
        val c = android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * Clipboard sync on Android. Sends what you copy (automatically when Android lets EarHop read the clipboard in the
 * background, otherwise with the Send clipboard tile, the notification button or Share, EarHop) and puts clips from
 * the other devices on the clipboard.
 */
object ClipSync {
    @Volatile private var tcp: ClipTcp? = null
    @Volatile private var gatt: ClipGatt? = null
    private val seen = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ownWriteAt = 0L
    @Volatile private var lastSentHash = 0
    /** Hash of the last clip that arrived from another device, so it is never sent straight back. */
    @Volatile private var lastReceivedHash = 0
    @Volatile private var rootWatch: Process? = null
    @Volatile private var lastTrigger = 0L
    @Volatile var rootWatching = false
        private set
    private var diagLines = 0
    /** Whether a background read has worked on this device (Android 10 and later usually refuse it). */
    @Volatile var backgroundWorks: Boolean? = null
        private set
    @Volatile var lastEvent = ""
        private set
    @Volatile var lastEventAt = 0L
        private set
    const val MAX_TEXT = 1024 * 1024
    const val MAX_IMAGE = 10 * 1024 * 1024
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("clipSync", true)
    private fun images(ctx: Context) = prefs(ctx).getBoolean("clipImages", true)
    private fun receiving(ctx: Context) = prefs(ctx).getBoolean("clipReceive", true)
    /** Clipboard sync paused until a time set on Home or Devices (Pause buttons). */
    fun paused(ctx: Context) = System.currentTimeMillis() < prefs(ctx).getLong("clipPausedUntil", 0)
    /** "both", "ble" or "wifi": how clips travel, separate from the handoff connection setting. */
    fun method(ctx: Context) = prefs(ctx).getString("clipTransport", "both") ?: "both"
    /** Devices this one does not send clips to. */
    fun excluded(ctx: Context): Set<String> = prefs(ctx).getStringSet("clipExclude", emptySet()) ?: emptySet()

    private fun event(text: String) { lastEvent = text; lastEventAt = System.currentTimeMillis() }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        tcp = ClipTcp { f -> receive(app, f, "Wi-Fi") }.also { it.start() }
        gatt = ClipGatt(app) { f -> receive(app, f, "Bluetooth") }.also { it.start() }
        main.post {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            listener?.let { try { cm.removePrimaryClipChangedListener(it) } catch (e: Exception) { } }
            val l = ClipboardManager.OnPrimaryClipChangedListener {
                if (System.currentTimeMillis() - ownWriteAt < 1500) return@OnPrimaryClipChangedListener // our own write
                if (!enabled(app)) return@OnPrimaryClipChangedListener
                // Android 10 and later only allow this while EarHop is in front, unless background access was granted.
                val clip = try { cm.primaryClip } catch (e: Exception) { null }
                if (clip == null) {
                    if (backgroundWorks != false) EhLog.w("clip: Android did not let EarHop read the clipboard in the background; use the Send clipboard tile")
                    backgroundWorks = false
                } else {
                    backgroundWorks = true
                    // auto: some phones report a change again whenever the clipboard is read, so the same clip
                    // must never be sent twice from here, or EarHop sends it every second while it is on screen.
                    sendClip(app, clip, auto = true)
                }
            }
            cm.addPrimaryClipChangedListener(l)
            listener = l
        }
        tryStartAuto(app)
    }

    // ---------- the clipboard helper (phone, no root) ----------

    /** When the helper last said hello (it does every 60 s). */
    @Volatile var helperAt = 0L
        private set
    fun helperRunning() = System.currentTimeMillis() - helperAt < 150_000

    /** A message from the shell-user helper (ClipHelper), through ClipHelperProvider, which checked the caller. */
    fun onHelper(app: Context, method: String, text: String?) {
        val first = !helperRunning()
        helperAt = System.currentTimeMillis()
        if (first) {
            EhLog.w("clip: clipboard helper connected, copies here are sent automatically")
            if (rootWatching && !watchRoot) stopRootWatch() // the log watch is not needed any more
        }
        if (method != "text" && method != "big") return
        if (!enabled(app) || !autoRoot(app) || !EhState.running || paused(app)) return
        if (System.currentTimeMillis() - ownWriteAt < 2000) return // our own write of a clip that just arrived
        EhLog.w("clip: copy noticed (helper)")
        if (method == "big" || text == null) {
            // Too long for the helper to pass on: read it with the invisible send screen ("Display over other apps").
            try { app.startActivity(Intent(app, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("auto", true)) }
            catch (e: Exception) { EhLog.w("clip: could not open the send screen: ${e.message}") }
            return
        }
        main.post { sendClip(app, ClipData.newPlainText("copy", text), auto = true) }
    }

    fun autoRoot(ctx: Context) = prefs(ctx).getBoolean("clipAutoRoot", true)

    /** Without root: log access (granted once from a computer) and "Display over other apps" (Settings). */
    fun hasLogAccess(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun hasOverlay(ctx: Context) = android.provider.Settings.canDrawOverlays(ctx)

    /** Starts automatic sending when it is switched on and possible: with root, or with the two permissions. */
    fun tryStartAuto(ctx: Context) {
        if (rootWatching || !autoRoot(ctx) || !enabled(ctx)) return
        when {
            prefs(ctx).getBoolean("rootGranted", false) -> startRootWatch(ctx, root = true)
            helperRunning() -> {} // the helper already sends every copy
            hasLogAccess(ctx) && hasOverlay(ctx) -> startRootWatch(ctx, root = false)
        }
    }

    /** "root" or "log access" while automatic sending runs. */
    @Volatile var watchMode = ""
        private set
    @Volatile private var watchRoot = true

    /**
     * Android 10 and later neither let a background app read the clipboard nor tell it that the clipboard changed. But
     * when it changes, Android logs that it refused EarHop ("Denying clipboard access to ..."). Reading the system log,
     * EarHop notices the copy and opens its invisible send screen, which reads the clipboard with focus and sends it.
     * With root: the log is read and the screen opened as root (no prompts). Without root: the log is read with the
     * READ_LOGS permission (granted once from a computer; Android 13 and later may ask to allow log access when EarHop
     * starts), and the screen is opened with "Display over other apps", which lifts the background launch block.
     */
    fun startRootWatch(ctx: Context, root: Boolean = true) {
        if (rootWatching) return
        val app = ctx.applicationContext
        rootWatching = true
        watchRoot = root
        watchMode = if (root) "root" else "log access"
        logOk = null; foreignLines = 0; diagLines = 0
        // After a few seconds: if no other app's line was seen, Android is showing EarHop only its own log.
        Handler(Looper.getMainLooper()).postDelayed({
            if (rootWatching && logOk == null && !helperRunning()) {
                logOk = false
                EhLog.w("clip: Android shows EarHop only its own log lines; open EarHop and tap Allow when Android asks for log access")
            }
        }, 8000)
        Thread({
            var failures = 0
            while (rootWatching) {
                try {
                    val p = (if (root) ProcessBuilder("su", "-c", "logcat -v brief -T 1 *:W")
                        else ProcessBuilder("logcat", "-v", "brief", "-T", "1", "*:W")).redirectErrorStream(true).start()
                    rootWatch = p
                    EhLog.w("clip: watching for copies through the system log ($watchMode)")
                    p.inputStream.bufferedReader().forEachLine { line -> onLogLine(app, line) }
                } catch (e: Exception) { EhLog.w("clip: root log watch failed: ${e.message}") }
                rootWatch = null
                if (!rootWatching) break
                failures++
                Thread.sleep(minOf(60_000L, 2000L shl minOf(failures, 5)))
            }
        }, "earhop-clip-root").start()
    }

    fun stopRootWatch() {
        rootWatching = false
        try { rootWatch?.destroy() } catch (e: Exception) { }
        rootWatch = null
    }

    /**
     * Whether the log watch can see other apps' lines: null until checked, false when Android shows EarHop only its own
     * (Android 13 and later ask "Allow access to all device logs?" only while EarHop is on screen).
     */
    @Volatile var logOk: Boolean? = null
        private set
    @Volatile private var foreignLines = 0
    private val myPid = android.os.Process.myPid()

    /** Restarts the log watch, used while EarHop is on screen so Android can ask for log access. */
    fun restartLogWatch(ctx: Context) {
        if (!rootWatching || watchRoot || helperRunning()) return
        EhLog.w("clip: restarting the log watch while EarHop is open, so Android can ask for log access")
        stopRootWatch()
        Handler(Looper.getMainLooper()).postDelayed({ tryStartAuto(ctx) }, 400)
    }

    private fun onLogLine(ctx: Context, line: String) {
        // "W/Tag( 1234): message": count lines from other processes to know whether log access was granted.
        if (foreignLines < 5) {
            val pid = line.substringAfter('(', "").substringBefore(')').trim().toIntOrNull()
            if (pid != null && pid != myPid) { foreignLines++; if (logOk != true) { logOk = true; EhLog.w("clip: log access works") } }
        }
        if (!line.contains(ctx.packageName) || !line.contains("lipboard", ignoreCase = true)) return
        if (diagLines < 3) { diagLines++; EhLog.w("clip: system log: ${line.take(160)}") }
        if (!line.contains("Deny", ignoreCase = true) && !line.contains("not in focus", ignoreCase = true)) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 1200 || now - ownWriteAt < 2000 || !enabled(ctx) || !EhState.running) return
        lastTrigger = now
        EhLog.w("clip: copy noticed (system log)")
        // Open the invisible send screen: as root, or directly (allowed with "Display over other apps").
        if (watchRoot) RootShell.run("am start -n ${ctx.packageName}/.ClipActivity --ez auto true", 5)
        else try {
            ctx.startActivity(Intent(ctx, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("auto", true))
        } catch (e: Exception) { EhLog.w("clip: could not open the send screen: ${e.message}") }
    }

    fun stop(ctx: Context) {
        stopRootWatch()
        try { listener?.let { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(it) } } catch (e: Exception) { }
        tcp?.stop(); gatt?.stop(); tcp = null; gatt = null
    }

    /** Reads the clip (call while EarHop has focus or background access) and sends it. */
    fun sendClip(ctx: Context, clip: ClipData, auto: Boolean = false): String {
        if (clip.itemCount == 0) return "The clipboard is empty"
        if (auto && paused(ctx)) return "Clipboard sync is paused"
        val desc = clip.description
        // Android 13 and later: apps mark passwords and similar as sensitive. Never send those.
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            EhLog.w("clip: skipped a sensitive item"); return "Not sent: marked as sensitive"
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && (desc.hasMimeType("image/*"))) {
            if (!images(ctx)) return "Image sending is off"
            val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
                ?: return "Could not read the image"
            if (bytes.size > MAX_IMAGE) return "Image too large (${bytes.size / 1024} KB)"
            return send(ctx, "image", ctx.contentResolver.getType(uri) ?: "image/png", bytes)
        }
        val text = item.coerceToText(ctx)?.toString() ?: return "Nothing to send"
        if (text.isEmpty()) return "The clipboard is empty"
        val b = text.toByteArray()
        // Automatic sends skip what just arrived from another device or was already sent.
        if (auto && (b.contentHashCode() == lastReceivedHash || b.contentHashCode() == lastSentHash)) return "Already synced"
        if (b.size > MAX_TEXT) return "Text too large"
        return send(ctx, "text", "text/plain", b)
    }

    private fun send(ctx: Context, kind: String, mime: String, data: ByteArray): String {
        val hash = data.contentHashCode()
        val p = prefs(ctx)
        val id = (1..8).joinToString("") { "%02x".format(java.util.Random().nextInt(256)) }
        val name = p.getString("name", "phone") ?: "phone"
        val frame = ClipCodec.encode(ClipItem(id, name, kind, mime, System.currentTimeMillis(), data), p.getString("secret", "") ?: "") ?: return "Could not encrypt"
        remember(id)
        lastSentHash = hash
        val mode = method(ctx)
        val skip = excluded(ctx)
        val links = ArrayList<String>()
        // The clipboard's own method (Clipboard card). Text: Bluetooth LE and Wi-Fi at the same time. Images: Wi-Fi only.
        var bleCarries = false
        if (kind == "text" && mode != "wifi" && frame.size <= ClipGatt.MAX_FRAME) {
            val now = System.currentTimeMillis()
            val devs = EhState.bleDevices.filter { it.key != name && it.key !in skip && now - it.value.second < 60_000 }.map { Pair(it.key, it.value.first) }
            if (devs.isNotEmpty()) { gatt?.send(frame, devs); links += "Bluetooth"; bleCarries = true }
        }
        // Wi-Fi as well, unless "Bluetooth" is chosen and Bluetooth can carry this clip (images and text over 64 KB cannot).
        if (mode != "ble" || !bleCarries) {
            val hosts = EhState.wifiPeers(skip)
            hosts.forEach { tcp?.send(frame, it) }
            if (hosts.isNotEmpty()) links += "Wi-Fi"
        }
        val what = if (kind == "text") "text (${data.size} bytes)" else "image (${data.size / 1024} KB)"
        return if (links.isEmpty()) {
            EhLog.w("clip: $what not sent, no other device ${if (kind == "image") "on Wi-Fi" else "in reach"} (method $mode)")
            event("Not sent, no device in reach")
            "No other device in reach"
        } else {
            EhLog.w("clip: sent $what over ${links.joinToString(" and ")}")
            event("Sent ${if (kind == "text") "text" else "an image"}")
            "Sent to your other devices"
        }
    }

    @Synchronized private fun remember(id: String) { seen += id; while (seen.size > 100) seen.removeAt(0) }
    @Synchronized private fun known(id: String) = id in seen

    private fun receive(ctx: Context, frame: ByteArray, via: String) {
        if (!enabled(ctx) || !receiving(ctx) || paused(ctx)) return
        val p = prefs(ctx)
        val item = ClipCodec.decode(frame, p.getString("secret", "") ?: "") ?: return
        if (item.dev == p.getString("name", "phone")) return
        if (Math.abs(System.currentTimeMillis() - item.ts) > ClipCodec.MAX_AGE_MS || known(item.id)) return
        remember(item.id)
        main.post { apply(ctx, item, via) }
    }

    private fun apply(ctx: Context, item: ClipItem, via: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = when (item.kind) {
            "text" -> ClipData.newPlainText("EarHop", String(item.data, Charsets.UTF_8))
            "image" -> {
                if (!images(ctx)) return
                val dir = File(ctx.cacheDir, "clip").apply { mkdirs() }
                dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
                val f = File(dir, "${item.id}.png").apply { writeBytes(item.data) }
                ClipData.newUri(ctx.contentResolver, "EarHop image", Uri.parse("content://${ctx.packageName}.clip/${f.name}"))
            }
            else -> return
        }
        ownWriteAt = System.currentTimeMillis()
        lastReceivedHash = if (item.kind == "text") String(item.data, Charsets.UTF_8).toByteArray().contentHashCode() else item.data.contentHashCode()
        try { cm.setPrimaryClip(clip) } catch (e: Exception) { EhLog.w("clip: could not set the clipboard: ${e.message}"); return }
        EhLog.w("clip: received ${item.kind} from ${item.dev} over $via")
        event("Received ${if (item.kind == "text") "text" else "an image"} from ${item.dev}")
    }
}

/**
 * Invisible screen that reads the clipboard (Android only allows that with focus) and sends it, then closes. Opened
 * by the Send clipboard tile, the notification button, and Share, EarHop from other apps.
 */
class ClipActivity : Activity() {
    private var done = false

    private val auto get() = intent?.getBooleanExtra("auto", false) == true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (intent?.action == Intent.ACTION_SEND) { done = true; sendShared(); finish() }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val msg = if (!EhState.running) "Start EarHop first" else if (clip == null) "The clipboard is empty" else ClipSync.sendClip(applicationContext, clip, auto)
        // Automatic sends stay silent; only a manual send confirms with a toast.
        if (!auto) Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun sendShared() {
        if (!EhState.running) { Toast.makeText(applicationContext, "Start EarHop first", Toast.LENGTH_SHORT).show(); return }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val clip = when {
            stream != null && (intent.type ?: "").startsWith("image/") -> ClipData("Shared", arrayOf(intent.type), ClipData.Item(stream))
            text != null -> ClipData.newPlainText("Shared", text)
            else -> null
        }
        val msg = if (clip == null) "Nothing to send" else ClipSync.sendClip(applicationContext, clip)
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}

/** Quick settings tile: send what is on the clipboard to the other devices. */
class ClipTile : TileService() {
    override fun onStartListening() {
        qsTile?.let {
            it.label = "Send clipboard"
            it.state = if (EhState.running) android.service.quicksettings.Tile.STATE_INACTIVE else android.service.quicksettings.Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = if (EhState.running) "To your other devices" else "EarHop is stopped"
            it.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 40, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(i)
    }
}
