package com.earhop.logger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Share, EarHop. Files and photos go to the chosen devices over Wi-Fi (any size); a web link opens on the chosen
 * device; other text goes to the clipboards. A direct share target ("Send to Mac") picks the device; otherwise EarHop
 * asks which device.
 */
class ShareActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (!EhState.running) { toast("Start EarHop first"); finish(); return }
        val self = getSharedPreferences("earhop", MODE_PRIVATE).getString("name", "phone") ?: "phone"
        val devices = (EhState.peers.keys + NameBook.all).distinct().filter { it != self }.sorted()
        val chosen = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("send:")?.takeIf { it in devices }
        if (chosen != null) { go(listOf(chosen)); return }
        val online = devices.filter { d -> EhState.peers[d]?.let { System.currentTimeMillis() - it.seenAt < 60_000 } == true }
        if (online.isEmpty()) { toast("No other device in reach"); finish(); return }
        val labels = (listOf("All my devices") + online.map { ClipSync.pretty(it) }).toTypedArray()
        AlertDialog.Builder(this).setTitle(if (isLink()) "Open on" else "Send to")
            .setItems(labels) { _, i -> go(if (i == 0) online else listOf(online[i - 1])) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun text() = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
    private fun isLink(): Boolean = intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true &&
        text()?.let { (it.startsWith("http://") || it.startsWith("https://")) && it.none { c -> c.isWhitespace() } } == true

    @Suppress("DEPRECATION")
    private fun go(devs: List<String>) {
        val app = applicationContext
        when {
            isLink() -> {
                val ok = devs.count { ClipSync.sendLink(app, text()!!, it) }
                toast(if (ok > 0) "Opening on ${devs.joinToString { ClipSync.pretty(it) }}" else "Not in reach")
            }
            intent.action == Intent.ACTION_SEND && intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) == null && text() != null ->
                toast(ClipSync.sendClip(app, android.content.ClipData.newPlainText("Shared", text())))
            else -> {
                val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
                    else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                if (uris.isEmpty()) { toast("Nothing to send"); finish(); return }
                // The stream permission is granted to this screen: read in the background while it stays briefly.
                var sent = 0
                for (u in uris) sent = maxOf(sent, FileShare.send(app, u, devs))
                toast(if (sent > 0) "Sending ${if (uris.size == 1) "it" else "${uris.size} files"} over Wi-Fi" else "No device on Wi-Fi")
            }
        }
        // Give the senders a moment to open the files before the share permission ends with this screen.
        window.decorView.postDelayed({ finish() }, 1500)
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    override fun finish() { super.finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
}

/** Publishes the direct share targets "Send to Mac", "Send to Tablet" ... for the devices EarHop knows. */
object ShareTargets {
    @Volatile private var last = ""

    fun publish(ctx: Context, devices: List<String>) {
        if (Build.VERSION.SDK_INT < 25) return
        val key = devices.joinToString()
        if (key == last) return
        last = key
        try {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            val list = devices.take(4).map { d ->
                ShortcutInfo.Builder(ctx, "send:$d")
                    .setShortLabel("Send to ${ClipSync.pretty(d)}")
                    .setIcon(Icon.createWithResource(ctx, R.drawable.ic_tile_clip))
                    .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .setCategories(setOf("com.earhop.logger.category.SEND"))
                    .apply { if (Build.VERSION.SDK_INT >= 29) setLongLived(true) }
                    .build()
            }
            sm.dynamicShortcuts = list
        } catch (e: Exception) { EhLog.w("share: direct share targets not published: ${e.message}") }
    }
}
