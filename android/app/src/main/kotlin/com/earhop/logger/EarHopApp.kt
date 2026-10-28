package com.earhop.logger

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash so the app can show it on the next launch instead of failing silently. */
class EarHopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EhLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                getSharedPreferences("earhop", MODE_PRIVATE).edit()
                    .putString("last_crash", "thread ${t.name}\n$sw".take(3000)).commit()
            } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }
}
