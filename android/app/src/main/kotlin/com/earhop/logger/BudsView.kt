package com.earhop.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * The hero picture: both earbuds and the case, drawn live. A bud in an ear floats above the case and bobs gently,
 * with sound waves while audio plays; a bud in the case sits in its slot; a bud that is out but not worn rests beside
 * the case, dimmed. Each bud has a battery ring with its level under it, and the case shows its own level. Buds move
 * smoothly between places. White on the coloured hero card; only a low battery ring changes colour (it is an outline).
 */
class BudsView(ctx: Context, private val density: Float) : View(ctx) {
    /** One bud: battery 0..100 or -1, place "wearing", "case", "idle" or "unknown". */
    data class Bud(val battery: Int = -1, val place: String = "unknown", val charging: Boolean = false)

    data class Model(
        val left: Bud = Bud(), val right: Bud = Bud(), val case: Int = -1, val caseCharging: Boolean = false,
        val playing: Boolean = false, val here: Boolean = false, val stem: Boolean = false,
    )

    var model = Model()
        set(v) {
            if (v == field) return
            field = v
            contentDescription = describe(v)
            invalidate()
        }

    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val oval = RectF()
    private val path = Path()
    private val low = 0xFFFF8A80.toInt()

    // Animated positions (x, y, scale, alpha) of the left and right bud, eased toward their targets every frame.
    private val pos = Array(2) { floatArrayOf(Float.NaN, 0f, 1f, 1f) }
    private val start = System.currentTimeMillis()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(206f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (System.currentTimeMillis() - start) / 1000f
        val m = model
        val cx = w / 2
        val r = dp(25f)

        // Case: a rounded box with the lid tipped open while a bud is out.
        val caseW = minOf(w * 0.40f, dp(150f)); val caseH = dp(66f)
        val caseTop = h - caseH - dp(30f)
        val anyOut = listOf(m.left, m.right).any { it.place == "wearing" || it.place == "idle" }
        oval.set(cx - caseW / 2, caseTop, cx + caseW / 2, caseTop + caseH)
        fill.color = 0x26FFFFFF; c.drawRoundRect(oval, dp(26f), dp(26f), fill)
        stroke.color = 0x66FFFFFF; stroke.strokeWidth = dp(1.5f); c.drawRoundRect(oval, dp(26f), dp(26f), stroke)
        c.save()
        val lidY = caseTop + dp(4f)
        if (anyOut) c.rotate(-14f, cx - caseW / 2 + dp(10f), lidY)
        oval.set(cx - caseW / 2, lidY - dp(16f), cx + caseW / 2, lidY + dp(6f))
        fill.color = 0x1AFFFFFF; c.drawRoundRect(oval, dp(14f), dp(14f), fill)
        stroke.color = 0x4DFFFFFF; c.drawRoundRect(oval, dp(14f), dp(14f), stroke)
        c.restore()
        label.textSize = dp(13f); label.color = 0xE6FFFFFF.toInt()
        val caseText = "Case " + (if (m.case >= 0) "${m.case}%" else "–")
        c.drawText(caseText, cx, h - dp(8f), label)
        if (m.caseCharging) bolt(c, cx + label.measureText(caseText) / 2 + dp(9f), h - dp(13f))

        var moving = false
        for (side in 0..1) {
            val bud = if (side == 0) m.left else m.right
            val sign = if (side == 0) -1f else 1f
            // Where this bud belongs.
            val (tx, ty, ts, ta) = when (bud.place) {
                "wearing" -> listOf(cx + sign * w * 0.29f, dp(62f) + Math.sin(t * 2.0 + side * 1.4).toFloat() * dp(4f), 1f, 1f)
                "case" -> listOf(cx + sign * caseW * 0.22f, caseTop + caseH * 0.42f, 0.62f, 1f)
                "idle" -> listOf(cx + sign * w * 0.33f, caseTop + caseH * 0.20f, 0.85f, 0.55f)
                else -> listOf(cx + sign * w * 0.29f, dp(70f), 0.95f, if (m.here) 0.75f else 0.45f)
            }
            val p = pos[side]
            if (p[0].isNaN()) { p[0] = tx; p[1] = ty; p[2] = ts; p[3] = ta }
            val k = 0.14f
            p[0] += (tx - p[0]) * k; p[1] += (ty - p[1]) * k; p[2] += (ts - p[2]) * k; p[3] += (ta - p[3]) * k
            if (Math.abs(tx - p[0]) > 0.5f || Math.abs(ty - p[1]) > 0.5f) moving = true
            val x = p[0]; val y = p[1]; val s = p[2]; val a = p[3]

            // Sound waves beside a worn bud while audio plays.
            if (bud.place == "wearing" && m.playing && m.here) {
                stroke.strokeWidth = dp(2f)
                for (i in 0..2) {
                    val ph = ((t * 0.9f + i / 3f) % 1f)
                    val rr = r + dp(14f) + ph * dp(26f)
                    stroke.color = ((1f - ph) * 150).toInt() shl 24 or 0xFFFFFF
                    oval.set(x - rr, y - rr, x + rr, y + rr)
                    c.drawArc(oval, if (side == 0) 150f else -30f, 60f, false, stroke)
                }
            }

            // Battery ring.
            val rr = (r + dp(8f)) * s
            oval.set(x - rr, y - rr, x + rr, y + rr)
            stroke.strokeWidth = dp(3f) * s
            stroke.color = alpha(0x33FFFFFF, a); c.drawArc(oval, 0f, 360f, false, stroke)
            if (bud.battery >= 0) {
                stroke.color = alpha(if (bud.battery <= 20) low else 0xFFFFFFFF.toInt(), a)
                c.drawArc(oval, -90f, bud.battery * 3.6f, false, stroke)
            }

            // The bud: a rounded body with a speaker grille (and a stem for AirPods), mirrored for the right side.
            c.save()
            c.translate(x, y); c.scale(s * sign * -1f, s)
            fill.color = alpha(0xF2FFFFFF.toInt(), a)
            if (m.stem) {
                oval.set(-r * 0.30f, r * 0.10f, r * 0.12f, r * 1.25f); c.drawRoundRect(oval, r * 0.2f, r * 0.2f, fill)
                c.drawCircle(0f, -r * 0.15f, r * 0.62f, fill)
            } else {
                path.reset()
                path.addOval(RectF(-r * 0.78f, -r * 0.70f, r * 0.70f, r * 0.72f), Path.Direction.CW)
                path.addOval(RectF(r * 0.25f, -r * 0.40f, r * 0.95f, r * 0.30f), Path.Direction.CW)
                c.drawPath(path, fill)
            }
            fill.color = alpha(0x40000000, a)
            c.drawCircle(if (m.stem) r * 0.18f else r * 0.45f, if (m.stem) -r * 0.2f else -r * 0.05f, r * 0.20f, fill)
            fill.color = alpha(0x1A000000, a)
            c.drawCircle(-r * 0.18f, r * 0.05f, r * 0.36f, fill)
            c.restore()

            // Level under the ring.
            label.textSize = dp(13f) * maxOf(s, 0.8f); label.color = alpha(0xFFFFFFFF.toInt(), maxOf(a, 0.7f))
            val text = (if (side == 0) "L " else "R ") + (if (bud.battery >= 0) "${bud.battery}%" else "–")
            val ly = y + rr + dp(17f)
            if (bud.place != "case") {
                c.drawText(text, x, ly, label)
                if (bud.charging) bolt(c, x + label.measureText(text) / 2 + dp(8f), ly - dp(5f))
            }
        }
        // Levels of buds in the case go beside it, level with the bud, clear of the lid.
        label.textSize = dp(13f); label.color = 0xFFFFFFFF.toInt()
        for (side in 0..1) {
            val b = if (side == 0) m.left else m.right
            if (b.place != "case") continue
            val sign = if (side == 0) -1f else 1f
            val text = (if (side == 0) "L " else "R ") + (if (b.battery >= 0) "${b.battery}%" else "\u2013")
            val x = cx + sign * (caseW / 2 + dp(32f)); val y = caseTop + caseH * 0.42f + dp(5f)
            c.drawText(text, x, y, label)
            if (b.charging) bolt(c, x + sign * dp(30f), y - dp(5f))
        }

        // Keep animating while something moves, bobs or plays; otherwise draw only when the model changes.
        val alive = moving || listOf(m.left, m.right).any { it.place == "wearing" }
        if (alive && isShown) postInvalidateDelayed(33)
    }

    private fun alpha(color: Int, a: Float): Int = (((color ushr 24) * a).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    /** A small lightning bolt for "charging". */
    private fun bolt(c: Canvas, x: Float, y: Float) {
        val u = dp(1f)
        path.reset()
        path.moveTo(x + 1 * u, y - 6 * u); path.lineTo(x - 3 * u, y + 1 * u); path.lineTo(x, y + 1 * u)
        path.lineTo(x - 1 * u, y + 6 * u); path.lineTo(x + 3 * u, y - 1 * u); path.lineTo(x, y - 1 * u); path.close()
        fill.color = 0xFFFFFFFF.toInt(); c.drawPath(path, fill)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun describe(m: Model): String {
        fun one(n: String, b: Bud) = "$n " + when (b.place) { "wearing" -> "in ear"; "case" -> "in case"; "idle" -> "out"; else -> "" } +
            (if (b.battery >= 0) " ${b.battery}%" else "") + (if (b.charging) " charging" else "")
        return listOf(one("Left", m.left), one("Right", m.right), "Case" + (if (m.case >= 0) " ${m.case}%" else "")).joinToString(", ")
    }
}
