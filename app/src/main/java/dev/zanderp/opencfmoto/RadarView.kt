// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A small N-axis radar (values 0–1) with a big number in the middle. Colours follow light/dark. */
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var center: String = ""
    private val d = resources.displayMetrics.density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * d; color = ContextCompat.getColor(ctx, R.color.outline)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = ContextCompat.getColor(ctx, R.color.accent); alpha = 70
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = ContextCompat.getColor(ctx, R.color.accent)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * d; color = ContextCompat.getColor(ctx, R.color.text_secondary); textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24 * d; color = ContextCompat.getColor(ctx, R.color.text_primary)
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    fun set(values: List<Float>, labels: List<String>, center: String) {
        this.values = values
        this.labels = labels
        this.center = center
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n < 3) return
        val cx = width / 2f
        val cy = height / 2f + 4 * d
        val r = min(width, height) / 2f - 26 * d
        fun pt(i: Int, f: Float): Pair<Float, Float> {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            return (cx + cos(a) * r * f).toFloat() to (cy + sin(a) * r * f).toFloat()
        }
        for (ring in listOf(1f / 3, 2f / 3, 1f)) {
            val p = Path()
            for (i in 0 until n) {
                val (x, y) = pt(i, ring)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            c.drawPath(p, grid)
        }
        val shape = Path()
        for (i in 0 until n) {
            val (x, y) = pt(i, values[i].coerceIn(0.05f, 1f))
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        c.drawPath(shape, fill)
        c.drawPath(shape, edge)
        for (i in 0 until n) {
            val (x, y) = pt(i, 1f + 16 * d / r)
            c.drawText(labels.getOrElse(i) { "" }, x, y + 4 * d, label)
        }
        c.drawText(center, cx, cy - (big.descent() + big.ascent()) / 2f, big)
    }
}
