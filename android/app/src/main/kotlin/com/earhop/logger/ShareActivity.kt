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
    private val night get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var dim: android.widget.FrameLayout
    private lateinit var sheet: android.widget.LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (!EhState.running) { toast("Start EarHop first"); finish(); return }
        val self = getSharedPreferences("earhop", MODE_PRIVATE).getString("name", "phone") ?: "phone"
        val devices = (EhState.peers.keys + NameBook.all).distinct().filter { it != self }.sorted()
        val chosen = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("send:")?.takeIf { it in devices }
        if (chosen != null) { go(listOf(chosen)); return }
        val now = System.currentTimeMillis()
        fun online(d: String) = EhState.peers[d]?.let { now - it.seenAt < 60_000 } == true
        val shown = devices.filter { online(it) || it in EhState.peers.keys }
        if (shown.none(::online)) { toast("No other device in reach"); finish(); return }
        showSheet(shown, ::online)
    }

    /** A bottom sheet: what is being sent, a round tile per device (and all), springy presses, tap outside to cancel. */
    private fun showSheet(devs: List<String>, online: (String) -> Boolean) {
        val bg = if (night) 0xFF1C1C1F.toInt() else 0xFFF7F7FA.toInt()
        val fg = if (night) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
        val sub = if (night) 0xFF8E8E98.toInt() else 0xFF6C6C76.toInt()
        val tile = if (night) 0xFF2C2C30.toInt() else 0xFFE6E6EC.toInt()
        dim = android.widget.FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; setOnClickListener { close() } }
        sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg); cornerRadii = floatArrayOf(dp(30).toFloat(), dp(30).toFloat(), dp(30).toFloat(), dp(30).toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true
        }
        sheet.addView(android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x40808080); cornerRadius = dp(3).toFloat() }
        }, android.widget.LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        fun label(t: String, size: Float, c: Int, bold: Boolean = false) = android.widget.TextView(this).apply {
            text = t; textSize = size; setTextColor(c); if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        sheet.addView(label(if (isLink()) "Open on" else "Send to", 22f, fg, true))
        sheet.addView(label(what(), 14f, sub).apply { maxLines = 2; setPadding(0, dp(4), 0, dp(18)) })

        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val scroller = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        val reach = devs.filter(online)
        fun addTile(name: String, icon: Int, on: Boolean, pick: () -> Unit) {
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), 0, dp(6), 0); alpha = if (on) 1f else 0.4f
            }
            val circle = android.widget.ImageView(this).apply {
                setImageResource(icon); setColorFilter(fg); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(20), dp(20), dp(20), dp(20))
                // Online on the outline (green), the glow of the touch on top.
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x55FFFFFF),
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(tile)
                        if (on) setStroke(dp(2), 0xFF30D158.toInt())
                    }, null)
                contentDescription = "$name${if (on) "" else ", not in reach"}"
                isEnabled = on
                setOnClickListener { Motion.jelly(this); postDelayed({ pick() }, 160) }
            }
            Motion.springy(circle, 0.88f)
            col.addView(circle, android.widget.LinearLayout.LayoutParams(dp(72), dp(72)))
            col.addView(label(name, 13f, fg).apply { gravity = android.view.Gravity.CENTER; maxLines = 1; setPadding(0, dp(8), 0, 0) },
                android.widget.LinearLayout.LayoutParams(dp(84), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(col)
        }
        if (reach.size > 1) addTile("All devices", R.drawable.ic_dev_all, true) { dismissThen { go(reach) } }
        for (d in devs) addTile(ClipSync.pretty(d), iconFor(d), online(d)) { dismissThen { go(listOf(d)) } }
        sheet.addView(scroller)

        sheet.addView(android.widget.TextView(this).apply {
            text = "Cancel"; textSize = 16f; setTextColor(fg); gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40808080),
                android.graphics.drawable.GradientDrawable().apply { setColor(tile); cornerRadius = dp(26).toFloat() }, null)
            setOnClickListener { close() }
            Motion.springy(this)
        }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(22) })

        // On a wide screen the sheet stays a comfortable width, centred.
        val w = minOf(resources.displayMetrics.widthPixels, dp(560))
        dim.addView(sheet, android.widget.FrameLayout.LayoutParams(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        setContentView(dim)
        @Suppress("DEPRECATION")
        dim.setOnApplyWindowInsetsListener { _, ins -> sheet.setPadding(dp(22), dp(10), dp(22), dp(26) + ins.systemWindowInsetBottom); ins }
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            dim.animate().alpha(1f).setDuration(180).start()
            sheet.animate().translationY(0f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
        }
    }

    private fun iconFor(d: String) = when {
        d.contains("tab") || d.contains("pad") -> R.drawable.ic_dev_tablet
        d.contains("mac") || d.contains("pc") || d.contains("book") || d.contains("laptop") -> R.drawable.ic_dev_laptop
        else -> R.drawable.ic_dev_phone
    }

    @Suppress("DEPRECATION")
    private fun what(): String {
        if (isLink()) return text() ?: ""
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (uris.isEmpty()) return text()?.let { "Text: " + it.take(80) } ?: ""
        if (uris.size > 1) return "${uris.size} items, over Wi-Fi"
        val name = try {
            contentResolver.query(uris[0], arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
        return (name ?: if (intent.type?.startsWith("image/") == true) "A photo" else "A file") + ", over Wi-Fi"
    }

    private fun dismissThen(next: () -> Unit) {
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(220).start()
        dim.animate().alpha(0f).setDuration(220).withEndAction { next() }.start()
    }

    private fun close() = dismissThen { finish() }

    @Deprecated("Back closes the sheet")
    override fun onBackPressed() { if (::sheet.isInitialized) close() else finish() }

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
