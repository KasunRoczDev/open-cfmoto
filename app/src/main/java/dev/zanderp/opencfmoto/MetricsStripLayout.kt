// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Axis-aligned rectangle in pixels. Pure, so layout math is unit-testable without Android. */
data class StripRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Geometry for the rider metrics strip: the band reserved under Android Auto on the bike dash.
 * Canvas coordinates are top-left origin; GL placement goes through [glStripY]/[glAaShift] only.
 */
object MetricsStripLayout {
    const val MIN_PX = 32
    const val MIN_CANVAS_H = 128

    /**
     * GL is bottom-origin. The band is expected at the visual bottom = GL y 0. If a bike test shows
     * the strip at the top (and upside-down), flip this one flag — [AaCompositor] derives both the
     * band position and the texture orientation from it.
     */
    internal const val STRIP_AT_GL_BOTTOM = true

    /** Band height for [canvasH]: 0 when off or the canvas is too small, else clamped to 32..H/4. */
    fun resolvePx(requestedPx: Int, canvasH: Int): Int {
        if (requestedPx <= 0 || canvasH < MIN_CANVAS_H) return 0
        return requestedPx.coerceIn(MIN_PX, canvasH / 4)
    }

    fun stripRect(canvasW: Int, canvasH: Int, stripPx: Int): StripRect =
        StripRect(0, canvasH - stripPx, canvasW, stripPx)

    /** GL (bottom-origin) y of the band. */
    fun glStripY(canvasH: Int, stripPx: Int): Int = if (STRIP_AT_GL_BOTTOM) 0 else canvasH - stripPx

    /** GL y offset applied to the Android Auto viewport so it clears the band. */
    fun glAaShift(stripPx: Int): Int = if (STRIP_AT_GL_BOTTOM) stripPx else 0

    /** True when a top-origin canvas y (a dash touch) lands inside the band. */
    fun isInStrip(cy: Int, canvasH: Int, stripPx: Int): Boolean = stripPx > 0 && cy >= canvasH - stripPx

    /**
     * Touch y to map into Android Auto, or null to drop it. A DOWN that lands in the band is dropped;
     * a MOVE/UP there is clamped to the last AA row so a drag that ends in the band still lifts the
     * pointer in AA (dropping it would leave the pointer stuck down).
     */
    fun touchY(cy: Int, canvasH: Int, stripPx: Int, isDown: Boolean): Int? = when {
        !isInStrip(cy, canvasH, stripPx) -> cy
        isDown -> null
        else -> canvasH - stripPx - 1
    }

    /** Panel shape Android Auto should lay out for when the band takes [stripPx] off the bottom. */
    fun panelForAa(panelW: Int, panelH: Int, stripPx: Int): Pair<Int, Int> =
        panelW to (panelH - stripPx).coerceAtLeast(16)

    /**
     * Cell rects in strip-local coordinates. With a turn: 4 equal cells [turn, speed, altitude,
     * heading]. Without: [speed (2 units), altitude, heading]. The last cell absorbs the remainder.
     */
    fun cells(stripW: Int, stripH: Int, hasTurn: Boolean): List<StripRect> {
        val unit = stripW / 4
        val widths = if (hasTurn) {
            listOf(unit, unit, unit, stripW - 3 * unit)
        } else {
            listOf(2 * unit, unit, stripW - 3 * unit)
        }
        var x = 0
        return widths.map { w -> StripRect(x, 0, w, stripH).also { x += w } }
    }
}
