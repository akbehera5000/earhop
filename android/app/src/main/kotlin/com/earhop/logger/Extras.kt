package com.earhop.logger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent

/**
 * Settings shared between devices (same names and rule as SettingsSync.swift): switching rules, clipboard options, low
 * battery alerts, speaker fallback and the sleep timer length. Each carries when it was last changed; the newest
 * change wins everywhere (stamps corrected for the sender's clock offset). Device name, secret and the per-device Buds
 * settings stay local.
 */
object SettingsSync {
    /** Wire name to (preference name, default). */
    val keys: Map<String, Pair<String, Any>> = linkedMapOf(
        "autoSwitch" to ("auto" to true), "speakerFallback" to ("speakerFallback" to true),
        "followAttention" to ("followAttention" to false), "lowBatteryAlerts" to ("lowBatteryAlerts" to true),
        "volumeHandover" to ("volumeHandover" to true), "pauseWhenOneOut" to ("pauseOnOne" to true),
        "transport" to ("transport" to "both"), "clipSync" to ("clipSync" to true), "clipImages" to ("clipImages" to true),
        "clipReceive" to ("clipReceive" to true), "clipTransport" to ("clipTransport" to "both"),
        "clipSkipPasswords" to ("clipSkipPasswords" to false), "sleepMinutes" to ("sleepMinutes" to 30),
    )

    /** The changes to apply: keys whose remote stamp, in this clock, is newer than ours. Pure, unit tested. */
    fun merge(local: Map<String, Long>, remote: Map<String, Pair<Any, Long>>, offsetMs: Long): Map<String, Pair<Any, Long>> =
        remote.filter { (k, r) -> k in keys && r.second - offsetMs > (local[k] ?: 0L) }.mapValues { (_, r) -> Pair(r.first, r.second - offsetMs) }

    fun values(ctx: Context): Map<String, Any> {
        val p = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
        return keys.mapValues { (_, d) ->
            when (val def = d.second) {
                is Boolean -> p.getBoolean(d.first, def)
                is Int -> p.getInt(d.first, def)
                else -> p.getString(d.first, def as String) ?: def
            }
        }
    }

    fun stamps(ctx: Context): Map<String, Long> {
        val p = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
        return keys.keys.associateWith { p.getLong("stamp_$it", 0L) }
    }

    /** Valid values only. */
    fun apply(ctx: Context, k: String, v: Any, stamp: Long) {
        val d = keys[k] ?: return
        val e = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).edit()
        when (d.second) {
            is Boolean -> (v as? Boolean)?.let { e.putBoolean(d.first, it) } ?: return
            is Int -> (v as? Number)?.toInt()?.takeIf { it in 5..240 }?.let { e.putInt(d.first, it) } ?: return
            else -> (v as? String)?.takeIf { it in setOf("ble", "both", "wifi") }?.let { e.putString(d.first, it) } ?: return
        }
        e.putLong("stamp_$k", stamp).apply()
    }

    fun encode(ctx: Context): ByteArray {
        val v = org.json.JSONObject(); val t = org.json.JSONObject()
        values(ctx).forEach { (k, x) -> v.put(k, x) }
        stamps(ctx).forEach { (k, s) -> if (s > 0) t.put(k, s) }
        return org.json.JSONObject().put("v", v).put("t", t).toString().toByteArray()
    }

    fun decode(b: ByteArray): Map<String, Pair<Any, Long>> = try {
        val o = org.json.JSONObject(String(b, Charsets.UTF_8))
        val v = o.getJSONObject("v"); val t = o.getJSONObject("t")
        v.keys().asSequence().mapNotNull { k -> t.optLong(k, 0L).takeIf { it > 0 }?.let { k to Pair(v.get(k), it) } }.toMap()
    } catch (e: Exception) { emptyMap() }
}

/**
 * Battery reports and alerts, volume handover, shared settings and the sleep timer, run by the service every tick.
 * Messages travel as clip-channel control messages: "batt", "vol" and "settings".
 */
class Extras(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
    private val alerts = BatteryAlerts()
    private var lastBattKey = ""; private var lastBattAt = 0L; private var checkedAt = 0L
    private var remoteVol: Triple<String, Float, Long>? = null
    private var lastVolSent = -1f; private var lastVolAt = 0L; private var wasOwner = false
    private var lastShared: Map<String, String> = emptyMap(); private var sharedAt = 0L; private var sharedPeers = setOf<String>()
    @Volatile private var remoteBattAt = 0L

    /** Messages from other devices. */
    fun onControl(item: ClipItem) {
        val text = String(item.data, Charsets.UTF_8)
        when (item.kind) {
            "batt" -> {
                val p = text.split(",")
                if (p.size < 6 || localBattery() != null) return
                EhState.batA = p[0].toIntOrNull() ?: -1; EhState.batB = p[1].toIntOrNull() ?: -1; EhState.batCase = p[2].toIntOrNull() ?: -1
                EhState.batAt = System.currentTimeMillis(); remoteBattAt = EhState.batAt
                remoteCharging = Triple(p[3] == "1", p[4] == "1", p[5] == "1")
            }
            "vol" -> text.toFloatOrNull()?.let { remoteVol = Triple(item.dev, it, System.currentTimeMillis()) }
            "settings" -> {
                val apply = SettingsSync.merge(SettingsSync.stamps(ctx), SettingsSync.decode(item.data), ClockGuard.shared.offset(item.dev) ?: 0L)
                if (apply.isEmpty()) return
                apply.forEach { (k, r) -> SettingsSync.apply(ctx, k, r.first, r.second) }
                lastShared = SettingsSync.values(ctx).mapValues { it.value.toString() }
                EhLog.w("settings: ${apply.keys.sorted().joinToString(", ")} changed on ${item.dev}, applied here")
            }
        }
    }
    @Volatile private var remoteCharging = Triple(false, false, false)

    /** This device's own battery reading (the Buds channel or the AirPods beacon), or null. */
    private fun localBattery(): List<Any>? {
        val c = EhState.budsClient
        if (Earbuds.isBuds(ctx)) {
            if (c == null || !c.connected) return null
            val s = c.snapshot()
            if (s.battL < 0 && s.battR < 0) return null
            return listOf(s.battL, s.battR, s.battCase, s.chargeL, s.chargeR, s.chargeCase)
        }
        if (System.currentTimeMillis() - EhState.beaconAt > 30_000 || EhState.batAt == remoteBattAt) return null
        return listOf(EhState.batA, EhState.batB, EhState.batCase, false, false, false)
    }

    private fun channel(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        channel("earhop-battery", "Low battery")
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, "earhop-battery") else android.app.Notification.Builder(ctx)
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, b.setSmallIcon(R.drawable.ic_tile_take).setContentTitle(title).setContentText(text).setAutoCancel(true).build()) } catch (e: Exception) { }
    }

    /** Every 2 s: share this device's own battery levels and alert at 20 % and 10 %. */
    fun batteryTick() {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 2000) return
        checkedAt = now
        val local = localBattery()
        val l: Int; val r: Int; val c: Int; val ch: Triple<Boolean, Boolean, Boolean>
        if (local != null) {
            l = local[0] as Int; r = local[1] as Int; c = local[2] as Int; ch = Triple(local[3] as Boolean, local[4] as Boolean, local[5] as Boolean)
            val key = "$l,$r,$c,${if (ch.first) 1 else 0},${if (ch.second) 1 else 0},${if (ch.third) 1 else 0}"
            if (key != lastBattKey || now - lastBattAt > 60_000) { lastBattKey = key; lastBattAt = now; ClipSync.sendControl(ctx, "batt", key.toByteArray(), null) }
        } else if (now - remoteBattAt < 600_000) {
            l = EhState.batA; r = EhState.batB; c = EhState.batCase; ch = remoteCharging
        } else return
        if (!prefs.getBoolean("lowBatteryAlerts", true)) return
        val buds = if (Earbuds.isBuds(ctx)) "Galaxy Buds" else "AirPods"
        listOf(Triple("left", l, ch.first) to "Left earbud", Triple("right", r, ch.second) to "Right earbud", Triple("case", c, ch.third) to "$buds case")
            .forEachIndexed { i, (p, words) ->
                alerts.update(p.first, p.second, p.third)?.let { t ->
                    notify(4100 + i, "$words battery low", "${p.second} % left" + if (t == 10) ", charge soon" else "")
                    EhLog.w("battery: ${p.first} at ${p.second} % (alert at $t %)")
                }
            }
    }

    /** While this device has the earbuds it tells the others its volume; when they arrive here it takes the last one. */
    fun volumeTick(owner: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val rv = remoteVol
        if (owner && !wasOwner && prefs.getBoolean("volumeHandover", true) && rv != null && System.currentTimeMillis() - rv.third < 600_000) {
            android.os.Handler(ctx.mainLooper).postDelayed({
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(rv.second * max), 0) } catch (e: Exception) { }
                EhLog.w("volume: set to ${(rv.second * 100).toInt()} %, as on ${rv.first}")
            }, 1500)
        }
        wasOwner = owner
        if (!owner) return
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        val now = System.currentTimeMillis()
        if (Math.abs(v - lastVolSent) > 0.02f || now - lastVolAt > 60_000) {
            lastVolSent = v; lastVolAt = now
            ClipSync.sendControl(ctx, "vol", "%.3f".format(java.util.Locale.US, v).toByteArray(), null)
        }
    }

    /** A shared setting changed here is stamped and sent; the whole set goes out every 5 minutes and to new devices. */
    fun settingsTick() {
        val now = System.currentTimeMillis()
        val vals = SettingsSync.values(ctx).mapValues { it.value.toString() }
        if (lastShared.isEmpty()) { lastShared = vals; return }
        var changed = false
        val e = prefs.edit()
        for ((k, v) in vals) if (lastShared[k] != v) { e.putLong("stamp_$k", now); changed = true }
        e.apply()
        lastShared = vals
        val peersNow = EhState.peers.keys.toSet()
        val newPeer = (peersNow - sharedPeers).isNotEmpty()
        sharedPeers = peersNow
        if (changed || newPeer || now - sharedAt > 300_000) { sharedAt = now; ClipSync.sendControl(ctx, "settings", SettingsSync.encode(ctx), null) }
    }

    /** The sleep timer: when it ends, pause and let go of the earbuds. */
    fun sleepTick(owner: Boolean, controller: HandoffController?) {
        val until = EhState.sleepUntil
        if (until == 0L || System.currentTimeMillis() < until) return
        EhState.sleepUntil = 0L
        EhLog.w("sleep: timer ended, pausing and releasing the earbuds")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        if (owner) android.os.Handler(ctx.mainLooper).postDelayed({ controller?.manual(false) }, 1000)
        notify(4200, "Sleep timer", "Paused and released the earbuds")
    }

    companion object {
        fun setSleep(ctx: Context, minutes: Int) {
            if (minutes <= 0) { EhState.sleepUntil = 0L; EhLog.w("sleep: timer cancelled"); return }
            EhState.sleepUntil = System.currentTimeMillis() + minutes * 60_000L
            if (minutes in 5..240) ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).edit().putInt("sleepMinutes", minutes).apply()
            EhLog.w("sleep: pause and release in $minutes min")
        }
    }
}

/**
 * Automation for Tasker and other apps (only when "Allow other apps to control EarHop" is on): broadcasts
 * com.earhop.logger.action.TAKE, RELEASE, SEND_CLIPBOARD, SEND_TEXT (extra "text"), SLEEP (extra "minutes") and
 * SLEEP_CANCEL.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        if (!app.getSharedPreferences("earhop", Context.MODE_PRIVATE).getBoolean("allowAutomation", false)) {
            EhLog.w("automation: ${i.action} ignored, other apps are not allowed to control EarHop"); return
        }
        if (!EhState.running) { EhLog.w("automation: ${i.action} ignored, EarHop is stopped"); return }
        EhLog.w("automation: ${i.action?.substringAfterLast('.')}")
        when (i.action) {
            "com.earhop.logger.action.TAKE" -> EhState.controller?.manual(true)
            "com.earhop.logger.action.RELEASE" -> EhState.controller?.manual(false)
            "com.earhop.logger.action.SEND_TEXT" -> i.getStringExtra("text")?.let { ClipSync.sendClip(app, android.content.ClipData.newPlainText("Automation", it)) }
            "com.earhop.logger.action.SEND_CLIPBOARD" -> try {
                app.startActivity(Intent(app, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { EhLog.w("automation: Android blocked opening the send screen; use SEND_TEXT with %CLIP") }
            "com.earhop.logger.action.SLEEP" -> Extras.setSleep(app, i.getIntExtra("minutes", app.getSharedPreferences("earhop", Context.MODE_PRIVATE).getInt("sleepMinutes", 30)))
            "com.earhop.logger.action.SLEEP_CANCEL" -> Extras.setSleep(app, 0)
        }
    }
}
