package com.earhop.logger

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper

/**
 * Runs as root outside the app (started through `su` with app_process), where the hidden Bluetooth
 * connect and disconnect calls are not blocked by the privileged permission check.
 * Usage: RootBt <connect|disconnect> <AA:BB:CC:DD:EE:FF>. Prints one line starting with EARHOP_ROOT.
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
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bm.adapter
            val dev: BluetoothDevice = adapter.getRemoteDevice(addr)
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val m = proxy.javaClass.getMethod(action, BluetoothDevice::class.java)
                        m.isAccessible = true
                        println("EARHOP_ROOT ok $action=${m.invoke(proxy, dev)}")
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
