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
    /** Whether a connecting address belongs to one of the user's devices. */
    @Volatile var allowed: (java.net.InetAddress) -> Boolean = { true }
    private val open = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    /** True while the Wi-Fi clipboard port is open (status card). */
    val listening: Boolean get() = server?.let { it.isBound && !it.isClosed } == true

    fun start() {
        Thread({
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                server = s
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    // Only the user's devices (addresses learned from a signed status in the last 10 minutes), at most
                    // 4 at a time: anything else on the network is closed at once and cannot tie the listener up.
                    if (!allowed(c.inetAddress) || open.get() >= 4) { try { c.close() } catch (e: Exception) { }; continue }
                    open.incrementAndGet()
                    pool.execute { try { serve(c) } finally { open.decrementAndGet() } }
                }
            } catch (e: Exception) { EhLog.w("clip: Wi-Fi listener not started: ${e.message}") }
        }, "earhop-clip-tcp").start()
    }

    /** A file stream arrived: handled by the clipboard sync (checks the header, saves the file). */
    @Volatile var onFileStream: ((DataInputStream) -> Unit)? = null

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 10_000 // the length must arrive within 10 s
            val inp = DataInputStream(c.getInputStream().buffered(1 shl 16))
            val len = inp.readInt()
            c.soTimeout = 30_000
            if (len == FileStream.MAGIC_INT) { onFileStream?.invoke(inp); return }
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
class ClipGatt(private val ctx: Context, private val selfName: String, private val secret: String, private val onFrame: (ByteArray) -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("eac1b000-5eed-4c1e-8a00-000000000001")
        val INBOX: UUID = UUID.fromString("eac1b001-5eed-4c1e-8a00-000000000001")
        /** Read: this device's name (v4 advertisements carry only an id). */
        val NAME: UUID = UUID.fromString("eac1b002-5eed-4c1e-8a00-000000000001")
        const val MAX_FRAME = 64 * 1024
    }
    @Volatile private var server: BluetoothGattServer? = null
    /** True once Android confirmed the clipboard service was added (for the status page). */
    @Volatile var serviceUp = false
        private set
    private val lookedUp = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val assembler = ClipChunks.Assembler()
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Opens the clipboard service, closing any earlier one first. Called again after Android's Bluetooth restarts:
     * the old server disappears with it, and other devices then find no clipboard service here.
     */
    @Synchronized
    fun start() {
        try { server?.close() } catch (e: Exception) { }
        server = null
        serviceUp = false
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (bm.adapter?.isEnabled != true) { EhLog.w("clip: Bluetooth is off, the clipboard service opens when it is back"); return }
            val s = bm.openGattServer(ctx, object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    if (status != BluetoothGatt.GATT_SUCCESS) EhLog.w("clip: Bluetooth clipboard service not added (status $status)")
                    else serviceUp = true
                }
                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
                    val v = selfName.toByteArray(Charsets.UTF_8)
                    val ok = ch.uuid == NAME && offset <= v.size
                    try {
                        server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset,
                            if (ok) v.copyOfRange(offset, v.size) else null)
                    } catch (e: Exception) { }
                }
                override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
                                                          prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                    if (responseNeeded) try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (e: Exception) { }
                    if (ch.uuid == INBOX && value != null) assembler.add(value, device.address)?.let(onFrame)
                }
            }) ?: run { EhLog.w("clip: Bluetooth server not available"); return }
            val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            // Both need an encrypted link: the first connection between two devices asks once to pair them. Paired devices
            // recognise each other's changing Bluetooth addresses, so a send never goes to an address that is gone.
            svc.addCharacteristic(BluetoothGattCharacteristic(INBOX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            svc.addCharacteristic(BluetoothGattCharacteristic(NAME, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            server = s
            if (!s.addService(svc)) EhLog.w("clip: Bluetooth clipboard service could not be added")
        } catch (e: Exception) { EhLog.w("clip: Bluetooth server failed: ${e.message}") }
    }

    /** Sends a frame to each device (name to Bluetooth device), one after the other, each with a short connection. */
    fun send(frame: ByteArray, devices: List<Pair<String, BluetoothDevice>>) {
        if (frame.size > MAX_FRAME) return
        val tag = java.util.Random().nextInt()
        for ((name, d0) in devices) worker.execute {
            // The address may have changed while earlier sends ran (some devices take a new one every minute).
            val d = EhState.bleDevices[name]?.first ?: d0
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
        val connected = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val chunks = ClipChunks.split(frame, 172, tag)
        var next = 0
        var gatt: BluetoothGatt? = null
        val cb = object : BluetoothGattCallback() {
            fun writeNext(g: BluetoothGatt) {
                val ch = g.getService(SERVICE)?.getCharacteristic(INBOX) ?: run { done.countDown(); return }
                if (next >= chunks.size) { ok = true; done.countDown(); return }
                stage = "writing (accept the pairing request if one appears)"
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = chunks[next]
                if (!g.writeCharacteristic(ch)) done.countDown()
            }
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) { connected.countDown(); stage = "setting up"; g.requestMtu(185) } else done.countDown()
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { stage = "finding the clipboard service"; g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { writeNext(g) }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (status == 5 || status == 15) stage = "pairing: accept the pairing request on both devices"
                    done.countDown(); return
                }
                next++; writeNext(g)
            }
        }
        try {
            gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            // 6 s to connect, then 30 s for the rest: enough to accept the one-time pairing request.
            if (connected.await(6, java.util.concurrent.TimeUnit.SECONDS)) done.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { }
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        EhLog.w("clip: Bluetooth send to $name ${if (ok) "done" else "failed while $stage"}")
        return ok
    }

    /** Reads the name of the device behind an unknown Bluetooth LE id, once a minute at most per id. */
    @Suppress("DEPRECATION")
    fun lookupName(id: Int, device: BluetoothDevice) {
        val now = System.currentTimeMillis()
        if (now - (lookedUp[id] ?: 0L) < 60_000) return
        lookedUp[id] = now
        worker.execute {
            val done = java.util.concurrent.CountDownLatch(1)
            val connected = java.util.concurrent.CountDownLatch(1)
            var name: String? = null
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                    if (state == BluetoothProfile.STATE_CONNECTED) { connected.countDown(); g.discoverServices() } else done.countDown()
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val ch = g.getService(SERVICE)?.getCharacteristic(NAME)
                    if (ch == null || !g.readCharacteristic(ch)) done.countDown()
                }
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) name = ch.value?.toString(Charsets.UTF_8)
                    done.countDown()
                }
            }
            var gatt: BluetoothGatt? = null
            try {
                gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                if (connected.await(6, java.util.concurrent.TimeUnit.SECONDS)) done.await(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) { }
            try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
            val n = name
            // Only a name that really belongs to this id (by the shared secret) is learned.
            if (n != null && DeviceNames.valid(n) && DeviceNames.id(n, secret) == id) {
                if (NameBook.add(n)) EhLog.w("ble: learned the device name $n")
            } else EhLog.w("ble: could not read the name of device id ${"%04x".format(id)}")
        }
    }

    fun stop() { try { server?.close() } catch (e: Exception) { }; worker.shutdownNow() }
}

/** Serves received clipboard images to other apps (the clipboard grants them read access when they paste). */
class ClipImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain" else "image/png"
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
    /** Apps (package names) whose copies are never sent automatically. */
    fun excludedApps(ctx: Context): Set<String> = prefs(ctx).getStringSet("clipExcludeApps", emptySet()) ?: emptySet()
    /** Skip password-like text in automatic sends. Off by default: such text is synced. */
    fun skipPasswords(ctx: Context) = prefs(ctx).getBoolean("clipSkipPasswords", false)

    private fun event(text: String) { lastEvent = text; lastEventAt = System.currentTimeMillis() }

    /**
     * Recent clips, newest first, kept in memory only: never written to a file or the log, gone when EarHop stops.
     * Text is kept so it can be copied again; images only as an entry.
     */
    class Recent(val at: Long, val sent: Boolean, val dev: String, val kind: String, val text: String?, val size: Int,
                 val clipId: String = "") {
        /** For sent clips: "Delivered to Mac ✓, Tablet ✓", who is still waited for, or who did not answer. */
        @Volatile var receipt = ""
    }
    val receipts = Receipts()
    /** Sent clips kept for a quiet retry until every device confirmed them (at most a minute). */
    private val outbox = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, String>>()
    /** Other control messages (battery, notices, volume, settings), handled by the service. */
    @Volatile var onControl: ((Context, ClipItem) -> Unit)? = null
    /** A diagnostics reply arrived: (device, text). Set by the screen while it collects. */
    @Volatile var onDiagnostics: ((String, String) -> Unit)? = null
    fun pretty(n: String) = when (n) { "mac" -> "Mac"; "phone" -> "Phone"; "tab" -> "Tablet"; else -> n.replaceFirstChar { it.uppercase() } }
    val recent = java.util.concurrent.CopyOnWriteArrayList<Recent>()
    fun keepRecent(ctx: Context) = prefs(ctx).getBoolean("clipHistory", true)
    private fun addRecent(ctx: Context, r: Recent) {
        if (!keepRecent(ctx)) return
        recent.add(0, r)
        while (recent.size > 50) recent.removeAt(recent.size - 1)
    }

    // ---------- pinned clips: the only ones saved, encrypted with the shared secret ----------

    val pinned = java.util.concurrent.CopyOnWriteArrayList<Recent>()
    private fun pinFile(ctx: Context) = File(ctx.filesDir, "pins.bin")

    fun loadPins(ctx: Context) {
        pinned.clear()
        try {
            val f = pinFile(ctx); if (!f.exists()) return
            val item = ClipCodec.decode(f.readBytes(), prefs(ctx).getString("secret", "") ?: "") ?: return
            val arr = org.json.JSONArray(String(item.data, Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pinned.add(Recent(o.optLong("at"), o.optBoolean("sent"), o.optString("dev"), o.optString("kind", "text"), o.optString("text"), o.optString("text").length))
            }
        } catch (e: Exception) { EhLog.w("clip: pinned clips not read: ${e.message}") }
    }

    private fun savePins(ctx: Context) {
        try {
            val arr = org.json.JSONArray()
            for (r in pinned) arr.put(org.json.JSONObject().put("at", r.at).put("sent", r.sent).put("dev", r.dev).put("kind", r.kind).put("text", r.text ?: ""))
            val frame = ClipCodec.encode(ClipItem("pins", "", "pins", "application/json", System.currentTimeMillis(), arr.toString().toByteArray()),
                prefs(ctx).getString("secret", "") ?: "") ?: return
            pinFile(ctx).writeBytes(frame)
        } catch (e: Exception) { EhLog.w("clip: pinned clips not saved: ${e.message}") }
    }

    fun isPinned(r: Recent) = pinned.any { it.text == r.text && it.kind == r.kind }

    /** Pins or unpins a text, link or file name. */
    fun togglePin(ctx: Context, r: Recent) {
        if (r.text == null || r.kind == "image") return
        if (isPinned(r)) pinned.removeAll { it.text == r.text && it.kind == r.kind } else pinned.add(0, Recent(r.at, r.sent, r.dev, r.kind, r.text, r.size))
        savePins(ctx)
    }

    /** Puts a recent text back on this clipboard without sending it again. */
    fun copyBack(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ownWriteAt = System.currentTimeMillis()
        lastReceivedHash = text.toByteArray().contentHashCode()
        cm.setPrimaryClip(ClipData.newPlainText("EarHop", text))
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        loadPins(app)
        tcp = ClipTcp { f -> receive(app, f, "Wi-Fi") }.also {
            it.allowed = { a -> a in EhState.wifiPeers(emptySet()) }
            it.onFileStream = { inp -> receiveFile(app, inp) }
            it.start()
        }
        val p0 = prefs(app)
        gatt = ClipGatt(app, p0.getString("name", "phone") ?: "phone", p0.getString("secret", "") ?: "") { f -> receive(app, f, "Bluetooth") }.also { it.start() }
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
        main.removeCallbacks(retryTick)
        retryApp = app
        main.postDelayed(retryTick, 1000)
        tryStartAuto(app)
        // Keep the helper running: check a minute after start (an existing helper says hello within 60 s), then every
        // minute, and again a minute after Shizuku comes up (after a phone restart it starts later than EarHop).
        main.removeCallbacks(helperWatch)
        helperApp = app
        main.postDelayed(helperWatch, 70_000)
        HelperStarter.onShizukuReady { main.postDelayed({ watchHelper(app) }, 70_000) }
    }

    @Volatile private var retryApp: Context? = null
    /** Once a second: sends unconfirmed clips again (after 5 and 15 s) and refreshes the receipt words. */
    private val retryTick: Runnable = object : Runnable {
        override fun run() {
            retryApp?.let { app -> try { retryUnconfirmed(app) } catch (e: Exception) { } }
            main.postDelayed(this, 1000)
        }
    }

    private fun retryUnconfirmed(ctx: Context) {
        val now = System.currentTimeMillis()
        for ((id, devs) in receipts.due(now)) {
            val o = outbox[id] ?: continue
            Thread { deliver(ctx, o.first, o.second, devs.toSet()) }.start()
            EhLog.w("clip: no receipt from ${devs.joinToString(", ")} yet, sending again")
        }
        for (r in recent) if (r.sent && r.clipId.isNotEmpty()) {
            val w = receipts.words(r.clipId, now, ::pretty)
            if (w.isNotEmpty() && w != r.receipt) {
                r.receipt = w
                if (w.contains("not delivered")) EhLog.w("clip: ${w.substringAfter("not delivered to ")} did not confirm a clip in 60 s")
            }
        }
        outbox.keys.removeIf { k -> (receipts.sentAt(k) ?: 0L) < now - Receipts.GIVE_UP }
    }

    /**
     * Sends a frame to the given devices (null: every device not excluded) over the clipboard's method: text and small
     * control messages over Bluetooth LE and Wi-Fi, images over Wi-Fi. Returns the links used.
     */
    fun deliver(ctx: Context, frame: ByteArray, kind: String, only: Set<String>?): List<String> {
        val p = prefs(ctx)
        val name = p.getString("name", "phone") ?: "phone"
        val mode = method(ctx)
        val skip = excluded(ctx)
        fun wanted(d: String) = d != name && (only?.contains(d) ?: (d !in skip))
        val links = ArrayList<String>()
        var bleCarries = false
        if (kind != "image" && mode != "wifi" && frame.size <= ClipGatt.MAX_FRAME) {
            val now = System.currentTimeMillis()
            val devs = EhState.bleDevices.filter { wanted(it.key) && now - it.value.second < 60_000 }.map { Pair(it.key, it.value.first) }
            if (devs.isNotEmpty()) { gatt?.send(frame, devs); links += "Bluetooth"; bleCarries = true }
        }
        if (mode != "ble" || !bleCarries) {
            val hosts = if (only != null) only.flatMap { EhState.wifiHostsOf(it) }.distinct() else EhState.wifiPeers(skip)
            hosts.forEach { tcp?.send(frame, it) }
            if (hosts.isNotEmpty()) links += "Wi-Fi"
        }
        return links
    }

    /** A control message (receipt or diagnostics) to one device, or to all when [to] is null. */
    fun sendControl(ctx: Context, kind: String, data: ByteArray, to: String?) {
        val p = prefs(ctx)
        val id = (1..8).joinToString("") { "%02x".format(java.util.Random().nextInt(256)) }
        val item = ClipItem(id, p.getString("name", "phone") ?: "phone", kind, "application/octet-stream", System.currentTimeMillis(), data, to ?: "")
        val frame = ClipCodec.encode(item, p.getString("secret", "") ?: "") ?: return
        remember(id)
        Thread { deliver(ctx, frame, kind, to?.let { setOf(it) }) }.start()
    }

    /** An incoming file stream: checks the header like a clip, saves the file in Downloads and confirms it. */
    private fun receiveFile(ctx: Context, inp: DataInputStream) {
        val p = prefs(ctx)
        val secret = p.getString("secret", "") ?: ""
        val hl = inp.readInt()
        if (hl !in 1..65535) return
        val h = ByteArray(hl); inp.readFully(h)
        val item = ClipCodec.decode(h, secret) ?: return
        val self = p.getString("name", "phone")
        if (item.kind != "file" || item.dev == self || (item.to.isNotEmpty() && item.to != self)) return
        if (!enabled(ctx) || !receiving(ctx) || paused(ctx)) return
        if (!ClockGuard.shared.fresh(item.dev, item.ts, System.currentTimeMillis(), ClipCodec.MAX_AGE_MS)) return
        val saved = FileShare.receive(ctx, item, inp, secret)
        if (saved == null) { EhLog.w("file: a file from ${item.dev} broke off"); return }
        EhLog.w("file: received $saved from ${item.dev}")
        sendControl(ctx, "ack", item.id.toByteArray(), item.dev)
        addRecent(ctx, Recent(System.currentTimeMillis(), false, item.dev, "file", saved, 0, item.id))
        event("Received $saved from ${item.dev}")
    }

    /** Records a sent file or link for the recent list and its receipts. */
    fun trackSent(ctx: Context, id: String, targets: Set<String>, kind: String, text: String) {
        val now = System.currentTimeMillis()
        receipts.track(id, targets, now)
        addRecent(ctx, Recent(now, true, "", kind, text, text.length, id).also {
            if (targets.isNotEmpty()) it.receipt = "Sending to " + targets.sorted().joinToString(", ") { t -> pretty(t) }
        })
    }

    /** Opens a web link on one device ("continue on"). Returns false when that device is not in reach. */
    fun sendLink(ctx: Context, url: String, dev: String): Boolean {
        val p = prefs(ctx)
        val id = (1..8).joinToString("") { "%02x".format(java.util.Random().nextInt(256)) }
        val item = ClipItem(id, p.getString("name", "phone") ?: "phone", "link", "text/uri-list", System.currentTimeMillis(), url.toByteArray(), dev)
        val frame = ClipCodec.encode(item, p.getString("secret", "") ?: "") ?: return false
        remember(id)
        val links = deliver(ctx, frame, "link", setOf(dev))
        if (links.isEmpty()) return false
        outbox[id] = Pair(frame, "link")
        trackSent(ctx, id, setOf(dev), "link", url)
        EhLog.w("link: sent to $dev")
        return true
    }

    /** Asks every device for its diagnostics; replies arrive through [onDiagnostics]. */
    fun requestDiagnostics(ctx: Context) = sendControl(ctx, "diag-req", ByteArray(0), null)

    /** This device's part of a diagnostics file: status lines and the end of the log (never clipboard contents). */
    fun diagnosticsPart(ctx: Context): String {
        val p = prefs(ctx)
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        val head = "EarHop $v on Android ${Build.VERSION.RELEASE}, name ${p.getString("name", "?")}\n" +
            "transport ${p.getString("transport", "both")} clip method ${method(ctx)} clip sync ${enabled(ctx)} auto method ${autoMethod(ctx)}\n" +
            EhState.statusLines().joinToString("\n") + "\n--- log ---\n"
        return head + EhLog.dumpAll(1500).takeLast(50_000)
    }

    @Volatile private var helperApp: Context? = null
    @Volatile private var helperStartedAt = 0L
    private val helperWatch: Runnable = object : Runnable {
        override fun run() { helperApp?.let { watchHelper(it) }; main.postDelayed(this, 60_000) }
    }

    /** Whether automatic sending should use the helper here (the method chosen on the Clipboard page). */
    fun wantsHelper(ctx: Context) = enabled(ctx) && autoRoot(ctx) && autoMethod(ctx) == "helper"

    /**
     * How copies on this device are noticed: "root" (system log watch as root), "helper" (the shell-user helper, started
     * by Shizuku or a computer) or "accessibility" (copy confirmations, ClipAccessibilityService). Only the chosen one
     * runs. Without a choice: root when it was granted, else the helper.
     */
    fun autoMethod(ctx: Context): String {
        val p = prefs(ctx)
        val m = p.getString("clipAutoMethod", null)
        if (m == "root" && !RootShell.hasSu()) return "helper"
        return m ?: if (p.getBoolean("rootGranted", false)) "root" else "helper"
    }

    /** Switches the method: stops what the old one runs and starts the new one. */
    fun setAutoMethod(ctx: Context, m: String) {
        val app = ctx.applicationContext
        prefs(app).edit().putString("clipAutoMethod", m).putBoolean("clipAccessibilityTrigger", m == "accessibility").apply()
        EhLog.w("clip: automatic sending method $m")
        stopRootWatch()
        if (m != "helper" && helperRunning() && HelperStarter.granted()) { Thread { HelperStarter.stop() }.start(); helperAt = 0L }
        if (EhState.running) { tryStartAuto(app); if (m == "helper") watchHelper(app, manual = !helperRunning()) }
    }

    /**
     * Starts the helper through Shizuku when it should run and has not said hello. At most every 90 s, so a helper that
     * cannot start does not loop. Returns what happened, for the Start helper button.
     */
    fun watchHelper(ctx: Context, manual: Boolean = false): String {
        if (!EhState.running) return "Start EarHop first"
        if (helperRunning() && !manual) return "The helper is running"
        if (!manual && !wantsHelper(ctx)) return "Not needed"
        if (!HelperStarter.shizukuInstalled(ctx)) return "Install Shizuku first"
        if (!HelperStarter.shizukuRunning()) return "Start Shizuku first"
        if (!HelperStarter.granted()) return "Allow EarHop in Shizuku first"
        val now = System.currentTimeMillis()
        if (!manual && now - helperStartedAt < 90_000) return "Starting"
        helperStartedAt = now
        if (!manual) EhLog.w("clip: the clipboard helper is not running, starting it")
        return if (HelperStarter.start(ctx)) "Starting the helper" else "Could not start the helper"
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
        // Another method is chosen: the helper may still run (started by a computer), but only one method sends.
        if (!enabled(app) || !autoRoot(app) || autoMethod(app) != "helper" || !EhState.running || paused(app)) return
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
    fun accessibilityCopyTrigger(ctx: Context) = autoMethod(ctx) == "accessibility"

    /** Without root: log access (granted once from a computer) and "Display over other apps" (Settings). */
    fun hasLogAccess(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun hasOverlay(ctx: Context) = android.provider.Settings.canDrawOverlays(ctx)

    /** Starts automatic sending when it is switched on and possible: with root, or with the two permissions. */
    fun tryStartAuto(ctx: Context) {
        if (rootWatching || !autoRoot(ctx) || !enabled(ctx)) return
        when (autoMethod(ctx)) {
            "root" -> if (prefs(ctx).getBoolean("rootGranted", false)) startRootWatch(ctx, root = true)
            "helper" -> if (!helperRunning() && hasLogAccess(ctx) && hasOverlay(ctx)) startRootWatch(ctx, root = false)
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

    /** Reads an unknown device's name over its Bluetooth clipboard service. */
    fun lookupName(id: Int, device: BluetoothDevice) { gatt?.lookupName(id, device) }
    /** Whether the Wi-Fi clipboard port is open (status page). */
    val portOpen: Boolean get() = tcp?.listening == true
    /** Whether the Bluetooth clipboard service is registered (status page). */
    val gattUp: Boolean get() = gatt?.serviceUp == true

    /** Opens the Bluetooth clipboard service again (after Android's Bluetooth came back). */
    fun restartGatt() { gatt?.let { g -> Thread { g.start() }.start() } }

    fun stop(ctx: Context) {
        recent.clear()
        main.removeCallbacks(helperWatch)
        main.removeCallbacks(retryTick)
        stopRootWatch()
        try { listener?.let { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(it) } } catch (e: Exception) { }
        tcp?.stop(); gatt?.stop(); tcp = null; gatt = null
    }

    /** Reads the clip (call while EarHop has focus or background access) and sends it. */
    fun sendClip(ctx: Context, clip: ClipData, auto: Boolean = false, source: String? = null): String {
        if (clip.itemCount == 0) return "The clipboard is empty"
        // Copies noticed in an excluded app stay here (the app is known when the accessibility trigger saw the copy).
        if (auto && source != null && source in excludedApps(ctx)) { EhLog.w("clip: not sent, copied in an excluded app"); return "Not sent: excluded app" }
        if (auto && paused(ctx)) return "Clipboard sync is paused"
        val desc = clip.description
        // Automatic sends never pass on what EarHop itself just put on the clipboard (a clip that arrived): Android shows
        // its clipboard preview for that too, which the copy triggers take for a new copy, and it would bounce back.
        if (EchoGuard.skip(auto, desc.label?.toString(), System.currentTimeMillis(), ownWriteAt, null, lastReceivedHash, lastSentHash)) return "Already synced"
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
            if (EchoGuard.skip(auto, null, 0, Long.MIN_VALUE / 2, bytes.contentHashCode(), lastReceivedHash, lastSentHash)) return "Already synced"
            return send(ctx, "image", ctx.contentResolver.getType(uri) ?: "image/png", bytes)
        }
        val text = item.coerceToText(ctx)?.toString() ?: return "Nothing to send"
        if (text.isEmpty()) return "The clipboard is empty"
        val b = text.toByteArray()
        // Automatic sends skip what just arrived from another device or was already sent.
        if (EchoGuard.skip(auto, null, 0, Long.MIN_VALUE / 2, b.contentHashCode(), lastReceivedHash, lastSentHash)) return "Already synced"
        if (auto && skipPasswords(ctx) && PasswordLike.looks(text)) {
            EhLog.w("clip: skipped text that looks like a password (Skip password-like text is on)")
            return "Not sent: looks like a password"
        }
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
        val skip = excluded(ctx)
        val now = System.currentTimeMillis()
        val targets = (EhState.bleDevices.filter { now - it.value.second < 60_000 }.keys + EhState.peers.keys)
            .filter { it != name && it !in skip }.toSet()
        val links = deliver(ctx, frame, kind, null)
        val what = if (kind == "text") "text (${data.size} bytes)" else "image (${data.size / 1024} KB)"
        return if (links.isEmpty()) {
            EhLog.w("clip: $what not sent, no other device ${if (kind == "image") "on Wi-Fi" else "in reach"} (method ${method(ctx)})")
            event("Not sent, no device in reach")
            "No other device in reach"
        } else {
            EhLog.w("clip: sent $what over ${links.joinToString(" and ")}")
            event("Sent ${if (kind == "text") "text" else "an image"}")
            receipts.track(id, targets, now)
            outbox[id] = Pair(frame, kind)
            addRecent(ctx, Recent(now, true, "", kind,
                if (kind == "text" && data.size <= 100_000) String(data, Charsets.UTF_8) else null, data.size, id).also {
                    if (targets.isNotEmpty()) it.receipt = "Sending to " + targets.sorted().joinToString(", ") { t -> pretty(t) }
                })
            "Sent to your other devices"
        }
    }

    @Synchronized private fun remember(id: String) { seen += id; while (seen.size > 100) seen.removeAt(0) }
    /** True the first time an id is seen (and records it), false for every later copy. */
    @Synchronized private fun firstTime(id: String): Boolean { if (id in seen) return false; remember(id); return true }

    private fun receive(ctx: Context, frame: ByteArray, via: String) {
        val p = prefs(ctx)
        val item = ClipCodec.decode(frame, p.getString("secret", "") ?: "") ?: return
        val self = p.getString("name", "phone")
        if (item.dev == self || (item.to.isNotEmpty() && item.to != self)) return
        // Receipts and diagnostics work whatever the clipboard settings; clips only when receiving is on.
        val content = item.kind == "text" || item.kind == "image" || item.kind == "link"
        if (content && (!enabled(ctx) || !receiving(ctx) || paused(ctx))) return
        // Within 5 minutes by the sender's own clock (its offset is learned from its status), so clocks may differ.
        if (!ClockGuard.shared.fresh(item.dev, item.ts, System.currentTimeMillis(), ClipCodec.MAX_AGE_MS)) return
        // Checked and recorded in one step: the copies over Bluetooth LE and Wi-Fi can arrive at the same moment.
        if (!firstTime(item.id)) {
            // A second copy (the other link, or a retry because the receipt was lost) is confirmed again.
            if (content) sendControl(ctx, "ack", item.id.toByteArray(), item.dev)
            return
        }
        when (item.kind) {
            "ack" -> {
                val id = String(item.data, Charsets.UTF_8)
                if (receipts.ack(id, item.dev)) {
                    val w = receipts.words(id, System.currentTimeMillis(), ::pretty)
                    recent.firstOrNull { it.clipId == id }?.receipt = w
                }
            }
            "diag-req" -> {
                EhLog.w("diag: ${item.dev} asked for diagnostics")
                sendControl(ctx, "diag", diagnosticsPart(ctx).toByteArray(), item.dev)
            }
            "diag" -> onDiagnostics?.invoke(item.dev, String(item.data, Charsets.UTF_8))
            "link" -> {
                val url = String(item.data, Charsets.UTF_8)
                FileShare.showLink(ctx, item.dev, url)
                sendControl(ctx, "ack", item.id.toByteArray(), item.dev)
                addRecent(ctx, Recent(System.currentTimeMillis(), false, item.dev, "link", url, url.length, item.id))
            }
            "text", "image" -> main.post { apply(ctx, item, via) }
            else -> onControl?.invoke(ctx, item)
        }
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
        sendControl(ctx, "ack", item.id.toByteArray(), item.dev)
        event("Received ${if (item.kind == "text") "text" else "an image"} from ${item.dev}")
        addRecent(ctx, Recent(System.currentTimeMillis(), false, item.dev, item.kind,
            if (item.kind == "text" && item.data.size <= 100_000) String(item.data, Charsets.UTF_8) else null, item.data.size))
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
        val msg = if (!EhState.running) "Start EarHop first" else if (clip == null) "The clipboard is empty"
            else ClipSync.sendClip(applicationContext, clip, auto, intent?.getStringExtra("source"))
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
