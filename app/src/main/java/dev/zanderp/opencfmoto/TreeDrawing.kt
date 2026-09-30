// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** A simple drawn tree for each growth stage (1 seed … 5 full). Used on the dash panel and the Forest screen. */
object TreeDrawing {
    private val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B5A2B.toInt(); style = Paint.Style.FILL }
    private val soil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B4636.toInt(); style = Paint.Style.FILL }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val greens = intArrayOf(0xFF34D399.toInt(), 0xFF22C55E.toInt(), 0xFF16A34A.toInt())

    /**
     * Draws a tree standing on ([cx], [baseY]) fitting a [size]×[size] box. [shade] (0–2) varies the green
     * so a forest doesn't look copy-pasted. Call from one thread at a time (shared Paints).
     */
    fun draw(c: Canvas, stage: Int, cx: Float, baseY: Float, size: Float, shade: Int = 0) {
        leaf.color = greens[((shade % 3) + 3) % 3]
        val s = size
        when (stage.coerceIn(1, 5)) {
            1 -> { // seed in soil + a tiny shoot
                c.drawOval(RectF(cx - s * 0.18f, baseY - s * 0.08f, cx + s * 0.18f, baseY), soil)
                c.drawCircle(cx, baseY - s * 0.12f, s * 0.05f, leaf)
            }
            2 -> { // sprout: stem + two leaves
                c.drawRect(cx - s * 0.02f, baseY - s * 0.3f, cx + s * 0.02f, baseY, trunk)
                c.drawOval(RectF(cx - s * 0.16f, baseY - s * 0.36f, cx, baseY - s * 0.26f), leaf)
                c.drawOval(RectF(cx, baseY - s * 0.36f, cx + s * 0.16f, baseY - s * 0.26f), leaf)
            }
            3 -> { // sapling
                c.drawRect(cx - s * 0.03f, baseY - s * 0.5f, cx + s * 0.03f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.55f, s * 0.18f, leaf)
            }
            4 -> { // young tree
                c.drawRect(cx - s * 0.045f, baseY - s * 0.6f, cx + s * 0.045f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.66f, s * 0.24f, leaf)
                c.drawCircle(cx - s * 0.15f, baseY - s * 0.52f, s * 0.14f, leaf)
                c.drawCircle(cx + s * 0.15f, baseY - s * 0.52f, s * 0.14f, leaf)
            }
            else -> { // full tree
                c.drawRect(cx - s * 0.06f, baseY - s * 0.62f, cx + s * 0.06f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.72f, s * 0.28f, leaf)
                c.drawCircle(cx - s * 0.22f, baseY - s * 0.55f, s * 0.2f, leaf)
                c.drawCircle(cx + s * 0.22f, baseY - s * 0.55f, s * 0.2f, leaf)
            }
        }
    }

    fun stageName(stage: Int): String = when (stage.coerceIn(1, 5)) {
        1 -> "Seed"
        2 -> "Sprout"
        3 -> "Sapling"
        4 -> "Young tree"
        else -> "Full tree"
    }
}
