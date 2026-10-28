package com.earhop.logger

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast

/** Home screen widget: where the earbuds are and what EarHop is doing, with Take and Release buttons. */
class EarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx, lastStatus))
    }

    companion object {
        @Volatile private var lastStatus = "Open EarHop to start"

        private fun pending(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, EarActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun views(ctx: Context, status: String) = RemoteViews(ctx.packageName, R.layout.widget_earhop).apply {
            setTextViewText(R.id.widget_title, "EarHop \u00B7 ${Earbuds.label(ctx)}")
            setTextViewText(R.id.widget_status, status)
            setOnClickPendingIntent(R.id.widget_take, pending(ctx, "com.earhop.logger.TAKE", 11))
            setOnClickPendingIntent(R.id.widget_release, pending(ctx, "com.earhop.logger.RELEASE", 12))
            setOnClickPendingIntent(R.id.widget_status, PendingIntent.getActivity(ctx, 13, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }

        /** Called by the service whenever the status line changes. */
        fun push(ctx: Context, status: String) {
            lastStatus = status
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, EarWidget::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx, status))
            } catch (e: Exception) { }
        }
    }
}

/** Runs the Take or Release from the widget without opening the app. */
class EarActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        when (i.action) {
            NotifUi.ACTION_NOISE -> {
                val mode = i.getStringExtra(NotifUi.EXTRA_MODE) ?: return
                val c = EhState.budsClient
                if (c == null || !c.connected) { Toast.makeText(app, "The Buds are not connected to this device", Toast.LENGTH_SHORT).show(); return }
                EhLog.w("buds: noise $mode from notification")
                c.setNoise(mode)
                return
            }
            NotifUi.ACTION_AUTO -> {
                val p = app.getSharedPreferences("earhop", Context.MODE_PRIVATE)
                val on = !p.getBoolean("auto", true)
                p.edit().putBoolean("auto", on).apply()
                EhLog.w("handoff: automatic switching ${if (on) "on" else "off"} from notification")
                return
            }
        }
        val take = i.action == "com.earhop.logger.TAKE"
        if (!take && i.action != "com.earhop.logger.RELEASE") return
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(app, "Open EarHop once and allow Nearby devices", Toast.LENGTH_LONG).show()
            return
        }
        val pr = goAsync()
        TileActions.run(app, take) { pr.finish() }
    }
}

/** Invisible target for the long press app shortcuts. */
class ActionActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val take = intent?.action == "com.earhop.logger.TAKE"
        if (intent?.action == "com.earhop.logger.TAKE" || intent?.action == "com.earhop.logger.RELEASE") {
            if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Open EarHop once and allow Nearby devices", Toast.LENGTH_LONG).show()
            } else {
                TileActions.run(applicationContext, take) { }
            }
        }
        finish()
    }
}
