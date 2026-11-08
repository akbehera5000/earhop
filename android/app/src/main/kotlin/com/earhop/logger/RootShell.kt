package com.earhop.logger

import android.content.Context
import java.util.concurrent.TimeUnit

/** Runs commands through `su`. Result of the first attempt is remembered so a phone without root is not asked again. */
object RootShell {
    @Volatile var state = "not tried" // not tried, available, denied or missing
        private set

    /**
     * One root shell kept open for every command. Each new `su` makes Magisk show "EarHop granted root access", and
     * EarHop ran one for every copy it noticed and every switch, so the toast kept appearing on the tablet. Now `su`
     * starts once (again only if that shell died or a command hung).
     */
    private var shell: Process? = null
    private var shellIn: java.io.Writer? = null
    private var shellOut: java.io.BufferedReader? = null
    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()

    @Synchronized
    fun run(cmd: String, timeoutSec: Long = 15): Pair<Boolean, String> {
        return try {
            if (shell?.isAlive != true) open()
            lines.clear()
            val marker = "__EARHOP_END_${System.nanoTime()}__"
            shellIn!!.apply { write("( $cmd ) 2>&1 </dev/null; echo \"$marker \$?\"\n"); flush() }
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val line = if (left > 0) lines.poll(left, TimeUnit.MILLISECONDS) else null
                if (line == null) { close(); return Pair(false, "timeout") }
                if (line.startsWith(marker)) return Pair(line.substringAfter(' ').trim() == "0", out.toString().trim())
                out.appendLine(line)
            }
            @Suppress("UNREACHABLE_CODE") Pair(false, "")
        } catch (e: Exception) {
            close()
            if (e is java.io.IOException && shell == null && e.message?.contains("su") == true) state = "missing"
            Pair(false, "no su (${e.message})")
        }
    }

    private fun open() {
        close()
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        shell = p
        shellIn = p.outputStream.bufferedWriter()
        shellOut = p.inputStream.bufferedReader()
        val reader = shellOut!!
        Thread({ try { reader.forEachLine { lines.offer(it) } } catch (e: Exception) { } }, "earhop-root-shell").apply { isDaemon = true }.start()
    }

    private fun close() {
        try { shellIn?.close() } catch (e: Exception) { }
        try { shell?.destroy() } catch (e: Exception) { }
        shell = null; shellIn = null; shellOut = null
    }

    /** True when a su binary exists. Looks at files only, so it never triggers a root prompt. */
    fun hasSu(): Boolean = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
        "/su/bin/su", "/product/bin/su", "/debug_ramdisk/su", "/data/adb/magisk", "/data/adb/ksu").any { java.io.File(it).exists() }

    /** Asks for root. On Magisk or KernelSU a grant prompt appears the first time. */
    fun check(): Boolean {
        if (state == "missing") return false
        val (ok, out) = run("id")
        state = when {
            ok && out.contains("uid=0") -> "available"
            out.startsWith("no su") -> "missing"
            else -> "denied"
        }
        EhLog.w("root: $state ${out.take(60)}")
        return state == "available"
    }

    /**
     * Whether the root Bluetooth helper can reach Bluetooth on this device: null until checked. Checked once (it
     * touches no device). On the tablet's Android version it cannot (no adapter as root), and every switch still tried
     * it first, adding a failed root call (and a "granted root access" toast) each time.
     */
    @Volatile var btHelper: Boolean? = null
        private set

    fun btHelperWorks(ctx: Context): Boolean {
        btHelper?.let { return it }
        if (state == "missing" || (state != "available" && !check())) return false
        val apk = ctx.applicationInfo.sourceDir
        val (_, out) = run("CLASSPATH=$apk /system/bin/app_process /system/bin com.earhop.logger.RootBt probe 00:00:00:00:00:00", 20)
        val ok = out.contains("EARHOP_ROOT ok probe=true")
        btHelper = ok
        EhLog.w(if (ok) "root: the Bluetooth helper works" else "root: the Bluetooth helper cannot reach Bluetooth on this Android version, so switching uses the normal way only (${out.lines().lastOrNull { it.startsWith("EARHOP_ROOT") } ?: out.take(80)})")
        return ok
    }

    /** Runs the connect or disconnect helper as root. Returns true when the call reported success. */
    fun bluetooth(ctx: Context, action: String, address: String): Boolean {
        if (state == "missing") return false
        if (state != "available" && !check()) return false
        if (!btHelperWorks(ctx)) return false
        val apk = ctx.applicationInfo.sourceDir
        val (_, out) = run("CLASSPATH=$apk /system/bin/app_process /system/bin com.earhop.logger.RootBt $action $address", 20)
        val line = out.lines().lastOrNull { it.startsWith("EARHOP_ROOT") } ?: "no result: ${out.take(120)}"
        EhLog.w("root: $action -> $line")
        return line.startsWith("EARHOP_ROOT ok") && line.endsWith("true")
    }
}
