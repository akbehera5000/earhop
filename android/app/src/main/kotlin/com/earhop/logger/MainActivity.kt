package com.earhop.logger

import android.Manifest
import android.app.Activity
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

    // palette: iOS grouped style. System grey (or black) background, white (or dark grey) sections, no borders.
    private val bgC get() = if (night) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val cardC get() = if (night) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val strokeC get() = 0
    private val chipC get() = if (night) 0xFF2C2C2E.toInt() else 0xFFEEEEF3.toInt()
    private val textC get() = if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val subC get() = 0xFF8E8E93.toInt()
    private val sepC get() = if (night) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()
    private val accent get() = when (val a = getSharedPreferences("earhop", MODE_PRIVATE).getString("accent", "Auto")) {
        "Auto", null -> if (Earbuds.isBuds(this)) budsAccent else airpodsAccent
        else -> accents[a]?.takeIf { it != 0 } ?: airpodsAccent
    }
    private val green = 0xFF34C759.toInt()
    private val orange = 0xFFFF9F0A.toInt()
    private val red = 0xFFFF3B30.toInt()

    // pages and the floating tab bar
    private val pages = LinkedHashMap<String, LinearLayout>()
    private val navItems = HashMap<String, Pair<android.widget.ImageView, TextView>>()
    private val tabs = listOf("Home" to R.drawable.ic_nav_home, "Buds" to R.drawable.ic_nav_buds, "Devices" to R.drawable.ic_nav_devices,
        "Settings" to R.drawable.ic_nav_settings, "Activity" to R.drawable.ic_nav_activity)
    private var currentPage = "Home"
    private lateinit var content: LinearLayout
    /** Keep switches and segmented controls in step with the settings; run on every refresh. */
    private val syncs = ArrayList<() -> Unit>()

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusChip: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var errorText: TextView
    private val values = HashMap<String, TextView>()
    private val labels = HashMap<String, String>()
    private lateinit var peersBox: LinearLayout
    private lateinit var permsBox: LinearLayout
    private lateinit var permsCard: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var takeBtn: Button
    private lateinit var releaseBtn: Button
    private lateinit var nameField: EditText
    private lateinit var secretField: EditText
    private lateinit var filterField: EditText
    private lateinit var logView: TextView
    private lateinit var handoffView: TextView
    private lateinit var earInfo: TextView
    private lateinit var gentleRow: View
    private lateinit var homeNoiseCard: LinearLayout
    private lateinit var linkText: TextView
    private lateinit var clipText: TextView
    private lateinit var homeClipText: TextView
    private lateinit var clipMethodText: TextView
    private lateinit var clipRootRow: View
    private lateinit var clipSetup: LinearLayout
    private var clipSetupSig = "init"
    private lateinit var rootBtn: Button
    private lateinit var rootUseRow: View
    private lateinit var rootFirstRow: View
    private lateinit var budsInfo: TextView
    private lateinit var budsCard: LinearLayout
    private var budsSig = ""
    private lateinit var rulesBox: LinearLayout
    private lateinit var onboardCard: LinearLayout
    private var onboardSig = ""
    private lateinit var appRulesBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private var historySig = "init"
    private var appAccessShown = false
    private lateinit var crashCard: LinearLayout
    // dashboard
    private lateinit var heroIcon: android.widget.ImageView
    private lateinit var heroWhere: TextView
    private lateinit var heroState: TextView
    private lateinit var stageBox: LinearLayout
    private lateinit var stageText: TextView
    private val stageBars = ArrayList<View>()
    private lateinit var heroNote: TextView
    private lateinit var chipEar: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipCase: TextView
    private lateinit var chipLink: TextView
    private lateinit var safeBtn: Button
    private lateinit var diagText: TextView
    private lateinit var diagToggle: TextView
    private lateinit var crashText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun pageBackground() = android.graphics.drawable.ColorDrawable(bgC)
    private fun ripple(color: Int, r: Int, stroke: Int = 0): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if (night) 0x55FFFFFF else 0x33000000), round(color, r, stroke), null)
    private val prefs get() = getSharedPreferences("earhop", MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!prefs.getBoolean("onboarded", false) && !prefs.getString("secret", "").isNullOrBlank()) prefs.edit().putBoolean("onboarded", true).apply()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 23 && !night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26 && !night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
        val frame = android.widget.FrameLayout(this).apply { background = pageBackground() }
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(120)) }
        scroll.addView(content)
        frame.addView(scroll)
        for ((name, _) in tabs) {
            val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            if (name != "Home") p.addView(text(name, 34f, textC, true).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(dp(4), dp(8), 0, dp(14))
            })
            pages[name] = p
            content.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // A solid strip behind the status bar, so scrolled content never runs under the clock and icons.
        val scrim = View(this).apply { setBackgroundColor(bgC); elevation = dp(2).toFloat() }
        frame.addView(scrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        val nav = buildNav()
        frame.addView(nav, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        @Suppress("DEPRECATION")
        frame.setOnApplyWindowInsetsListener { _, ins ->
            content.setPadding(dp(16), dp(16) + ins.systemWindowInsetTop, dp(16), dp(120) + ins.systemWindowInsetBottom)
            (nav.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = dp(14) + ins.systemWindowInsetBottom
            scrim.layoutParams = (scrim.layoutParams as android.widget.FrameLayout.LayoutParams).apply { height = ins.systemWindowInsetTop }
            nav.requestLayout()
            ins
        }
        setContentView(frame)
        root = pages.getValue("Home")
        build()
        showPage(prefs.getString("page", "Home") ?: "Home")
    }

    // ---------- building blocks ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** A section body. Hiding it hides its whole section, title included. */
    private class SectionBody(ctx: Context) : LinearLayout(ctx) {
        override fun setVisibility(v: Int) { super.setVisibility(v); (parent as? View)?.visibility = v }
    }

    /**
     * An iOS style grouped section on the current page: a small grey title above a rounded card. Returns the card. Its
     * first child is an invisible anchor, so code that keeps "child 0" while rebuilding a card keeps working.
     */
    private fun card(title: String?): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (title != null) section.addView(text(title.uppercase(), 13f, subC).apply {
            letterSpacing = 0.03f; setPadding(dp(16), dp(6), dp(16), dp(7))
        })
        val c = SectionBody(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cardC, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(View(this).apply { visibility = View.GONE })
        section.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })
        return c
    }

    // ---------- pages and tab bar ----------

    /** The floating pill tab bar at the bottom. */
    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(if (night) 0xFC1C1C1E.toInt() else 0xFCFFFFFF.toInt(), 30, if (night) 0xFF2C2C2E.toInt() else 0xFFE5E5EA.toInt())
            elevation = dp(10).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        for ((name, icon) in tabs) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                contentDescription = name
                setOnClickListener { showPage(name) }
            }
            val img = android.widget.ImageView(this).apply { setImageResource(icon) }
            item.addView(img, LinearLayout.LayoutParams(dp(24), dp(24)))
            val lab = text(name, 10.5f, subC).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0); maxLines = 1 }
            item.addView(lab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            item.minimumWidth = dp(58)
            navItems[name] = Pair(img, lab)
            bar.addView(item)
        }
        return bar
    }

    private fun showPage(name: String) {
        val target = if (pages.containsKey(name)) name else "Home"
        currentPage = target
        pages.forEach { (n, p) -> p.visibility = if (n == target) View.VISIBLE else View.GONE }
        navItems.forEach { (n, v) ->
            val on = n == target
            v.first.setColorFilter(if (on) accent else subC)
            v.second.setTextColor(if (on) accent else subC)
            (v.first.parent as View).background = if (on) round((accent and 0x00FFFFFF) or 0x22000000, 22) else null
        }
        scroll.scrollTo(0, 0)
        prefs.edit().putString("page", target).apply()
    }

    // ---------- controls ----------

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(sepC) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10); bottomMargin = dp(10) })
    }

    /** An iOS style switch row: title, optional detail, switch on the right. Returns the row. */
    private fun switchRow(parent: LinearLayout, title: String, sub: String?, get: () -> Boolean, track: Boolean = true, set: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, textC))
        if (sub != null) texts.addView(text(sub, 12.5f, subC).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        val sw = android.widget.Switch(this).apply {
            isChecked = get()
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(green, if (night) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()))
            contentDescription = title
            setOnClickListener { set(isChecked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle(); set(sw.isChecked) }
        parent.addView(row)
        // Rows in parts that are rebuilt on every change (rules, Buds controls) do not need syncing.
        if (track) syncs += { val v = get(); if (sw.isChecked != v) sw.isChecked = v }
        return row
    }

    /** Pause buttons (15 min, 1 hour, until tomorrow 7:00) for a pref holding an end time; while paused: the end and Resume. */
    private fun pauseRow(parent: LinearLayout, key: String) {
        parent.addView(text("Pause", 13f, subC).apply { setPadding(dp(2), dp(6), 0, dp(2)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        parent.addView(box)
        var sig = ""
        lateinit var render: () -> Unit
        fun setUntil(t: Long) { prefs.edit().putLong(key, t).apply(); render(); refresh() }
        render = {
            val until = prefs.getLong(key, 0)
            val on = until > System.currentTimeMillis()
            val now = "$on$until"
            if (now != sig) {
                sig = now
                box.removeAllViews()
                if (on) {
                    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
                    box.addView(text("Paused until $at", 15f, orange, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    box.addView(button("Resume", true) { setUntil(0) })
                } else {
                    val tomorrow = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 7)
                        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                    }.timeInMillis
                    listOf("15 min" to 15 * 60_000L, "1 hour" to 3_600_000L, "Tomorrow" to -1L).forEachIndexed { i, (label, ms) ->
                        box.addView(button(label, false) { setUntil(if (ms < 0) tomorrow else System.currentTimeMillis() + ms) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < 2) rightMargin = dp(6) })
                    }
                }
            }
        }
        syncs += render
        render()
    }

    /** An iOS style segmented control. Returns the control. */
    private fun segmented(parent: LinearLayout, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(chipC, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val cells = options.map { (value, label) ->
            text(label, 14f, textC).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { set(value); paintSeg(bar, options, get()) }
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
            val cell = bar.getChildAt(i) as TextView
            val on = options[i].first == value
            cell.background = if (on) round(if (night) 0xFF636366.toInt() else Color.WHITE, 9).apply { } else null
            cell.elevation = if (on) dp(1).toFloat() else 0f
            cell.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
            cell.contentDescription = options[i].second + if (on) ", selected" else ""
        }
    }

    private fun row(parent: LinearLayout, key: String, label: String): TextView {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(text(label, 16f, textC), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text("-", 16f, subC, true).apply { gravity = Gravity.END }
        r.addView(v)
        parent.addView(r)
        values[key] = v
        labels[key] = label
        return v
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (filled) Color.WHITE else accent)
        // Filled: solid accent. Plain: the accent as a soft tint, like iOS tinted buttons.
        background = ripple(if (filled) accent else ((accent and 0x00FFFFFF) or (if (night) 0x33000000 else 0x1F000000)), 14)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { onClick() }
    }

    private fun field(parent: LinearLayout, label: String, value: String, pw: Boolean = false): EditText {
        parent.addView(text(label, 13f, subC).apply { setPadding(0, dp(8), 0, dp(2)) })
        val e = EditText(this).apply {
            setText(value)
            setTextColor(textC)
            textSize = 16f
            background = round(chipC, 16)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setSingleLine()
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    // ---------- screen ----------

    private fun build() {
        // ================= Home =================
        root = pages.getValue("Home")
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), 0, 0) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("EarHop", 34f, textC, true).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        titles.addView(text("Your ${Earbuds.short(this)} follow you between devices", 14f, subC))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = text("Stopped", 13f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { toggle() }
            contentDescription = "EarHop service, tap to start or stop"
        }
        head.addView(statusChip)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        onboardCard = card("Set up EarHop")
        onboardCard.visibility = View.GONE

        // crash from the previous run, if any
        crashCard = card("The app closed unexpectedly last time")
        crashText = text("", 11f, subC).apply { typeface = Typeface.MONOSPACE }
        crashCard.addView(crashText)
        val crashRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        crashRow.addView(button("Copy", false) { copy("EarHop crash", prefs.getString("last_crash", "") ?: "") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        crashRow.addView(button("Dismiss", false) { prefs.edit().remove("last_crash").apply(); crashCard.visibility = View.GONE }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        crashCard.addView(crashRow, lp())
        crashCard.visibility = View.GONE

        errorCard = card(null)
        errorText = text("", 14f, red)
        errorCard.addView(errorText)
        errorCard.visibility = View.GONE

        buildDashboard()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        takeBtn = button("Take ${Earbuds.short(this)}", true) { quick(true) }.apply { minHeight = dp(54); textSize = 17f }
        releaseBtn = button("Release", false) { quick(false) }.apply { minHeight = dp(54); textSize = 17f }
        actions.addView(takeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { rightMargin = dp(10) })
        actions.addView(releaseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })

        // Quick noise control, shown while Galaxy Buds are connected to this device.
        homeNoiseCard = card("Noise control")
        segmented(homeNoiseCard, listOf("off" to "Off", "anc" to "Noise cancelling", "ambient" to "Ambient"),
            { EhState.budsClient?.takeIf { it.connected }?.snapshot()?.noise ?: "" }) { m -> EhState.budsClient?.setNoise(m) }
        homeNoiseCard.visibility = View.GONE

        // Earbud switching on Home: automatic on or off, and a pause (Take and Release always work).
        val homeSwitch = card("Earbud switching")
        switchRow(homeSwitch, "Switch automatically", "Take the ${Earbuds.short(this)} when this device starts playing",
            { prefs.getBoolean("auto", true) }) { prefs.edit().putBoolean("auto", it).apply(); refresh() }
        pauseRow(homeSwitch, "autoPausedUntil")

        // Clipboard on Home: on or off, the method, what happened last, Pause, Send now.
        val homeClip = card("Clipboard")
        switchRow(homeClip, "Sync clipboard", null, { prefs.getBoolean("clipSync", true) }) { setClipSync(it) }
        segmented(homeClip, clipMethods, { ClipSync.method(this) }) { v -> prefs.edit().putString("clipTransport", v).apply(); refresh() }
        homeClipText = text("", 12.5f, subC).apply { setPadding(dp(2), dp(6), 0, 0) }
        homeClip.addView(homeClipText)
        pauseRow(homeClip, "clipPausedUntil")
        homeClip.addView(button("Send clipboard now", false) { startActivity(Intent(this, ClipActivity::class.java)) }, lp(10))

        val histCard = card("Recent switches")
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histCard.addView(historyBox)

        // ================= Buds =================
        root = pages.getValue("Buds")
        val eb = card("Earbuds")
        segmented(eb, listOf("airpods" to "AirPods Pro", "buds" to "Galaxy Buds2 Pro"), { if (Earbuds.isBuds(this)) "buds" else "airpods" }) { v ->
            val buds = v == "buds"
            if (Earbuds.isBuds(this) != buds) {
                Earbuds.setBuds(this, buds)
                if (EhState.running) {
                    stopService(Intent(this, EarService::class.java))
                    handler.postDelayed({ try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, EarService::class.java)) else startService(Intent(this, EarService::class.java)) } catch (e: Exception) { } }, 800)
                }
                recreate()
            }
        }
        budsInfo = text("", 12.5f, subC).apply { setPadding(dp(2), dp(8), 0, 0) }
        eb.addView(budsInfo)

        budsCard = card("Galaxy Buds controls")
        budsCard.visibility = if (Earbuds.isBuds(this)) View.VISIBLE else View.GONE

        val earCard = card("Earbud detection")
        earInfo = text("", 13f, subC)
        earCard.addView(earInfo)
        gentleRow = switchRow(earCard, "Gentle scanning while playing", "Less crackle on some phones, slower ear detection",
            { prefs.getBoolean("gentle", false) }) { prefs.edit().putBoolean("gentle", it).apply() }
        earCard.addView(button("Test pause now", false) { sendAction(EarService.ACTION_TEST_PAUSE) }, lp(10))

        // ================= Devices =================
        root = pages.getValue("Devices")
        val conn = card("Earbud switching link")
        conn.addView(text("How this device tells your Mac and tablet where the earbuds are", 13f, subC).apply { setPadding(dp(2), 0, 0, dp(6)) })
        segmented(conn, listOf("ble" to "Bluetooth", "both" to "Both", "wifi" to "Wi-Fi"), { prefs.getString("transport", "both") ?: "both" }) { v ->
            prefs.edit().putString("transport", v).apply()
        }
        linkText = text("", 13f, subC).apply { setPadding(dp(2), dp(8), 0, 0) }
        conn.addView(linkText)

        val clip = card("Clipboard")
        switchRow(clip, "Sync clipboard", "Copy here, paste on your other devices, and the other way round",
            { prefs.getBoolean("clipSync", true) }) { setClipSync(it) }
        divider(clip)
        clip.addView(text("Method", 13f, subC).apply { setPadding(dp(2), 0, 0, dp(2)) })
        segmented(clip, clipMethods, { ClipSync.method(this) }) { v -> prefs.edit().putString("clipTransport", v).apply(); refresh() }
        clipMethodText = text("", 12.5f, subC).apply { setPadding(dp(2), dp(4), 0, 0) }
        clip.addView(clipMethodText)
        divider(clip)
        clipRootRow = switchRow(clip, "Send copies automatically",
            if (RootShell.hasSu()) "Uses root to notice copies, because Android 10 and later block this for normal apps"
            else "Android blocks this for normal apps; it works with the clipboard helper below",
            { prefs.getBoolean("clipAutoRoot", true) }) { on ->
            prefs.edit().putBoolean("clipAutoRoot", on).apply()
            if (!on) { ClipSync.stopRootWatch(); refresh(); return@switchRow }
            if (!RootShell.hasSu()) { if (EhState.running) ClipSync.tryStartAuto(applicationContext); refresh(); return@switchRow }
            Thread {
                val ok = prefs.getBoolean("rootGranted", false) || RootShell.check().also { if (it) prefs.edit().putBoolean("rootGranted", true).apply() }
                runOnUiThread {
                    if (ok && EhState.running) ClipSync.tryStartAuto(applicationContext)
                    if (!ok) Toast.makeText(this, "Root was not granted", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }.start()
        }
        clipSetup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clip.addView(clipSetup)
        divider(clip)
        pauseRow(clip, "clipPausedUntil")
        divider(clip)
        switchRow(clip, "Receive clips", "Put copies from your other devices on this clipboard",
            { prefs.getBoolean("clipReceive", true) }) { prefs.edit().putBoolean("clipReceive", it).apply() }
        divider(clip)
        switchRow(clip, "Include images", "Images always go over Wi-Fi",
            { prefs.getBoolean("clipImages", true) }) { prefs.edit().putBoolean("clipImages", it).apply() }
        divider(clip)
        clip.addView(text("Send to", 13f, subC).apply { setPadding(dp(2), 0, 0, dp(2)) })
        val self = prefs.getString("name", "phone") ?: "phone"
        for (dev in listOf("mac", "phone", "tab").filter { it != self }) {
            switchRow(clip, dev.replaceFirstChar { it.uppercase() }, null, { dev !in ClipSync.excluded(this) }) { on ->
                val now = ClipSync.excluded(this).toMutableSet()
                if (on) now.remove(dev) else now.add(dev)
                prefs.edit().putStringSet("clipExclude", now).apply()
            }
        }
        clip.addView(button("Send clipboard now", true) {
            startActivity(Intent(this, ClipActivity::class.java))
        }, lp(12))
        clipText = text("", 12.5f, subC).apply { setPadding(dp(2), dp(10), 0, 0) }
        clip.addView(clipText)

        val peers = card("Other devices")
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peers.addView(peersBox)

        val setup = card("This device")
        val pairedSecret = intent?.data?.takeIf { it.scheme == "earhop" }?.getQueryParameter("secret")
        // Tablets default to "tab" so a phone and a tablet never share the default name.
        val defaultName = if (resources.configuration.smallestScreenWidthDp >= 600) "tab" else "phone"
        nameField = field(setup, "Name (each device needs its own: mac, phone, tab)", prefs.getString("name", defaultName) ?: defaultName)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        chips.addView(button("phone", false) { nameField.setText("phone") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        chips.addView(button("tab", false) { nameField.setText("tab") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setup.addView(chips)
        secretField = field(setup, "Shared secret (Mac: Settings, Network)", pairedSecret ?: prefs.getString("secret", "") ?: "", true)
        setup.addView(button("Paste secret", false) { paste() }, lp(8))
        filterField = field(setup, "${Earbuds.label(this)} name contains", Earbuds.filter(this))
        startBtn = button("Start", true) { toggle() }
        setup.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        val tiles = card("Quick settings and widget")
        tiles.addView(text("Add the Take, Release, Auto switching and Noise control tiles to quick settings, or the EarHop widget to your home screen.", 13f, subC))
        val tileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tileRow.addView(button("Add tiles", false) { addTiles() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        tileRow.addView(button("Add widget", false) { addWidget() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tiles.addView(tileRow, lp(10))

        // ================= Settings =================
        root = pages.getValue("Settings")
        val ho = card("Automatic switching")
        switchRow(ho, "Switch automatically", "Take the earbuds when this device starts playing",
            { prefs.getBoolean("auto", true) }) { prefs.edit().putBoolean("auto", it).apply() }
        divider(ho)
        switchRow(ho, "Pause when one earbud is out", "Otherwise only when both are out",
            { prefs.getBoolean("pauseOnOne", true) }) { prefs.edit().putBoolean("pauseOnOne", it).apply() }
        divider(ho)
        pauseRow(ho, "autoPausedUntil")

        val rootCard = card("Root (optional)")
        rootBtn = button("", false) {
            if (!RootShell.hasSu()) return@button
            if (!prefs.getBoolean("rootGranted", false)) {
                Toast.makeText(this, "Asking for root... allow it in your root manager", Toast.LENGTH_LONG).show()
                Thread {
                    val ok = RootShell.check()
                    if (ok) prefs.edit().putBoolean("rootGranted", true).putBoolean("useRoot", true).apply()
                    runOnUiThread { refresh() }
                }.start()
            } else {
                prefs.edit().putBoolean("rootGranted", false).putBoolean("useRoot", false).putBoolean("rootFirst", false).apply(); refresh()
            }
        }
        rootCard.addView(rootBtn)
        rootUseRow = switchRow(rootCard, "Use root when blocked", "Only if the normal Bluetooth connect is refused",
            { prefs.getBoolean("useRoot", true) }) { prefs.edit().putBoolean("useRoot", it).apply() }
        rootFirstRow = switchRow(rootCard, "Always use root first", "Most reliable on the rooted tablet",
            { prefs.getBoolean("rootFirst", false) }) { prefs.edit().putBoolean("rootFirst", it).apply() }

        val rulesCard = card("Switching rules")
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rulesCard.addView(rulesBox)
        rebuildRules()

        val appCard = card("App rules")
        appRulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appCard.addView(appRulesBox)
        rebuildAppRules()

        val look = card("Appearance")
        look.addView(text("Theme", 13f, subC).apply { setPadding(dp(2), 0, 0, dp(4)) })
        segmented(look, listOf("system" to "Automatic", "light" to "Light", "dark" to "Dark"), { prefs.getString("theme", "system") ?: "system" }) { v ->
            if (v != prefs.getString("theme", "system")) { prefs.edit().putString("theme", v).apply(); recreate() }
        }
        look.addView(text("Accent colour", 13f, subC).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        accents.forEach { (name, col) ->
            val chosen = (prefs.getString("accent", "Auto") ?: "Auto") == name
            val v = View(this).apply {
                background = (if (name == "Auto") GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(airpodsAccent, budsAccent))
                    else GradientDrawable()).apply { shape = GradientDrawable.OVAL; if (name != "Auto") setColor(col); if (chosen) setStroke(dp(3), textC) }
                contentDescription = (if (name == "Auto") "Accent follows the earbuds" else "$name accent") + if (chosen) ", selected" else ""
                setOnClickListener { prefs.edit().putString("accent", name).apply(); recreate() }
            }
            sw.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })
        }
        look.addView(sw, lp(4))

        permsCard = card("Permissions")
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permsCard.addView(permsBox)

        // ================= Activity =================
        root = pages.getValue("Activity")
        val dec = card("Recent decisions")
        handoffView = text("", 12f, subC).apply { typeface = Typeface.MONOSPACE }
        dec.addView(handoffView)

        val log = card("Log")
        logView = text("", 11f, subC).apply { typeface = Typeface.MONOSPACE }
        log.addView(logView)
        log.addView(button("Copy log", false) { copy("EarHop log", EhLog.dumpAll()) }, lp(10))
        root = pages.getValue("Home")
    }

    // ---------- dashboard ----------

    private fun chip(): TextView = text("", 13f, textC, true).apply {
        background = round(chipC, 16)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /** The main card: which earbuds, where they are, what EarHop is doing, and why. */
    private fun buildDashboard() {
        val c = card(null)
        c.background = round(cardC, 24)
        c.setPadding(dp(20), dp(20), dp(20), dp(16))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_earhop)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(heroIcon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(16) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(if (Earbuds.isBuds(this)) "Galaxy Buds2 Pro" else "AirPods Pro 2", 17f, textC, true))
        heroWhere = text("", 14f, subC)
        words.addView(heroWhere)
        heroState = text("", 30f, accent, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        words.addView(heroState)
        top.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(top)

        stageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0); visibility = View.GONE }
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(4) { i ->
            val v = View(this)
            stageBars += v
            bars.addView(v, LinearLayout.LayoutParams(0, dp(5), 1f).apply { if (i < 3) rightMargin = dp(5) })
        }
        stageBox.addView(bars)
        stageText = text("", 13f, subC).apply { setPadding(0, dp(6), 0, 0) }
        stageBox.addView(stageText)
        c.addView(stageBox)

        heroNote = text("", 13f, subC).apply { setPadding(0, dp(12), 0, 0); visibility = View.GONE }
        c.addView(heroNote)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        chipEar = chip(); chipBattery = chip(); chipCase = chip(); chipLink = chip()
        for (ch in listOf(chipEar, chipBattery, chipCase, chipLink)) chips.addView(ch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        c.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        safeBtn = button("Resume automatic switching", true) {
            EhState.controller?.clearSafeMode(); refresh()
        }.apply { visibility = View.GONE }
        c.addView(safeBtn, lp(12))

        diagToggle = text("Diagnostics  \u25B8", 13f, subC, true).apply {
            setPadding(0, dp(14), 0, 0)
            setOnClickListener {
                val open = diagText.visibility != View.VISIBLE
                diagText.visibility = if (open) View.VISIBLE else View.GONE
                this.text = if (open) "Diagnostics  \u25BE" else "Diagnostics  \u25B8"
            }
        }
        c.addView(diagToggle)
        diagText = text("", 12f, subC).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        c.addView(diagText)
    }

    private fun pretty(n: String) = when (n) { "mac" -> "Mac"; "phone" -> "Phone"; "tab" -> "Tablet"; else -> n }

    private fun refreshDashboard(run: Boolean) {
        val now = System.currentTimeMillis()
        val holder = EhState.peers.values.firstOrNull { it.owner && now - it.seenAt < 5000 }?.name
        val stage = EhState.stage
        val phase = EhState.phase
        val switching = run && (stage != null || phase == HandoffPhase.CLAIM_PENDING || phase == HandoffPhase.RELEASE_PENDING)
        heroIcon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (EhState.owner) accent else chipC) }
        heroIcon.setColorFilter(if (EhState.owner) Color.WHITE else subC)
        heroWhere.text = when {
            !run -> "EarHop is stopped"
            EhState.owner -> "Connected to this ${pretty(prefs.getString("name", "phone") ?: "phone").lowercase()}"
            holder != null -> "Connected to ${pretty(holder)}"
            else -> "Not connected"
        }
        val word = when {
            !run -> "Stopped"
            switching -> "Switching"
            EhState.safeMode -> "Safe mode"
            EhState.call -> "Call"
            EhState.owner && EhState.playing -> "Playing"
            EhState.owner && phase == HandoffPhase.OWNER_PAUSED_BY_EAR_REMOVAL -> "Paused"
            EhState.owner -> "Ready"
            EhState.playing -> "Playing here"
            else -> if (holder == null) "Idle" else "Ready"
        }
        heroState.text = word
        heroState.setTextColor(if (EhState.safeMode) orange else if (EhState.owner || switching) accent else textC)

        stageBox.visibility = if (switching) View.VISIBLE else View.GONE
        if (switching) {
            val s = stage ?: ""
            val cur = when {
                s.startsWith("Confirming") -> 3
                s.startsWith("Connecting") -> 2
                s.startsWith("Waiting") || s.startsWith("Releasing") -> 1
                else -> 0
            }
            stageBars.forEachIndexed { i, v -> v.background = round(if (i <= cur) accent else chipC, 3) }
            stageText.text = stage ?: phase.label
        }

        val failure = EhState.lastFailure
        val hold = EhState.holdReason
        heroNote.visibility = View.VISIBLE
        val bad = EhState.rejected.entries.firstOrNull()
        val dup = EhState.duplicates.entries.firstOrNull { now - it.value < 180000 }?.key
        when {
            !run -> heroNote.visibility = View.GONE
            dup != null -> {
                heroNote.text = "Two devices are both named $dup, so EarHop mixes them up. Give each its own name below (phone and tab), then Stop and Start."
                heroNote.setTextColor(red)
            }
            bad != null -> {
                heroNote.text = if (bad.value == "clock") "${pretty(bad.key)} is ignored: its clock differs by more than 30 seconds. Set both to automatic time."
                    else "${pretty(bad.key)} uses a different secret, so it is ignored. Scan the QR code on the Mac (Settings, Network) again."
                heroNote.setTextColor(orange)
            }
            failure != null && !switching -> { heroNote.text = "Last switch failed: $failure."; heroNote.setTextColor(orange) }
            hold != null && !EhState.owner && !switching -> { heroNote.text = "Not switching: $hold."; heroNote.setTextColor(subC) }
            else -> heroNote.visibility = View.GONE
        }
        safeBtn.visibility = if (run && EhState.safeMode) View.VISIBLE else View.GONE

        val ear = EhState.ear
        chipEar.text = when (ear) { "both" -> "Both in ears"; "one" -> "One in ear"; "none" -> "Out of ears"; else -> "Ears unknown" }
        chipEar.setTextColor(if (ear == "none") orange else textC)
        val fresh = now - EhState.batAt < 60000
        val low = minOf(EhState.batA.takeIf { it >= 0 } ?: 100, EhState.batB.takeIf { it >= 0 } ?: 100) <= 20
        chipBattery.visibility = if (fresh && (EhState.batA >= 0 || EhState.batB >= 0)) View.VISIBLE else View.GONE
        chipBattery.text = "L ${if (EhState.batA >= 0) "${EhState.batA}%" else "-"}  R ${if (EhState.batB >= 0) "${EhState.batB}%" else "-"}"
        chipBattery.setTextColor(if (low) red else textC)
        chipCase.visibility = if (fresh && EhState.batCase >= 0) View.VISIBLE else View.GONE
        chipCase.text = "Case ${EhState.batCase}%"
        chipLink.visibility = if (run) View.VISIBLE else View.GONE
        chipLink.text = EhState.transport
        chipLink.setTextColor(if (EhState.transport.contains("nobody")) orange else textC)

        val r = EhState.earReading
        diagText.text = "state   ${phase.label}\n" +
            "ears    ${r.ear} via ${r.source} (${r.confidence} confidence)\n" +
            "audio   ${if (EhState.playing) "playing" else "idle"}${if (EhState.call) ", call" else ""}\n" +
            "link    ${EhState.transport}\n" +
            (if (Earbuds.isBuds(this)) "buds    ${EhState.buds}\n" else "beacon  ${EhState.beaconInfo.ifEmpty { "none yet" }}\n") +
            "auto    ${if (prefs.getBoolean("auto", true)) "on" else "off"}"
        takeBtn.isEnabled = !EhState.owner && !switching
        takeBtn.alpha = if (takeBtn.isEnabled) 1f else 0.5f
        takeBtn.text = if (EhState.owner) "Connected here" else "Take ${Earbuds.short(this)}"
        releaseBtn.isEnabled = EhState.owner && !switching
        releaseBtn.alpha = if (releaseBtn.isEnabled) 1f else 0.5f
    }

    // ---------- clipboard setup (without root) ----------

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

    /** Copy to run on the Mac with the phone connected: starts the clipboard helper as the shell user. */
    private fun helperCommand() = "adb shell 'kill \$(pidof earhop-clip) 2>/dev/null; CLASSPATH=\$(pm path $packageName | cut -d: -f2) " +
        "setsid nohup app_process /system/bin --nice-name=earhop-clip com.earhop.logger.ClipHelper >/dev/null 2>&1 </dev/null &'"

    /**
     * Without root, a copy here is noticed by the clipboard helper (started once from a computer, see ClipHelper).
     * Shows that one-time step, or that the helper runs.
     */
    private fun refreshClipSetup(run: Boolean) {
        val want = prefs.getBoolean("clipAutoRoot", true) && prefs.getBoolean("clipSync", true)
        val root = RootShell.hasSu()
        val helper = ClipSync.helperRunning()
        val logWorks = ClipSync.rootWatching && ClipSync.watchMode == "log access" && ClipSync.logOk == true
        val sig = "$want$root$helper$logWorks$run"
        if (sig == clipSetupSig) return
        clipSetupSig = sig
        clipSetup.removeAllViews()
        if (!want || root) return
        fun step(done: Boolean, title: String, body: String, action: String?, act: (() -> Unit)?) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
            r.addView(text("${if (done) "\u2713" else "\u2022"}  $title", 15f, if (done) green else textC, true))
            if (!done) {
                r.addView(text(body, 12.5f, subC).apply { setPadding(dp(18), dp(2), 0, 0); setTextIsSelectable(true) })
                if (action != null && act != null) r.addView(button(action, false) { act() }, lp(6))
            }
            clipSetup.addView(r)
        }
        if (logWorks) return
        if (helper) { step(true, "Clipboard helper running", "", null, null); return }
        step(false, "Clipboard helper (once, from your Mac)",
            "Android does not tell apps when you copy, so a small helper started from a computer does. Connect this phone " +
            "to your Mac with USB debugging on and run tools/phone_clip.sh from the EarHop folder, or this command:\n" +
            "${helperCommand()}\nIt keeps running when you unplug the cable. Run it again after the phone restarts." +
            if (!run) "\nStart EarHop first." else "",
            "Copy the command") { copy("EarHop command", helperCommand()) }
    }

    // ---------- Galaxy Buds controls ----------

    private fun placeWord(p: String, charging: Boolean) = when (p) {
        "wearing" -> "in ear"; "idle" -> "out"; "case" -> if (charging) "charging" else "in case"; "disconnected" -> "off"; else -> "?"
    }

    private fun refreshBuds() {
        if (!Earbuds.isBuds(this)) { budsCard.visibility = View.GONE; return }
        budsCard.visibility = View.VISIBLE
        val c = EhState.budsClient
        val live = c != null && c.connected
        val sn = if (live) c!!.snapshot() else null
        val sig = if (sn == null) "off${EhState.buds}${c?.findMessage}" else with(sn) {
            "$battL$battR$battCase$placeL$placeR$noise$eq$spatial$touchOn$seamless${c!!.findActive}${c.findMessage}$muteL$muteR"
        }
        if (sig == budsSig) return
        budsSig = sig
        while (budsCard.childCount > 1) budsCard.removeViewAt(1)
        if (sn == null || c == null) {
            budsCard.addView(text("Take the Buds to this device and start EarHop to control them. Wear channel: ${EhState.buds}. Noise control, equalizer, battery and Find only work on the device that holds the Buds.", 13f, subC))
            return
        }
        fun bat(v: Int) = if (v >= 0) "$v%" else "-"
        // Battery: three tiles, like the iOS battery view.
        val tilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, v, where) in listOf(Triple("Left", sn.battL, placeWord(sn.placeL, sn.chargeL)),
                Triple("Right", sn.battR, placeWord(sn.placeR, sn.chargeR)), Triple("Case", sn.battCase, if (sn.chargeCase) "charging" else ""))) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = round(chipC, 14); setPadding(dp(6), dp(10), dp(6), dp(10))
                contentDescription = "$name ${bat(v)} $where"
            }
            t.addView(text(bat(v), 22f, if (v in 0..20) red else textC, true).apply { gravity = Gravity.CENTER })
            t.addView(text(if (where.isEmpty()) name else "$name \u00B7 $where", 12f, subC).apply { gravity = Gravity.CENTER })
            tilesRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (name != "Case") rightMargin = dp(8) })
        }
        budsCard.addView(tilesRow)

        fun pill(label: String, on: Boolean, desc: String, act: () -> Unit) = button(label, on) { act(); budsSig = "" }.apply {
            contentDescription = "$desc${if (on) ", selected" else ""}"
        }
        fun rowOf(vararg btns: Button) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.forEachIndexed { i, b -> r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(6) }) }
            budsCard.addView(r, lp(8))
        }

        budsCard.addView(text("Noise control", 13f, subC).apply { setPadding(0, dp(12), 0, 0) })
        rowOf(pill("Off", sn.noise == "off", "Noise control off") { c.setNoise("off") },
            pill("ANC", sn.noise == "anc", "Active noise cancelling") { c.setNoise("anc") },
            pill("Ambient", sn.noise == "ambient", "Ambient sound") { c.setNoise("ambient") })
        budsCard.addView(text("Noise cancelling needs both Buds in your ears, unless Noise controls with one earbud is on in Galaxy Wearable.", 11f, subC))

        budsCard.addView(text("Equalizer${if (sn.eq == "custom") " (custom curve set in Galaxy Wearable)" else ""}", 13f, subC).apply { setPadding(0, dp(12), 0, 0) })
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

        budsCard.addView(text("Find my Buds", 13f, subC).apply { setPadding(0, dp(12), 0, 0) })
        if (c.findActive) budsCard.addView(pill("Stop ringing", true, "Stop ringing the Buds") { c.findStop("Search stopped") }, lp(8))
        else budsCard.addView(pill("Ring the Buds", false, "Ring the Buds") { c.findStart() }, lp(8))
        if (c.findMessage.isNotEmpty()) budsCard.addView(text(c.findMessage, 12f, subC))
        budsCard.addView(text("Rings quietly then loud. Refused while a bud may be in your ear, and it stops itself after a minute.", 11f, subC))
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
            onboardCard.addView(text("${if (done) "\u2713" else "$n."}  $title", 16f, if (done) green else textC, true).apply { setPadding(0, dp(8), 0, 0) })
            onboardCard.addView(text(body, 13f, subC))
            if (!done && action != null && act != null) onboardCard.addView(button(action, n == 1 || true) { act() }, lp(6))
        }
        step(1, hasSecret, "Pair with your Mac", "On the Mac open Settings, Network, and scan the QR code with this phone camera. It opens EarHop with the secret filled in. Or paste the secret.", "Paste secret") { paste(); onboardSig = "" }
        step(2, perms, "Allow permissions", "EarHop needs Nearby devices and Location to find your earbuds.", "Allow") {
            val first = checks().firstOrNull { !it.ok }
            if (first != null) first.fix()
        }
        step(3, owner, "Test a handoff", if (!run) "Press Start at the bottom, put the earbuds in, then press Take ${Earbuds.short(this)}." else "Press Take ${Earbuds.short(this)}. The earbuds should connect to this device.",
            if (run) "Take ${Earbuds.short(this)}" else null, if (run) ({ quick(true) }) else null)
        onboardCard.addView(button(if (hasSecret && perms && owner) "Finish" else "Skip for now", false) {
            prefs.edit().putBoolean("onboarded", true).apply(); onboardCard.visibility = View.GONE
        }, lp(12))
    }

    // ---------- switching rules ----------

    private val full = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun onOff(label: String, on: Boolean) = "$label: ${if (on) "ON" else "OFF"}"
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
            r.addView(text("${i + 1}.  ${pretty(name).replaceFirstChar { it.uppercase() }}", 16f, textC), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val up = button("\u2191", false) {
                val l = order.toMutableList(); java.util.Collections.swap(l, i, i - 1); RuleStore.setPriority(this, l); rebuildRules()
            }.apply { isEnabled = i > 0; alpha = if (i > 0) 1f else 0.4f; contentDescription = "Move $name up" }
            val down = button("\u2193", false) {
                val l = order.toMutableList(); java.util.Collections.swap(l, i, i + 1); RuleStore.setPriority(this, l); rebuildRules()
            }.apply { isEnabled = i < order.size - 1; alpha = if (i < order.size - 1) 1f else 0.4f; contentDescription = "Move $name down" }
            for (bt in listOf(up, down)) { bt.minWidth = 0; bt.minimumWidth = 0; bt.minHeight = 0; bt.minimumHeight = 0; bt.setPadding(0, 0, 0, 0) }
            r.addView(up, LinearLayout.LayoutParams(dp(40), dp(36)))
            r.addView(down, LinearLayout.LayoutParams(dp(40), dp(36)).apply { leftMargin = dp(6) })
            box.addView(r, lp(4))
        }

        flag(box, "Quiet hours (no auto claiming)", "quietOn") { rebuildRules() }
        if (prefs.getBoolean("quietOn", false)) {
            val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun timeBtn(label: String, key: String, def: Int) = button("$label ${fmt(prefs.getInt(key, def))}", false) {
                val cur = prefs.getInt(key, def)
                android.app.TimePickerDialog(this, { _, h, m -> prefs.edit().putInt(key, h * 60 + m).apply(); rebuildRules() }, cur / 60, cur % 60, true).show()
            }
            tr.addView(timeBtn("From", "quietStart", 22 * 60), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            tr.addView(timeBtn("Until", "quietEnd", 7 * 60), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(tr, lp())
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
        box.addView(text("These rules stop automatic claiming only. Take and the tiles always work. Each device keeps its own settings.", 12f, subC).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun appLabel(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    private fun pickApps(title: String, key: String) {
        val pm = packageManager
        val launch = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val saved = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(launch.size) { launch[it].first in saved }
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(launch.map { it.second }.toTypedArray(), checked) { _, i, on -> if (on) saved.add(launch[i].first) else saved.remove(launch[i].first) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet(key, saved).apply(); rebuildAppRules() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun rebuildAppRules() {
        val box = appRulesBox
        box.removeAllViews()
        val access = AppRules.hasAccess(this)
        box.addView(text(if (access) "Notification access is on, so EarHop can tell which app is playing."
            else "To tell which app is playing, EarHop needs notification access. It never reads your notifications.", 13f, subC))
        if (!access) box.addView(button("Allow notification access", false) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) { }
        }, lp())
        val ig = prefs.getStringSet("ignoredApps", emptySet()) ?: emptySet()
        val pf = prefs.getStringSet("preferredApps", emptySet()) ?: emptySet()
        box.addView(button("Ignored apps: ${if (ig.isEmpty()) "none" else ig.joinToString { appLabel(it) }}", false) { pickApps("Never claim for these apps", "ignoredApps") }.apply { isEnabled = access; alpha = if (access) 1f else 0.4f }, lp())
        box.addView(button("Preferred apps: ${if (pf.isEmpty()) "none" else pf.joinToString { appLabel(it) }}", false) { pickApps("Claim at once for these apps", "preferredApps") }.apply { isEnabled = access; alpha = if (access) 1f else 0.4f }, lp())
        box.addView(text("Ignored apps never start a switch, good for notification sounds. Preferred apps claim the earbuds without the usual short wait.", 12f, subC).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun refreshHistory() {
        val list = EhState.history.toList()
        val sig = list.joinToString("|") { "${it.at}${it.kind}" }
        if (sig == historySig) return
        historySig = sig
        historyBox.removeAllViews()
        if (list.isEmpty()) { historyBox.addView(text("Nothing yet. Switches appear here with the reason.", 13f, subC)); return }
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        list.take(8).forEach {
            historyBox.addView(text("${f.format(java.util.Date(it.at))}   ${if (it.kind == "claim") "Took ${Earbuds.short(this)}" else "Released"}   (${it.reason})", 13f, textC).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
        val last = list.first()
        historyBox.addView(button(if (last.kind == "claim") "Undo: release the earbuds" else "Undo: take the earbuds back", false) { quick(last.kind != "claim") }, lp())
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
        if (RootShell.hasSu()) {
            list += Check("Root (optional)", "Only used if this device blocks the normal Bluetooth connect, and only after you allow it under Automatic switching",
                prefs.getBoolean("rootGranted", false)) { scroll.post { scroll.smoothScrollTo(0, rootBtn.top) } }
        }
        return list
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
        if (t.isNullOrEmpty()) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show() else secretField.setText(t)
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
        Toast.makeText(this, "Swipe down, tap the pencil, and drag Take earbuds and Release earbuds into your tiles", Toast.LENGTH_LONG).show()
    }

    private fun addWidget() {
        try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            if (Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(android.content.ComponentName(this, EarWidget::class.java), null, null)
                return
            }
        } catch (e: Exception) { EhLog.w("add widget: ${e.message}") }
        Toast.makeText(this, "Long press the home screen, choose Widgets, and add EarHop", Toast.LENGTH_LONG).show()
    }

    private fun sendAction(a: String) {
        if (!EhState.running) { Toast.makeText(this, "Start EarHop first", Toast.LENGTH_SHORT).show(); return }
        val take = a == EarService.ACTION_TAKE
        val test = a == EarService.ACTION_TEST_PAUSE
        Toast.makeText(this, if (test) "Pausing playback..." else if (take) "Taking the ${Earbuds.short(this)}..." else "Releasing the ${Earbuds.short(this)}...", Toast.LENGTH_SHORT).show()
        if (!test) EhLog.w("handoff: manual ${if (take) "take" else "release"} requested")
        try { startService(Intent(this, EarService::class.java).setAction(a)) } catch (e: Exception) { EhLog.w("action failed ${e.message}") }
    }

    private fun toggle() {
        if (EhState.running) {
            prefs.edit().putBoolean("keepRunning", false).apply() // stopped on purpose: no restart after an update or a restart
            stopService(Intent(this, EarService::class.java))
            return
        }
        val n = nameField.text.toString().trim()
        if (n.isEmpty() || n.contains("|")) { Toast.makeText(this, "Enter a device name without |", Toast.LENGTH_LONG).show(); return }
        if (secretField.text.isNullOrBlank()) { Toast.makeText(this, "Enter the shared secret from the Mac", Toast.LENGTH_LONG).show(); return }
        if (!required()) {
            Toast.makeText(this, "Allow the permissions below first", Toast.LENGTH_LONG).show()
            scroll.post { scroll.smoothScrollTo(0, permsCard.top) }
            return
        }
        prefs.edit().putString("name", n).putString("secret", secretField.text.toString().trim())
            .putString(if (Earbuds.isBuds(this)) "filterBuds" else "filter", filterField.text.toString().trim()).apply()
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
        if (crash != null) { crashText.text = crash.take(900); crashCard.visibility = View.VISIBLE }
        // Android 13 and later only ask for log access while EarHop is on screen, so restart an unconfirmed log watch now.
        if (EhState.running && ClipSync.rootWatching && ClipSync.watchMode == "log access" && ClipSync.logOk != true) {
            ClipSync.restartLogWatch(applicationContext)
        }
        tick.run()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            try { refresh() } catch (e: Exception) { EhLog.w("ui refresh ${e.message}") }
            handler.postDelayed(this, 1000)
        }
    }

    private fun set(key: String, value: String, color: Int = subC) {
        values[key]?.apply {
            text = value; setTextColor(color)
            (parent as? View)?.contentDescription = "${labels[key] ?: key}: $value"
        }
    }

    private fun refresh() {
        val run = EhState.running
        statusChip.text = if (run) "Running" else "Stopped"
        statusChip.background = round(if (run) green else 0xFF8E8E93.toInt(), 20)
        startBtn.text = if (run) "Stop" else "Start"
        startBtn.background = ripple(if (run) red else accent, 28)

        refreshHistory()
        refreshBuds()
        budsInfo.text = if (Earbuds.isBuds(this)) "Galaxy Buds: connect, release and play based switching work. In-ear pause and resume is experimental: it reads Samsung's status channel only while the Buds are connected to this device. Wear channel: ${EhState.buds}. If it says refused, close Galaxy Wearable and try again; the Buds still switch without it."
            else "AirPods: full in-ear detection from their Bluetooth beacon."
        refreshOnboarding()
        if (this::appRulesBox.isInitialized && AppRules.hasAccess(this) != appAccessShown) { appAccessShown = !appAccessShown; rebuildAppRules() }
        val err = EhState.error
        errorCard.visibility = if (err == null) View.GONE else View.VISIBLE
        errorText.text = err ?: ""

        refreshDashboard(run)

        peersBox.removeAllViews()
        val now = System.currentTimeMillis()
        val live = EhState.peers.values.filter { now - it.seenAt < 15000 }.sortedBy { it.name }
        if (live.isEmpty()) {
            peersBox.addView(text(if (run) "No other devices seen yet. Open EarHop on the Mac with the same secret." else "Start EarHop to look for your Mac.", 14f, subC))
        }
        for ((idx, p) in live.withIndex()) {
            if (idx > 0) divider(peersBox)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(text(pretty(p.name).replaceFirstChar { it.uppercase() }, 16f, textC), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val state = buildString {
                if (p.call) append("on a call · ")
                if (p.owner) append("has the earbuds · ")
                append(if (p.playing) "playing" else "idle")
                append(if (p.via == "ble") " · Bluetooth" else " · Wi-Fi")
            }
            r.addView(text(state, 14f, if (p.owner) green else subC))
            peersBox.addView(r)
        }

        permsBox.removeAllViews()
        var allOk = true
        for (c in checks()) {
            allOk = allOk && (c.ok || c.label.endsWith("(optional)"))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(c.label, 16f, textC))
            if (c.detail.isNotEmpty() && !c.ok) t.addView(text(c.detail, 12f, subC))
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (c.ok) r.addView(text("✓", 20f, green, true))
            else r.addView(button("Allow", true) { c.fix() }.apply { setPadding(dp(14), dp(4), dp(14), dp(4)); minHeight = 0; minimumHeight = 0 })
            permsBox.addView(r)
        }
        permsCard.visibility = if (allOk && run) View.GONE else View.VISIBLE

        val age = if (EhState.beaconAt == 0L) -1L else (System.currentTimeMillis() - EhState.beaconAt) / 1000
        val buds = Earbuds.isBuds(this)
        gentleRow.visibility = if (buds) View.GONE else View.VISIBLE
        earInfo.text = when {
            !run -> "Start EarHop to detect your earbuds."
            buds -> "Galaxy Buds report in-ear status over their status channel, only while they are connected to this device. " +
                "Channel: ${EhState.buds}. Reading: ${EhState.earReading.ear} (${EhState.earReading.source}, ${EhState.earReading.confidence} confidence)." +
                if (EhState.buds.startsWith("refused")) "\nIf it keeps refusing, close Galaxy Wearable; switching still works without it." else ""
            age < 0 && EhState.appleSeen == 0 -> "No Apple Bluetooth signal seen yet (scan: ${EhState.scanMode.ifEmpty { "starting" }}). Check Location permission, Location switched on, and that the AirPods are close."
            age < 0 -> "Apple devices seen (${EhState.appleSeen}), but no AirPods beacon from your model yet. Put the earbuds in your ears and keep them close."
            else -> "Last beacon ${age}s ago: ${EhState.beaconInfo}\nScan: ${EhState.scanMode}  ·  Direct channel: ${EhState.aacp}" +
                if (EhState.aacp == "not supported") "\nOn this phone the AirPods report one bud instantly but the other bud only after several seconds. Both buds out is always detected at once." else ""
        }
        val hasSu = RootShell.hasSu()
        val granted = hasSu && prefs.getBoolean("rootGranted", false)
        rootBtn.isEnabled = hasSu
        rootBtn.alpha = if (hasSu) 1f else 0.4f
        rootBtn.text = when {
            !hasSu -> "Root is not available on this device"
            granted -> "Root granted (tap to revoke)"
            else -> "Grant root access"
        }
        rootUseRow.visibility = if (granted) View.VISIBLE else View.GONE
        rootFirstRow.visibility = if (granted) View.VISIBLE else View.GONE
        // Connection status and the quick noise control on Home.
        linkText.text = if (run) "Working now: ${EhState.transport}" else "Start EarHop to connect"
        linkText.setTextColor(if (run && EhState.transport.contains("nobody")) orange else subC)
        refreshClipSetup(run)
        val bg = if (ClipSync.helperRunning()) "Copies here are sent automatically (clipboard helper). Copies from your other devices arrive automatically."
        else if (ClipSync.rootWatching && (ClipSync.watchMode == "root" || ClipSync.logOk == true)) "Copies here are sent automatically (${ClipSync.watchMode}). Copies from your other devices arrive automatically." else when (ClipSync.backgroundWorks) {
            true -> "Copies here are sent automatically."
            false -> "Android does not let apps read the clipboard in the background, so send copies with the Send clipboard tile, the notification button, or Share, Send to my devices. Copies from your Mac arrive here automatically."
            null -> "Copies from your Mac arrive here automatically. To send from here, use the Send clipboard tile, the notification button, or Share, Send to my devices."
        }
        val ago = (System.currentTimeMillis() - ClipSync.lastEventAt) / 1000
        val last = if (ClipSync.lastEventAt > 0 && ago < 3600) "${ClipSync.lastEvent}, ${if (ago < 60) "${ago} s" else "${ago / 60} min"} ago." else ""
        clipText.text = (if (last.isNotEmpty()) "$last\n" else "") + bg
        clipMethodText.text = clipMethodWords(ClipSync.method(this))
        val auto = prefs.getBoolean("clipAutoRoot", true) && (ClipSync.helperRunning() || (ClipSync.rootWatching && (ClipSync.watchMode == "root" || ClipSync.logOk == true)))
        homeClipText.text = when {
            !prefs.getBoolean("clipSync", true) -> "Off. Copies stay on this device."
            last.isNotEmpty() -> last
            auto -> "Copies are sent automatically."
            else -> "Copies from your other devices arrive here. Send yours with Send clipboard now or the tile."
        }
        homeNoiseCard.visibility = if (run && Earbuds.isBuds(this) && EhState.budsClient?.connected == true) View.VISIBLE else View.GONE
        syncs.forEach { it() }
        val key = listOf(" claim:", " release:", " pause:", " resume:", " hold:", " safe:", " handoff:")
        handoffView.text = EhLog.dump().lines().filter { l -> key.any { l.contains(it) } }.takeLast(6).joinToString("\n")
            .ifEmpty { "No handoffs yet. Play audio on this device with the ${Earbuds.short(this)} in your ears." }
        logView.text = EhLog.dump().lines().takeLast(14).joinToString("\n").ifEmpty { "Nothing yet" }
    }
}
