package com.earhop.logger


import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

/**
 * The ongoing EarHop notification: where the earbuds are and what EarHop is doing, battery bars, noise control for
 * Galaxy Buds connected to this device, Take or Release, and automatic switching on or off. The buttons go to
 * EarActionReceiver, so they work without opening the app.
 */
object NotifUi {
    const val ACTION_NOISE = "com.earhop.logger.NOISE"
    const val ACTION_AUTO = "com.earhop.logger.AUTO"
    const val EXTRA_MODE = "mode"

    private fun pretty(n: String) = when (n) { "mac" -> "Mac"; "phone" -> "phone"; "tab" -> "tablet"; else -> n }

    fun accent(ctx: Context) = if (Earbuds.isBuds(ctx)) 0xFF9466FF.toInt() else 0xFF4D9EFF.toInt()

    private fun broadcast(ctx: Context, action: String, code: Int, mode: String? = null): PendingIntent {
        val i = Intent(ctx, EarActionReceiver::class.java).setAction(action)
        if (mode != null) i.putExtra(EXTRA_MODE, mode)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Everything the notification shows, as one string, so it is only rebuilt when something visible changed. */
    fun signature(ctx: Context): String {
        val sn = EhState.budsClient?.takeIf { it.connected }?.snapshot()
        val now = System.currentTimeMillis()
        val holder = EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
        val auto = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE).getBoolean("auto", true)
        return listOf(EhState.owner, EhState.playing, EhState.stage, EhState.safeMode, EhState.phase, holder, auto, EhState.call,
            EhState.batA, EhState.batB, EhState.batCase, now - EhState.batAt < 60000, sn?.noise, EhState.lastFailure, Earbuds.isBuds(ctx)).joinToString("|")
    }

    fun build(ctx: Context): Notification {
        val prefs = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val label = Earbuds.label(ctx)
        val me = prefs.getString("name", "phone") ?: "phone"
        val holder = EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
        val where = when {
            EhState.owner -> "On this ${pretty(me)}"
            holder != null -> "On the ${pretty(holder)}"
            else -> "Not connected"
        }
        val word = EhState.stage ?: when {
            EhState.safeMode -> "Safe mode, tap to open"
            EhState.call -> "Call"
            EhState.owner && EhState.playing -> "Playing"
            EhState.owner && EhState.phase == HandoffPhase.OWNER_PAUSED_BY_EAR_REMOVAL -> "Paused, earbud out"
            EhState.owner -> "Ready"
            EhState.playing -> "Playing here"
            else -> "Idle"
        }
        val sn = EhState.budsClient?.takeIf { it.connected && Earbuds.isBuds(ctx) }?.snapshot()
        val noiseWord = when (sn?.noise) { "anc" -> "Noise cancelling"; "ambient" -> "Ambient"; "off" -> "Noise control off"; else -> null }
        val sub = listOfNotNull(where, word, noiseWord).joinToString(" · ")
        val note = EhState.lastFailure?.let { "Last switch failed: $it" } ?: EhState.holdReason?.takeIf { !EhState.owner }?.let { "Not switching: $it" }
        val fresh = now - EhState.batAt < 60000
        fun pct(v: Int) = if (fresh && v >= 0) "$v%" else "-"
        val batt = if (fresh && (EhState.batA >= 0 || EhState.batB >= 0)) "L ${pct(EhState.batA)}  R ${pct(EhState.batB)}" else ""
        val auto = prefs.getBoolean("auto", true)
        val pkg = ctx.packageName

        val compact = RemoteViews(pkg, R.layout.notif_compact).apply {
            setTextViewText(R.id.n_title, label)
            setTextViewText(R.id.n_sub, sub)
            setTextViewText(R.id.n_batt, batt)
        }
        val big = RemoteViews(pkg, R.layout.notif_expanded).apply {
            setTextViewText(R.id.n_title, label)
            setTextViewText(R.id.n_sub, if (note != null) "$sub\n$note" else sub)
            val vals = listOf(EhState.batA, EhState.batB, EhState.batCase)
            val names = listOf("Left", "Right", "Case")
            val bars = listOf(R.id.n_p0, R.id.n_p1, R.id.n_p2)
            val texts = listOf(R.id.n_b0, R.id.n_b1, R.id.n_b2)
            val anyBattery = fresh && vals.any { it >= 0 }
            setViewVisibility(R.id.n_bars, if (anyBattery) View.VISIBLE else View.GONE)
            for (i in 0..2) {
                setTextViewText(texts[i], "${names[i]} ${pct(vals[i])}")
                setProgressBar(bars[i], 100, if (fresh && vals[i] >= 0) vals[i] else 0, false)
            }
            // Noise control: only for Galaxy Buds connected to this device.
            setViewVisibility(R.id.n_noise, if (sn != null) View.VISIBLE else View.GONE)
            if (sn != null) {
                for ((id, mode) in listOf(R.id.n_off to "off", R.id.n_anc to "anc", R.id.n_amb to "ambient")) {
                    val on = sn.noise == mode
                    setInt(id, "setBackgroundResource", if (on) R.drawable.notif_pill_on else R.drawable.notif_pill_off)
                    if (on) setTextColor(id, Color.WHITE)
                    setOnClickPendingIntent(id, broadcast(ctx, ACTION_NOISE, 30 + mode.length, mode))
                }
            }
            // Take or Release, and automatic switching.
            if (EhState.owner) {
                setTextViewText(R.id.n_take, "Release")
                setOnClickPendingIntent(R.id.n_take, broadcast(ctx, "com.earhop.logger.RELEASE", 21))
            } else {
                setTextViewText(R.id.n_take, "Take ${Earbuds.short(ctx)}")
                setInt(R.id.n_take, "setBackgroundResource", R.drawable.notif_pill_on)
                setTextColor(R.id.n_take, Color.WHITE)
                setOnClickPendingIntent(R.id.n_take, broadcast(ctx, "com.earhop.logger.TAKE", 20))
            }
            setOnClickPendingIntent(R.id.n_clip, PendingIntent.getActivity(ctx, 23,
                Intent(ctx, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            setTextViewText(R.id.n_auto, if (auto) "Auto switch: On" else "Auto switch: Off")
            setOnClickPendingIntent(R.id.n_auto, broadcast(ctx, ACTION_AUTO, 22))
        }

        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, "earhop")
            .setSmallIcon(R.drawable.ic_stat_earhop)
            .setColor(accent(ctx))
            .setContentTitle(label)
            .setContentText(sub)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(big)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }
}
