// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws one Directions band frame: [next turn | then | lanes | ETA]. Called only from the single
 * `metrics-strip` thread (shared Paints). New bitmap per call; the compositor recycles it.
 */
object DirectionsRenderer {
    private const val GREY = 0xFFAAAAAA.toInt()
    private const val DIM = 0xFF555555.toInt()
    private const val DIVIDER = 0xFF333333.toInt()

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val divider = Paint().apply { color = DIVIDER; strokeWidth = 2f }

    fun render(v: DirectionsView, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val hf = h.toFloat()
        var x = 0f

        // 1) Next turn: arrow + distance, road under it.
        val turnW = hf * (DirectionsFormat.TURN_W + if (v.road != null) DirectionsFormat.ROAD_EXTRA_W else 0f)
        val box = hf * 0.9f
        val pad = box * 0.12f
        MetricsStripRenderer.drawArrow(c, v.turn, x + pad, (hf - box) / 2f + pad, box - 2 * pad)
        val textX = x + box + hf * 0.1f
        val textW = turnW - box - hf * 0.15f
        big.textSize = hf * (if (v.road != null) 0.42f else 0.55f)
        val distBase = if (v.road != null) hf * 0.48f else hf * 0.68f
        c.drawText(MetricsStripRenderer.fit(v.distance, big, textW), textX, distBase, big)
        if (v.road != null) {
            small.textSize = hf * 0.24f
            c.drawText(MetricsStripRenderer.fit(v.road, small, textW), textX, hf * 0.86f, small)
        }
        x += turnW

        // 2) Then: small "then" + arrow.
        if (v.then != null) {
            c.drawLine(x, hf * 0.15f, x, hf * 0.85f, divider)
            val cellW = hf * DirectionsFormat.THEN_W
            small.textSize = hf * 0.24f
            c.drawText("then", x + hf * 0.12f, hf * 0.6f, small)
            val s = hf * 0.55f
            MetricsStripRenderer.drawArrow(c, v.then, x + cellW - s - hf * 0.1f, (hf - s) / 2f, s)
            x += cellW
        }

        // 3) Lanes: one arrow per lane (first arrow of each lane); lanes to use bright, others dim.
        if (v.lanes.isNotEmpty()) {
            c.drawLine(x, hf * 0.15f, x, hf * 0.85f, divider)
            val laneW = hf * DirectionsFormat.LANE_W
            var lx = x + hf * DirectionsFormat.LANES_PAD_W / 2f
            val s = laneW * 0.9f
            for (lane in v.lanes) {
                val (kind, side) = lane.arrows.firstOrNull() ?: (TurnKind.STRAIGHT to TurnSide.NONE)
                val bmpLane = Bitmap.createBitmap(s.toInt().coerceAtLeast(1), s.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                MetricsStripRenderer.drawArrow(Canvas(bmpLane), NextTurn(kind, side), 0f, 0f, s)
                val tint = Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        if (lane.highlighted) Color.WHITE else DIM, android.graphics.PorterDuff.Mode.SRC_IN,
                    )
                }
                c.drawBitmap(bmpLane, lx, (hf - s) / 2f, tint)
                bmpLane.recycle()
                lx += laneW
            }
            x += laneW * v.lanes.size + hf * DirectionsFormat.LANES_PAD_W
        }

        // 4) ETA + remaining, right-aligned.
        if (v.eta != null) {
            val etaW = hf * DirectionsFormat.ETA_W
            val left = (w - etaW).coerceAtLeast(x)
            c.drawLine(left, hf * 0.15f, left, hf * 0.85f, divider)
            val right = w - hf * 0.15f
            big.textSize = hf * 0.36f
            big.textAlign = Paint.Align.RIGHT
            c.drawText(MetricsStripRenderer.fit(v.eta, big, etaW * 0.9f), right, hf * 0.48f, big)
            big.textAlign = Paint.Align.LEFT
            if (v.remaining != null) {
                small.textSize = hf * 0.24f
                small.textAlign = Paint.Align.RIGHT
                c.drawText(v.remaining, right, hf * 0.86f, small)
                small.textAlign = Paint.Align.LEFT
            }
        }
        return bmp
    }
}
