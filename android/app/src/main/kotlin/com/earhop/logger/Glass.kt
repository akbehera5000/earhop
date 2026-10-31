package com.earhop.logger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity

/**
 * EarHop's look: an AMOLED black page with a faint dot grid, dark graphite cards, and a few glowing colour cards
 * (magenta, indigo, ember) for the parts that matter most. Colours are decoration only and stay in backgrounds; on and
 * off states are shown by outlines ([on] green, [off] red-grey). Works in light mode too, with light cards.
 */
class Glass(private val ctx: Context, val night: Boolean, val accent: Int) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    // palette
    val bg = if (night) 0xFF000000.toInt() else 0xFFF2F2F5.toInt()
    val text = if (night) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
    val sub = if (night) 0xFF8E8E98.toInt() else 0xFF6C6C76.toInt()
    val sep = if (night) 0x1AFFFFFF else 0x14000000
    /** Translucent fill for chips, fields and tracks. */
    val fill = if (night) 0x14FFFFFF else 0x0F000000
    /** State colours, for outlines only. */
    val on = 0xFF30D158.toInt()
    val off = 0xFFA07878.toInt()
    /** Text on a primary button and on the selected segment. */
    val onPrimary = if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    private val primary = if (night) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()
    private val hairline = if (night) 0x1FFFFFFF else 0x17000000

    /** The colour cards. Text on them is always white. */
    enum class Hue(val top: Int, val mid: Int, val deep: Int, val rim: Int) {
        MAGENTA(0xFFE35AA6.toInt(), 0xFF6B1F5E.toInt(), 0xFF1D0819.toInt(), 0xFFF59AD2.toInt()),
        INDIGO(0xFF4A50E6.toInt(), 0xFF1D2080.toInt(), 0xFF080922.toInt(), 0xFF9599FF.toInt()),
        EMBER(0xFFFF6A3D.toInt(), 0xFF8C2612.toInt(), 0xFF1A0805.toInt(), 0xFFFFA27A.toInt()),
        VIOLET(0xFF8A5CFF.toInt(), 0xFF3A1E8A.toInt(), 0xFF0E0724.toInt(), 0xFFB79CFF.toInt()),
    }

    companion object {
        fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        /** Mixes [c] with white (t > 0) or black (t < 0). */
        fun shade(c: Int, t: Float): Int {
            val to = if (t > 0) 255 else 0
            val k = Math.abs(t)
            fun ch(v: Int) = (v + (to - v) * k).toInt().coerceIn(0, 255)
            return (c and 0xFF000000.toInt()) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
        }
    }

    /** The page: black (or light grey) with a faint dot grid, drawn from one small repeated tile. */
    fun page(): Drawable = DotGrid(bg, if (night) 0x1CFFFFFF else 0x16000000, dp(16), dpf(0.9f))

    /** A neutral card: graphite fading darker towards the bottom, with a thin rim. */
    fun surface(r: Int, strong: Boolean = false, tint: Int = 0): Drawable {
        val base = if (tint != 0) GradientDrawable().apply { setColor(tint) }
            else if (night) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (strong) intArrayOf(0xFF26262B.toInt(), 0xFF101012.toInt()) else intArrayOf(0xFF1B1B1F.toInt(), 0xFF0C0C0E.toInt()))
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF9F9FB.toInt()))
        base.cornerRadius = dpf(r.toFloat())
        base.setStroke(dp(1).coerceAtLeast(1), hairline)
        return base
    }

    /**
     * A glowing colour card: the colour bright at one edge, deep at the other, with a soft inner glow and a light rim.
     * [flip] puts the bright edge at the bottom. [outline], when set, replaces the light rim (a state colour).
     */
    fun aurora(r: Int, hue: Hue, flip: Boolean = false, outline: Int = 0): Drawable {
        val rad = dpf(r.toFloat())
        val cols = if (flip) intArrayOf(hue.deep, hue.mid, hue.top) else intArrayOf(hue.top, hue.mid, hue.deep)
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply { cornerRadius = rad }
        val sheen = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(alpha(hue.rim, 0x55), alpha(hue.rim, 0))
            gradientRadius = dpf(240f)
            setGradientCenter(0.5f, if (flip) 1f else 0f)
            cornerRadius = rad
        }
        val glow = GradientDrawable().apply { setColor(0); cornerRadius = rad; setStroke(dp(6), alpha(hue.rim, 0x26)) }
        val rim = GradientDrawable().apply {
            setColor(0); cornerRadius = rad; setStroke(dp(1).coerceAtLeast(1) + 1, if (outline != 0) outline else alpha(hue.rim, 0x99))
        }
        return LayerDrawable(arrayOf(base, sheen, glow, rim))
    }

    /** A neutral card with a state colour outline. */
    fun outlined(r: Int, color: Int): Drawable = (surface(r) as GradientDrawable).apply { setStroke(dp(1) + 1, color) }

    /** A small switch picture (not a control): track with a state outline and the knob at the right when on. */
    fun knob(isOn: Boolean, light: Boolean): Drawable {
        val track = GradientDrawable().apply {
            setColor(if (light) 0x33FFFFFF else fill); cornerRadius = dpf(100f); setStroke(dp(1) + 1, if (isOn) on else off)
        }
        val thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (light || night) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()) }
        return LayerDrawable(arrayOf(track, thumb)).apply {
            val w = dp(40); val h = dp(24); val k = dp(16); val pad = (h - k) / 2
            setLayerInset(1, if (isOn) w - k - pad else pad, pad, if (isOn) pad else w - k - pad, pad)
        }
    }

    /** [aurora] with faint dotted orbits along the bottom, for the main card. */
    fun hero(r: Int, hue: Hue): Drawable = LayerDrawable(arrayOf(aurora(r, hue), Orbits(0x2EFFFFFF, density)))

    private fun ripple(d: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (night) 0x40FFFFFF else 0x26000000), d, null)

    /** A prominent pill button: white on black (black on light). */
    fun filled(r: Int = 100, color: Int = primary): Drawable = ripple(GradientDrawable().apply { setColor(color); cornerRadius = dpf(r.toFloat()) })

    /** A quiet pill button: translucent, with a hairline. */
    fun tinted(r: Int = 100, color: Int = 0): Drawable = ripple(GradientDrawable().apply {
        setColor(if (color != 0) alpha(color, 0x2E) else if (night) 0x1FFFFFFF else 0x0F000000)
        cornerRadius = dpf(r.toFloat()); setStroke(1, hairline)
    })

    fun track(r: Int = 100): Drawable = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(r.toFloat()); setStroke(1, hairline) }

    fun selected(r: Int = 100): Drawable = GradientDrawable().apply { setColor(primary); cornerRadius = dpf(r.toFloat()) }

    fun input(): Drawable = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(18f); setStroke(1, hairline) }

    /** A round icon button, like the ones beside the page title. */
    fun circle(): Drawable = ripple(GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (night) 0xFF151517.toInt() else 0xFFFFFFFF.toInt()); setStroke(1, hairline)
    })

    /** An outlined pill showing a state: green outline when [isOn], red-grey when off. */
    fun statePill(isOn: Boolean, r: Int = 100): Drawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(r.toFloat()); setStroke(dp(1) + 1, if (isOn) on else off)
    }

    /** The tint over the floating tab bar's blurred backdrop. */
    fun bar(): Drawable = GradientDrawable().apply {
        setColor(if (night) 0xB30E0E10.toInt() else 0xBFFFFFFF.toInt()); cornerRadius = dpf(32f); setStroke(1, hairline)
    }

    /** The tab bar's moving highlight. */
    fun indicator(): Drawable = GradientDrawable().apply { setColor(if (night) 0x26FFFFFF else 0x12000000); cornerRadius = dpf(24f) }

    /** The tint over the header's blurred backdrop, with a hairline at the bottom. */
    fun header(): Drawable {
        val line = ColorDrawable(hairline)
        return LayerDrawable(arrayOf(ColorDrawable(alpha(bg, if (night) 0xB3 else 0xC0)), line)).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
        }
    }

    fun pill(color: Int, r: Int = 20): Drawable = GradientDrawable().apply { setColor(color); cornerRadius = dpf(r.toFloat()) }

    /** A small status ring in a state colour. */
    fun dot(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), color); setSize(dp(10), dp(10))
    }

    /** Switch track: neutral fill, green outline when on, red-grey outline when off. */
    fun switchTrack(): Drawable = StateListDrawable().apply {
        fun t(isOn: Boolean) = GradientDrawable().apply {
            setColor(if (isOn) (if (night) 0x33FFFFFF else 0x1F000000) else fill)
            cornerRadius = dpf(100f); setStroke(dp(1) + 1, if (isOn) on else off); setSize(dp(48), dp(28))
        }
        addState(intArrayOf(android.R.attr.state_checked), t(true))
        addState(intArrayOf(), t(false))
    }

    fun switchThumb(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (night) 0xFFFFFFFF.toInt() else 0xFF111114.toInt())
        setStroke(dp(4), 0) // transparent ring: keeps the thumb inside the track
        setSize(dp(28), dp(28))
    }

    /** The page background: a colour plus a grid of small dots, drawn with a repeated tile so it costs one draw call. */
    private class DotGrid(private val color: Int, dotColor: Int, step: Int, radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            val tile = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
            Canvas(tile).drawCircle(step / 2f, step / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = dotColor })
            paint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        override fun draw(c: Canvas) { c.drawColor(color); c.drawRect(bounds, paint) }
        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** Faint dotted circles rising from the bottom edge, with a few dots on them. The card clips them to its shape. */
    private class Orbits(color: Int, private val d: Float) : Drawable() {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = d; this.color = color
            pathEffect = DashPathEffect(floatArrayOf(2 * d, 4 * d), 0f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xCCFFFFFF.toInt() }
        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val r = b.height() * 0.42f
            val cy = b.bottom + r * 0.35f
            val xs = floatArrayOf(0.22f, 0.5f, 0.78f)
            for (x in xs) c.drawCircle(b.left + b.width() * x, cy, r, line)
            c.drawLine(b.left.toFloat(), cy - r * 0.2f, b.right.toFloat(), cy - r * 0.2f, line)
            // a few dots resting on the orbits
            val angles = floatArrayOf(-2.2f, -1.2f, -0.6f)
            for (i in xs.indices) {
                val cx = b.left + b.width() * xs[i]
                c.drawCircle(cx + r * Math.cos(angles[i].toDouble()).toFloat(), cy + r * Math.sin(angles[i].toDouble()).toFloat(), 2.2f * d, dot)
            }
        }
        override fun setAlpha(a: Int) { line.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { line.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
