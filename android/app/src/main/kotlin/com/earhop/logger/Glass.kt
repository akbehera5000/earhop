package com.earhop.logger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity

/**
 * Liquid Glass style surfaces for the app's code-built screens. Android cannot blur what lies behind a single view, so
 * glass here is a translucent fill over the page (AMOLED black in dark mode), a light sheen along the top, a bright inner
 * edge and a faint outer hairline. Works the same in light and dark mode.
 */
class Glass(private val ctx: Context, val night: Boolean, val accent: Int) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Int) = v * density

    // palette
    val bg = if (night) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val text = if (night) 0xFFFFFFFF.toInt() else 0xFF0B0B10.toInt()
    val sub = if (night) 0xFF9C9CA8.toInt() else 0xFF6C6C76.toInt()
    val sep = if (night) 0x1FFFFFFF else 0x14000000
    /** Translucent fill for chips, fields and segmented tracks. */
    val fill = if (night) 0x1AFFFFFF else 0x0F000000
    private val edge = if (night) 0x24FFFFFF else 0xF2FFFFFF.toInt()
    private val hairline = if (night) 0x14FFFFFF else 0x14000000

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

    /** The page: plain AMOLED black in dark mode (pixels off), a plain light grey in light mode. */
    fun page(): Drawable = ColorDrawable(bg)

    /**
     * A glass surface: cards, the hero, tiles. Flat and translucent like frosted glass (no gradient), with a thin bright
     * rim and a short specular highlight along the top edge, where light would catch real glass. [strong] is a little
     * more opaque (the hero).
     */
    fun surface(r: Int, strong: Boolean = false, tint: Int = 0): Drawable {
        val fillC = when {
            tint != 0 -> tint
            night -> if (strong) 0x26FFFFFF else 0x1AFFFFFF
            else -> if (strong) 0xE6FFFFFF.toInt() else 0xCCFFFFFF.toInt()
        }
        val base = GradientDrawable().apply { setColor(fillC); cornerRadius = dpf(r); setStroke(1, hairline) }
        val rim = GradientDrawable().apply { setColor(0); cornerRadius = dpf(r) - 1; setStroke(1, edge) }
        return LayerDrawable(arrayOf(base, rim, specular())).apply {
            setLayerInset(1, 1, 1, 1, 1)
            setLayerGravity(2, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(2, Math.max(1, dp(1)))
            setLayerInsetLeft(2, dp(r)); setLayerInsetRight(2, dp(r)); setLayerInsetTop(2, 1)
        }
    }

    /** The light catching the top edge: a hairline that fades out towards both ends. */
    private fun specular(): Drawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x00FFFFFF, if (night) 0x59FFFFFF else 0xFFFFFFFF.toInt(), 0x00FFFFFF))

    private fun ripple(d: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (night) 0x40FFFFFF else 0x26000000), d, null)

    /** A prominent button: solid accent with a thin light rim. */
    fun filled(r: Int = 16, color: Int = accent): Drawable {
        val base = GradientDrawable().apply { setColor(color); cornerRadius = dpf(r) }
        val rim = GradientDrawable().apply { setColor(0); cornerRadius = dpf(r); setStroke(1, 0x40FFFFFF) }
        return ripple(LayerDrawable(arrayOf(base, rim)))
    }

    /** A tinted glass button. */
    fun tinted(r: Int = 16, color: Int = accent): Drawable = ripple(GradientDrawable().apply {
        setColor(alpha(color, if (night) 0x2E else 0x1F)); cornerRadius = dpf(r); setStroke(1, alpha(color, if (night) 0x47 else 0x38))
    })

    fun track(r: Int = 13): Drawable = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(r); setStroke(1, hairline) }

    fun selected(r: Int = 10): Drawable = GradientDrawable().apply {
        setColor(if (night) 0x3DFFFFFF else 0xFFFFFFFF.toInt()); cornerRadius = dpf(r)
        setStroke(1, if (night) 0x2EFFFFFF else 0x14000000)
    }

    fun input(): Drawable = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(14); setStroke(1, hairline) }

    /**
     * The tint over the floating tab bar's blurred backdrop (MainActivity draws the page under it, blurred): see-through
     * enough to show colours moving under it, dense enough to keep the labels readable.
     */
    fun bar(): Drawable = surface(30, tint = if (night) 0x991C1C1E.toInt() else 0xA6FFFFFF.toInt())

    /** The tab bar's moving highlight. */
    fun indicator(): Drawable = GradientDrawable().apply { setColor(alpha(accent, if (night) 0x38 else 0x24)); cornerRadius = dpf(22) }

    /** The tint over the header's blurred backdrop, with a hairline at the bottom. */
    fun header(): Drawable {
        val line = ColorDrawable(hairline)
        return LayerDrawable(arrayOf(ColorDrawable(alpha(bg, if (night) 0xA6 else 0xB3)), line)).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
        }
    }

    fun pill(color: Int, r: Int = 20): Drawable = GradientDrawable().apply { setColor(color); cornerRadius = dpf(r) }

    fun dot(color: Int): Drawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(8), dp(8)) }
}
