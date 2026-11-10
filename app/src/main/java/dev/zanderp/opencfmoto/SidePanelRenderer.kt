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
    /** "⛽ ~185 km"; null when there's no range estimate (no full fill-up logged / unknown tank). */
    val fuelText: String? = null,
    /** How worried to be about [fuelText]. */
    val fuelLevel: FuelLevel = FuelLevel.OK,
    /** "🌧 Rain in 35 min" / "☁ 31°"; null when off or not fetched yet. */
    val weatherText: String? = null,
    /** Rain now, soon or ahead — drawn in blue to stand out. */
    val weatherRain: Boolean = false,
    /** The bike with each tyre's pressure on its wheel; null when TPMS isn't set up or is hidden. */
    val tyres: dev.zanderp.opencfmoto.tpms.TyrePanel? = null,
)

enum class FuelLevel { OK, LOW, CRITICAL }

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
    private const val FUEL_CRITICAL = 0xFFF87171.toInt()
    private const val RAIN = 0xFF60A5FA.toInt()
    private const val NO_SENSOR = 0xFF6B7280.toInt()

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val divider = Paint().apply { color = 0xFF222222.toInt(); strokeWidth = 2f }

    /**
     * @param bike the model's render for the tyre block (null → a drawn outline); [art] says where its wheels are.
     */
    fun render(v: SidePanelView, w: Int, h: Int, bike: Bitmap? = null, art: DashBikeArt = DashBikeArt.GENERIC): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val pad = w * 0.06f
        val d = v.directions
        val t = v.tyres
        if (d != null) {
            val treeTop = drawDirections(c, d, w, h, pad)
            c.drawLine(pad, treeTop, w - pad, treeTop, divider)
            // While navigating the tyres take the tree's place: they matter more mid-route.
            if (t != null) drawTyres(c, t, bike, art, w, treeTop + pad * 0.4f, h - pad * 0.5f, pad)
            else drawEcoCompact(c, v, w, treeTop + pad * 0.6f, h - pad, pad)
        } else if (t != null) {
            val split = h * 0.60f
            drawTyres(c, t, bike, art, w, pad * 0.5f, split, pad)
            c.drawLine(pad, split + pad * 0.3f, w - pad, split + pad * 0.3f, divider)
            drawEcoCompact(c, v, w, split + pad, h - pad, pad)
        } else {
            drawEcoFull(c, v, w, h, pad)
        }
        return bmp
    }

    private val tyreRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bikePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // The studio renders are matte black — lift them so they read on the black panel.
        colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.5f, 0f, 0f, 0f, 38f,
                    0f, 1.5f, 0f, 0f, 38f,
                    0f, 0f, 1.5f, 0f, 42f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF777777.toInt(); strokeCap = Paint.Cap.ROUND
    }

    private fun tyreColor(l: dev.zanderp.opencfmoto.tpms.TyreLevel): Int = when (l) {
        dev.zanderp.opencfmoto.tpms.TyreLevel.OK -> ECO
        dev.zanderp.opencfmoto.tpms.TyreLevel.ALARM -> FUEL_CRITICAL
        dev.zanderp.opencfmoto.tpms.TyreLevel.STALE -> GREY
        dev.zanderp.opencfmoto.tpms.TyreLevel.NONE -> NO_SENSOR
    }

    /**
     * Model + unit title, the bike with a status ring on each wheel, and each tyre's pressure right under
     * its own wheel (front on the right, as the renders face).
     */
    private fun drawTyres(
        c: Canvas, t: dev.zanderp.opencfmoto.tpms.TyrePanel, bike: Bitmap?, art: DashBikeArt,
        w: Int, top: Float, bottom: Float, pad: Float,
    ) {
        val blockH = (bottom - top).coerceAtLeast(1f)
        val titleSize = minOf(blockH * 0.085f, w * 0.075f)
        val titleH = titleSize * 1.35f
        val valueSize = minOf(blockH * 0.17f, w * 0.15f)
        val subSize = valueSize * 0.5f
        val tagsH = valueSize + subSize * 1.5f
        val imgTop = top + titleH
        val availH = (bottom - tagsH - imgTop).coerceAtLeast(1f)
        var imgW = w - 2 * pad
        var imgH = imgW / art.aspect
        if (imgH > availH) {
            imgH = availH
            imgW = imgH * art.aspect
        }
        val imgL = (w - imgW) / 2f
        val cx = w / 2f
        val cy = imgTop + imgH * 0.55f
        glow.shader = android.graphics.RadialGradient(
            cx, cy, imgW * 0.62f,
            intArrayOf(0xFF2E3440.toInt(), 0xFF15181D.toInt(), Color.BLACK), floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, imgW * 0.62f, glow)
        glow.shader = null
        // Title after the backlight, so the glow never covers it.
        small.textSize = titleSize
        small.textAlign = Paint.Align.CENTER
        small.color = if (t.alarm) FUEL_CRITICAL else GREY
        c.drawText(MetricsStripRenderer.fit(t.title, small, w - 2 * pad), w / 2f, top + titleSize, small)
        small.color = GREY
        if (bike != null) {
            c.drawBitmap(bike, null, android.graphics.RectF(imgL, imgTop, imgL + imgW, imgTop + imgH), bikePaint)
        } else {
            drawOutline(c, art, imgL, imgTop, imgW, imgH)
        }
        val wheels = listOf(
            Triple(art.rearX, art.rearY, art.rearR) to t.rear,
            Triple(art.frontX, art.frontY, art.frontR) to t.front,
        )
        val tagY = imgTop + imgH + valueSize * 0.95f
        for ((pos, tag) in wheels) {
            val (fx, fy, fr) = pos
            val wx = imgL + fx * imgW
            val wy = imgTop + fy * imgH
            val r = fr * imgH
            val col = tyreColor(tag.level)
            tyreRing.color = col
            tyreRing.alpha = 90
            tyreRing.strokeWidth = r * 0.34f
            c.drawCircle(wx, wy, r * 1.02f, tyreRing)
            tyreRing.alpha = 255
            tyreRing.strokeWidth = (r * 0.09f).coerceAtLeast(2f)
            c.drawCircle(wx, wy, r * 1.08f, tyreRing)
            // Keep the tag inside the panel even when the wheel sits near its edge.
            big.textSize = valueSize
            big.textAlign = Paint.Align.CENTER
            val half = maxOf(big.measureText(tag.value), w * 0.22f) / 2f
            val tx = wx.coerceIn(pad + half, w - pad - half)
            big.color = if (tag.level == dev.zanderp.opencfmoto.tpms.TyreLevel.OK) Color.WHITE else col
            c.drawText(tag.value, tx, tagY, big)
            small.textSize = subSize
            small.color = col
            c.drawText(MetricsStripRenderer.fit(tag.sub, small, w / 2f - pad), tx, tagY + subSize * 1.3f, small)
        }
        big.color = Color.WHITE
        big.textAlign = Paint.Align.LEFT
        small.color = GREY
        small.textAlign = Paint.Align.LEFT
    }

    /** A plain scooter outline for models we ship no render for: two wheels, deck, seat and fork. */
    private fun drawOutline(c: Canvas, art: DashBikeArt, l: Float, top: Float, w: Float, h: Float) {
        outline.strokeWidth = (h * 0.035f).coerceAtLeast(2f)
        val rx = l + art.rearX * w
        val ry = top + art.rearY * h
        val rr = art.rearR * h
        val fx = l + art.frontX * w
        val fy = top + art.frontY * h
        val fr = art.frontR * h
        c.drawCircle(rx, ry, rr, outline)
        c.drawCircle(fx, fy, fr, outline)
        c.drawLine(rx, ry - rr * 1.3f, fx - fr * 1.2f, fy - fr * 0.4f, outline)
        c.drawLine(fx, fy, fx - fr * 0.9f, top + h * 0.12f, outline)
        c.drawLine(rx - rr * 0.6f, ry - rr * 1.3f, rx + rr * 1.6f, ry - rr * 1.6f, outline)
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
        val fuelY = top + size * 0.98f + small.textSize * 1.4f
        if (fuelY <= bottom) drawFuel(c, v, x, fuelY, w - x - pad)
        val weatherY = fuelY + (if (v.fuelText != null) small.textSize * 1.4f else 0f)
        if (weatherY <= bottom) drawWeather(c, v, x, weatherY, w - x - pad)
    }

    private fun drawWeather(c: Canvas, v: SidePanelView, x: Float, baseline: Float, width: Float) {
        val text = v.weatherText ?: return
        val was = small.color
        small.color = if (v.weatherRain) RAIN else GREY
        c.drawText(MetricsStripRenderer.fit(text, small, width), x, baseline, small)
        small.color = was
    }

    /** "⛽ ~185 km": grey normally, amber when low, red when nearly empty. Skipped with no estimate. */
    private fun drawFuel(c: Canvas, v: SidePanelView, x: Float, baseline: Float, width: Float) {
        val text = v.fuelText ?: return
        val was = small.color
        small.color = when (v.fuelLevel) {
            FuelLevel.OK -> GREY
            FuelLevel.LOW -> EASE_OFF
            FuelLevel.CRITICAL -> FUEL_CRITICAL
        }
        c.drawText(MetricsStripRenderer.fit(text, small, width), x, baseline, small)
        small.color = was
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
        // Each extra line (fuel, weather) borrows a little of the tree so the panel still fits.
        val extraLines = listOfNotNull(v.fuelText, v.weatherText).size
        val size = minOf(w - 2 * pad, h * (0.5f - 0.05f * extraLines))
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
        if (v.fuelText != null) {
            y += small.textSize * 1.6f
            drawFuel(c, v, w / 2f, y, w - 2 * pad)
        }
        if (v.weatherText != null) {
            y += small.textSize * 1.6f
            drawWeather(c, v, w / 2f, y, w - 2 * pad)
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
