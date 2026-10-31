package com.earhop.logger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.IBinder

/** Foreground service: once a second compute status, broadcast it, track peers. Every step is guarded so one failure cannot crash the app. */
class EarService : Service() {
    private var link: UdpLink? = null
    private var ble: BleLink? = null
    private var ears: EarScanner? = null
    private var aacp: AacpClient? = null
    private var buds: BudsClient? = null
    private var a2dp: BluetoothA2dp? = null
    private var headset: android.bluetooth.BluetoothHeadset? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var controller: HandoffController? = null
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val startedAt = System.currentTimeMillis()
    /** The active earbuds; both can change while running (see detectEarbuds). */
    @Volatile private var useBuds = false
    @Volatile private var filter = ""
    private var lastEarbudCheck = 0L
    private val speaker = SpeakerPolicy()
    private var speakerRoute: SpeakerRoute? = null
    private val speakerWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var routing = false

    companion object {
        const val ACTION_TAKE = "com.earhop.logger.TAKE"
        const val ACTION_RELEASE = "com.earhop.logger.RELEASE"
        const val ACTION_TEST_PAUSE = "com.earhop.logger.TEST_PAUSE"
        const val ACTION_TEST_SPEAKER = "com.earhop.logger.TEST_SPEAKER"
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun handleAction(action: String?) {
        try {
            val filter = Earbuds.filter(this)
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val c = Claimer(this, bm.adapter, { a2dp }) { headset }
            val ctl = controller
            when {
                ctl != null -> ctl.manual(action == ACTION_TAKE)
                action == ACTION_TAKE -> if (c.take(filter) != true) EhLog.w("claim failed: connect from Bluetooth settings instead")
                action == ACTION_RELEASE -> c.release(filter)
            }
        } catch (e: Exception) {
            EhLog.w("claim error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_TEST_PAUSE) {
            if (running) controller?.testPause()
            return START_STICKY
        }
        if (action == ACTION_TEST_SPEAKER) {
            if (running) testSpeaker()
            return START_STICKY
        }
        if (action == ACTION_TAKE || action == ACTION_RELEASE) {
            if (running) Thread { handleAction(action) }.start()
            return START_STICKY
        }
        if (running) return START_STICKY

        if (!startForegroundSafely()) {
            EhState.error = "Could not start. Grant Bluetooth and location permission, then press Start."
            stopSelf()
            return START_NOT_STICKY
        }

        val p = getSharedPreferences("earhop", MODE_PRIVATE)
        p.edit().putBoolean("keepRunning", true).apply()
        val name = p.getString("name", if (resources.configuration.smallestScreenWidthDp >= 600) "tab" else "phone")
            ?: "phone"
        val secret = p.getString("secret", "") ?: ""
        useBuds = Earbuds.isBuds(this)
        filter = Earbuds.filter(this)
        EhState.error = null
        EhLog.w("service start dev=$name selftest=${Proto.selfTest()}")

        try {
            link = UdpLink(this, 47474, secret, name) { s -> onPeer(s, "wifi") }
            link?.start()
        } catch (e: Exception) {
            EhLog.w("wifi link failed: ${e.message}")
            link = null
        }
        try { ble = BleLink(this, secret, name) { s -> onPeer(s, "ble") } } catch (e: Exception) { EhLog.w("ble init failed: ${e.message}") }
        try { openA2dp() } catch (e: Exception) { EhLog.w("a2dp failed: ${e.message}") }
        startEarbudParts()

        // Keep the CPU and Wi-Fi awake so packets keep flowing with the screen off.
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "earhop:loop").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "earhop:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { EhLog.w("lock failed: ${e.message}") }

        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            controller = HandoffController(this, name, Claimer(this, bm.adapter, { a2dp }) { headset }, filter) { isOwner(filter) }
            speakerRoute = SpeakerRoute(this, bm.adapter) { a2dp }
        } catch (e: Exception) { EhLog.w("controller failed: ${e.message}") }

        // Clipboard sync (Bluetooth LE and Wi-Fi).
        EhState.wifiPeers = { skip -> link?.peerHostsExcept(skip) ?: emptyList() }
        try { ClipSync.start(this) } catch (e: Exception) { EhLog.w("clip: start failed ${e.message}") }

        // Log network changes: a switch of Wi-Fi network or a lost connection explains peers going quiet.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: android.net.Network) { EhLog.w("network: available") }
                override fun onLost(n: android.net.Network) { EhLog.w("network: lost, Bluetooth LE takes over after 10 s") }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { EhLog.w("network: callback failed ${e.message}") }

        running = true
        EhState.running = true
        thread = Thread({
            var seq = 0
            var lastKey = ""
            var lastSendAt = 0L
            var lastLogAt = 0L
            var commSince = 0L
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            while (running) {
                try {
                    val playing = AppRules.effectivePlaying(this, am.isMusicActive)
                    val reading = if (useBuds) (buds?.reading() ?: EarReading.UNKNOWN) else (ears?.reading ?: EarReading.UNKNOWN)
                    val ear = reading.ear
                    EhState.earReading = reading
                    val owner = isOwner(filter)
                    val mode = am.mode
                    // Ringing and phone calls count at once. Voice chat mode is also used by apps for voice messages,
                    // so it only counts as a call after it has lasted 2 seconds.
                    val tNow = System.currentTimeMillis()
                    if (mode == AudioManager.MODE_IN_COMMUNICATION) { if (commSince == 0L) commSince = tNow } else commSince = 0L
                    val call = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
                        (commSince != 0L && tNow - commSince >= 2000)
                    ears?.setGentle(getSharedPreferences("earhop", MODE_PRIVATE).getBoolean("gentle", false) && owner && playing)
                    if (owner != EhState.owner && EhState.running) EhLog.w("owner: ${EhState.owner} -> $owner")
                    EhState.playing = playing; EhState.ear = ear; EhState.owner = owner; EhState.call = call
                    // Send at once when something changed, otherwise once a second as a heartbeat.
                    // Stale audio right after letting go is not announced as playing (see HandoffCore.advertisedPlaying).
                    val shown = controller?.core?.advertisedPlaying(playing) ?: playing
                    val key = "$shown|$ear|$owner|$call|$useBuds"
                    val now = System.currentTimeMillis()
                    if (key != lastKey || now - lastSendAt >= 1000) {
                        lastKey = key
                        lastSendAt = now
                        seq++
                        val st = Status(name, now, seq, shown, ear, owner, call, if (useBuds) "buds" else "airpods")
                        sendStatus(st)
                    }
                    controller?.tick(playing, reading, owner, call, EhState.peers.values, AppRules.preferredPlaying(this))
                    speakerTick(playing, reading, owner, call)
                    if (now - lastEarbudCheck >= 2000) { lastEarbudCheck = now; detectEarbuds() }
                    updateNotification()
                    if (now - lastLogAt >= 5000) {
                        lastLogAt = now
                        EhLog.w("state: playing=$playing ear=$ear owner=$owner call=$call")
                    }
                } catch (e: Exception) {
                    EhLog.w("loop error: ${e.javaClass.simpleName} ${e.message}")
                }
                try { Thread.sleep(250) } catch (e: InterruptedException) { break }
            }
        }, "earhop-loop")
        thread?.start()
        return START_STICKY
    }

    /**
     * Speaker fallback: audio started while the connected earbuds are out of your ears plays on this device's speaker,
     * and moves back to the earbuds when one goes back in. Runs whether or not automatic switching is on.
     */
    private fun speakerTick(playing: Boolean, reading: EarReading, owner: Boolean, call: Boolean) {
        val route = speakerRoute ?: return
        if (routing) return
        val enabled = getSharedPreferences("earhop", MODE_PRIVATE).getBoolean("speakerFallback", true)
        val act = speaker.update(System.currentTimeMillis(), enabled, owner, reading, playing, call)
        EhState.onSpeaker = speaker.onSpeaker
        if (act == null) return
        routing = true
        EhLog.w(if (act == SpeakerPolicy.Action.TO_SPEAKER) "speaker: audio started with the earbuds out (${reading.summary}), moving it to the speaker"
            else "speaker: earbuds back in (${reading.summary}), moving audio back to them")
        speakerWorker.execute {
            val ok = try { if (act == SpeakerPolicy.Action.TO_SPEAKER) route.toSpeaker(filter) else route.toEarbuds(filter) } catch (e: Exception) { EhLog.w("speaker: error ${e.message}"); false }
            speaker.routed(act, ok)
            EhState.onSpeaker = speaker.onSpeaker
            routing = false
        }
    }

    /** Settings, Test speaker: media to the speaker for 5 seconds, then back to the earbuds. */
    private fun testSpeaker() {
        val route = speakerRoute ?: return
        speakerWorker.execute {
            EhLog.w("speaker: test, media to the speaker for 5 s")
            if (route.toSpeaker(filter)) { Thread.sleep(5000); route.toEarbuds(filter) }
        }
    }

    /** Starts the parts that belong to the active earbuds: the Galaxy Buds channel, or the AirPods scanner and channel. */
    private fun startEarbudParts() {
        if (useBuds) {
            try { buds = BudsClient(this, { Earbuds.filter(this) }) { isOwner(filter) }.also { it.start() } } catch (e: Exception) { EhLog.w("buds init failed: ${e.message}") }
        } else {
            try { ears = EarScanner(this, -75, false).also { it.start() } } catch (e: Exception) { EhLog.w("ear scan failed: ${e.message}") }
            try {
                aacp = AacpClient(this, filter, { isOwner(filter) }) { e -> ears?.setAacp(e) }.also { it.start() }
            } catch (e: Exception) { EhLog.w("aacp init failed: ${e.message}") }
        }
    }

    private fun stopEarbudParts() {
        try { buds?.stop() } catch (e: Exception) { }
        try { aacp?.stop() } catch (e: Exception) { }
        try { ears?.stop() } catch (e: Exception) { }
        buds = null; aacp = null; ears = null
    }

    /**
     * Which earbuds to use: chosen by hand ("airpods" or "buds"), or automatic (the default): the pair connected to this
     * device, else the pair of the device that has the earbuds (it says so in its status), else no change.
     */
    @SuppressLint("MissingPermission")
    private fun detectEarbuds() {
        val p = getSharedPreferences("earhop", MODE_PRIVATE)
        // Earbud names edited on the Buds page apply at once.
        Earbuds.filter(this).let { if (it != filter && Earbuds.isBuds(this) == useBuds) { filter = it; controller?.filter = it } }
        val want: Boolean = when (p.getString("earbudsMode", "auto")) {
            "airpods" -> false
            "buds" -> true
            else -> {
                val names = try { a2dp?.connectedDevices?.mapNotNull { it.name } ?: emptyList() } catch (e: Exception) { emptyList() }
                val budsName = p.getString("filterBuds", "Buds2 Pro") ?: "Buds2 Pro"
                val podsName = p.getString("filter", "AirPods") ?: "AirPods"
                val holder = EhState.peers.values.firstOrNull { it.owner && it.prof.isNotEmpty() && System.currentTimeMillis() - it.seenAt < 5000 }
                when {
                    names.any { it.contains(budsName, ignoreCase = true) } -> true
                    names.any { it.contains(podsName, ignoreCase = true) } -> false
                    holder != null -> holder.prof == "buds"
                    else -> return
                }
            }
        }
        if (want == useBuds) return
        EhLog.w("earbuds: switching to ${if (want) "Galaxy Buds2 Pro" else "AirPods"} (${p.getString("earbudsMode", "auto")})")
        Earbuds.setBuds(this, want)
        stopEarbudParts()
        useBuds = want
        filter = Earbuds.filter(this)
        controller?.filter = filter
        startEarbudParts()
    }

    /**
     * Bluetooth LE is the main link and Wi-Fi runs alongside it ("both", the default): every status goes out over both at
     * once and receivers keep the newest copy (PeerOrder), so a failing link costs nothing and there is no failover gap.
     * "ble" uses Bluetooth LE only (for testing it), "wifi" uses Wi-Fi only.
     */
    private fun sendStatus(st: Status) {
        val mode = getSharedPreferences("earhop", MODE_PRIVATE).getString("transport", "both") ?: "both"
        val b = ble
        val wantBle = mode != "wifi" && b != null
        if (b != null && wantBle != b.active) {
            try { b.setActive(wantBle) } catch (e: Exception) { EhLog.w("ble switch failed: ${e.message}") }
            EhLog.w("transport: Bluetooth LE ${if (wantBle) "on" else "off"} (mode $mode)" +
                if (wantBle && !b.supported) ", needs the device name mac, phone or tab" else "")
        }
        if (mode != "ble") link?.send(st)
        if (b != null && b.active) try { b.advertise(st) } catch (e: Exception) { EhLog.w("ble advertise failed: ${e.message}") }
        // Which links actually bring news from other devices right now.
        val now = System.currentTimeMillis()
        val viaBle = b != null && b.active && now - b.lastPeerAt < 10000
        val viaWifi = mode != "ble" && now - (link?.lastHeard ?: 0L) < 10000
        val label = when {
            viaBle && viaWifi -> "Bluetooth LE + Wi-Fi"
            viaBle -> "Bluetooth LE"
            viaWifi -> "Wi-Fi"
            mode == "wifi" -> "Wi-Fi (nobody heard)"
            mode == "ble" -> "Bluetooth LE (nobody heard)"
            else -> "Bluetooth LE + Wi-Fi (nobody heard)"
        }
        if (label != EhState.transport) { EhLog.w("transport: $label"); EhState.transport = label }
    }

    @Synchronized
    private fun onPeer(s: Status, via: String) {
        val now = System.currentTimeMillis()
        val prev = EhState.peers[s.dev]
        // The same status arrives over Bluetooth LE and Wi-Fi, often several times. Only a newer copy changes anything;
        // an older or repeated copy still shows the device is alive.
        val (take, fullSeq) = PeerOrder.accept(prev?.seq, s.seq, via == "ble")
        if (!take) { if (prev != null) EhState.peers[s.dev] = prev.copy(seenAt = now); return }
        val since = if (!s.playing) 0L else (prev?.playingSince?.takeIf { it != 0L } ?: now)
        val last = if (s.playing) now else (prev?.lastPlayingAt ?: 0L)
        val old = EhState.peers.put(s.dev, PeerState(s.dev, s.playing, s.ear, s.owner, s.call, via, now, since, last, fullSeq, s.ts,
            s.prof.ifEmpty { prev?.prof ?: "" }))
        if (old == null || old.playing != s.playing || old.ear != s.ear || old.owner != s.owner || old.call != s.call) {
            EhLog.w("peer ${s.dev} ($via): playing=${s.playing} ear=${s.ear} owner=${s.owner} call=${s.call}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openA2dp() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
        bm.adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { headset = proxy as android.bluetooth.BluetoothHeadset }
            override fun onServiceDisconnected(profile: Int) { headset = null }
        }, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun isOwner(filter: String): Boolean = try {
        a2dp?.connectedDevices?.any { it.name?.contains(filter, ignoreCase = true) == true } ?: false
    } catch (e: Exception) {
        false
    }

    private var lastNote = ""
    private var lastWidget = ""
    private var lastTileKey = ""

    /** Keeps the notification, the widget and the quick settings tiles in step with what EarHop is doing. */
    private fun updateNotification() {
        val sig = NotifUi.signature(this)
        if (sig != lastNote) {
            lastNote = sig
            try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, NotifUi.build(this)) } catch (e: Exception) { }
        }
        val holder = EhState.peers.values.firstOrNull { it.owner && System.currentTimeMillis() - it.seenAt < 5000 }?.name
        val text = EhState.stage ?: when {
            EhState.safeMode -> "Safe mode: automatic switching paused"
            EhState.owner -> "${Earbuds.label(this)} on this device \u00B7 ${EhState.phase.label}"
            holder != null -> "${Earbuds.label(this)} on $holder \u00B7 ready to switch"
            else -> "Ready \u00B7 ${EhState.transport}"
        }
        if (text != lastWidget) { lastWidget = text; EarWidget.push(this, text) }
        val tileKey = "${EhState.owner}|${EhState.budsClient?.takeIf { it.connected }?.snapshot()?.noise}|${getSharedPreferences("earhop", MODE_PRIVATE).getBoolean("auto", true)}"
        if (tileKey != lastTileKey) { lastTileKey = tileKey; Tiles.refreshAll(this) }
    }

    private fun startForegroundSafely(): Boolean = try {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("earhop", "EarHop", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotifUi.build(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
        true
    } catch (e: Exception) {
        EhLog.w("foreground start failed: ${e.javaClass.simpleName} ${e.message}")
        false
    }

    override fun onDestroy() {
        // Never leave media stuck on the speaker with the earbuds connected.
        if (speaker.onSpeaker) speakerRoute?.let { r -> Thread { try { r.toEarbuds(filter) } catch (e: Exception) { } }.start() }
        running = false
        EhState.running = false
        EhState.peers.clear()
        thread?.interrupt()
        try { wake?.release() } catch (e: Exception) { }
        try { wifiLock?.release() } catch (e: Exception) { }
        try { aacp?.stop() } catch (e: Exception) { }
        try { buds?.stop() } catch (e: Exception) { }
        try { ears?.stop() } catch (e: Exception) { }
        try { ble?.setActive(false) } catch (e: Exception) { }
        try { link?.stop() } catch (e: Exception) { }
        try { netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } } catch (e: Exception) { }
        EhState.controller = null
        try { ClipSync.stop(this) } catch (e: Exception) { }
        EarWidget.push(this, "EarHop is stopped. Open it to start.")
        EhLog.w("service stopped")
        super.onDestroy()
    }
}

/**
 * Starts EarHop again after an update of the app or a phone restart, if it was running and not stopped on purpose.
 * Installing a new EarHop used to leave the phone without handoff until Start was pressed.
 */
class RestartReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_MY_PACKAGE_REPLACED && a != Intent.ACTION_BOOT_COMPLETED) return
        if (!ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).getBoolean("keepRunning", false)) return
        try {
            val i = Intent(ctx, EarService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) { EhLog.w("restart after ${if (a == Intent.ACTION_BOOT_COMPLETED) "boot" else "update"} failed: ${e.message}") }
    }
}
