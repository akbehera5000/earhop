package com.earhop.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice

/**
 * EXPERIMENTAL claim and release. BluetoothA2dp.connect/disconnect are hidden APIs, so they are
 * called by reflection and may be blocked by some Android builds. Every result is logged so the
 * milestone 2 test shows what the real devices allow.
 */
class Claimer(
    private val ctx: android.content.Context, private val adapter: BluetoothAdapter?, private val a2dp: () -> BluetoothA2dp?,
    private val headset: () -> android.bluetooth.BluetoothHeadset? = { null },
) {

    @SuppressLint("MissingPermission")
    private fun find(filter: String): BluetoothDevice? =
        adapter?.bondedDevices?.firstOrNull { it.name?.contains(filter, ignoreCase = true) == true }

    /** Returns true or false when the call ran, null when the phone blocked it (hidden API refused). */
    private fun call(method: String, filter: String): Boolean? {
        val dev = find(filter)
        if (dev == null) { EhLog.w("claim: no paired device matching '$filter'"); return false }
        val proxy = a2dp()
        if (proxy == null) { EhLog.w("claim: A2DP proxy not ready"); return false }
        return try {
            val m = BluetoothA2dp::class.java.getMethod(method, BluetoothDevice::class.java)
            val ok = m.invoke(proxy, dev) as? Boolean ?: false
            EhLog.w("claim: $method returned $ok")
            ok
        } catch (e: Exception) {
            EhLog.w("claim: $method blocked (${e.javaClass.simpleName}: ${e.cause?.message ?: e.message})")
            null
        }
    }

    /**
     * Normal call only, exactly as before. Root is used only when the user switched it on and the
     * normal call was blocked outright, never when it merely returned false.
     */
    private fun withRoot(method: String, filter: String): Boolean? {
        val prefs = ctx.getSharedPreferences("earhop", android.content.Context.MODE_PRIVATE)
        val useRoot = prefs.getBoolean("rootGranted", false) && prefs.getBoolean("useRoot", false)
        if (useRoot && prefs.getBoolean("rootFirst", false)) {
            val dev = find(filter)
            if (dev != null && RootShell.bluetooth(ctx, method, dev.address)) return true
        }
        val r = call(method, filter)
        if (r != null) return r
        if (!useRoot) return null
        val dev = find(filter) ?: return false
        EhLog.w("claim: trying $method as root")
        return RootShell.bluetooth(ctx, method, dev.address)
    }

    /** true or false when the call ran, null when the phone blocked it and root is not in use. */
    fun take(filter: String): Boolean? = withRoot("connect", filter)
    fun release(filter: String): Boolean? {
        val r = withRoot("disconnect", filter)
        dropCallLink(filter)
        return r
    }

    /**
     * Also drops the call link (HFP). With only the music link dropped, Galaxy Buds stayed tied to this device by the
     * call link and reconnected the music within 2 s, so the Mac or tablet could not take them. Best effort: some
     * Android builds refuse the hidden call, which is logged and otherwise ignored.
     */
    @SuppressLint("MissingPermission")
    private fun dropCallLink(filter: String) {
        val hs = headset() ?: return
        val dev = find(filter) ?: return
        try {
            if (hs.getConnectionState(dev) != android.bluetooth.BluetoothProfile.STATE_CONNECTED) return
            val m = android.bluetooth.BluetoothHeadset::class.java.getMethod("disconnect", BluetoothDevice::class.java)
            EhLog.w("claim: call link disconnect returned ${m.invoke(hs, dev)}")
        } catch (e: Exception) {
            EhLog.w("claim: call link disconnect blocked (${e.javaClass.simpleName}: ${e.cause?.message ?: e.message})")
        }
    }
}
