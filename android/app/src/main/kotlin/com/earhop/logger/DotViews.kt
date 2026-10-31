package com.earhop.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Text drawn as a 5 by 7 dot matrix, for the big numbers (battery levels). Knows digits, %, -, ., : and space; other
 * characters are drawn as a space. [dotHeight] is the height of the 7 rows in pixels.
 */
class DotText(ctx: Context, private val dotHeight: Int, color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var text: String = ""
        set(v) { if (v != field) { field = v; contentDescription = v; requestLayout(); invalidate() } }

    fun setColor(c: Int) { if (paint.color != c) { paint.color = c; invalidate() } }

    private val cell get() = dotHeight / 7f

    private fun cols(ch: Char) = GLYPHS[ch]?.get(0)?.length ?: 3

    override fun onMeasure(w: Int, h: Int) {
        val width = text.sumOf { cols(it) + 1 } * cell
        setMeasuredDimension(Math.ceil(width.toDouble()).toInt().coerceAtLeast(1), dotHeight)
    }

    override fun onDraw(c: Canvas) {
        val r = cell * 0.36f
        var x = 0f
        for (ch in text) {
            val g = GLYPHS[ch]
            if (g != null) for (row in 0 until 7) for (col in g[row].indices) {
                if (g[row][col] == '#') c.drawCircle(x + col * cell + cell / 2, row * cell + cell / 2, r, paint)
            }
            x += (cols(ch) + 1) * cell
        }
    }

    companion object {
        private val GLYPHS: Map<Char, List<String>> = mapOf(
            '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
            '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
            '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
            '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
            '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
            '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
            '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
            '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
            '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
            '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
            '%' to listOf("##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"),
            '-' to listOf(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
            '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
            ':' to listOf(".", "#", ".", ".", ".", "#", "."),
            ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
        )
    }
}

/**
 * A row of small dots, the first [fraction] of them lit, with a short upright marker where the lit part ends. Used for
 * battery levels and switching progress.
 */
class DotBar(ctx: Context, private val litColor: Int, private val dimColor: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND }
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }

    fun setColors(litC: Int, dimC: Int) {
        if (lit.color != litC || dim.color != dimC) { lit.color = litC; mark.color = litC; dim.color = dimC; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (12 * d).toInt())

    override fun onDraw(c: Canvas) {
        val step = 6 * d
        val n = ((width - step) / step).toInt().coerceAtLeast(1)
        val cy = height / 2f
        val litN = Math.round(n * fraction)
        for (i in 0 until n) c.drawCircle(step / 2 + i * step, cy, if (i < litN) 1.6f * d else 1.1f * d, if (i < litN) lit else dim)
        if (fraction > 0f) {
            val x = step / 2 + (litN - 0.5f).coerceAtLeast(0f) * step + step / 2
            c.drawLine(x, cy - 4.5f * d, x, cy + 4.5f * d, mark)
        }
    }
}
