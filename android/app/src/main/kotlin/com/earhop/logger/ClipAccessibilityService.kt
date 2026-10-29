package com.earhop.logger

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, heuristic clipboard trigger for devices where Android blocks background clipboard reads.
 * It only checks short accessibility event text for copy confirmations. The clipboard itself is read by
 * ClipActivity after Android brings EarHop to the foreground.
 */
class ClipAccessibilityService : AccessibilityService() {
    companion object {
        /** True while Android has this service bound and running (not just listed in the settings). */
        @Volatile var connected = false
            private set

        /** Whether EarHop clipboard send is switched on in Android's Accessibility settings (the saved setting). */
        fun switchedOn(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val full = android.content.ComponentName(ctx, ClipAccessibilityService::class.java)
            val listed = list.split(':').any {
                it.equals(full.flattenToString(), ignoreCase = true) || it.equals(full.flattenToShortString(), ignoreCase = true)
            }
            return listed && Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }

        /** Whether Android's accessibility manager itself counts the service as enabled (its live list, not the saved string). */
        fun systemEnabled(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
            return try {
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                    val s = it.resolveInfo?.serviceInfo
                    s != null && s.packageName == ctx.packageName && s.name == ClipAccessibilityService::class.java.name
                }
            } catch (e: Exception) { false }
        }

        /**
         * "on" only when all three agree: switched on in Settings, enabled in Android's live list, and connected to EarHop.
         * "listed" when switched on but Android has not (yet) started it, "off" otherwise. A stale `connected` flag or a
         * leftover settings string alone never shows On.
         */
        fun state(ctx: Context): String {
            val on = switchedOn(ctx)
            return when {
                on && systemEnabled(ctx) && connected -> "on"
                on -> "listed"
                else -> "off"
            }
        }

        private val phrases = listOf("copied to clipboard", "copied to the clipboard", "copied to your clipboard", "copied text",
            "link copied", "text copied")
        private val exact = setOf("copied", "copy successful", "copied!")
    }

    private var lastLaunchAt = 0L

    override fun onServiceConnected() { super.onServiceConnected(); connected = true; EhLog.w("clip: accessibility service running") }
    override fun onUnbind(intent: Intent?): Boolean { connected = false; return super.onUnbind(intent) }
    override fun onDestroy() { connected = false; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() == packageName) return
        if (!ClipSync.enabled(this) || !ClipSync.autoRoot(this) || !ClipSync.accessibilityCopyTrigger(this) ||
            ClipSync.paused(this) || !EhState.running) return

        // Toasts and snackbars ("Copied"), and Android 13+'s own clipboard preview, which System UI shows after a copy.
        val words = (event.text.mapNotNull { it?.toString() } + listOfNotNull(event.contentDescription?.toString()))
            .map { it.trim().lowercase() }
        val systemPreview = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == "com.android.systemui" && event.className?.toString()?.contains("lipboard") == true
        val confirmsCopy = systemPreview || words.any { t -> t in exact || phrases.any { t.contains(it) } }
        if (!confirmsCopy) return

        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 1800) return
        lastLaunchAt = now
        EhLog.w("clip: copy noticed (accessibility${if (systemPreview) ", clipboard preview" else ""})")
        try {
            startActivity(Intent(this, ClipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("auto", true))
        } catch (e: Exception) {
            EhLog.w("clip: could not open the send screen: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit
}
