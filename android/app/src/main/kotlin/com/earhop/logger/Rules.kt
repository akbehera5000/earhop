package com.earhop.logger

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import java.util.Calendar

/** Empty listener. Its only job is to let EarHop see which app is playing media, once the user allows notification access. */
class EarMediaListener : NotificationListenerService()

/** Settings that shape automatic switching. All values live in the "earhop" preferences and are per device. */
object RuleStore {
    val devices = listOf("mac", "tab", "phone")
    private fun p(ctx: Context) = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)

    fun priority(ctx: Context): List<String> {
        val saved = (p(ctx).getString("priority", "") ?: "").split(",").map { it.trim() }.filter { it in devices }.distinct()
        return saved + devices.filter { it !in saved }
    }

    fun setPriority(ctx: Context, list: List<String>) { p(ctx).edit().putString("priority", list.joinToString(",")).apply() }

    fun rules(ctx: Context): AutoGate.Rules {
        val pr = p(ctx)
        return AutoGate.Rules(
            quietEnabled = pr.getBoolean("quietOn", false),
            quietStartMin = pr.getInt("quietStart", 22 * 60),
            quietEndMin = pr.getInt("quietEnd", 7 * 60),
            wifiOnly = pr.getBoolean("wifiOnly", false),
            wifiNames = pr.getStringSet("wifiNames", emptySet()) ?: emptySet(),
            respectDnd = pr.getBoolean("respectDnd", false),
        )
    }

    @Suppress("DEPRECATION")
    fun ssid(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val s = wm.connectionInfo?.ssid?.trim('"')
        if (s == null || s == "<unknown ssid>" || s.isEmpty()) null else s
    } catch (e: Exception) { null }

    fun dndOn(ctx: Context): Boolean = try {
        val f = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) { false }

    @Volatile private var gateAt = 0L
    @Volatile private var gateCached: String? = null

    /**
     * Reason automatic claiming should hold off right now, or null. Reading the Wi-Fi name and Do Not Disturb costs
     * system calls, so the answer is reused for 0.5 s: checked twice a second while the loop runs four times.
     */
    fun gate(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - gateAt < 500) return gateCached
        val c = Calendar.getInstance()
        gateCached = AutoGate.reason(rules(ctx), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), ssid(ctx), dndOn(ctx))
        gateAt = now
        return gateCached
    }

    /** Per-device volume profile. */
    fun applyVolume(ctx: Context) {
        val pr = p(ctx)
        if (!pr.getBoolean("volumeOn", false)) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val pct = pr.getInt("volumePct", 40).coerceIn(0, 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * pct / 100, 0)
            EhLog.w("volume profile: set to $pct%")
        } catch (e: Exception) { EhLog.w("volume profile failed ${e.message}") }
    }
}

/** Per-app rules. Needs notification access, otherwise every app counts. */
object AppRules {
    private var cachedAt = 0L
    private var cached: Set<String>? = null

    fun hasAccess(ctx: Context): Boolean =
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true

    private fun playingPackages(ctx: Context): Set<String>? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 800) return cached
        cachedAt = now
        cached = try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(ctx, EarMediaListener::class.java))
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }.toSet()
        } catch (e: SecurityException) { null } catch (e: Exception) { null }
        return cached
    }

    private fun set(ctx: Context, key: String) = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()

    /** Music is active, but not only because of an ignored app. */
    fun effectivePlaying(ctx: Context, musicActive: Boolean): Boolean {
        if (!musicActive) return false
        val ignored = set(ctx, "ignoredApps")
        if (ignored.isEmpty()) return true
        val pk = playingPackages(ctx) ?: return true
        return pk.isEmpty() || !pk.all { it in ignored }
    }

    /** A preferred app is playing, so claim without waiting out the minimum play time. */
    fun preferredPlaying(ctx: Context): Boolean {
        val pref = set(ctx, "preferredApps")
        if (pref.isEmpty()) return false
        return playingPackages(ctx)?.any { it in pref } ?: false
    }
}
