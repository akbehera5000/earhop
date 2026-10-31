package com.earhop.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val night get() = when (getSharedPreferences("earhop", MODE_PRIVATE).getString("theme", "system")) {
        "light" -> false
        "dark" -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    /** "Auto" follows the earbuds: cool blue for AirPods, violet for Galaxy Buds. */
    private val airpodsAccent = 0xFF4D9EFF.toInt()
    private val budsAccent = 0xFF9466FF.toInt()
    private val accents = linkedMapOf("Auto" to 0, "Blue" to 0xFF2F7BFF.toInt(), "Purple" to 0xFF7B5CFF.toInt(), "Green" to 0xFF1FB86B.toInt(),
        "Orange" to 0xFFFF8A1F.toInt(), "Pink" to 0xFFFF4F8B.toInt())
    private val accent get() = when (val a = getSharedPreferences("earhop", MODE_PRIVATE).getString("accent", "Auto")) {
        "Auto", null -> if (Earbuds.isBuds(this)) budsAccent else airpodsAccent
        else -> accents[a]?.takeIf { it != 0 } ?: airpodsAccent
    }
    /** Colours and glass surfaces (Glass.kt), made once per screen build; theme and accent changes recreate the screen. */
    private lateinit var g: Glass
    private val textC get() = g.text
    private val subC get() = g.sub
    private val sepC get() = g.sep
    private val chipC get() = g.fill
    private val green = 0xFF30D158.toInt()
    private val orange = 0xFFFF9F0A.toInt()
    private val red = 0xFFFF453A.toInt()

    // pages, the floating tab bar and the header that appears when the page scrolls
    private val pages = LinkedHashMap<String, LinearLayout>()
    private val navItems = LinkedHashMap<String, Pair<ImageView, TextView>>()
    private lateinit var navRow: LinearLayout
    private lateinit var navIndicator: View
    private lateinit var header: FrameLayout
    private lateinit var navBar: FrameLayout
    // Real glass for the tab bar and the header: the page under them, drawn small and blurred (blurBehind()).
    private lateinit var navBackdrop: ImageView
    private lateinit var headerBackdrop: ImageView
    private var navBitmap: android.graphics.Bitmap? = null
    private var headerBitmap: android.graphics.Bitmap? = null
    private var blurPending = false
    private lateinit var headerTitle: TextView
    private val tabs = listOf("Home" to R.drawable.ic_nav_home, "Buds" to R.drawable.ic_nav_buds, "Clipboard" to R.drawable.ic_tile_clip,
        "Devices" to R.drawable.ic_nav_devices, "Settings" to R.drawable.ic_nav_settings, "Activity" to R.drawable.ic_nav_activity)
    private var currentPage = "Home"
    private lateinit var content: LinearLayout
    /** Keep switches and segmented controls in step with the settings; run on every refresh. */
    private val syncs = ArrayList<() -> Unit>()

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusChip: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var peersBox: LinearLayout
    private var peersSig = "init"
    private lateinit var permsBox: LinearLayout
    private lateinit var permsCard: LinearLayout
    private var permsSig = "init"
    private lateinit var startBtn: Button
    private lateinit var takeBtn: Button
    private lateinit var releaseBtn: Button
    private lateinit var nameField: EditText
    private lateinit var secretField: EditText
    private lateinit var podsField: EditText
    private lateinit var budsField: EditText
    private lateinit var earbudsText: TextView
    /** The earbuds the screen was built for; when automatic detection switches them, the screen is rebuilt. */
    private var shownBuds = false
    private lateinit var logView: TextView
    private lateinit var handoffView: TextView
    private lateinit var earInfo: TextView
    private lateinit var gentleRow: View
    private lateinit var linkText: TextView
    private lateinit var clipText: TextView
    private lateinit var homeClipText: TextView
    private lateinit var clipMethodText: TextView
    private lateinit var clipSetup: LinearLayout
    private var clipSetupSig = "init"
    private lateinit var recentBox: LinearLayout
    private var recentSig = "init"
    private var rootCard: LinearLayout? = null
    private lateinit var rootBtn: Button
    private lateinit var rootUseRow: View
    private lateinit var rootFirstRow: View
    private lateinit var budsCard: LinearLayout
    private var budsSig = ""
    private lateinit var batteryBox: LinearLayout
    private var batterySig = "init"
    private lateinit var rulesBox: LinearLayout
    private lateinit var onboardCard: LinearLayout
    private var onboardSig = ""
    private lateinit var appRulesBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private var historySig = "init"
    private lateinit var healthBox: LinearLayout
    private var healthSig = "init"
    private lateinit var stripBox: LinearLayout
    private var stripSig = "init"
    private lateinit var keepBox: LinearLayout
    private var keepSig = "init"
    private lateinit var selfTestText: TextView
    private lateinit var techText: TextView
    private lateinit var timelineBox: LinearLayout
    /** Whether the "Set up from a computer" part of the helper setup is open. */
    private var macHelpOpen = false
    private var appAccessShown = false
    private lateinit var crashCard: LinearLayout
    private val quickTiles = ArrayList<() -> Unit>()
    // dashboard
    private lateinit var hero: LinearLayout
    private var heroHue: Glass.Hue? = null
    private lateinit var heroBuds: BudsView
    private lateinit var heroUnit: TextView
    private lateinit var heroWhere: TextView
    private lateinit var heroState: TextView
    private lateinit var stageBox: LinearLayout
    private lateinit var stageBar: DotBar
    private lateinit var stageText: TextView
    private lateinit var heroNote: TextView
    private lateinit var chipEar: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipCase: TextView
    private lateinit var chipLink: TextView
    private lateinit var safeBtn: Button
    private lateinit var crashText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private val prefs get() = getSharedPreferences("earhop", MODE_PRIVATE)
    private val haptics get() = prefs.getBoolean("haptics", true)
    private fun tap(v: View, strong: Boolean = false) {
        if (haptics) v.performHapticFeedback(if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        g = Glass(this, night, accent)
        if (!prefs.getBoolean("onboarded", false) && !prefs.getString("secret", "").isNullOrBlank()) prefs.edit().putBoolean("onboarded", true).apply()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
        val frame = FrameLayout(this).apply { background = g.page() }
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(120)) }
        scroll.addView(content)
        frame.addView(scroll)
        for ((name, _) in tabs) {
            val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            p.addView(pageHead(name))
            pages[name] = p
            content.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // The header: invisible at the top of a page, frosted with the page name once the large title scrolls away.
        header = FrameLayout(this).apply { alpha = 0f }
        headerBackdrop = backdrop()
        header.addView(headerBackdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(View(this).apply { background = g.header() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        headerTitle = text("", 17f, textC, true).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        header.addView(headerTitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM))
        frame.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            header.alpha = (y / dp(48).toFloat()).coerceIn(0f, 1f)
            headerTitle.alpha = ((y - dp(28)) / dp(24).toFloat()).coerceIn(0f, 1f)
            blurSoon()
        }
        val nav = buildNav()
        frame.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        @Suppress("DEPRECATION")
        frame.setOnApplyWindowInsetsListener { _, ins ->
            content.setPadding(dp(16), dp(16) + ins.systemWindowInsetTop, dp(16), dp(120) + ins.systemWindowInsetBottom)
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(14) + ins.systemWindowInsetBottom
            header.layoutParams = (header.layoutParams as FrameLayout.LayoutParams).apply { height = dp(44) + ins.systemWindowInsetTop }
            nav.requestLayout()
            ins
        }
        setContentView(frame)
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> blurSoon() }
        root = pages.getValue("Home")
        build()
        handleScan(intent?.data?.toString())
        showPage(prefs.getString("page", "Home") ?: "Home")
    }

    // ---------- building blocks ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(0f, 1.08f)
    }

    /** A section body. Hiding it hides its whole section, title and footer included. */
    private class SectionBody(ctx: Context) : LinearLayout(ctx) {
        override fun setVisibility(v: Int) { super.setVisibility(v); (parent as? View)?.visibility = v }
    }

    private val medium get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * A card on the current page: a graphite panel with its title inside at the top and an optional footer note at the
     * bottom. Returns the card's body. The body's first child is an invisible anchor, so code that keeps "child 0" while
     * rebuilding a card keeps working.
     */
    private fun card(title: String?, footer: String? = null): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(28)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        if (title != null) section.addView(text(title, 15.5f, textC).apply { typeface = medium; setPadding(0, 0, 0, dp(10)) })
        val c = SectionBody(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(View(this).apply { visibility = View.GONE })
        section.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (footer != null) section.addView(text(footer, 12.5f, subC).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        return c
    }

    /** A card whose body opens and closes with a tap on its title row, closed at first. Returns the body. */
    private fun expander(title: String, footer: String? = null): LinearLayout {
        val c = card(null, footer)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 15.5f, textC).apply { typeface = medium }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = text("+", 20f, subC)
        head.addView(arrow)
        c.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        c.addView(body)
        head.setOnClickListener {
            tap(it)
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "−" else "+"
            if (open) refresh()
        }
        head.contentDescription = "$title, tap to open"
        return body
    }

    /** A page's title row: the name centred between two round buttons. Home also gets the running state below it. */
    private fun pageHead(name: String): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(20)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun circle(icon: Int, desc: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon); setColorFilter(textC); background = g.circle()
            setPadding(dp(13), dp(13), dp(13), dp(13)); contentDescription = desc
            setOnClickListener { tap(this); act() }
        }
        fun size() = LinearLayout.LayoutParams(dp(48), dp(48))
        if (name == "Home") row.addView(circle(R.drawable.ic_tile_clip, "Share clipboard") { startActivity(Intent(this, ClipActivity::class.java)) }, size())
        else row.addView(circle(R.drawable.ic_back, "Back to Home") { showPage("Home") }, size())
        row.addView(text(if (name == "Home") "EarHop" else name, 26f, textC).apply {
            gravity = Gravity.CENTER; typeface = medium; letterSpacing = -0.01f; maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (name == "Home") row.addView(circle(R.drawable.ic_menu, "Settings") { showPage("Settings") }, size())
        else row.addView(View(this), size())
        col.addView(row)
        if (name == "Home") {
            statusChip = text("Stopped", 12.5f, textC).apply {
                typeface = medium
                setPadding(dp(14), dp(6), dp(14), dp(6))
                // EarHop should keep running: the chip starts it, but stopping is only in Settings, About.
                setOnClickListener { tap(this); if (EhState.running) showPage("Settings") else toggle() }
                contentDescription = "EarHop service"
            }
            col.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10)
            })
        }
        return col
    }

    // ---------- pages and tab bar ----------

    /** The floating glass tab bar, with a highlight that slides to the open page. */
    private fun buildNav(): FrameLayout {
        val bar = FrameLayout(this).apply {
            elevation = dp(12).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(30).toFloat()) }
            }
            clipToOutline = true
        }
        navBar = bar
        navBackdrop = backdrop()
        // The glass layers take the row's size (set below); match_parent would make the bar fill the screen.
        val tintLayer = View(this).apply { background = g.bar() }
        bar.addView(navBackdrop, FrameLayout.LayoutParams(0, 0))
        bar.addView(tintLayer, FrameLayout.LayoutParams(0, 0))
        navIndicator = View(this).apply { background = g.indicator() }
        bar.addView(navIndicator, FrameLayout.LayoutParams(0, 0).apply { leftMargin = dp(6); topMargin = dp(6) })
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, icon) in tabs) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(7), dp(6), dp(7), dp(6))
                contentDescription = name
                setOnClickListener { if (name != currentPage) { tap(this); showPage(name) } else scroll.smoothScrollTo(0, 0) }
            }
            val img = ImageView(this).apply { setImageResource(icon) }
            item.addView(img, LinearLayout.LayoutParams(dp(23), dp(23)))
            val lab = text(name, 10.5f, subC).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0); maxLines = 1 }
            item.addView(lab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            item.minimumWidth = dp(56)
            navItems[name] = Pair(img, lab)
            navRow.addView(item)
        }
        bar.addView(navRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        navRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = navRow.width + dp(12); val h = navRow.height + dp(12)
            for (v in listOf(navBackdrop, tintLayer)) if (v.layoutParams.width != w || v.layoutParams.height != h) {
                v.layoutParams = v.layoutParams.apply { width = w; height = h }
            }
            moveIndicator(false)
        }
        return bar
    }

    /** An image that shows the page behind a glass bar, blurred (Android 12 and later blur it; before, it is drawn tiny and scaled up). */
    private fun backdrop() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
            dp(16).toFloat(), dp(16).toFloat(), android.graphics.Shader.TileMode.CLAMP))
    }

    /** Redraws the blurred backdrops once in the next frame (after scrolling, page changes and each refresh). */
    private fun blurSoon() {
        if (blurPending) return
        blurPending = true
        scroll.postOnAnimation { blurPending = false; blurBehind() }
    }

    private fun blurBehind() {
        try {
            navBitmap = snapshot(navBar, navBackdrop, navBitmap)
            if (header.alpha > 0f) headerBitmap = snapshot(header, headerBackdrop, headerBitmap)
        } catch (e: Exception) { }
    }

    /** Draws the part of the page under [target] (a direct child of the screen frame) into a small bitmap shown by [iv]. */
    private fun snapshot(target: View, iv: ImageView, old: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (target.width == 0 || target.height == 0) return old
        val s = if (Build.VERSION.SDK_INT >= 31) 0.25f else 0.1f
        val w = Math.max(1, (target.width * s).toInt())
        val h = Math.max(1, (target.height * s).toInt())
        val bmp = if (old != null && old.width == w && old.height == h) old else android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(g.bg)
        c.scale(s, s)
        c.translate(-(target.left + target.translationX), -(target.top + target.translationY) - scroll.scrollY)
        content.draw(c)
        if (iv.drawable == null || bmp !== old) iv.setImageBitmap(bmp) else iv.invalidate()
        return bmp
    }

    private fun moveIndicator(animate: Boolean) {
        val item = navItems[currentPage]?.first?.parent as? View ?: return
        if (item.width == 0) return
        val lp = navIndicator.layoutParams
        if (lp.width != item.width || lp.height != item.height) {
            lp.width = item.width; lp.height = item.height; navIndicator.layoutParams = lp
        }
        val x = item.left.toFloat()  // the indicator's margin matches the row's, so the row's x is the indicator's
        if (animate) navIndicator.animate().translationX(x).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        else navIndicator.translationX = x
    }

    private fun showPage(name: String) {
        val target = if (pages.containsKey(name)) name else "Home"
        currentPage = target
        pages.forEach { (n, p) -> p.visibility = if (n == target) View.VISIBLE else View.GONE }
        navItems.forEach { (n, v) ->
            val on = n == target
            v.first.setColorFilter(if (on) textC else subC)
            v.second.setTextColor(if (on) textC else subC)
            v.second.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        moveIndicator(true)
        headerTitle.text = if (target == "Home") "EarHop" else target
        scroll.scrollTo(0, 0)
        header.alpha = 0f
        pages[target]?.alpha = 0f
        pages[target]?.animate()?.alpha(1f)?.setDuration(180)?.start()
        prefs.edit().putString("page", target).apply()
    }

    // ---------- controls ----------

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(sepC) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10); bottomMargin = dp(10) })
    }

    /** A switch row: title, optional detail, switch on the right. Returns the row. */
    private fun switchRow(parent: LinearLayout, title: String, sub: String?, get: () -> Boolean, track: Boolean = true, set: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, textC))
        if (sub != null) texts.addView(text(sub, 12.5f, subC).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        val sw = android.widget.Switch(this).apply {
            isChecked = get()
            // Neutral switch; the state shows on the track's outline (green on, red-grey off).
            thumbDrawable = g.switchThumb(); trackDrawable = g.switchTrack()
            thumbTintList = null; trackTintList = null
            showText = false
            contentDescription = title
            setOnClickListener { tap(this); set(isChecked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle(); tap(sw); set(sw.isChecked) }
        parent.addView(row)
        // Rows in parts that are rebuilt on every change (rules, Buds controls) do not need syncing.
        if (track) syncs += { val v = get(); if (sw.isChecked != v) sw.isChecked = v }
        return row
    }

    private fun pauseUntil(key: String, choice: Int) {
        val until = when (choice) {
            0 -> System.currentTimeMillis() + 15 * 60_000L
            1 -> System.currentTimeMillis() + 3_600_000L
            2 -> java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 7)
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
            else -> 0L
        }
        prefs.edit().putLong(key, until).apply()
        refresh()
    }

    private fun shortTime(t: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

    /** Pause buttons (15 min, 1 hour, until tomorrow 7:00) for a pref holding an end time; while paused: the end and Resume. */
    private fun pauseRow(parent: LinearLayout, key: String) {
        parent.addView(text("Pause", 13f, subC).apply { setPadding(dp(2), dp(6), 0, dp(4)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        parent.addView(box)
        var sig = ""
        val render = {
            val until = prefs.getLong(key, 0)
            val on = until > System.currentTimeMillis()
            val now = "$on$until"
            if (now != sig) {
                sig = now
                box.removeAllViews()
                if (on) {
                    box.addView(text("Paused until ${shortTime(until)}", 15f, orange, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    box.addView(button("Resume", true) { pauseUntil(key, -1) })
                } else {
                    listOf("15 min", "1 hour", "Tomorrow").forEachIndexed { i, label ->
                        box.addView(button(label, false) { pauseUntil(key, i) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < 2) rightMargin = dp(6) })
                    }
                }
            }
        }
        syncs += render
        render()
    }

    /** A pop-up to pause a feature, used by the Home tiles (long press). Blurs the screen behind it on Android 12 and later. */
    private fun pauseDialog(title: String, key: String) {
        val paused = prefs.getLong(key, 0) > System.currentTimeMillis()
        val items = if (paused) arrayOf("Resume now") else arrayOf("Pause for 15 minutes", "Pause for 1 hour", "Pause until tomorrow 7:00")
        val d = AlertDialog.Builder(this).setTitle(title).setItems(items) { _, i -> pauseUntil(key, if (paused) -1 else i) }
            .setNegativeButton("Cancel", null).create()
        blurBehind(d)
        d.show()
    }

    private fun blurBehind(d: android.app.Dialog) {
        if (Build.VERSION.SDK_INT < 31) return
        d.window?.let {
            it.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            it.attributes = it.attributes.apply { blurBehindRadius = dp(24) }
        }
    }

    /** A segmented control. Returns the control. */
    private fun segmented(parent: LinearLayout, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = g.track()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEach { (value, label) ->
            text(label, 14f, textC).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(4), dp(9), dp(4), dp(9))
                setOnClickListener { if (value != get()) { tap(this); set(value) }; paintSeg(bar, options, get()) }
                contentDescription = label
            }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) })
        syncs += { paintSeg(bar, options, get()) }
        paintSeg(bar, options, get())
        return bar
    }

    private fun paintSeg(bar: LinearLayout, options: List<Pair<String, String>>, value: String) {
        for (i in options.indices) {
            val cell = bar.getChildAt(i) as? TextView ?: continue
            val on = options[i].first == value
            cell.background = if (on) g.selected() else null
            cell.setTextColor(if (on) g.onPrimary else subC)
            cell.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
            cell.contentDescription = options[i].second + if (on) ", selected" else ""
        }
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15.5f
        setTextColor(if (filled) g.onPrimary else textC)
        background = if (filled) g.filled() else g.tinted()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { tap(this, true); onClick() }
    }

    /** A row of equally wide buttons. */
    private fun buttons(parent: LinearLayout, vararg b: Button, top: Int = 10) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b.forEachIndexed { i, x -> r.addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
        parent.addView(r, lp(top))
    }

    private fun field(parent: LinearLayout, label: String, value: String, pw: Boolean = false): EditText {
        parent.addView(text(label, 13f, subC).apply { setPadding(dp(2), dp(8), 0, dp(4)) })
        val e = EditText(this).apply {
            setText(value)
            setTextColor(textC)
            setHintTextColor(subC)
            textSize = 16f
            background = g.input()
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setSingleLine()
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    /** A status line with a coloured dot in front. */
    private fun status(s: String, color: Int, size: Float = 14f, plain: Boolean = false) = text(s, size, if (color == subC && !plain) subC else textC).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(g.dot(color), null, null, null)
        compoundDrawablePadding = dp(8)
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A command or other technical text in a small monospace box, selectable. */
    private fun codeBox(s: String) = text(s, 12f, textC).apply {
        typeface = Typeface.MONOSPACE
        background = g.input()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextIsSelectable(true)
        breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    // ---------- screen ----------

    private fun build() {
        buildHome()
        buildBudsPage()
        buildClipboardPage()
        buildDevicesPage()
        buildSettingsPage()
        buildActivityPage()
        root = pages.getValue("Home")
    }

    private fun buildHome() {
        root = pages.getValue("Home")

        onboardCard = card("Set up EarHop")
        onboardCard.visibility = View.GONE

        // crash from the previous run, if any; the report itself is only copied, never shown
        crashCard = card("EarHop closed unexpectedly last time")
        crashText = text("A short report was saved on this device. Copy it to report the problem.", 13f, subC)
        crashCard.addView(crashText)
        buttons(crashCard, button("Copy report", false) { copy("EarHop crash", prefs.getString("last_crash", "") ?: "") },
            button("Dismiss", false) { prefs.edit().remove("last_crash").apply(); crashCard.visibility = View.GONE })
        crashCard.visibility = View.GONE

        errorCard = card(null)
        errorText = text("", 14f, red)
        errorCard.addView(errorText)
        errorCard.visibility = View.GONE

        // Problems that stop EarHop from working, each with its fix. Hidden when all is well.
        val health = card("Needs attention")
        healthBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        health.addView(healthBox)
        health.visibility = View.GONE

        buildDashboard()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        takeBtn = button("Take ${Earbuds.short(this)}", true) { quick(true) }.apply { minHeight = dp(56); textSize = 16.5f }
        releaseBtn = button("Release", false) { quick(false) }.apply { minHeight = dp(56); textSize = 16.5f }
        actions.addView(takeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { rightMargin = dp(10) })
        actions.addView(releaseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        // Large toggles in a grid: switching and clipboard, and noise control with Galaxy Buds. Long press pauses.
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickTile(row1, Glass.Hue.INDIGO, "Switching", "autoPausedUntil", { prefs.getBoolean("auto", true) }) {
            prefs.edit().putBoolean("auto", !prefs.getBoolean("auto", true)).apply()
            try { Tiles.refreshAll(applicationContext) } catch (e: Exception) { }
        }
        quickTile(row1, Glass.Hue.EMBER, "Clipboard", "clipPausedUntil", { prefs.getBoolean("clipSync", true) }) {
            setClipSync(!prefs.getBoolean("clipSync", true))
        }
        root.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        if (Earbuds.isBuds(this)) {
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            quickTile(row2, Glass.Hue.VIOLET, "Noise control", null, {
                val n = EhState.budsClient?.takeIf { it.connected }?.snapshot()?.noise
                n != null && n != "off"
            }, state = {
                when (EhState.budsClient?.takeIf { it.connected }?.snapshot()?.noise) {
                    null -> "Buds not on this device"
                    "anc" -> "Noise cancelling"; "ambient" -> "Ambient sound"; else -> "Off"
                }
            }) {
                val c = EhState.budsClient?.takeIf { it.connected }
                if (c == null) Toast.makeText(this, "Noise control works while the Galaxy Buds are connected to this device", Toast.LENGTH_SHORT).show()
                else c.setNoise(when (c.snapshot().noise) { "off" -> "anc"; "anc" -> "ambient"; else -> "off" })
            }
            root.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        }

        val strip = card("Your devices")
        stripBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(stripBox)

        val homeClip = card("Clipboard")
        homeClipText = text("", 14f, textC)
        homeClip.addView(homeClipText)
        buttons(homeClip, button("Share clipboard", true) { startActivity(Intent(this, ClipActivity::class.java)) },
            button("Settings", false) { showPage("Clipboard") }, top = 14)

        val histCard = card("Recent switches")
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histCard.addView(historyBox)
    }

    /**
     * One large Home toggle. Tap switches it; long press pauses it ([pauseKey]) for a while. When on it glows in [hue]
     * with a green outline; when off it is graphite with a red-grey outline.
     */
    private fun quickTile(parent: LinearLayout, hue: Glass.Hue, label: String, pauseKey: String?, isOn: () -> Boolean,
                          state: (() -> String)? = null, act: () -> Unit) {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(14))
            minimumHeight = dp(132)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = text(label, 15.5f, textC).apply { typeface = medium; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val knob = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        top.addView(knob, LinearLayout.LayoutParams(dp(40), dp(24)))
        t.addView(top)
        t.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val st = text("", 13f, subC).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(18), 0, 0) }
        t.addView(st)
        val bar = DotBar(this, textC, sepC)
        t.addView(bar, lp(8))
        t.setOnClickListener { tap(it, true); act(); refresh() }
        if (pauseKey != null) t.setOnLongClickListener { tap(it, true); pauseDialog("Pause ${label.lowercase()}", pauseKey); true }
        parent.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (parent.childCount > 0) leftMargin = dp(14) })
        var sig = ""
        quickTiles += {
            val on = isOn()
            val until = if (pauseKey != null) prefs.getLong(pauseKey, 0) else 0L
            val paused = on && until > System.currentTimeMillis()
            val s = state?.invoke() ?: when { !on -> "Off"; paused -> "Paused until ${shortTime(until)}"; else -> "On" }
            val now = "$on$paused$s"
            if (now != sig) {
                sig = now
                val lit = on && !paused
                t.background = if (lit) g.aurora(28, hue, flip = hue == Glass.Hue.EMBER, outline = g.on) else g.outlined(28, g.off)
                knob.background = g.knob(lit, lit)
                name.setTextColor(if (lit) Color.WHITE else textC)
                st.text = s
                st.setTextColor(if (lit) 0xD9FFFFFF.toInt() else if (paused) orange else subC)
                bar.setColors(if (lit) Color.WHITE else textC, if (lit) 0x40FFFFFF else sepC)
                bar.fraction = if (lit) 1f else 0f
                t.contentDescription = "$label: $s${if (pauseKey != null) ". Long press to pause." else ""}"
            }
        }
    }

    private fun buildBudsPage() {
        root = pages.getValue("Buds")
        shownBuds = Earbuds.isBuds(this)
        val eb = card("Earbuds", "Automatic uses the pair connected to this device, or the pair of the device that has them.")
        segmented(eb, listOf("auto" to "Automatic", "airpods" to "AirPods Pro", "buds" to "Galaxy Buds"),
            { prefs.getString("earbudsMode", "auto") ?: "auto" }) { v ->
            prefs.edit().putString("earbudsMode", v).apply()
            // A pair picked by hand applies at once; the service follows within 2 s, the screen rebuilds when it changed.
            if (v != "auto") { Earbuds.setBuds(this, v == "buds"); if (shownBuds != (v == "buds")) recreate() }
            refresh()
        }
        earbudsText = text("", 14f, textC).apply { setPadding(dp(2), dp(10), 0, 0) }
        eb.addView(earbudsText)

        val bat = card("Battery")
        batteryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bat.addView(batteryBox)

        budsCard = card("Galaxy Buds controls")
        budsCard.visibility = if (Earbuds.isBuds(this)) View.VISIBLE else View.GONE

        val earCard = card("In-ear detection")
        earInfo = text("", 13f, subC)
        earCard.addView(earInfo)
        divider(earCard)
        switchRow(earCard, "Pause when one earbud is out", "Otherwise only when both are out. Putting it back resumes.",
            { prefs.getBoolean("pauseOnOne", true) }) { prefs.edit().putBoolean("pauseOnOne", it).apply() }
        switchRow(earCard, "Use the speaker when earbuds are out", "Audio you start with the earbuds out of your ears plays on this device's speaker, and moves back to the earbuds when you put one in. Keeps the earbuds connected while they are out.",
            { prefs.getBoolean("speakerFallback", true) }) { prefs.edit().putBoolean("speakerFallback", it).apply() }
        gentleRow = switchRow(earCard, "Gentle scanning while playing", "Less crackle on some phones, slower ear detection",
            { prefs.getBoolean("gentle", false) }) { prefs.edit().putBoolean("gentle", it).apply() }
        earCard.addView(button("Test pause now", false) { sendAction(EarService.ACTION_TEST_PAUSE) }, lp(10))
        earCard.addView(button("Test speaker (5 seconds)", false) { sendAction(EarService.ACTION_TEST_SPEAKER) }, lp(8))

        val names = card("Advanced: earbud names", "EarHop finds each pair by a part of its Bluetooth name. Changes apply within a few seconds.")
        podsField = field(names, "AirPods name contains", prefs.getString("filter", "AirPods") ?: "AirPods")
        budsField = field(names, "Galaxy Buds name contains", prefs.getString("filterBuds", "Buds2 Pro") ?: "Buds2 Pro")
        names.addView(button("Save names", false) {
            val pn = podsField.text.toString().trim(); val bn = budsField.text.toString().trim()
            if (pn.isEmpty() || bn.isEmpty()) { Toast.makeText(this, "Names cannot be empty", Toast.LENGTH_SHORT).show(); return@button }
            prefs.edit().putString("filter", pn).putString("filterBuds", bn).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, lp(12))
    }

    private fun buildClipboardPage() {
        root = pages.getValue("Clipboard")
        val clip = card("Clipboard sync")
        switchRow(clip, "Sync clipboard", "Copy here, paste on your other devices, and the other way round",
            { prefs.getBoolean("clipSync", true) }) { setClipSync(it) }
        divider(clip)
        switchRow(clip, "Receive clips", "Put copies from your other devices on this clipboard",
            { prefs.getBoolean("clipReceive", true) }) { prefs.edit().putBoolean("clipReceive", it).apply() }
        divider(clip)
        switchRow(clip, "Include images", "Images always go over Wi-Fi",
            { prefs.getBoolean("clipImages", true) }) { prefs.edit().putBoolean("clipImages", it).apply() }
        clipText = text("", 13f, subC).apply { setPadding(dp(2), dp(10), 0, 0) }
        clip.addView(clipText)
        clip.addView(button("Share clipboard now", true) { startActivity(Intent(this, ClipActivity::class.java)) }, lp(12))

        // Sending copies from here: one method at a time, each with its own state and setup steps.
        val auto = card("Send copies automatically", "Android does not let apps read the clipboard in the background. " +
            "Pick how EarHop notices a copy here. Copies from your other devices arrive either way.")
        switchRow(auto, "Send copies automatically", null, { prefs.getBoolean("clipAutoRoot", true) }) { on ->
            prefs.edit().putBoolean("clipAutoRoot", on).apply()
            if (!on) ClipSync.stopRootWatch() else if (EhState.running) { ClipSync.tryStartAuto(applicationContext); ClipSync.watchHelper(applicationContext) }
            clipSetupSig = ""; refresh()
        }
        val methods = listOf("root" to "Root", "helper" to "Helper", "accessibility" to "Accessibility")
        segmented(auto, methods, { ClipSync.autoMethod(this) }) { m ->
            if (m == "root" && !RootShell.hasSu()) Toast.makeText(this, "This device is not rooted. Use Helper or Accessibility.", Toast.LENGTH_LONG).show()
            else if (m == "root" && !prefs.getBoolean("rootGranted", false)) askRoot { ok -> if (ok) ClipSync.setAutoMethod(this, "root") }
            else ClipSync.setAutoMethod(this, m)
            clipSetupSig = ""; handler.postDelayed({ refresh() }, 300)
        }.also { bar -> syncs += { bar.visibility = if (prefs.getBoolean("clipAutoRoot", true)) View.VISIBLE else View.GONE } }
        clipSetup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        auto.addView(clipSetup)

        val recent = card("Recent clips", "Kept only in memory on this device, never saved or logged, and cleared when EarHop stops. Tap one to copy it again.")
        switchRow(recent, "Keep recent clips", null, { ClipSync.keepRecent(this) }) { on ->
            prefs.edit().putBoolean("clipHistory", on).apply(); if (!on) ClipSync.recent.clear(); recentSig = ""; refresh()
        }
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recent.addView(recentBox)

        val sendTo = card("Send to")
        val self = prefs.getString("name", "phone") ?: "phone"
        val others = listOf("mac", "phone", "tab").filter { it != self }
        others.forEachIndexed { i, dev ->
            if (i > 0) divider(sendTo)
            switchRow(sendTo, pretty(dev), null, { dev !in ClipSync.excluded(this) }) { on ->
                val now = ClipSync.excluded(this).toMutableSet()
                if (on) now.remove(dev) else now.add(dev)
                prefs.edit().putStringSet("clipExclude", now).apply()
            }
        }

        val method = card("Connection for clips")
        segmented(method, clipMethods, { ClipSync.method(this) }) { v -> prefs.edit().putString("clipTransport", v).apply(); refresh() }
        clipMethodText = text("", 12.5f, subC).apply { setPadding(dp(2), dp(6), 0, 0) }
        method.addView(clipMethodText)

        val clipPause = card("Pause", "Stops sending and receiving for a while. It ends by itself.")
        pauseRow(clipPause, "clipPausedUntil")
    }

    private fun buildDevicesPage() {
        root = pages.getValue("Devices")
        val peers = card("Other devices")
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peers.addView(peersBox)

        val conn = card("Earbud switching link", "How this device tells your other devices where the earbuds are. Both is the most reliable.")
        segmented(conn, listOf("ble" to "Bluetooth", "both" to "Both", "wifi" to "Wi-Fi"), { prefs.getString("transport", "both") ?: "both" }) { v ->
            prefs.edit().putString("transport", v).apply()
        }
        linkText = text("", 13f, subC).apply { setPadding(dp(2), dp(8), 0, 0) }
        conn.addView(linkText)

        val setup = card("This device", "Each device needs its own name: mac, phone or tab. The secret comes from the Mac (Settings, Network).")
        val pairedSecret = intent?.data?.takeIf { it.scheme == "earhop" }?.getQueryParameter("secret")
        // Tablets default to "tab" so a phone and a tablet never share the default name.
        val defaultName = if (resources.configuration.smallestScreenWidthDp >= 600) "tab" else "phone"
        nameField = field(setup, "Name", prefs.getString("name", defaultName) ?: defaultName)
        buttons(setup, button("phone", false) { nameField.setText("phone") }, button("tab", false) { nameField.setText("tab") }, top = 8)
        secretField = field(setup, "Shared secret", pairedSecret ?: prefs.getString("secret", "") ?: "", true)
        buttons(setup, button("Scan QR code", false) { scanQr() }, button("Paste", false) { paste() }, top = 8)
        startBtn = button("Start", true) { saveDevice() }
        setup.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        val tiles = card("Quick settings and widget", "Take, Release, Auto switching, Send clipboard and Noise control tiles, and the EarHop home screen widget.")
        buttons(tiles, button("Add tiles", false) { addTiles() }, button("Add widget", false) { addWidget() }, top = 0)
    }

    private fun buildSettingsPage() {
        root = pages.getValue("Settings")
        val ho = card("Automatic switching", "After 3 failed switches in a row (for example the earbuds were in their case), switching " +
            "pauses for 2 minutes or until a device has the earbuds again. Take and Release always work.")
        switchRow(ho, "Switch automatically", "Take the earbuds when this device starts playing",
            { prefs.getBoolean("auto", true) }) { prefs.edit().putBoolean("auto", it).apply(); try { Tiles.refreshAll(applicationContext) } catch (e: Exception) { } }
        divider(ho)
        pauseRow(ho, "autoPausedUntil")

        val rulesCard = card("Switching rules")
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rulesCard.addView(rulesBox)
        rebuildRules()

        val appCard = card("App rules")
        appRulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appCard.addView(appRulesBox)
        rebuildAppRules()

        val keep = card("Keep EarHop running", "EarHop starts with the phone and whenever you open it. Some phones still stop background " +
            "apps: give EarHop (and Shizuku, if you use it) no battery limits and allow auto-start.")
        keepBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keep.addView(keepBox)

        if (RootShell.hasSu()) {
            val rc = card("Root", "Used to connect the earbuds when the normal way is blocked, and for sending copies automatically (Clipboard).")
            rootCard = rc
            rootBtn = button("", false) {
                if (!prefs.getBoolean("rootGranted", false)) askRoot { }
                else { prefs.edit().putBoolean("rootGranted", false).putBoolean("useRoot", false).putBoolean("rootFirst", false).apply(); refresh() }
            }
            rc.addView(rootBtn)
            rootUseRow = switchRow(rc, "Use root when blocked", "Only if the normal Bluetooth connect is refused",
                { prefs.getBoolean("useRoot", true) }) { prefs.edit().putBoolean("useRoot", it).apply() }
            rootFirstRow = switchRow(rc, "Always use root first", "Most reliable on a rooted tablet",
                { prefs.getBoolean("rootFirst", false) }) { prefs.edit().putBoolean("rootFirst", it).apply() }
        }

        permsCard = card("Permissions")
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permsCard.addView(permsBox)

        val look = card("Appearance")
        look.addView(text("Theme", 13f, subC).apply { setPadding(dp(2), 0, 0, dp(4)) })
        segmented(look, listOf("system" to "Automatic", "light" to "Light", "dark" to "Dark"), { prefs.getString("theme", "system") ?: "system" }) { v ->
            if (v != prefs.getString("theme", "system")) { prefs.edit().putString("theme", v).apply(); recreate() }
        }
        divider(look)
        switchRow(look, "Haptic feedback", "A light tap when you press buttons and switches", { prefs.getBoolean("haptics", true) }) {
            prefs.edit().putBoolean("haptics", it).apply()
        }

        val backup = card("Backup", "The file holds your pairing secret. Keep it private, and delete it once you have restored it.")
        backup.addView(text("Save this device's EarHop settings to a file, or restore them after reinstalling or on a new device.", 13f, subC))
        buttons(backup, button("Export", false) { exportSettings() }, button("Import", false) { importSettings() }, top = 12)

        val about = card("About")
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        about.addView(text("EarHop $version", 17f, textC).apply { typeface = medium })
        about.addView(text("${pretty(prefs.getString("name", "phone") ?: "phone")}, Android ${Build.VERSION.RELEASE}", 13f, subC).apply { setPadding(0, dp(2), 0, 0) })
        about.addView(button("Stop EarHop", false) { if (EhState.running) toggle() }.also { b ->
            syncs += { b.text = if (EhState.running) "Stop EarHop" else "EarHop is stopped"; b.isEnabled = EhState.running; b.alpha = if (EhState.running) 1f else 0.5f }
        }, lp(14))
        about.addView(text("Stopping lasts until you open EarHop again.", 12.5f, subC).apply { setPadding(dp(2), dp(8), 0, 0) })

        // For bug reports: what the service sees, the protocol self test and a summary to copy.
        val adv = expander("Advanced")
        techText = text("", 12f, subC).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        adv.addView(techText)
        selfTestText = text("", 13f, subC).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        adv.addView(selfTestText)
        buttons(adv, button("Run self test", false) { runSelfTest() }, button("Copy diagnostics", false) { copy("EarHop diagnostics", diagnostics()) }, top = 12)
    }

    private fun buildActivityPage() {
        root = pages.getValue("Activity")
        val tl = card("Timeline")
        timelineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tl.addView(timelineBox)

        // The raw log stays available for bug reports, closed by default.
        val log = expander("Technical log", "Never contains clipboard contents or your pairing secret.")
        log.addView(text("Recent decisions", 13f, subC).apply { setPadding(0, 0, 0, dp(4)) })
        handoffView = text("", 12f, subC).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, 0, dp(12)) }
        log.addView(handoffView)
        segmented(log, listOf("all" to "All", "switch" to "Switching", "clip" to "Clipboard", "problems" to "Problems"),
            { prefs.getString("logFilter", "all") ?: "all" }) { v -> prefs.edit().putString("logFilter", v).apply(); refresh() }
        logView = text("", 11f, subC).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
        log.addView(logView)
        buttons(log, button("Copy log", false) { copy("EarHop log", EhLog.dumpAll()) }, button("Share log", false) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EhLog.dumpAll())
            try { startActivity(Intent.createChooser(i, "Share the EarHop log")) } catch (e: Exception) { }
        })
    }

    // ---------- dashboard ----------

    private fun chip(): TextView = text("", 13f, Color.WHITE).apply {
        typeface = medium
        background = round(0x24FFFFFF, 16)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /**
     * The main card: the earbuds, a big dot-matrix battery level, where they are and what they do. It glows magenta while
     * the earbuds are here, indigo while they are elsewhere, violet while switching.
     */
    private fun buildDashboard() {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            minimumHeight = dp(330)
            clipToOutline = true
        }
        hero = c
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(if (Earbuds.isBuds(this)) "Galaxy Buds2 Pro" else "AirPods Pro 2", 16f, Color.WHITE).apply { typeface = medium },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_info); setColorFilter(0xCCFFFFFF.toInt())
            background = round(0x24FFFFFF, 18); setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Earbud details"
            setOnClickListener { tap(this); showPage("Buds") }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        c.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        heroBuds = BudsView(this, resources.displayMetrics.density)
        c.addView(heroBuds, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        heroUnit = text("", 14f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        c.addView(heroUnit)

        heroState = text("", 13.5f, Color.WHITE).apply { typeface = medium; setPadding(dp(16), dp(7), dp(16), dp(7)) }
        c.addView(heroState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        heroWhere = text("", 14f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        c.addView(heroWhere)

        stageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0); visibility = View.GONE }
        stageBar = DotBar(this, Color.WHITE, 0x40FFFFFF)
        stageBox.addView(stageBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stageText = text("", 13f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        stageBox.addView(stageText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        heroNote = text("", 13f, 0xE6FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0); visibility = View.GONE }
        c.addView(heroNote)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }
        chipEar = chip(); chipBattery = chip(); chipCase = chip(); chipLink = chip()
        for (ch in listOf(chipEar, chipBattery, chipCase)) chips.addView(ch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3); rightMargin = dp(3) })
        c.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        safeBtn = button("Resume automatic switching", true) {
            EhState.controller?.clearSafeMode(); refresh()
        }.apply { visibility = View.GONE; setTextColor(Color.BLACK); background = g.filled(color = Color.WHITE) }
        c.addView(safeBtn, lp(14))
    }

    private fun pretty(n: String) = when (n) { "mac" -> "Mac"; "phone" -> "Phone"; "tab" -> "Tablet"; else -> n.replaceFirstChar { it.uppercase() } }

    private fun refreshDashboard(run: Boolean) {
        val now = System.currentTimeMillis()
        val holder = EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
        val stage = EhState.stage
        val phase = EhState.phase
        val switching = run && (stage != null || phase == HandoffPhase.CLAIM_PENDING || phase == HandoffPhase.RELEASE_PENDING)
        val hue = when { switching -> Glass.Hue.VIOLET; run && EhState.owner -> Glass.Hue.MAGENTA; else -> Glass.Hue.INDIGO }
        if (hue != heroHue) { heroHue = hue; hero.background = g.hero(36, hue) }
        heroWhere.visibility = if (run) View.VISIBLE else View.GONE
        heroWhere.text = when {
            !run -> ""
            EhState.owner -> "Connected to this ${pretty(prefs.getString("name", "phone") ?: "phone").lowercase()}"
            holder != null -> "Connected to ${pretty(holder)}"
            else -> "Not connected to any of your devices"
        }
        val word = when {
            !run -> "Stopped"
            switching -> "Switching"
            EhState.safeMode -> "Switching paused"
            EhState.call -> "On a call"
            EhState.owner && EhState.playing -> "Playing"
            EhState.owner && phase == HandoffPhase.OWNER_PAUSED_BY_EAR_REMOVAL -> "Paused"
            EhState.owner -> "Ready"
            EhState.playing -> "Playing on speaker"
            else -> if (holder == null) "Idle" else "Ready"
        }
        heroState.text = word
        // The outline says whether the earbuds are here: green yes, red-grey no.
        heroState.background = g.statePill(run && EhState.owner).also { (it as GradientDrawable).setColor(0x24FFFFFF) }

        // The picture: both earbuds and the case with their batteries, and where each bud is. Galaxy Buds report every
        // bud and the case over their channel; AirPods give the wear state and the two buds' levels.
        val live = EhState.budsClient?.takeIf { it.connected }?.snapshot()
        // Battery reports arrive only when a level changes, so a level stays good while the channel is open and for
        // 10 minutes after (it used to vanish after a minute of no change).
        val fresh = live != null || (EhState.batAt != 0L && now - EhState.batAt < 600_000)
        val ear = EhState.ear
        fun place(p: String?, side: Int): String = when {
            !run -> "unknown"
            p != null && p != "unknown" -> if (p == "disconnected") "unknown" else p
            !EhState.owner -> "unknown"
            ear == "both" -> "wearing"
            ear == "one" -> if (side == 0) "wearing" else "idle"
            ear == "none" -> "idle"
            else -> "unknown"
        }
        heroBuds.model = BudsView.Model(
            left = BudsView.Bud(if (fresh) EhState.batA else -1, place(live?.placeL?.takeIf { run && EhState.owner }, 0), live?.chargeL == true),
            right = BudsView.Bud(if (fresh) EhState.batB else -1, place(live?.placeR?.takeIf { run && EhState.owner }, 1), live?.chargeR == true),
            case = if (fresh) EhState.batCase else -1, caseCharging = live?.chargeCase == true,
            playing = EhState.playing, here = run && EhState.owner, stem = !Earbuds.isBuds(this),
        )
        heroUnit.text = when {
            !run -> "Start EarHop to follow your earbuds"
            EhState.owner && !fresh && Earbuds.isBuds(this) -> "Reading the Buds\u2026 (${EhState.buds})"
            EhState.owner && EhState.onSpeaker -> "Out of your ears \u00B7 playing on the speaker"
            EhState.owner -> when (ear) { "both" -> "Both in your ears"; "one" -> "One in your ear"; "none" -> "Out of your ears"; else -> "" }
            else -> ""
        }
        heroUnit.visibility = if (heroUnit.text.isEmpty()) View.GONE else View.VISIBLE

        stageBox.visibility = if (switching) View.VISIBLE else View.GONE
        if (switching) {
            val s = stage ?: ""
            val cur = when {
                s.startsWith("Confirming") -> 3
                s.startsWith("Connecting") -> 2
                s.startsWith("Waiting") || s.startsWith("Releasing") -> 1
                else -> 0
            }
            stageBar.fraction = (cur + 1) / 4f
            stageText.text = stage ?: phase.label
        }

        // Wrong secret, clock and duplicate names are on the Needs attention card; here only the switching state.
        val failure = EhState.lastFailure
        val hold = EhState.holdReason
        heroNote.visibility = View.VISIBLE
        when {
            !run -> heroNote.visibility = View.GONE
            failure != null && !switching -> heroNote.text = "Last switch failed: $failure."
            hold != null && !EhState.owner && !switching -> heroNote.text = "Not switching: $hold."
            else -> heroNote.visibility = View.GONE
        }
        safeBtn.visibility = if (run && EhState.safeMode) View.VISIBLE else View.GONE

        // Wear state and batteries are in the picture now, so the chips stay hidden.
        for (ch in listOf(chipEar, chipBattery, chipCase)) ch.visibility = View.GONE

        takeBtn.isEnabled = !EhState.owner && !switching
        takeBtn.alpha = if (takeBtn.isEnabled) 1f else 0.45f
        takeBtn.text = if (EhState.owner) "Connected here" else "Take ${Earbuds.short(this)}"
        releaseBtn.isEnabled = EhState.owner && !switching
        releaseBtn.alpha = if (releaseBtn.isEnabled) 1f else 0.45f
    }

    /** What the service sees right now, for the Advanced card and bug reports. */
    private fun technical(): String {
        val r = EhState.earReading
        return "state   ${EhState.phase.label}\n" +
            "ears    ${r.ear} via ${r.source} (${r.confidence} confidence)\n" +
            "audio   ${if (EhState.playing) "playing" else "idle"}${if (EhState.call) ", call" else ""}\n" +
            "link    ${EhState.transport}\n" +
            (if (Earbuds.isBuds(this)) "buds    ${EhState.buds}\n" else "beacon  ${EhState.beaconInfo.ifEmpty { "none yet" }}\nscan    ${EhState.scanMode}, direct ${EhState.aacp}\n") +
            "auto    ${if (prefs.getBoolean("auto", true)) "on" else "off"}"
    }

    // ---------- Home: needs attention, devices ----------

    private class Issue(val text: String, val color: Int, val action: String?, val act: (() -> Unit)?)

    private fun issues(run: Boolean): List<Issue> {
        val list = ArrayList<Issue>()
        val now = System.currentTimeMillis()
        val hasSecret = !prefs.getString("secret", "").isNullOrBlank()
        if (!run && hasSecret && prefs.getBoolean("onboarded", false)) list += Issue("EarHop is stopped, so nothing switches or syncs.", orange, "Start") { toggle() }
        if (!required()) list += Issue("Nearby devices or Location permission is missing.", red, "Allow") { checks().firstOrNull { !it.ok }?.fix?.invoke() }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt != null && !bt.isEnabled) list += Issue("Bluetooth is off.", red, "Turn on") { tryOpen(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) list += Issue("Android may stop EarHop in the background.", orange, "No battery limits") { askBattery() }
        EhState.duplicates.entries.firstOrNull { now - it.value < 180000 }?.key?.let { dup ->
            list += Issue("Two devices are both named $dup, so EarHop mixes them up. Give each its own name.", red, "Fix name") { showPage("Devices") }
        }
        EhState.rejected.entries.firstOrNull()?.let { bad ->
            list += if (bad.value == "clock") Issue("${pretty(bad.key)} is ignored: its clock differs by more than 30 seconds. Set both to automatic time.", orange, null, null)
            else Issue("${pretty(bad.key)} uses a different secret, so it is ignored. Scan the Mac's QR code again.", orange, "Scan QR code") { scanQr() }
        }
        val clipOn = prefs.getBoolean("clipSync", true) && prefs.getBoolean("clipAutoRoot", true) && !ClipSync.paused(this)
        if (run && clipOn) when (ClipSync.autoMethod(this)) {
            "helper" -> if (!ClipSync.helperRunning()) list += Issue(
                if (HelperStarter.granted()) "Clipboard helper stopped. EarHop is starting it again." else "Clipboard helper is not running, so copies here are not sent by themselves.",
                orange, if (HelperStarter.granted()) null else "Set up") { showPage("Clipboard") }
            "accessibility" -> if (ClipAccessibilityService.state(this) != "on") list += Issue(
                "The accessibility service is not running, so copies here are not sent by themselves.", orange, "Turn on") { openAccessibility() }
            "root" -> if (!prefs.getBoolean("rootGranted", false)) list += Issue("Root is not granted, so copies here are not sent by themselves.", orange, "Grant root") { askRoot { } }
        }
        return list
    }

    private fun refreshHealth(run: Boolean) {
        val list = issues(run)
        val sig = list.joinToString("|") { it.text + it.action }
        if (sig == healthSig) return
        healthSig = sig
        healthBox.removeAllViews()
        healthBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEachIndexed { i, it ->
            if (i > 0) divider(healthBox)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(status(it.text, it.color), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            if (it.action != null && it.act != null) r.addView(button(it.action, false) { it.act.invoke() }.apply {
                minHeight = dp(38); minimumHeight = dp(38); setPadding(dp(12), dp(4), dp(12), dp(4)); textSize = 14f
            })
            healthBox.addView(r)
        }
    }

    /** One line for a device: its state word and colour. */
    private fun deviceState(name: String, run: Boolean): Pair<String, Int> {
        val self = prefs.getString("name", "phone") ?: "phone"
        val now = System.currentTimeMillis()
        if (name == self) return when {
            !run -> "Stopped" to subC
            EhState.owner -> "Has earbuds" to green
            EhState.playing -> "Playing" to green
            else -> "This device" to subC
        }
        val p = EhState.peers[name]?.takeIf { now - it.seenAt < 15000 } ?: return "Not seen" to subC
        return when {
            p.call -> "On a call" to orange
            p.owner -> "Has earbuds" to green
            p.playing -> "Playing" to green
            else -> "Online" to green
        }
    }

    private fun refreshStrip(run: Boolean) {
        val names = listOf("mac", "phone", "tab")
        val states = names.map { deviceState(it, run) }
        val sig = states.joinToString { it.first }
        if (sig == stripSig) return
        stripSig = sig
        stripBox.removeAllViews()
        names.forEachIndexed { i, n ->
            val (s, color) = states[i]
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(chipC, 16)
                setPadding(dp(12), dp(10), dp(10), dp(10))
                setOnClickListener { tap(this); showPage("Devices") }
                contentDescription = "${pretty(n)}: $s"
            }
            cell.addView(text(pretty(n), 15f, textC, true))
            cell.addView(status(s, color, 12.5f).apply { setPadding(0, dp(3), 0, 0); maxLines = 1 })
            stripBox.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    private fun refreshPeers(run: Boolean) {
        val now = System.currentTimeMillis()
        val live = EhState.peers.values.filter { now - it.seenAt < 15000 }.sortedBy { it.name }
        val rows = live.map { p ->
            val state = buildString {
                if (p.call) append("on a call, ")
                if (p.owner) append("has the earbuds, ")
                append(if (p.playing) "playing" else "idle")
                append(if (p.via == "ble") " over Bluetooth" else " over Wi-Fi")
            }
            Triple(p.name, state, p.owner)
        }
        val sig = "$run" + rows.joinToString()
        if (sig == peersSig) return
        peersSig = sig
        peersBox.removeAllViews()
        if (rows.isEmpty()) {
            peersBox.addView(status(if (run) "No other devices seen yet. Open EarHop on the Mac with the same secret." else "Start EarHop to look for your other devices.", subC))
        }
        rows.forEachIndexed { idx, (name, state, owner) ->
            if (idx > 0) divider(peersBox)
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            r.addView(text(pretty(name), 16f, textC, true))
            r.addView(status(state.replaceFirstChar { it.uppercase() }, green, 13f).apply { setPadding(0, dp(3), 0, 0) })
            peersBox.addView(r)
        }
    }

    // ---------- Buds: battery ----------

    private fun placeWord(p: String, charging: Boolean) = when (p) {
        "wearing" -> "in ear"; "idle" -> "out"; "case" -> if (charging) "charging" else "in case"; "disconnected" -> "off"; else -> ""
    }

    private fun refreshBattery() {
        val c = EhState.budsClient?.takeIf { it.connected }
        val sn = if (Earbuds.isBuds(this)) c?.snapshot() else null
        val l = sn?.battL ?: EhState.batA
        val r = sn?.battR ?: EhState.batB
        val cs = sn?.battCase ?: EhState.batCase
        val ago = if (EhState.batAt == 0L) -1L else (System.currentTimeMillis() - EhState.batAt) / 60000
        val wl = if (sn != null) placeWord(sn.placeL, sn.chargeL) else ""
        val wr = if (sn != null) placeWord(sn.placeR, sn.chargeR) else ""
        val wc = if (sn?.chargeCase == true) "charging" else ""
        val sig = "$l$r$cs$ago$wl$wr$wc"
        if (sig == batterySig) return
        batterySig = sig
        batteryBox.removeAllViews()
        if (l < 0 && r < 0 && cs < 0) {
            batteryBox.addView(status(if (Earbuds.isBuds(this)) "Shown while the Galaxy Buds are connected to this device." else "Shown when the AirPods are near this device with the case open or in your ears.", subC, 13f))
            return
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, v, where) in listOf(Triple("Left", l, wl), Triple("Right", r, wr), Triple("Case", cs, wc))) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = round(chipC, 22); setPadding(dp(10), dp(16), dp(10), dp(12))
                contentDescription = "$name ${if (v >= 0) "$v percent" else "unknown"} $where"
            }
            t.addView(DotText(this, dp(26), if (v in 0..20) red else textC).apply { text = if (v >= 0) "$v%" else "-" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // The level as a dotted bar under the number.
            t.addView(DotBar(this, if (v in 0..20) red else textC, sepC).apply { fraction = if (v > 0) v / 100f else 0f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(6) })
            t.addView(text(if (where.isEmpty()) name else "$name · $where", 12f, subC).apply { gravity = Gravity.CENTER })
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (name != "Left") leftMargin = dp(10) })
        }
        batteryBox.addView(row)
        if (sn == null && ago >= 1) batteryBox.addView(text("Updated $ago min ago", 12f, subC).apply { setPadding(dp(2), dp(8), 0, 0) })
    }

    // ---------- clipboard ----------

    private val clipMethods = listOf("ble" to "Bluetooth", "both" to "Both", "wifi" to "Wi-Fi")

    private fun clipMethodWords(m: String) = when (m) {
        "ble" -> "Text over Bluetooth LE only, works without Wi-Fi within about a room. Images and long text still use Wi-Fi."
        "wifi" -> "Everything over Wi-Fi. The devices must be on the same network."
        else -> "Text over Bluetooth LE and Wi-Fi at once, the first copy to arrive wins. Images over Wi-Fi. Recommended."
    }

    private fun setClipSync(on: Boolean) {
        prefs.edit().putBoolean("clipSync", on).apply()
        if (!on) ClipSync.stopRootWatch() else if (EhState.running) ClipSync.tryStartAuto(applicationContext)
        refresh()
    }

    /** Opens a page in another app, false when it does not exist on this phone. */
    private fun tryOpen(i: Intent): Boolean = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }

    private fun askBattery(pkg: String = packageName) {
        if (pkg == packageName && tryOpen(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))) return
        if (!tryOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))) openAppSettings()
    }

    /** The phone maker's auto-start list (Vivo, Samsung), else the app's own settings page. */
    private fun openAutostart(pkg: String = packageName) {
        val pages = listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        for ((p, c) in pages) if (tryOpen(Intent().setComponent(android.content.ComponentName(p, c)))) {
            Toast.makeText(this, "Turn on ${if (pkg == packageName) "EarHop" else "Shizuku"} in this list", Toast.LENGTH_LONG).show()
            return
        }
        tryOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    /**
     * Opens Accessibility settings scrolled to EarHop clipboard send, with its row highlighted where Settings supports it.
     * (Its own page cannot be opened directly: that needs OPEN_ACCESSIBILITY_DETAILS_SETTINGS, a system-only permission.)
     */
    private fun openAccessibility() {
        val comp = android.content.ComponentName(this, ClipAccessibilityService::class.java).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", comp)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", comp) })
        if (!tryOpen(i)) { Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, if (ClipAccessibilityService.switchedOn(this)) "Tap EarHop clipboard send to change it"
            else "Tap EarHop clipboard send (under Installed apps) and turn it on", Toast.LENGTH_LONG).show()
    }

    /** Updates the accessibility status the moment the setting changes, not only on the next refresh. */
    private val a11yObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            clipSetupSig = ""; healthSig = ""
            refresh()
            // Android starts or stops the service a moment after the setting changes.
            handler.postDelayed({ clipSetupSig = ""; refresh() }, 700)
        }
    }

    /** Asks the root manager once; on success root is remembered for switching and the clipboard. */
    private fun askRoot(done: (Boolean) -> Unit) {
        if (!RootShell.hasSu()) { done(false); return }
        Toast.makeText(this, "Asking for root... allow it in your root manager", Toast.LENGTH_LONG).show()
        Thread {
            val ok = RootShell.check()
            if (ok) prefs.edit().putBoolean("rootGranted", true).putBoolean("useRoot", true).apply()
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Root was not granted", Toast.LENGTH_SHORT).show()
                done(ok); clipSetupSig = ""; refresh()
            }
        }.start()
    }

    /** Copy to run on the Mac with the phone connected: starts the clipboard helper as the shell user. */
    private fun helperCommand() = "adb shell 'kill \$(pidof earhop-clip) 2>/dev/null; CLASSPATH=\$(pm path $packageName | cut -d: -f2) " +
        "setsid nohup app_process /system/bin --nice-name=earhop-clip com.earhop.logger.ClipHelper >/dev/null 2>&1 </dev/null &'"

    /**
     * The setup for the chosen automatic sending method: its live state, then the steps still missing. Rebuilt only when
     * something it shows changed.
     */
    private fun refreshClipSetup(run: Boolean) {
        val want = prefs.getBoolean("clipAutoRoot", true) && prefs.getBoolean("clipSync", true)
        val m = ClipSync.autoMethod(this)
        val acc = ClipAccessibilityService.state(this)
        val rootOk = prefs.getBoolean("rootGranted", false)
        val helper = ClipSync.helperRunning()
        val installed = HelperStarter.shizukuInstalled(this)
        val shizuku = HelperStarter.shizukuRunning()
        val allowed = HelperStarter.granted()
        val watching = ClipSync.rootWatching
        val sig = "$want$m$acc$rootOk$helper$installed$shizuku$allowed$run$watching${ClipSync.logOk}"
        if (sig == clipSetupSig) return
        clipSetupSig = sig
        clipSetup.removeAllViews()
        if (!prefs.getBoolean("clipSync", true)) {
            clipSetup.addView(status("Clipboard sync is off.", subC).apply { setPadding(0, dp(10), 0, 0) }); return
        }
        if (!want) {
            clipSetup.addView(status("Off. Send copies with Share clipboard, the quick settings tile or the notification.", subC).apply { setPadding(0, dp(8), 0, 0) }); return
        }
        fun head(s: String, color: Int) { clipSetup.addView(status(s, color).apply { setPadding(0, dp(12), 0, dp(2)) }) }
        fun note(s: String) { clipSetup.addView(text(s, 12.5f, subC).apply { setPadding(dp(16), dp(2), 0, 0) }) }
        fun step(done: Boolean, title: String, body: String, action: String?, act: (() -> Unit)?) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            r.addView(text("${if (done) "✓" else "○"}  $title", 15f, if (done) subC else textC).apply { typeface = medium })
            if (!done && body.isNotEmpty()) r.addView(text(body, 12.5f, subC).apply { setPadding(dp(22), dp(2), 0, 0) })
            if (!done && action != null && act != null) r.addView(button(action, true) { act() }, lp(8))
            clipSetup.addView(r)
        }
        when (m) {
            "root" -> {
                when {
                    !rootOk -> head("Root is not granted yet.", orange)
                    !run -> head("Starts with EarHop.", subC)
                    watching && ClipSync.watchMode == "root" -> head("Working: copies here are sent automatically (root).", green)
                    else -> head("Starting the root watch.", orange)
                }
                note("Watches Android's log as root for blocked clipboard reads, then sends the copy. No extra app needed.")
                if (!rootOk) step(false, "Grant root", "Your root manager asks once.", "Grant root") { askRoot { ok -> if (ok) ClipSync.setAutoMethod(this, "root") } }
            }
            "accessibility" -> {
                when (acc) {
                    "on" -> head("On and working: EarHop listens for copy messages.", green)
                    "listed" -> head("On in Android settings, but Android has not started it. If this stays, turn it off and on again.", orange)
                    else -> head("Off in Android settings, so copies here are not sent by themselves.", orange)
                }
                // What Android reports, so the screen can be compared with the Accessibility page.
                val sw = ClipAccessibilityService.switchedOn(this)
                clipSetup.addView(text("Android setting: ${if (sw) "On" else "Off"}    Service: ${if (acc == "on") "running" else "not running"}",
                    12.5f, if (acc == "on") subC else orange).apply { setPadding(dp(16), dp(4), 0, 0) })
                note("Notices \"Copied\" messages and Android's clipboard preview, then briefly opens EarHop to send. " +
                    "Apps that copy silently are missed. EarHop never reads the screen; it reads the clipboard only after it opens.")
                if (acc != "on") clipSetup.addView(text("In Accessibility, open EarHop clipboard send (under Installed or Downloaded apps) and turn it on. " +
                    "If it is greyed out: Settings, Apps, EarHop, the three dots, Allow restricted settings, then try again.", 12.5f, subC).apply { setPadding(dp(16), dp(8), 0, 0) })
                clipSetup.addView(button(if (acc == "on") "Accessibility settings" else "Turn on in Accessibility settings", acc != "on") { openAccessibility() }, lp(10))
            }
            else -> {
                when {
                    helper && allowed -> head("Working: the helper sends every copy. EarHop restarts it through Shizuku when needed.", green)
                    helper -> head("Working, until the phone restarts. Set up Shizuku so EarHop can restart it.", green)
                    allowed && run -> head("Not running. EarHop starts it within a minute.", orange)
                    !run -> head("Start EarHop first.", subC)
                    else -> head("Not running. Only Shizuku or a computer can start it.", orange)
                }
                note("A small helper that runs as Android's shell user, which may read the clipboard in the background. Android shows \"Shell pasted\" once per copy.")
                clipSetup.addView(button(if (helper) "Restart helper" else "Start helper now", !helper) {
                    val msg = ClipSync.watchHelper(applicationContext, manual = true)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    clipSetupSig = ""; handler.postDelayed({ refresh() }, 3000)
                }, lp(10))
                step(installed, "Install Shizuku", "A free open-source app that starts helpers like this one, also after a restart.", "Get Shizuku") {
                    if (!tryOpen(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${HelperStarter.SHIZUKU_PACKAGE}"))))
                        tryOpen(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${HelperStarter.SHIZUKU_PACKAGE}")))
                }
                if (installed) step(shizuku, "Start Shizuku",
                    "Open Shizuku and start it with Wireless debugging (it guides you). Turn on its Start on boot option, so it starts by itself after a restart.",
                    "Open Shizuku") { packageManager.getLaunchIntentForPackage(HelperStarter.SHIZUKU_PACKAGE)?.let { tryOpen(it) } }
                if (shizuku) step(allowed, "Allow EarHop in Shizuku", "Shizuku asks once.", "Allow") {
                    HelperStarter.requestPermission { ok ->
                        runOnUiThread {
                            if (ok) Toast.makeText(this, ClipSync.watchHelper(applicationContext, manual = true), Toast.LENGTH_SHORT).show()
                            clipSetupSig = ""; refresh()
                        }
                    }
                }
                if (installed) step(false, "Keep Shizuku running", "No battery limits and auto-start for Shizuku too.", null, null)
                if (installed) buttons(clipSetup, button("Shizuku battery", false) { askBattery(HelperStarter.SHIZUKU_PACKAGE) },
                    button("Shizuku auto-start", false) { openAutostart(HelperStarter.SHIZUKU_PACKAGE) }, top = 8)
                // The computer route is for people who use adb; closed unless opened.
                clipSetup.addView(text("Set up from a computer  ${if (macHelpOpen) "−" else "+"}", 15f, textC).apply {
                    typeface = medium; setPadding(0, dp(18), 0, dp(4))
                    setOnClickListener { tap(this); macHelpOpen = !macHelpOpen; clipSetupSig = ""; refresh() }
                })
                if (macHelpOpen) {
                    clipSetup.addView(text("With this phone connected over USB debugging, run tools/phone_clip.sh, or this command:", 12.5f, subC).apply { setPadding(0, 0, 0, dp(6)) })
                    clipSetup.addView(codeBox(helperCommand()))
                    clipSetup.addView(button("Copy the command", false) { copy("EarHop command", helperCommand()) }, lp(8))
                }
            }
        }
    }

    private fun refreshRecent() {
        val list = ClipSync.recent.toList()
        val minute = System.currentTimeMillis() / 60000
        val sig = "${ClipSync.keepRecent(this)}$minute" + list.joinToString { "${it.at}" }
        if (sig == recentSig) return
        recentSig = sig
        recentBox.removeAllViews()
        if (!ClipSync.keepRecent(this)) return
        if (list.isEmpty()) { recentBox.addView(status("Nothing yet.", subC, 13f).apply { setPadding(0, dp(8), 0, 0) }); return }
        list.forEach { r ->
            divider(recentBox)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = text(if (r.sent) "↑" else "↓", 15f, textC, true).apply {
                gravity = Gravity.CENTER; background = round(chipC, 14)
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val preview = when {
                r.kind == "image" -> "Image, ${r.size / 1024} KB"
                r.text == null -> "Long text, ${r.size / 1024} KB"
                else -> r.text.replace(Regex("\\s+"), " ").trim().take(90)
            }
            words.addView(text(preview, 14.5f, textC).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            val ago = (System.currentTimeMillis() - r.at) / 60000
            val whenS = if (ago < 1) "just now" else if (ago < 60) "$ago min ago" else shortTime(r.at)
            words.addView(text("${if (r.sent) "Sent" else "From ${pretty(r.dev)}"}, $whenS", 12f, subC))
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (r.text != null) row.setOnClickListener {
                tap(it, true); ClipSync.copyBack(applicationContext, r.text)
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            recentBox.addView(row)
        }
        recentBox.addView(button("Clear", false) { ClipSync.recent.clear(); recentSig = ""; refresh() }, lp(12))
    }

    // ---------- Galaxy Buds controls ----------

    private fun refreshBuds() {
        if (!Earbuds.isBuds(this)) { budsCard.visibility = View.GONE; return }
        budsCard.visibility = View.VISIBLE
        val c = EhState.budsClient
        val live = c != null && c.connected
        val sn = if (live) c!!.snapshot() else null
        val sig = if (sn == null) "off${EhState.buds}${c?.findMessage}" else with(sn) {
            "$noise$eq$spatial$touchOn$seamless${c!!.findActive}${c.findMessage}$muteL$muteR"
        }
        if (sig == budsSig) return
        budsSig = sig
        while (budsCard.childCount > 1) budsCard.removeViewAt(1)
        if (sn == null || c == null) {
            budsCard.addView(text("Take the Buds to this device and start EarHop to control them. Wear channel: ${EhState.buds}. Noise control, equalizer and Find only work on the device that holds the Buds.", 13f, subC))
            return
        }
        fun pill(label: String, on: Boolean, desc: String, act: () -> Unit) = button(label, on) { act(); budsSig = "" }.apply {
            contentDescription = "$desc${if (on) ", selected" else ""}"
        }
        fun rowOf(vararg btns: Button) = buttons(budsCard, *btns, top = 8)

        budsCard.addView(text("Noise control", 13f, subC))
        rowOf(pill("Off", sn.noise == "off", "Noise control off") { c.setNoise("off") },
            pill("ANC", sn.noise == "anc", "Active noise cancelling") { c.setNoise("anc") },
            pill("Ambient", sn.noise == "ambient", "Ambient sound") { c.setNoise("ambient") })
        budsCard.addView(text("Noise cancelling needs both Buds in your ears, unless Noise controls with one earbud is on in Galaxy Wearable.", 11.5f, subC).apply { setPadding(0, dp(6), 0, 0) })

        budsCard.addView(text("Equalizer${if (sn.eq == "custom") " (custom curve set in Galaxy Wearable)" else ""}", 13f, subC).apply { setPadding(0, dp(14), 0, 0) })
        val names = listOf("Off", "Bass", "Soft", "Dynamic", "Clear", "Treble")
        for (row in 0 until 2) rowOf(*Array(3) { i ->
            val k = row * 3 + i
            pill(names[k], sn.eq == BudsProtocol.EQ_PRESETS[k], "Equalizer ${names[k]}") { c.setEq(BudsProtocol.EQ_PRESETS[k]) }
        })

        fun toggle(label: String, sub: String?, v: Boolean?, act: (Boolean) -> Unit) {
            if (v == null) return
            divider(budsCard)
            switchRow(budsCard, label, sub, { v }, track = false) { act(it); budsSig = "" }
        }
        budsCard.addView(View(this), lp(4))
        toggle("360 Audio", null, sn.spatial) { c.setSpatial(it) }
        toggle("Touch controls", null, sn.touchOn) { c.setTouch(it) }
        toggle("Quick connect", "Samsung's own switching. Turn off if it fights EarHop.", sn.seamless) { c.setSeamless(it) }

        budsCard.addView(text("Find my Buds", 13f, subC).apply { setPadding(0, dp(14), 0, 0) })
        if (c.findActive) budsCard.addView(pill("Stop ringing", true, "Stop ringing the Buds") { c.findStop("Search stopped") }, lp(8))
        else budsCard.addView(pill("Ring the Buds", false, "Ring the Buds") { c.findStart() }, lp(8))
        if (c.findMessage.isNotEmpty()) budsCard.addView(text(c.findMessage, 12f, subC))
        budsCard.addView(text("Rings quietly then loud. Refused while a bud may be in your ear, and it stops itself after a minute.", 11.5f, subC).apply { setPadding(0, dp(6), 0, 0) })
    }

    // ---------- onboarding ----------

    private fun refreshOnboarding() {
        if (prefs.getBoolean("onboarded", false)) { onboardCard.visibility = View.GONE; return }
        val hasSecret = !prefs.getString("secret", "").isNullOrBlank() || secretField.text.isNotBlank()
        val perms = required()
        val run = EhState.running
        val owner = EhState.owner
        val sig = "$hasSecret$perms$run$owner"
        onboardCard.visibility = View.VISIBLE
        if (sig == onboardSig) return
        onboardSig = sig
        while (onboardCard.childCount > 1) onboardCard.removeViewAt(1)
        fun step(n: Int, done: Boolean, title: String, body: String, action: String?, act: (() -> Unit)?) {
            onboardCard.addView(text("${if (done) "✓" else "$n."}  $title", 16f, if (done) subC else textC).apply { typeface = medium; setPadding(0, dp(8), 0, 0) })
            onboardCard.addView(text(body, 13f, subC))
            if (!done && action != null && act != null) onboardCard.addView(button(action, true) { act() }, lp(6))
        }
        step(1, hasSecret, "Pair with your Mac", "On the Mac open Settings, Network, and scan the QR code. Or paste the secret.", "Scan QR code") { scanQr(); onboardSig = "" }
        step(2, perms, "Allow permissions", "EarHop needs Nearby devices and Location to find your earbuds.", "Allow") {
            checks().firstOrNull { !it.ok }?.fix?.invoke()
        }
        step(3, owner, "Test a handoff", if (!run) "Start EarHop (Devices, This device), put the earbuds in, then press Take ${Earbuds.short(this)}." else "Press Take ${Earbuds.short(this)}. The earbuds should connect to this device.",
            if (run) "Take ${Earbuds.short(this)}" else null, if (run) ({ quick(true) }) else null)
        onboardCard.addView(button(if (hasSecret && perms && owner) "Finish" else "Skip for now", false) {
            prefs.edit().putBoolean("onboarded", true).apply(); onboardCard.visibility = View.GONE
        }, lp(12))
    }

    // ---------- switching rules ----------

    private fun fmt(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    private fun flag(box: LinearLayout, label: String, key: String, def: Boolean = false, after: () -> Unit) {
        divider(box)
        switchRow(box, label, null, { prefs.getBoolean(key, def) }, track = false) { prefs.edit().putBoolean(key, it).apply(); after() }
    }

    private fun rebuildRules() {
        val box = rulesBox
        box.removeAllViews()
        box.addView(text("Device priority. If two devices start playing together, the higher one wins.", 13f, subC))
        val order = RuleStore.priority(this)
        order.forEachIndexed { i, name ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(text("${i + 1}.  ${pretty(name)}", 16f, textC), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val up = button("↑", false) {
                val l = order.toMutableList(); java.util.Collections.swap(l, i, i - 1); RuleStore.setPriority(this, l); rebuildRules()
            }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.4f; contentDescription = "Move $name up" }
            val down = button("↓", false) {
                val l = order.toMutableList(); java.util.Collections.swap(l, i, i + 1); RuleStore.setPriority(this, l); rebuildRules()
            }.apply { isEnabled = i < order.size - 1; alpha = if (i < order.size - 1) 1f else 0.4f; contentDescription = "Move $name down" }
            for (bt in listOf(up, down)) { bt.minWidth = 0; bt.minimumWidth = 0; bt.minHeight = 0; bt.minimumHeight = 0; bt.setPadding(0, 0, 0, 0) }
            r.addView(up, LinearLayout.LayoutParams(dp(40), dp(36)))
            r.addView(down, LinearLayout.LayoutParams(dp(40), dp(36)).apply { leftMargin = dp(6) })
            box.addView(r, lp(6))
        }

        flag(box, "Quiet hours (no automatic switching)", "quietOn") { rebuildRules() }
        if (prefs.getBoolean("quietOn", false)) {
            fun timeBtn(label: String, key: String, def: Int) = button("$label ${fmt(prefs.getInt(key, def))}", false) {
                val cur = prefs.getInt(key, def)
                android.app.TimePickerDialog(this, { _, h, m -> prefs.edit().putInt(key, h * 60 + m).apply(); rebuildRules() }, cur / 60, cur % 60, true).show()
            }
            buttons(box, timeBtn("From", "quietStart", 22 * 60), timeBtn("Until", "quietEnd", 7 * 60), top = 8)
        }
        flag(box, "Hold during Do Not Disturb", "respectDnd") { }
        flag(box, "Only switch on listed Wi-Fi", "wifiOnly") { rebuildRules() }
        if (prefs.getBoolean("wifiOnly", false)) {
            val names = (prefs.getStringSet("wifiNames", emptySet()) ?: emptySet()).sorted()
            names.forEach { n ->
                box.addView(button("$n   (tap to remove)", false) {
                    prefs.edit().putStringSet("wifiNames", (prefs.getStringSet("wifiNames", emptySet()) ?: emptySet()) - n).apply(); rebuildRules()
                }, lp(4))
            }
            val cur = RuleStore.ssid(this)
            if (cur != null) {
                box.addView(button("Add current network ($cur)", false) {
                    prefs.edit().putStringSet("wifiNames", (prefs.getStringSet("wifiNames", emptySet()) ?: emptySet()) + cur).apply(); rebuildRules()
                }, lp())
            } else {
                box.addView(text("Wi-Fi name not readable. Connect to Wi-Fi and allow Location. Until then every network is allowed.", 12f, subC).apply { setPadding(0, dp(6), 0, 0) })
            }
        }

        flag(box, "Volume profile for this device", "volumeOn") { rebuildRules() }
        if (prefs.getBoolean("volumeOn", false)) {
            val vr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pct = prefs.getInt("volumePct", 40)
            vr.addView(button("-", false) { prefs.edit().putInt("volumePct", (pct - 5).coerceAtLeast(0)).apply(); rebuildRules() })
            vr.addView(text("Volume on arrival: $pct%", 15f, textC).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            vr.addView(button("+", false) { prefs.edit().putInt("volumePct", (pct + 5).coerceAtMost(100)).apply(); rebuildRules() })
            box.addView(vr, lp())
        }
        box.addView(text("These rules stop automatic claiming only. Take and the tiles always work. Each device keeps its own settings.", 12f, subC).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun appLabel(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    private fun pickApps(title: String, key: String) {
        val pm = packageManager
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val saved = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(launch.size) { launch[it].first in saved }
        val d = AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(launch.map { it.second }.toTypedArray(), checked) { _, i, on -> if (on) saved.add(launch[i].first) else saved.remove(launch[i].first) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet(key, saved).apply(); rebuildAppRules() }
            .setNegativeButton("Cancel", null).create()
        blurBehind(d)
        d.show()
    }

    private fun rebuildAppRules() {
        val box = appRulesBox
        box.removeAllViews()
        val access = AppRules.hasAccess(this)
        box.addView(text(if (access) "Notification access is on, so EarHop can tell which app is playing."
            else "To tell which app is playing, EarHop needs notification access. It never reads your notifications.", 13f, subC))
        if (!access) box.addView(button("Allow notification access", true) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) { }
        }, lp(10))
        val ig = prefs.getStringSet("ignoredApps", emptySet()) ?: emptySet()
        val pf = prefs.getStringSet("preferredApps", emptySet()) ?: emptySet()
        box.addView(button("Ignored apps: ${if (ig.isEmpty()) "none" else ig.joinToString { appLabel(it) }}", false) { pickApps("Never claim for these apps", "ignoredApps") }.apply { isEnabled = access; alpha = if (access) 1f else 0.4f }, lp(10))
        box.addView(button("Preferred apps: ${if (pf.isEmpty()) "none" else pf.joinToString { appLabel(it) }}", false) { pickApps("Claim at once for these apps", "preferredApps") }.apply { isEnabled = access; alpha = if (access) 1f else 0.4f }, lp())
        box.addView(text("Ignored apps never start a switch, good for notification sounds. Preferred apps claim the earbuds without the usual short wait.", 12f, subC).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun refreshHistory() {
        val list = EhState.history.toList()
        val sig = list.joinToString("|") { "${it.at}${it.kind}" }
        if (sig == historySig) return
        historySig = sig
        for (box in listOf(historyBox, timelineBox)) {
            box.removeAllViews()
            if (list.isEmpty()) { box.addView(status("Nothing yet. Switches appear here with the reason.", subC, 13f)); continue }
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            // Home shows the last five with Undo; the Activity timeline shows all of them.
            val shown = if (box === historyBox) list.take(5) else list
            shown.forEachIndexed { i, it ->
                if (i > 0) divider(box)
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(View(this).apply { background = g.dot(if (it.kind == "claim") g.on else g.off) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
                val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                words.addView(text(if (it.kind == "claim") "Took the ${Earbuds.short(this)}" else "Released", 15f, textC))
                words.addView(text(it.reason, 12f, subC))
                r.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(text(f.format(java.util.Date(it.at)), 13f, subC))
                box.addView(r)
            }
            if (box === historyBox) {
                val last = list.first()
                box.addView(button(if (last.kind == "claim") "Undo: release the earbuds" else "Undo: take the earbuds back", false) { quick(last.kind != "claim") }, lp(12))
            }
        }
    }

    // ---------- Settings: keep running, about ----------

    private fun refreshKeep() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val mine = pm.isIgnoringBatteryOptimizations(packageName)
        val shz = HelperStarter.shizukuInstalled(this)
        val shzOk = shz && pm.isIgnoringBatteryOptimizations(HelperStarter.SHIZUKU_PACKAGE)
        val sig = "$mine$shz$shzOk"
        if (sig == keepSig) return
        keepSig = sig
        keepBox.removeAllViews()
        fun line(ok: Boolean?, s: String, action: String, act: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(status(s, when (ok) { true -> green; false -> orange; null -> subC }, plain = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
            r.addView(button(action, false) { act() }.apply { minHeight = dp(38); minimumHeight = dp(38); setPadding(dp(12), dp(4), dp(12), dp(4)); textSize = 14f })
            keepBox.addView(r)
        }
        line(mine, if (mine) "EarHop: no battery limits" else "EarHop: battery limited", if (mine) "Check" else "Fix") { askBattery() }
        line(null, "EarHop auto-start (cannot be checked)", "Open") { openAutostart() }
        if (shz) {
            divider(keepBox)
            line(shzOk, if (shzOk) "Shizuku: no battery limits" else "Shizuku: battery limited", if (shzOk) "Check" else "Fix") { askBattery(HelperStarter.SHIZUKU_PACKAGE) }
            line(null, "Shizuku auto-start (cannot be checked)", "Open") { openAutostart(HelperStarter.SHIZUKU_PACKAGE) }
        }
    }

    private fun runSelfTest() {
        val ok = try { Proto.selfTest() } catch (e: Exception) { false }
        selfTestText.visibility = View.VISIBLE
        selfTestText.text = if (ok) "✓  Protocol self test passed: signatures, versions 1 to 3 and the Bluetooth LE format match the Mac."
            else "✗  Protocol self test failed. Copy diagnostics and the log for a bug report."
        selfTestText.setTextColor(if (ok) green else red)
        EhLog.w("selftest from the app: ${if (ok) "ok" else "FAILED"}")
    }

    /** A summary for bug reports. No secret, no Bluetooth addresses, no clipboard contents. */
    private fun diagnostics(): String {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("EarHop $version on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("name ${prefs.getString("name", "?")}, running ${EhState.running}, secret set ${!prefs.getString("secret", "").isNullOrBlank()}")
            appendLine("earbuds ${Earbuds.short(this@MainActivity)} (${prefs.getString("earbudsMode", "auto")}), owner ${EhState.owner}, phase ${EhState.phase.label}")
            appendLine("ears ${EhState.earReading.ear} via ${EhState.earReading.source}, safe mode ${EhState.safeMode}")
            appendLine("link ${EhState.transport} (setting ${prefs.getString("transport", "both")})")
            appendLine("peers " + EhState.peers.values.joinToString { "${it.name} ${(now - it.seenAt) / 1000}s ${it.via}${if (it.owner) " owner" else ""}" })
            appendLine("auto ${prefs.getBoolean("auto", true)}, clip sync ${prefs.getBoolean("clipSync", true)}, clip auto ${prefs.getBoolean("clipAutoRoot", true)} method ${ClipSync.autoMethod(this@MainActivity)}")
            appendLine("helper ${ClipSync.helperRunning()}, shizuku installed ${HelperStarter.shizukuInstalled(this@MainActivity)} running ${HelperStarter.shizukuRunning()} allowed ${HelperStarter.granted()}")
            appendLine("accessibility ${ClipAccessibilityService.state(this@MainActivity)}, root ${RootShell.hasSu()} granted ${prefs.getBoolean("rootGranted", false)}, watch ${ClipSync.watchMode.ifEmpty { "none" }}")
            appendLine("battery unrestricted ${pm.isIgnoringBatteryOptimizations(packageName)}")
            append("permissions " + checks().joinToString { "${it.label}=${if (it.ok) "ok" else "missing"}" })
        }
    }

    // ---------- backup ----------

    private val reqExport = 41
    private val reqImport = 42
    /** Not carried over: per-install grants (root managers ask again) and screen state. */
    private val skipKeys = setOf("last_crash", "asked", "page", "rootGranted")

    private fun exportSettings() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "earhop-${prefs.getString("name", "device")}-settings.json")
        try { startActivityForResult(i, reqExport) } catch (e: Exception) { Toast.makeText(this, "No file picker on this device", Toast.LENGTH_SHORT).show() }
    }

    private fun importSettings() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, reqImport) } catch (e: Exception) { Toast.makeText(this, "No file picker on this device", Toast.LENGTH_SHORT).show() }
    }

    /** The settings as JSON, each value with its type so it restores exactly. */
    private fun settingsJson(): String {
        val all = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (k in skipKeys || v == null) continue
            val e = org.json.JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }))
                else -> continue
            }
            all.put(k, e)
        }
        return org.json.JSONObject().put("earhopSettings", 1).put("prefs", all).toString(2)
    }

    /** Writes the values of a settings file over the current ones. Returns how many were restored. */
    private fun applySettings(json: String): Int {
        val file = org.json.JSONObject(json)
        if (file.optInt("earhopSettings") != 1) throw IllegalArgumentException("this is not an EarHop settings file")
        val all = file.getJSONObject("prefs")
        val ed = prefs.edit()
        var n = 0
        for (k in all.keys()) {
            if (k in skipKeys) continue
            val e = all.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "set" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }

    private fun backupResult(requestCode: Int, uri: Uri) {
        try {
            if (requestCode == reqExport) {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(settingsJson().toByteArray()) }
                Toast.makeText(this, "Settings saved. The file holds your pairing secret, keep it private.", Toast.LENGTH_LONG).show()
                return
            }
            val n = applySettings(contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            EhLog.w("settings: restored $n values from a backup file")
            Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
            // Restart with the restored name and secret; onResume starts EarHop again when it is ready.
            if (EhState.running) stopService(Intent(this, EarService::class.java))
            handler.postDelayed({ recreate() }, 800)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not ${if (requestCode == reqExport) "save" else "restore"} the settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- pairing ----------

    private fun scanQr() {
        try {
            com.google.zxing.integration.android.IntentIntegrator(this)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setPrompt("Scan the QR code on the Mac: Settings, Network")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open the camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqExport || requestCode == reqImport) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) backupResult(requestCode, uri)
            return
        }
        val r =com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r == null) { super.onActivityResult(requestCode, resultCode, data); return }
        if (r.contents == null) return
        if (!handleScan(r.contents)) Toast.makeText(this, "That is not an EarHop pairing code", Toast.LENGTH_LONG).show()
    }

    /** Fills in the secret from an earhop://pair?secret=... link (QR code or camera app). True when it was one. */
    private fun handleScan(s: String?): Boolean {
        val u = try { Uri.parse(s ?: return false) } catch (e: Exception) { return false }
        if (u.scheme != "earhop") return false
        val secret = u.getQueryParameter("secret")?.trim().orEmpty()
        if (secret.isEmpty()) return false
        secretField.setText(secret)
        val changed = secret != prefs.getString("secret", "")
        if (changed) {
            Toast.makeText(this, "Paired with the Mac. Saving...", Toast.LENGTH_SHORT).show()
            showPage("Devices")
            saveDevice()
        }
        return true
    }

    // ---------- permissions ----------

    private class Check(val label: String, val detail: String, val ok: Boolean, val fix: () -> Unit)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun btPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyList()

    private fun checks(): List<Check> {
        val list = ArrayList<Check>()
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = btPermissions().filter { !granted(it) }
            list += Check("Nearby devices", "Find and talk to your earbuds", missing.isEmpty()) { ask(missing) }
        }
        list += Check("Location permission", "Android requires it to scan for Bluetooth", granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { true }
        list += Check("Location switched on", "Turn on Location in quick settings, Bluetooth scans need it", locOn) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        list += Check("Bluetooth switched on", "", bt?.isEnabled == true) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Check("Notifications", "Shows that EarHop is running", granted(Manifest.permission.POST_NOTIFICATIONS)) {
                ask(listOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        list += Check("Run in background", "Battery: No restrictions (needed on some phones)", pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { openAppSettings() }
        }
        list += Check("Notification access (optional)", "Lets ignored and preferred app rules see which app is playing", AppRules.hasAccess(this)) {
            try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) { openAppSettings() }
        }
        return list
    }

    private fun refreshPerms(run: Boolean) {
        val list = checks()
        val sig = "$run" + list.joinToString { "${it.label}${it.ok}" }
        if (sig == permsSig) return
        permsSig = sig
        permsBox.removeAllViews()
        var allOk = true
        list.forEachIndexed { i, c ->
            allOk = allOk && (c.ok || c.label.endsWith("(optional)"))
            if (i > 0) divider(permsBox)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(c.label, 16f, textC))
            if (c.detail.isNotEmpty() && !c.ok) t.addView(text(c.detail, 12f, subC))
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (c.ok) r.addView(View(this).apply { background = g.dot(g.on); contentDescription = "Allowed" }, LinearLayout.LayoutParams(dp(14), dp(14)))
            else r.addView(button("Allow", true) { c.fix() }.apply { setPadding(dp(14), dp(4), dp(14), dp(4)); minHeight = dp(38); minimumHeight = dp(38) })
            permsBox.addView(r)
        }
        permsCard.visibility = if (allOk && run) View.GONE else View.VISIBLE
    }

    private fun required(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && btPermissions().any { !granted(it) }) return false
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun ask(perms: List<String>) {
        if (perms.isEmpty()) return
        val asked = prefs.getStringSet("asked", emptySet()) ?: emptySet()
        // A second ask after a denial usually shows no dialog, so send the user to the app page instead.
        if (perms.all { it in asked } && perms.any { !shouldShowRequestPermissionRationale(it) }) {
            Toast.makeText(this, "Open Permissions and allow it there", Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }
        prefs.edit().putStringSet("asked", asked + perms).apply()
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    // ---------- actions ----------

    private fun copy(label: String, s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (t.isNullOrEmpty()) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        else if (!handleScan(t)) secretField.setText(t)
    }

    private fun quick(take: Boolean) {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) { ask(btPermissions().filter { !granted(it) }); return }
        Toast.makeText(this, if (take) "Taking the ${Earbuds.short(this)}..." else "Releasing the ${Earbuds.short(this)}...", Toast.LENGTH_SHORT).show()
        TileActions.run(applicationContext, take) { refresh() }
    }

    private fun addTiles() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sbm = getSystemService(android.app.StatusBarManager::class.java)
                val ex = java.util.concurrent.Executor { it.run() }
                sbm.requestAddTileService(android.content.ComponentName(this, TakeTile::class.java), "Take earbuds",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_take), ex) { }
                sbm.requestAddTileService(android.content.ComponentName(this, ReleaseTile::class.java), "Release earbuds",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_release), ex) { }
                sbm.requestAddTileService(android.content.ComponentName(this, AutoTile::class.java), "Auto switching",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_auto), ex) { }
                sbm.requestAddTileService(android.content.ComponentName(this, ClipTile::class.java), "Send clipboard",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_clip), ex) { }
                if (Earbuds.isBuds(this)) sbm.requestAddTileService(android.content.ComponentName(this, NoiseTile::class.java), "Noise control",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_noise), ex) { }
                return
            } catch (e: Exception) { EhLog.w("add tiles: ${e.message}") }
        }
        Toast.makeText(this, "Swipe down, tap the pencil, and drag the EarHop tiles into your quick settings", Toast.LENGTH_LONG).show()
    }

    private fun addWidget() {
        try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(android.content.ComponentName(this, EarWidget::class.java), null, null)
                return
            }
        } catch (e: Exception) { EhLog.w("add widget: ${e.message}") }
        Toast.makeText(this, "Long press the home screen, choose Widgets, and add EarHop", Toast.LENGTH_LONG).show()
    }

    private fun sendAction(a: String) {
        if (!EhState.running) { Toast.makeText(this, "Start EarHop first", Toast.LENGTH_SHORT).show(); return }
        val take = a == EarService.ACTION_TAKE
        val test = a == EarService.ACTION_TEST_PAUSE || a == EarService.ACTION_TEST_SPEAKER
        Toast.makeText(this, when {
            a == EarService.ACTION_TEST_SPEAKER -> "Speaker for 5 seconds..."
            test -> "Pausing playback..."
            take -> "Taking the ${Earbuds.short(this)}..."
            else -> "Releasing the ${Earbuds.short(this)}..."
        }, Toast.LENGTH_SHORT).show()
        if (!test) EhLog.w("handoff: manual ${if (take) "take" else "release"} requested")
        try { startService(Intent(this, EarService::class.java).setAction(a)) } catch (e: Exception) { EhLog.w("action failed ${e.message}") }
    }

    /**
     * This device card's button: Start when stopped; when running, Save, which restarts EarHop only if the name or the
     * secret changed.
     */
    private fun saveDevice() {
        if (!EhState.running) { toggle(); return }
        val n = nameField.text.toString().trim()
        val s = secretField.text.toString().trim()
        if (n == prefs.getString("name", "") && s == prefs.getString("secret", "")) { Toast.makeText(this, "Nothing changed", Toast.LENGTH_SHORT).show(); return }
        if (n.isEmpty() || n.contains("|") || s.isEmpty()) { Toast.makeText(this, "Enter a name without | and the secret", Toast.LENGTH_LONG).show(); return }
        stopService(Intent(this, EarService::class.java))
        Toast.makeText(this, "Restarting EarHop with the new settings", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ if (!EhState.running) toggle() }, 800)
    }

    private fun toggle() {
        if (EhState.running) {
            prefs.edit().putBoolean("keepRunning", false).apply() // stopped on purpose: no restart after an update or a restart
            stopService(Intent(this, EarService::class.java))
            return
        }
        val n = nameField.text.toString().trim()
        if (n.isEmpty() || n.contains("|")) { Toast.makeText(this, "Enter a device name without |", Toast.LENGTH_LONG).show(); showPage("Devices"); return }
        if (secretField.text.isNullOrBlank()) { Toast.makeText(this, "Enter the shared secret from the Mac", Toast.LENGTH_LONG).show(); showPage("Devices"); return }
        if (!required()) {
            Toast.makeText(this, "Allow the permissions first", Toast.LENGTH_LONG).show()
            showPage("Settings")
            scroll.post { scroll.smoothScrollTo(0, (permsCard.parent as View).top) }
            return
        }
        prefs.edit().putString("name", n).putString("secret", secretField.text.toString().trim()).apply()
        try {
            val i = Intent(this, EarService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            EhState.error = "Could not start: ${e.message}"
        }
    }

    // ---------- refresh ----------

    override fun onResume() {
        super.onResume()
        val crash = prefs.getString("last_crash", null)
        if (crash != null) crashCard.visibility = View.VISIBLE
        // Android 13 and later only ask for log access while EarHop is on screen, so restart an unconfirmed log watch now.
        if (EhState.running && ClipSync.rootWatching && ClipSync.watchMode == "log access" && ClipSync.logOk != true) {
            ClipSync.restartLogWatch(applicationContext)
        }
        startIfReady()
        for (key in listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, a11yObserver)
        }
        clipSetupSig = ""
        tick.run()
    }

    /** EarHop should always run: opening the app starts it when it is set up and the permissions are there. */
    private fun startIfReady() {
        if (EhState.running || prefs.getString("secret", "").isNullOrBlank() || !required()) return
        try {
            val i = Intent(this, EarService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) { EhLog.w("auto start failed: ${e.message}") }
    }

    override fun onPause() {
        try { contentResolver.unregisterContentObserver(a11yObserver) } catch (e: Exception) { }
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            try { refresh(); blurSoon() } catch (e: Exception) { EhLog.w("ui refresh ${e.message}") }
            handler.postDelayed(this, 1000)
        }
    }

    private fun logLines(): String {
        val lines = EhLog.dump().lines()
        val keySwitch = listOf(" claim", " release", " pause", " resume", " hold:", " safe", " handoff", " sm:", "earbuds:")
        val keyProblem = listOf("fail", "error", "could not", "refused", "crash", "denied", "not sent", "timeout")
        val f = when (prefs.getString("logFilter", "all")) {
            "switch" -> lines.filter { l -> keySwitch.any { l.contains(it) } }
            "clip" -> lines.filter { it.contains("clip:") }
            "problems" -> lines.filter { l -> keyProblem.any { l.contains(it, ignoreCase = true) } }
            else -> lines
        }
        return f.takeLast(18).joinToString("\n").ifEmpty { "Nothing yet" }
    }

    private fun refresh() {
        val run = EhState.running
        statusChip.text = if (run) "Running" else "Stopped"
        statusChip.background = g.statePill(run)
        if (techText.isShown) techText.text = technical()
        startBtn.text = if (run) "Save" else "Start"

        refreshHistory()
        refreshBuds()
        refreshBattery()
        refreshOnboarding()
        if (AppRules.hasAccess(this) != appAccessShown) { appAccessShown = !appAccessShown; rebuildAppRules() }
        val err = EhState.error
        errorCard.visibility = if (err == null) View.GONE else View.VISIBLE
        errorText.text = err ?: ""

        refreshDashboard(run)
        refreshHealth(run)
        refreshStrip(run)
        refreshPeers(run)
        refreshPerms(run)
        refreshKeep()
        quickTiles.forEach { it() }

        val age = if (EhState.beaconAt == 0L) -1L else (System.currentTimeMillis() - EhState.beaconAt) / 1000
        val buds = Earbuds.isBuds(this)
        gentleRow.visibility = if (buds) View.GONE else View.VISIBLE
        // Plain words here; the raw readings are in Settings, Advanced.
        val earWords = when (EhState.earReading.ear) {
            "both" -> "Both earbuds are in your ears."; "one" -> "One earbud is in your ear."
            "none" -> "The earbuds are out of your ears."; else -> "In-ear status is not known yet."
        }
        earInfo.text = when {
            !run -> "Start EarHop to detect your earbuds."
            buds -> "$earWords Galaxy Buds report this while they are connected to this device." +
                if (EhState.buds.startsWith("refused")) "\nIf it stays unknown, close Galaxy Wearable; switching still works without it." else ""
            age < 0 && EhState.appleSeen == 0 -> "Looking for your AirPods. Keep them close, and check that Location is allowed and switched on."
            age < 0 -> "Apple devices are nearby, but not your AirPods yet. Put the earbuds in your ears and keep them close."
            else -> "$earWords Updated ${age}s ago." +
                if (EhState.aacp == "not supported") "\nOn this phone the second earbud is reported a few seconds late. Both out is always detected at once." else ""
        }
        rootCard?.let {
            val granted = prefs.getBoolean("rootGranted", false)
            rootBtn.text = if (granted) "Root granted (tap to turn off)" else "Grant root access"
            rootUseRow.visibility = if (granted) View.VISIBLE else View.GONE
            rootFirstRow.visibility = if (granted) View.VISIBLE else View.GONE
        }
        linkText.text = if (run) "Working now: ${EhState.transport}" else "Start EarHop to connect"
        linkText.setTextColor(if (run && EhState.transport.contains("nobody")) orange else subC)
        if (Earbuds.isBuds(this) != shownBuds) { recreate(); return }
        val mode = prefs.getString("earbudsMode", "auto") ?: "auto"
        earbudsText.text = "Using ${Earbuds.label(this)}" + if (mode == "auto") ", detected automatically." else ", chosen by hand."

        refreshClipSetup(run)
        refreshRecent()
        val autoWorks = prefs.getBoolean("clipAutoRoot", true) && when (ClipSync.autoMethod(this)) {
            "helper" -> ClipSync.helperRunning()
            "accessibility" -> ClipAccessibilityService.state(this) == "on"
            else -> ClipSync.rootWatching && ClipSync.watchMode == "root"
        }
        val ago = (System.currentTimeMillis() - ClipSync.lastEventAt) / 1000
        val last = if (ClipSync.lastEventAt > 0 && ago < 3600) "${ClipSync.lastEvent}, ${if (ago < 60) "$ago s" else "${ago / 60} min"} ago." else ""
        val clipOn = prefs.getBoolean("clipSync", true)
        val state = when {
            !clipOn -> "Off. Copies stay on this device."
            ClipSync.paused(this) -> "Paused until ${shortTime(prefs.getLong("clipPausedUntil", 0))}."
            !run -> "Starts with EarHop."
            autoWorks -> "Copies here are sent automatically. Copies from your other devices arrive here."
            else -> "Copies from your other devices arrive here. Send yours with Share clipboard."
        }
        clipText.text = if (last.isNotEmpty()) "$last\n$state" else state
        homeClipText.text = if (last.isNotEmpty() && clipOn) "$last\n$state" else state
        homeClipText.setTextColor(if (clipOn && run && !autoWorks && prefs.getBoolean("clipAutoRoot", true)) orange else textC)
        clipMethodText.text = clipMethodWords(ClipSync.method(this))
        syncs.forEach { it() }
        val key = listOf(" claim:", " release:", " pause:", " resume:", " hold:", " safe:", " handoff:")
        handoffView.text = EhLog.dump().lines().filter { l -> key.any { l.contains(it) } }.takeLast(8).joinToString("\n")
            .ifEmpty { "No handoffs yet. Play audio on this device with the ${Earbuds.short(this)} in your ears." }
        logView.text = logLines()
    }
}
