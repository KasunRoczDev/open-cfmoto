// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one strip frame. Called only from the single `metrics-strip` thread, so the shared Paints
 * are safe. Returns a new bitmap each call; the compositor recycles it after upload (≤ 1/s).
 */
object MetricsStripRenderer {
    private const val LABEL_GREY = 0xFFAAAAAA.toInt()
    private const val DIVIDER = 0xFF333333.toInt()

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_GREY; textAlign = Paint.Align.CENTER
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint().apply { color = DIVIDER; strokeWidth = 2f }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    /** [metrics] = the rider's chosen cells; the turn cell is added whenever a turn is known. */
    fun render(v: StripValues, w: Int, h: Int, metrics: Set<StripCell> = StripCells.ALL): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val shown = if (v.turn != null) metrics + StripCell.TURN else metrics - StripCell.TURN
        val cells = MetricsStripLayout.layout(w, h, shown.toList())
        // Speed gets the double-width cell (and bigger digits) when there's no turn and company.
        val bigSpeed = v.turn == null && cells.size > 1
        for ((cell, r) in cells) {
            when (cell) {
                StripCell.TURN -> drawTurn(c, r, v)
                StripCell.SPEED -> drawValue(c, r, v.speed, v.speedUnit, if (bigSpeed) 0.65f else 0.55f)
                StripCell.ALTITUDE -> drawValue(c, r, v.altitude, "ALT ${v.altitudeUnit}", 0.55f)
                StripCell.HEADING -> drawValue(c, r, v.compass, v.degrees, 0.55f)
            }
        }
        for (k in 1 until cells.size) {
            val x = cells[k].second.x.toFloat()
            c.drawLine(x, h * 0.15f, x, h * 0.85f, dividerPaint)
        }
        return bmp
    }

    private fun drawValue(c: Canvas, r: StripRect, value: String, label: String, frac: Float) {
        val cx = r.x + r.w / 2f
        valuePaint.textSize = r.h * frac
        labelPaint.textSize = r.h * 0.22f
        val valueBaseline = r.h * 0.08f + valuePaint.textSize * 0.9f
        c.drawText(fit(value, valuePaint, r.w * 0.92f), cx, valueBaseline, valuePaint)
        if (label.isNotEmpty()) c.drawText(fit(label, labelPaint, r.w * 0.92f), cx, r.h * 0.95f, labelPaint)
    }

    private fun drawTurn(c: Canvas, r: StripRect, v: StripValues) {
        val t = v.turn ?: return
        val box = min(r.h.toFloat(), r.w * 0.38f)
        val pad = box * 0.12f
        drawArrow(c, t, r.x + pad, (r.h - box) / 2f + pad, box - 2 * pad)
        val text = StripRect(r.x + box.toInt(), 0, r.w - box.toInt(), r.h)
        drawValue(c, text, v.turnDistance.ifEmpty { MetricsFormat.NONE }, v.turnRoad, 0.45f)
    }

    internal fun drawArrow(c: Canvas, t: NextTurn, left: Float, top: Float, s: Float) {
        arrowStroke.strokeWidth = s * 0.12f
        val cx = left + s / 2f
        val bottom = top + s
        val mid = top + s * 0.5f
        when (t.kind) {
            TurnKind.DESTINATION -> {
                c.drawCircle(cx, mid, s * 0.22f, arrowFill)
                return
            }
            TurnKind.ROUNDABOUT -> {
                c.drawCircle(cx, mid, s * 0.28f, arrowStroke)
                t.exitNumber?.let { n ->
                    numberPaint.textSize = s * 0.34f
                    c.drawText(n.toString(), cx, mid + s * 0.12f, numberPaint)
                }
                return
            }
            TurnKind.U_TURN -> {
                val dir = if (t.side == TurnSide.RIGHT) 1f else -1f
                val x2 = cx + dir * s * 0.3f
                val path = Path().apply {
                    moveTo(cx, bottom)
                    lineTo(cx, top + s * 0.35f)
                    cubicTo(cx, top, x2, top, x2, top + s * 0.35f)
                    lineTo(x2, top + s * 0.55f)
                }
                c.drawPath(path, arrowStroke)
                arrowHead(c, x2, top + s * 0.8f, Math.PI.toFloat(), s)
                return
            }
            else -> Unit
        }
        val deg = when (t.kind) {
            TurnKind.SLIGHT, TurnKind.MERGE, TurnKind.FORK, TurnKind.RAMP_ON, TurnKind.RAMP_OFF -> 45f
            TurnKind.NORMAL -> 90f
            TurnKind.SHARP -> 135f
            else -> 0f
        }
        val sign = when (t.side) { TurnSide.LEFT -> -1f; TurnSide.RIGHT -> 1f; TurnSide.NONE -> 0f }
        val a = Math.toRadians((deg * sign).toDouble()).toFloat()
        val len = s * 0.42f
        val tipX = cx + sin(a) * len
        val tipY = mid - cos(a) * len
        val headLen = s * 0.22f
        val baseX = tipX - sin(a) * headLen
        val baseY = tipY + cos(a) * headLen
        val path = Path().apply {
            moveTo(cx, bottom)
            lineTo(cx, mid)
            lineTo(baseX, baseY)
        }
        c.drawPath(path, arrowStroke)
        arrowHead(c, tipX, tipY, a, s)
    }

    /** Filled triangle with its point at ([tipX],[tipY]) heading [angle] rad (0 = up, +π/2 = right). */
    private fun arrowHead(c: Canvas, tipX: Float, tipY: Float, angle: Float, s: Float) {
        val headLen = s * 0.22f
        val halfW = s * 0.16f
        val dx = sin(angle); val dy = -cos(angle)
        val bx = tipX - dx * headLen; val by = tipY - dy * headLen
        val px = cos(angle); val py = sin(angle)
        val p = Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px * halfW, by + py * halfW)
            lineTo(bx - px * halfW, by - py * halfW)
            close()
        }
        c.drawPath(p, arrowFill)
    }

    internal fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var end = text.length
        val ell = p.measureText("…")
        while (end > 1 && p.measureText(text, 0, end) + ell > maxW) end--
        return text.substring(0, end) + "…"
    }
}
