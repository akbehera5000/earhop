package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connect or release the AirPods without needing the main service, used by the quick settings tiles. */
object TileActions {
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    private fun withProxy(ctx: Context, body: (android.bluetooth.BluetoothAdapter, BluetoothA2dp?) -> Unit) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) return
        val latch = CountDownLatch(1)
        var proxy: BluetoothA2dp? = null
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) { proxy = p as BluetoothA2dp; latch.countDown() }
            override fun onServiceDisconnected(profile: Int) { proxy = null }
        }, BluetoothProfile.A2DP)
        latch.await(3, TimeUnit.SECONDS)
        try { body(adapter, proxy) } finally {
            try { proxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (e: Exception) { }
        }
    }

    private fun filter(ctx: Context) = Earbuds.filter(ctx)

    @SuppressLint("MissingPermission")
    fun isOwner(ctx: Context, cb: (Boolean) -> Unit) {
        Thread {
            var r = false
            try {
                withProxy(ctx) { _, p -> r = p?.connectedDevices?.any { it.name?.contains(filter(ctx), true) == true } ?: false }
            } catch (e: Exception) { }
            main.post { cb(r) }
        }.start()
    }

    fun run(ctx: Context, take: Boolean, done: () -> Unit) {
        // With the service running, go through the handoff controller so it knows about the switch.
        val c = EhState.controller
        if (EhState.running && c != null) {
            c.manual(take) { ok ->
                main.post {
                    Toast.makeText(ctx, if (ok) (if (take) "${Earbuds.label(ctx)} connected here" else "${Earbuds.label(ctx)} released")
                        else (EhState.lastFailure ?: "Could not ${if (take) "connect" else "release"}"), Toast.LENGTH_SHORT).show()
                    done()
                }
            }
            return
        }
        Thread {
            var ok = false
            try {
                withProxy(ctx) { adapter, p ->
                    val c = Claimer(ctx, adapter) { p }
                    EhLog.w("handoff: manual ${if (take) "take" else "release"} from tile")
                    ok = (if (take) c.take(filter(ctx)) else c.release(filter(ctx))) == true
                    EhState.record(if (take) "claim" else "release", "manual tile")
                    if (ok && take) RuleStore.applyVolume(ctx)
                    Thread.sleep(400)
                }
            } catch (e: Exception) { EhLog.w("tile action failed ${e.message}") }
            main.post {
                Toast.makeText(ctx, if (ok) (if (take) "${Earbuds.label(ctx)} taking over" else "${Earbuds.label(ctx)} released") else "Could not ${if (take) "connect" else "release"}, check Bluetooth permission", Toast.LENGTH_SHORT).show()
                done()
            }
        }.start()
    }
}

/** Asks Android to refresh every EarHop tile, called by the service whenever the state they show changes. */
object Tiles {
    fun refreshAll(ctx: Context) {
        for (c in listOf(TakeTile::class.java, ReleaseTile::class.java, AutoTile::class.java, NoiseTile::class.java)) {
            try { TileService.requestListeningState(ctx, android.content.ComponentName(ctx, c)) } catch (e: Exception) { }
        }
    }

    /** "On the Mac", "L 80%  R 79%" and similar, for tile subtitles. */
    fun where(ctx: Context): String {
        val now = System.currentTimeMillis()
        val holder = EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
        return when (holder) { "mac" -> "On the Mac"; "phone" -> "On the phone"; "tab" -> "On the tablet"; null -> "Tap to connect"; else -> "On $holder" }
    }

    fun battery(): String? {
        if (System.currentTimeMillis() - EhState.batAt > 60000 || (EhState.batA < 0 && EhState.batB < 0)) return null
        fun p(v: Int) = if (v >= 0) "$v%" else "-"
        return "L ${p(EhState.batA)}  R ${p(EhState.batB)}"
    }
}

/** Take (connect here) or Release. Shows live state while the EarHop service runs. */
abstract class BaseTile(private val take: Boolean) : TileService() {
    private fun refresh() {
        val t = qsTile ?: return
        t.label = if (take) "Take ${Earbuds.short(this)}" else "Release ${Earbuds.short(this)}"
        if (EhState.running) {
            show(t, EhState.owner)
            return
        }
        TileActions.isOwner(this) { own -> show(t, own) }
    }

    private fun show(t: Tile, own: Boolean) {
        if (take) {
            t.state = if (own) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (own) (Tiles.battery() ?: "Connected here") else Tiles.where(this)
        } else {
            t.state = if (own) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
            if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (own) "Tap to let go" else "Not on this device"
        }
        t.updateTile()
    }

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val t = qsTile
        t?.state = Tile.STATE_UNAVAILABLE
        if (android.os.Build.VERSION.SDK_INT >= 29) t?.subtitle = if (take) "Connecting..." else "Letting go..."
        t?.updateTile()
        TileActions.run(applicationContext, take) { refresh() }
    }
}

class TakeTile : BaseTile(true)
class ReleaseTile : BaseTile(false)

/** Galaxy Buds noise control: each tap moves Off, Noise cancelling, Ambient. Only while the Buds are connected here. */
class NoiseTile : TileService() {
    private val order = listOf("off", "anc", "ambient")
    private fun word(m: String?) = when (m) { "anc" -> "Noise cancelling"; "ambient" -> "Ambient"; "off" -> "Off"; else -> "Unknown" }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = "Noise control"
        val c = EhState.budsClient
        val sn = c?.takeIf { it.connected }?.snapshot()
        when {
            !Earbuds.isBuds(this) -> { t.state = Tile.STATE_UNAVAILABLE; if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = "Galaxy Buds only" }
            sn == null -> { t.state = Tile.STATE_UNAVAILABLE; if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = "Buds not on this device" }
            else -> {
                t.state = if (sn.noise == "off") Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = word(sn.noise)
            }
        }
        t.updateTile()
    }

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val c = EhState.budsClient ?: return
        val sn = c.takeIf { it.connected }?.snapshot() ?: return
        val next = order[(order.indexOf(sn.noise).coerceAtLeast(0) + 1) % order.size]
        EhLog.w("buds: noise $next from tile")
        c.setNoise(next)
        qsTile?.let { if (android.os.Build.VERSION.SDK_INT >= 29) it.subtitle = word(next); it.state = if (next == "off") Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; it.updateTile() }
    }
}
/** Turns automatic switching on or off. Take and Release keep working either way. */
class AutoTile : TileService() {
    private val prefs get() = getSharedPreferences("earhop", Context.MODE_PRIVATE)

    private fun refresh() {
        val t = qsTile ?: return
        val on = prefs.getBoolean("auto", true)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Auto switching"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val on = !prefs.getBoolean("auto", true)
        prefs.edit().putBoolean("auto", on).apply()
        EhLog.w("handoff: automatic switching ${if (on) "on" else "off"} from tile")
        refresh()
        Tiles.refreshAll(applicationContext)
    }
}
