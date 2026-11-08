package com.earhop.logger

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper

/**
 * Runs as root outside the app (started through `su` with app_process), where the hidden Bluetooth
 * connect and disconnect calls are not blocked by the privileged permission check.
 * Usage: RootBt <connect|disconnect|activeNone|activeSet> <AA:BB:CC:DD:EE:FF>. Prints one line starting with EARHOP_ROOT.
 */
object RootBt {
    @JvmStatic
    fun main(args: Array<String>) {
        val action = args.getOrNull(0) ?: "connect"
        val addr = args.getOrNull(1)
        if (addr == null) { println("EARHOP_ROOT fail no address"); return }
        Thread {
            try { Thread.sleep(12000) } catch (e: InterruptedException) { }
            println("EARHOP_ROOT fail timeout")
            System.exit(1)
        }.also { it.isDaemon = true }.start()
        try {
            try { Looper.prepare() } catch (e: Throwable) { }
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread) as Context
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            // On the tablet (2026-11-08) the system context's manager has no adapter: every root connect failed with a
            // NullPointerException. The process-wide default adapter works there.
            @Suppress("DEPRECATION")
            val adapter = bm?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: run { println("EARHOP_ROOT fail no Bluetooth adapter"); System.exit(1); return }
            // A check only: Bluetooth is reachable as root here. Touches no device.
            if (action == "probe") { println("EARHOP_ROOT ok probe=true"); System.exit(0) }
            val dev: BluetoothDevice = adapter.getRemoteDevice(addr)
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        // activeNone / activeSet: move media to the speaker or back to these earbuds (speaker fallback).
                        val method = if (action.startsWith("active")) "setActiveDevice" else action
                        val m = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
                        m.isAccessible = true
                        println("EARHOP_ROOT ok $action=${m.invoke(proxy, if (action == "activeNone") null else dev)}")
                    } catch (e: Throwable) {
                        println("EARHOP_ROOT fail ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
                    }
                    Thread { Thread.sleep(400); System.exit(0) }.start()
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
            Looper.loop()
        } catch (e: Throwable) {
            println("EARHOP_ROOT fail ${e.javaClass.simpleName}: ${e.message}")
            System.exit(1)
        }
    }
}
