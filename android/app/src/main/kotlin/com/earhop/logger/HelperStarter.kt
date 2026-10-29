package com.earhop.logger

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Starts the clipboard helper (ClipHelper) without a computer, through Shizuku. Android only lets the shell user read the
 * clipboard in the background, and only adb or an app like Shizuku (which itself runs as the shell user, started over
 * Wi-Fi debugging, also after a reboot) can start shell processes. EarHop asks Shizuku once for permission, then starts
 * the helper whenever it is not running (ClipSync.watchHelper).
 */
object HelperStarter {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST = 4711

    fun shizukuInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true } catch (e: Exception) { false }

    /** Shizuku's service is up (it stops when the phone restarts until Shizuku starts again). */
    fun shizukuRunning() = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted() = shizukuRunning() && try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** Shows Shizuku's own "Allow EarHop" prompt. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!shizukuRunning()) { onResult(false); return }
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(code: Int, result: Int) {
                if (code != REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(result == PackageManager.PERMISSION_GRANTED)
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(l)
            Shizuku.requestPermission(REQUEST)
        } catch (e: Throwable) { EhLog.w("clip: Shizuku permission request failed: ${e.message}"); onResult(false) }
    }

    /** Calls [onReady] whenever Shizuku comes up (for example a while after the phone restarted). */
    fun onShizukuReady(onReady: () -> Unit) {
        try { Shizuku.addBinderReceivedListenerSticky { onReady() } } catch (e: Throwable) { }
    }

    /** Stops a running helper through Shizuku (when another automatic sending method is chosen). */
    fun stop() {
        if (!granted()) return
        try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            (m.invoke(null, arrayOf("sh", "-c", "kill \$(pidof earhop-clip) 2>/dev/null"), null, null) as Process).waitFor()
            EhLog.w("clip: stopped the clipboard helper")
        } catch (e: Throwable) { EhLog.w("clip: could not stop the helper: ${e.cause?.message ?: e.message}") }
    }

    /**
     * Starts the helper as the shell user through Shizuku, replacing an old one. The same command as tools/phone_clip.sh.
     * Returns false when Shizuku is not available or not allowed.
     */
    fun start(ctx: Context): Boolean {
        if (!granted()) return false
        val uid = try { Shizuku.getUid() } catch (e: Throwable) { -1 }
        if (uid != 2000) {
            // The helper reads the clipboard as the shell package; Shizuku started with root runs as uid 0, where that fails.
            EhLog.w("clip: Shizuku runs as uid $uid, the helper needs the shell user (start Shizuku with Wi-Fi debugging)")
            return false
        }
        val apk = ctx.applicationInfo.sourceDir
        val cmd = "kill \$(pidof earhop-clip) 2>/dev/null; CLASSPATH=$apk setsid nohup app_process /system/bin " +
            "--nice-name=earhop-clip com.earhop.logger.ClipHelper >/data/local/tmp/earhop-clip.log 2>&1 </dev/null &"
        return try {
            // newProcess is not public in the Shizuku API 13 library, but it is the documented way to run a command.
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            Thread { try { p.waitFor() } catch (e: Exception) { } }.start()
            EhLog.w("clip: started the clipboard helper with Shizuku")
            true
        } catch (e: Throwable) {
            EhLog.w("clip: could not start the helper with Shizuku: ${e.cause?.message ?: e.message}")
            false
        }
    }
}
