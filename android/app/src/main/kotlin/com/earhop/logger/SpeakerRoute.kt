package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * Moves media between the earbuds and this device's speaker while the earbuds stay connected, by changing Android's
 * active audio device: none sends media to the speaker, the earbuds send it back. These are hidden calls, tried in
 * order (the adapter's, then the A2DP profile's), with root as a last resort when it is switched on. The result is
 * checked by reading the active device back. Every outcome is logged, so a phone that refuses shows up clearly.
 */
class SpeakerRoute(private val ctx: Context, private val adapter: BluetoothAdapter?, private val a2dp: () -> BluetoothA2dp?) {

    @SuppressLint("MissingPermission")
    private fun find(filter: String): BluetoothDevice? =
        adapter?.bondedDevices?.firstOrNull { it.name?.contains(filter, ignoreCase = true) == true }

    /** The A2DP device media plays on now, or null for the speaker; failure to read gives "unknown". */
    private fun active(): Any? = try {
        BluetoothA2dp::class.java.getMethod("getActiveDevice").invoke(a2dp())
    } catch (e: Exception) { "unknown" }

    fun toSpeaker(filter: String): Boolean = route(null, filter)
    fun toEarbuds(filter: String): Boolean { val dev = find(filter) ?: return false; return route(dev, filter) }

    private fun route(dev: BluetoothDevice?, filter: String): Boolean {
        val what = if (dev == null) "speaker" else "earbuds"
        val tries = listOf<Pair<String, () -> Any?>>(
            "adapter" to {
                if (dev == null) BluetoothAdapter::class.java.getMethod("removeActiveDevice", Int::class.javaPrimitiveType).invoke(adapter, 0)
                else BluetoothAdapter::class.java.getMethod("setActiveDevice", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(adapter, dev, 0)
            },
            "a2dp" to { BluetoothA2dp::class.java.getMethod("setActiveDevice", BluetoothDevice::class.java).invoke(a2dp(), dev) },
        )
        for ((name, call) in tries) {
            val r = try { call() } catch (e: Exception) {
                EhLog.w("speaker: $name route to $what blocked (${e.javaClass.simpleName}: ${(e.cause ?: e).message})"); continue
            }
            if (confirm(dev)) { EhLog.w("speaker: media on the $what ($name returned $r)"); return true }
            EhLog.w("speaker: $name route to $what returned $r but did not take effect")
        }
        val p = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
        if (p.getBoolean("rootGranted", false) && p.getBoolean("useRoot", false)) {
            val target = dev ?: find(filter)
            if (target != null && RootShell.bluetooth(ctx, if (dev == null) "activeNone" else "activeSet", target.address) && confirm(dev)) {
                EhLog.w("speaker: media on the $what (root)"); return true
            }
        }
        EhLog.w("speaker: could not move media to the $what on this device")
        return false
    }

    /**
     * Where music plays now: "speaker", "earbuds", or "unknown" when this phone does not say. Read from the output devices
     * of the music stream (hidden but long-standing), else from the A2DP active device.
     */
    private fun musicOutput(): String {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val mask = android.media.AudioManager::class.java.getMethod("getDevicesForStream", Int::class.javaPrimitiveType)
                .invoke(am, android.media.AudioManager.STREAM_MUSIC) as Int
            if (mask and 0x380 != 0) return "earbuds" // bt_a2dp, bt_a2dp_hp, bt_a2dp_spk
            if (mask and 0x2 != 0) return "speaker"
        } catch (e: Exception) { }
        return when (val a = active()) { "unknown" -> "unknown"; null -> "speaker"; else -> "earbuds" }
    }

    /** Waits up to 1.5 s for music to play on the target. A phone that does not say where counts as done, and is logged. */
    private fun confirm(dev: BluetoothDevice?): Boolean {
        val want = if (dev == null) "speaker" else "earbuds"
        var now = "unknown"
        repeat(15) {
            now = musicOutput()
            if (now == want) return true
            if (now == "unknown") { EhLog.w("speaker: this phone does not say where music plays, assuming the switch worked"); return true }
            Thread.sleep(100)
        }
        EhLog.w("speaker: music still plays on the $now")
        return false
    }
}
