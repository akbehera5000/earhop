package com.earhop.logger

import android.content.Context
import java.util.concurrent.TimeUnit

/** Runs commands through `su`. Result of the first attempt is remembered so a phone without root is not asked again. */
object RootShell {
    @Volatile var state = "not tried" // not tried, available, denied or missing
        private set

    fun run(cmd: String, timeoutSec: Long = 15): Pair<Boolean, String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (e: Exception) { } }
            reader.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return Pair(false, "timeout") }
            reader.join(500)
            Pair(p.exitValue() == 0, out.toString().trim())
        } catch (e: Exception) {
            state = "missing"
            Pair(false, "no su (${e.message})")
        }
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

    /** Runs the connect or disconnect helper as root. Returns true when the call reported success. */
    fun bluetooth(ctx: Context, action: String, address: String): Boolean {
        if (state == "missing") return false
        if (state != "available" && !check()) return false
        val apk = ctx.applicationInfo.sourceDir
        val (_, out) = run("CLASSPATH=$apk /system/bin/app_process /system/bin com.earhop.logger.RootBt $action $address", 20)
        val line = out.lines().lastOrNull { it.startsWith("EARHOP_ROOT") } ?: "no result: ${out.take(120)}"
        EhLog.w("root: $action -> $line")
        return line.startsWith("EARHOP_ROOT ok") && line.endsWith("true")
    }
}
