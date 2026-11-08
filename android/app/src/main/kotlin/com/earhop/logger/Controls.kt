package com.earhop.logger

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Touch feel shared by the app: a springy press (the view sinks a little under the finger and bounces back past its
 * size when let go) and a short jelly wobble when something lands. Only runs while touched, so the screen still settles.
 */
object Motion {
    @SuppressLint("ClickableViewAccessibility")
    fun springy(v: View, depth: Float = 0.94f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(depth).scaleY(depth).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(4f)).start()
            }
            false
        }
    }

    /** A quick squash and stretch, for a choice that just landed. */
    fun jelly(v: View) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val t = it.animatedValue as Float
                val k = (Math.sin(t * Math.PI * 3) * (1 - t) * 0.08).toFloat()
                v.scaleX = 1 + k; v.scaleY = 1 - k
            }
            start()
        }
    }

    /** Eases a value toward a target with a little overshoot. */
    fun spring(from: Float, to: Float, ms: Long = 460, onValue: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms; interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { onValue(it.animatedValue as Float) }
            start()
        }
}

/** Colours a control draws with, from the app's palette. */
class ControlColors(val text: Int, val sub: Int, val fill: Int, val line: Int, val selected: Int, val onSelected: Int, val glow: Int)

/**
 * Noise control as three round buttons on a line: Active Noise Canceling, Off, Ambient Sound. The white highlight
 * slides to the chosen one with a spring, glows under the finger and wobbles when it lands.
 */
class NoiseSelector(ctx: Context, private val density: Float, private val c: ControlColors, private val onPick: (String) -> Unit) : View(ctx) {
    val modes = listOf("anc", "off", "ambient")
    private val labels = listOf(listOf("Active Noise", "Canceling"), listOf("Off"), listOf("Ambient", "Sound"))
    private val words = listOf("Active noise canceling", "Off", "Ambient sound")
    private fun dp(v: Float) = v * density
    private val r get() = dp(28f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(13f); color = c.text }

    var mode = ""
        private set
    private var pos = -1f
    private var wobble = 0f
    private var pressed = -1
    private var press = 0f
    private var anim: ValueAnimator? = null
    var enabledChoice = true

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Shows a mode; animated when it changed while on screen. */
    fun setMode(m: String, animate: Boolean = true) {
        val i = modes.indexOf(m)
        if (m == mode) return
        mode = m
        contentDescription = "Noise control: ${words.getOrElse(i) { "unknown" }}"
        if (i < 0) { pos = -1f; invalidate(); return }
        if (pos < 0 || !animate || !isShown) { pos = i.toFloat(); invalidate(); return }
        anim?.cancel()
        anim = Motion.spring(pos, i.toFloat()) { pos = it; invalidate() }
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 560; addUpdateListener { wobble = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(132f).toInt())

    private fun cx(i: Float) = width * (0.18f + 0.32f * i)
    private val cy get() = dp(36f)

    override fun onDraw(cv: Canvas) {
        // The line joining the three.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = c.line
        cv.drawLine(cx(0f), cy, cx(2f), cy, paint)
        for (i in 0..2) {
            paint.style = Paint.Style.FILL; paint.color = c.fill
            val s = if (i == pressed) 1f - 0.08f * press else 1f
            cv.drawCircle(cx(i.toFloat()), cy, r * s, paint)
        }
        // The highlight, with a glow while touched and a wobble when it lands.
        if (pos >= 0) {
            val x = cx(pos)
            val k = (Math.sin(wobble * Math.PI * 3) * (1 - wobble) * 0.14).toFloat()
            val g = r * (1.75f + 0.25f * press)
            paint.shader = RadialGradient(x, cy, g, c.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawCircle(x, cy, g, paint)
            paint.shader = null
            paint.color = c.selected
            cv.save(); cv.scale(1 + k, 1 - k, x, cy)
            cv.drawCircle(x, cy, r * (if (pressed == Math.round(pos)) 1f - 0.08f * press else 1f), paint)
            cv.restore()
        }
        for (i in 0..2) {
            val near = pos >= 0 && Math.abs(pos - i) < 0.5f
            icon(cv, i, cx(i.toFloat()), cy, if (near) c.onSelected else c.text)
            label.color = if (enabledChoice) c.text else c.sub
            labels[i].forEachIndexed { n, line -> cv.drawText(line, cx(i.toFloat()), cy + r + dp(22f) + n * dp(17f), label) }
        }
    }

    private fun icon(cv: Canvas, i: Int, x: Float, y: Float, color: Int) {
        paint.color = color; paint.strokeWidth = dp(2f)
        when (i) {
            0 -> { paint.style = Paint.Style.STROKE; cv.drawCircle(x, y, r * 0.42f, paint); paint.style = Paint.Style.FILL; cv.drawCircle(x, y, r * 0.17f, paint) }
            1 -> { paint.style = Paint.Style.STROKE; cv.drawCircle(x, y, r * 0.32f, paint); paint.style = Paint.Style.FILL; cv.drawCircle(x, y, r * 0.13f, paint) }
            else -> {
                paint.style = Paint.Style.FILL
                for (k in 0 until 8) {
                    val a = Math.PI * 2 * k / 8
                    cv.drawCircle(x + (Math.cos(a) * r * 0.42).toFloat(), y + (Math.sin(a) * r * 0.42).toFloat(), dp(1.6f), paint)
                }
                cv.drawCircle(x, y, r * 0.13f, paint)
            }
        }
    }

    private fun pressAnim(to: Float) {
        ValueAnimator.ofFloat(press, to).apply {
            duration = if (to > 0) 110 else 380
            interpolator = if (to > 0) DecelerateInterpolator() else OvershootInterpolator(3f)
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledChoice) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = (0..2).minByOrNull { Math.abs(e.x - cx(it.toFloat())) }!!
                if (Math.abs(e.x - cx(pressed.toFloat())) > r * 1.6f) { pressed = -1; return false }
                parent?.requestDisallowInterceptTouchEvent(true)
                pressAnim(1f)
            }
            MotionEvent.ACTION_UP -> {
                val i = pressed
                pressAnim(0f)
                if (i >= 0 && modes[i] != mode) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); setMode(modes[i]); onPick(modes[i]) }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pressAnim(0f); pressed = -1 }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // Accessibility: one action per mode.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        modes.forEachIndexed { i, _ -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(1000 + i, words[i])) }
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (action in 1000..1002 && enabledChoice) { val m = modes[action - 1000]; setMode(m); onPick(m); return true }
        return super.performAccessibilityAction(action, args)
    }
}

/**
 * The equalizer as six round buttons side by side (Off, Bass, Soft, Dynamic, Clear, Treble), the same look as the noise
 * control: each shows five small bars with the shape of its sound. The white highlight springs to the chosen one,
 * glows under the finger and wobbles when it lands. A custom preset from Galaxy Wearable selects none.
 */
class EqButtons(ctx: Context, private val density: Float, private val c: ControlColors, private val onPick: (Int) -> Unit) : View(ctx) {
    val labels = listOf("Off", "Bass", "Soft", "Dynamic", "Clear", "Treble")
    private val bars = listOf(
        floatArrayOf(7f, 7f, 7f, 7f, 7f), floatArrayOf(15f, 12f, 8f, 6f, 5f), floatArrayOf(7f, 10f, 12f, 10f, 7f),
        floatArrayOf(14f, 8f, 5f, 8f, 14f), floatArrayOf(5f, 8f, 13f, 11f, 8f), floatArrayOf(5f, 6f, 8f, 12f, 15f))
    private fun dp(v: Float) = v * density
    private val n get() = labels.size
    private val r get() = Math.min(dp(24f), width / n * 0.38f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(12f) }
    private val rect = RectF()

    var index = -1
        private set
    private var pos = -1f
    private var wobble = 0f
    private var pressed = -1
    private var press = 0f
    private var anim: ValueAnimator? = null
    var enabledChoice = true

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Shows a preset (-1 for none); animated when it changed while on screen. */
    fun setIndex(i: Int, animate: Boolean = true) {
        if (i == index) return
        index = i
        contentDescription = "Equalizer: ${labels.getOrElse(i) { "custom" }}"
        if (i < 0) { pos = -1f; invalidate(); return }
        if (pos < 0 || !animate || !isShown) { pos = i.toFloat(); invalidate(); return }
        anim?.cancel()
        anim = Motion.spring(pos, i.toFloat()) { pos = it; invalidate() }
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 560; addUpdateListener { wobble = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(88f).toInt())

    private fun cx(i: Float) = width / n * (i + 0.5f)
    private val cy get() = dp(28f)

    override fun onDraw(cv: Canvas) {
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            paint.color = c.fill
            val s = if (i == pressed) 1f - 0.08f * press else 1f
            cv.drawCircle(cx(i.toFloat()), cy, r * s, paint)
        }
        if (pos >= 0) {
            val x = cx(pos)
            val k = (Math.sin(wobble * Math.PI * 3) * (1 - wobble) * 0.14).toFloat()
            val g = r * (1.7f + 0.25f * press)
            paint.shader = RadialGradient(x, cy, g, c.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawCircle(x, cy, g, paint)
            paint.shader = null
            paint.color = c.selected
            cv.save(); cv.scale(1 + k, 1 - k, x, cy)
            cv.drawCircle(x, cy, r * (if (pressed == Math.round(pos)) 1f - 0.08f * press else 1f), paint)
            cv.restore()
        }
        // The icon is drawn for a 48 dp button and scales with the real button, so it always fits inside the circle.
        val unit = r / dp(24f)
        val gap = dp(4.2f) * unit
        val half = dp(1.2f) * unit
        for (i in 0 until n) {
            val near = pos >= 0 && Math.abs(pos - i) < 0.5f
            paint.color = if (near) c.onSelected else c.text
            val x0 = cx(i.toFloat()) - 2 * gap
            bars[i].forEachIndexed { b, h ->
                val x = x0 + b * gap
                val hh = dp(h) * unit
                rect.set(x - half, cy - hh / 2, x + half, cy + hh / 2)
                cv.drawRoundRect(rect, half, half, paint)
            }
            label.color = if (!enabledChoice) c.sub else if (near) c.text else c.sub
            cv.drawText(labels[i], cx(i.toFloat()), cy + r + dp(20f), label)
        }
    }

    private fun pressAnim(to: Float) {
        ValueAnimator.ofFloat(press, to).apply {
            duration = if (to > 0) 110 else 380
            interpolator = if (to > 0) DecelerateInterpolator() else OvershootInterpolator(3f)
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledChoice) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = (0 until n).minByOrNull { Math.abs(e.x - cx(it.toFloat())) }!!
                parent?.requestDisallowInterceptTouchEvent(true)
                pressAnim(1f)
            }
            MotionEvent.ACTION_UP -> {
                val i = pressed
                pressAnim(0f)
                if (i >= 0 && i != index) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); setIndex(i); onPick(i) }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pressAnim(0f); pressed = -1 }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        labels.forEachIndexed { i, l -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(1000 + i, "Equalizer $l")) }
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (action in 1000 until 1000 + n && enabledChoice) { setIndex(action - 1000); onPick(action - 1000); return true }
        return super.performAccessibilityAction(action, args)
    }
}

/**
 * A slider with a few stops (the equalizer presets). Drag the thumb or tap a stop;
 * it snaps with a bounce, glows while touched and wobbles when it lands.
 */
class StopSlider(ctx: Context, private val density: Float, private val labels: List<String>, private val c: ControlColors,
                 private val onPick: (Int) -> Unit) : View(ctx) {
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(13f) }
    private val rect = RectF()
    var index = -1
        private set
    private var pos = -1f
    private var touching = 0f
    private var wobble = 0f
    private var dragging = false
    private var downX = 0f
    private var anim: ValueAnimator? = null
    var enabledChoice = true
    var name = "Choice"

    init { isFocusable = true }

    fun setIndex(i: Int, animate: Boolean = true) {
        if (i == index) return
        index = i
        contentDescription = "$name: ${labels.getOrElse(i) { "unknown" }}"
        if (i < 0) { pos = -1f; invalidate(); return }
        if (pos < 0 || !animate || !isShown) { pos = i.toFloat(); invalidate(); return }
        snapTo(i)
    }

    private fun snapTo(i: Int) {
        anim?.cancel()
        anim = Motion.spring(pos, i.toFloat(), 420) { pos = it; invalidate() }
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 520; addUpdateListener { wobble = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(48f).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Labels shrink (down to 10 sp) to fit a narrow screen instead of being cut.
        text.textSize = dp(13f)
        val widest = labels.maxOfOrNull { text.measureText(it) } ?: 0f
        val room = w.toFloat() / labels.size.coerceAtLeast(1) - dp(10f)
        if (widest > room && widest > 0) text.textSize = maxOf(dp(10f), dp(13f) * room / widest)
    }

    private val seg get() = width.toFloat() / labels.size

    override fun onDraw(cv: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.style = Paint.Style.FILL; paint.color = c.fill
        cv.drawRoundRect(rect, h / 2, h / 2, paint)
        if (pos >= 0) {
            val x = seg * pos
            val k = (Math.sin(wobble * Math.PI * 3) * (1 - wobble) * 0.12).toFloat()
            val cx = x + seg / 2
            if (touching > 0) {
                paint.shader = RadialGradient(cx, h / 2, seg * 0.9f, c.glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                paint.alpha = (255 * touching).toInt()
                cv.drawCircle(cx, h / 2, seg * 0.9f, paint)
                paint.shader = null; paint.alpha = 255
            }
            cv.save(); cv.scale(1 + k, 1 - k + 0.04f * touching, cx, h / 2)
            rect.set(x + dp(3f), dp(3f), x + seg - dp(3f), h - dp(3f))
            paint.color = c.selected
            cv.drawRoundRect(rect, (h - dp(6f)) / 2, (h - dp(6f)) / 2, paint)
            cv.restore()
        }
        val base = h / 2 - (text.descent() + text.ascent()) / 2
        labels.forEachIndexed { i, l ->
            val near = pos >= 0 && Math.abs(pos - i) < 0.5f
            text.color = if (near) c.onSelected else if (enabledChoice) c.text else c.sub
            text.isFakeBoldText = near
            cv.drawText(l, seg * i + seg / 2, base, text)
        }
    }

    private fun touchAnim(to: Float) {
        ValueAnimator.ofFloat(touching, to).apply { duration = if (to > 0) 120 else 300; addUpdateListener { touching = it.animatedValue as Float; invalidate() }; start() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledChoice || labels.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; dragging = false; anim?.cancel(); touchAnim(1f)
                if (pos < 0) pos = (e.x / seg - 0.5f).coerceIn(0f, labels.size - 1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(e.x - downX) > dp(6f)) dragging = true
                if (dragging) { pos = (e.x / seg - 0.5f).coerceIn(0f, labels.size - 1f); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val i = (if (dragging) Math.round(pos) else (e.x / seg).toInt()).coerceIn(0, labels.size - 1)
                touchAnim(0f)
                snapTo(i)
                if (i != index) {
                    index = i
                    contentDescription = "$name: ${labels[i]}"
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onPick(i)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { touchAnim(0f); if (index >= 0) snapTo(index) }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        labels.forEachIndexed { i, l -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(2000 + i, l)) }
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val i = action - 2000
        if (i in labels.indices && enabledChoice) { setIndex(i); onPick(i); return true }
        return super.performAccessibilityAction(action, args)
    }
}

/**
 * A card that answers every touch: it sinks a little under the finger, a soft glow spreads from the touch point, and
 * it bounds back past its size when let go. Works for cards with and without their own click; scrolling cancels it.
 */
open class SpringCard(ctx: Context, private val radius: Float, private val depth: Float = 0.985f) : android.widget.LinearLayout(ctx) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private var gx = 0f
    private var gy = 0f
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    var glowColor = 0x33FFFFFF

    init { setWillNotDraw(false) }

    private fun glowTo(to: Float, ms: Long) {
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofFloat(glow, to).apply { duration = ms; addUpdateListener { glow = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gx = e.x; gy = e.y
                animate().scaleX(depth).scaleY(depth).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                glowTo(1f, 140)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(3.5f)).start()
                glowTo(0f, 420)
            }
        }
        val handled = super.dispatchTouchEvent(e)
        // Keep receiving the gesture even when nothing inside took it, so the card always springs back.
        return handled || e.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (glow <= 0f) return
        val r = maxOf(width, height) * 0.8f
        glowPaint.shader = RadialGradient(gx, gy, r, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glowPaint.alpha = (255 * glow).toInt()
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        c.restore()
    }
}

/**
 * A scroll view whose pages stretch when pulled past the top or the bottom (with growing resistance) and spring back
 * with a bounce when let go. Works on short pages too, which cannot scroll at all.
 */
class SpringScrollView(ctx: Context) : android.widget.ScrollView(ctx) {
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val density = ctx.resources.displayMetrics.density
    private var downY = 0f
    private var lastY = 0f
    private var pull = 0f
    private var pulling = false
    private var back: ValueAnimator? = null

    private fun atTop() = !canScrollVertically(-1)
    private fun atBottom() = !canScrollVertically(1)

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; lastY = e.y; back?.cancel(); pull = getChildAt(0)?.translationY ?: 0f }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (Math.abs(dy) > slop && ((dy > 0 && atTop()) || (dy < 0 && atBottom()))) { lastY = e.y; pulling = true; return true }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val child = getChildAt(0) ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                if (!pulling && ((dy > 0 && atTop()) || (dy < 0 && atBottom()))) pulling = true
                if (pulling) {
                    // Stretching gets harder the further it goes; pushing back moves it freely.
                    val k = if (pull * dy > 0) 0.5f / (1f + Math.abs(pull) / (240 * density)) else 1f
                    val next = pull + dy * k
                    if (pull != 0f && Math.signum(next) != Math.signum(pull)) { pull = 0f; child.translationY = 0f; pulling = false; return super.onTouchEvent(e) }
                    pull = next
                    child.translationY = pull
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling || pull != 0f) {
                pulling = false
                back = ValueAnimator.ofFloat(pull, 0f).apply {
                    duration = 560; interpolator = OvershootInterpolator(2.2f)
                    addUpdateListener { child.translationY = it.animatedValue as Float }
                    start()
                }
                pull = 0f
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
