// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/** Everything the side panel shows this frame. Data-class equality = "nothing visible changed". */
data class SidePanelView(
    val directions: DirectionsView?,
    val stage: Int,
    val progress: Float,
    val ecoKm: Double,
    val ecoPercent: Int?,
    val treeNumber: Int,
    val noGps: Boolean,
    /** The model's eco speed band, e.g. "40–60 km/h". */
    val ecoBand: String,
    /** The band's top speed, e.g. "60 km/h" (the "Ease off" line). */
    val ecoMax: String,
    /** Spec-derived engine-speed reference for that band, e.g. "~5–6.5k rpm"; null when unknown. */
    val ecoRpm: String?,
    /** Live speed against the band; null when stopped or no speed. */
    val ecoHint: EcoHint?,
)

/**
 * Draws the side panel: directions block on top while a route is active, eco tree block below (or the whole
 * panel when there's no route). Called only from the `metrics-strip` thread. New bitmap per call.
 */
object SidePanelRenderer {
    private const val GREY = 0xFFAAAAAA.toInt()
    private const val DIM = 0xFF555555.toInt()
    private const val TRACK = 0xFF1A1A1A.toInt()
    private const val ECO = 0xFF34D399.toInt()
    private const val EASE_OFF = 0xFFFBBF24.toInt()

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val divider = Paint().apply { color = 0xFF222222.toInt(); strokeWidth = 2f }

    fun render(v: SidePanelView, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val pad = w * 0.06f
        val d = v.directions
        if (d != null) {
            val treeTop = drawDirections(c, d, w, h, pad)
            c.drawLine(pad, treeTop, w - pad, treeTop, divider)
            drawEcoCompact(c, v, w, treeTop + pad * 0.6f, h - pad, pad)
        } else {
            drawEcoFull(c, v, w, h, pad)
        }
        return bmp
    }

    /** @return y where the eco block starts. */
    private fun drawDirections(c: Canvas, d: DirectionsView, w: Int, h: Int, pad: Float): Float {
        val arrow = minOf(w * 0.38f, h * 0.22f)
        MetricsStripRenderer.drawArrow(c, d.turn, pad, pad, arrow)
        val textX = pad + arrow + pad * 0.6f
        val textW = w - textX - pad
        big.textSize = arrow * 0.42f
        big.textSize = DirectionsFormat.shrinkToFit(big.textSize, arrow * 0.25f, textW) { size ->
            big.textSize = size
            big.measureText(d.distance)
        }
        c.drawText(MetricsStripRenderer.fit(d.distance, big, textW), textX, pad + arrow * 0.55f, big)
        var y = pad + arrow + pad * 0.4f
        small.textSize = SidePanelLayout.textPx(w, h, 0.045f, 0.075f)
        d.road?.let {
            c.drawText(MetricsStripRenderer.fit(it, small, w - 2 * pad), pad, y + small.textSize, small)
            y += small.textSize * 1.5f
        }
        if (d.then != null || d.lanes.isNotEmpty()) {
            val rowH = minOf(h * 0.075f, w * 0.14f)
            var x = pad
            if (d.then != null && x + small.measureText("then ") + rowH <= w - pad) {
                c.drawText("then", x, y + rowH * 0.7f, small)
                x += small.measureText("then ") 
                MetricsStripRenderer.drawArrow(c, d.then, x, y, rowH)
                x += rowH + pad
            }
            for (lane in d.lanes) {
                if (x + rowH * 0.8f > w - pad) break
                val (kind, side) = lane.arrows.firstOrNull() ?: (TurnKind.STRAIGHT to TurnSide.NONE)
                val sz = (rowH * 0.8f).toInt().coerceAtLeast(1)
                val lb = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                MetricsStripRenderer.drawArrow(Canvas(lb), NextTurn(kind, side), 0f, 0f, sz * 0.9f)
                val tint = Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        if (lane.highlighted) Color.WHITE else DIM, android.graphics.PorterDuff.Mode.SRC_IN,
                    )
                }
                c.drawBitmap(lb, x, y + rowH * 0.1f, tint)
                lb.recycle()
                x += rowH * 0.85f
            }
            y += rowH + pad * 0.3f
        }
        d.eta?.let {
            big.textSize = SidePanelLayout.textPx(w, h, 0.055f, 0.085f)
            val line = it + (d.remaining?.let { r -> " · $r" } ?: "")
            c.drawText(MetricsStripRenderer.fit(line, big, w - 2 * pad), pad, y + big.textSize, big)
            y += big.textSize * 1.6f
        }
        return y.coerceAtMost(h * 0.72f)
    }

    private fun drawEcoCompact(c: Canvas, v: SidePanelView, w: Int, top: Float, bottom: Float, pad: Float) {
        val size = (bottom - top).coerceAtMost(w * 0.3f)
        TreeDrawing.draw(c, v.stage, pad + size / 2f, top + size, size)
        val x = pad * 2 + size
        small.textSize = size * 0.22f
        val label = if (v.noGps) "No GPS" else "Eco ${v.ecoPercent?.let { "$it%" } ?: "—"} · ${v.stage}/5"
        c.drawText(MetricsStripRenderer.fit(label, small, w - x - pad), x, top + size * 0.4f, small)
        drawBar(c, x, top + size * 0.6f, w - x - pad, size * 0.1f, v.progress)
        if (!v.noGps) drawHint(c, v, x, top + size * 0.98f, w - x - pad)
    }

    /** The live eco-speed coaching line: green inside the band, amber "Ease off" above it, grey otherwise. */
    private fun drawHint(c: Canvas, v: SidePanelView, x: Float, baseline: Float, width: Float) {
        val (text, color) = when (v.ecoHint) {
            EcoHint.IN_BAND -> "✓ ${v.ecoBand}" to ECO
            EcoHint.ABOVE -> "Ease off ≤${v.ecoMax}" to EASE_OFF
            EcoHint.BELOW, null -> "Eco ${v.ecoBand}" to GREY
        }
        val was = small.color
        small.color = color
        c.drawText(MetricsStripRenderer.fit(text, small, width), x, baseline, small)
        small.color = was
    }

    private fun drawEcoFull(c: Canvas, v: SidePanelView, w: Int, h: Int, pad: Float) {
        val size = minOf(w - 2 * pad, h * 0.5f)
        TreeDrawing.draw(c, v.stage, w / 2f, pad + size, size)
        var y = pad + size + pad
        small.textSize = SidePanelLayout.textPx(w, h, 0.045f, 0.075f)
        small.textAlign = Paint.Align.CENTER
        c.drawText(MetricsStripRenderer.fit("${TreeDrawing.stageName(v.stage)} · stage ${v.stage} of 5", small, w - 2 * pad), w / 2f, y, small)
        y += pad * 0.8f
        drawBar(c, pad * 2, y, w - pad * 4, h * 0.02f, v.progress)
        y += pad * 1.8f
        big.textSize = SidePanelLayout.textPx(w, h, 0.08f, 0.16f)
        big.textAlign = Paint.Align.CENTER
        val eco = if (v.noGps) "No GPS" else "Eco ${v.ecoPercent?.let { "$it%" } ?: "—"}"
        c.drawText(MetricsStripRenderer.fit(eco, big, w - 2 * pad), w / 2f, y + big.textSize * 0.4f, big)
        y += big.textSize + pad * 0.4f
        c.drawText(
            MetricsStripRenderer.fit(String.format(Locale.US, "%.1f / 20 eco km · tree %d", v.ecoKm, v.treeNumber), small, w - 2 * pad),
            w / 2f, y, small,
        )
        if (!v.noGps) {
            y += small.textSize * 1.8f
            drawHint(c, v, w / 2f, y, w - 2 * pad)
            v.ecoRpm?.let {
                y += small.textSize * 1.4f
                c.drawText(MetricsStripRenderer.fit("Engine $it", small, w - 2 * pad), w / 2f, y, small)
            }
        }
        big.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawBar(c: Canvas, x: Float, y: Float, width: Float, height: Float, f: Float) {
        bar.color = TRACK
        c.drawRoundRect(x, y, x + width, y + height, height / 2, height / 2, bar)
        bar.color = ECO
        c.drawRoundRect(x, y, x + width * f.coerceIn(0f, 1f), y + height, height / 2, height / 2, bar)
    }
}
