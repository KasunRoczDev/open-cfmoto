// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Axis-aligned rectangle in pixels. Pure, so layout math is unit-testable without Android. */
data class StripRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Strip cells, in display order. [TURN] follows Turn info; the rest are the rider's choice. */
enum class StripCell { TURN, SPEED, ALTITUDE, HEADING }

/** The rider's choice of metric cells (stored as "SPEED,ALTITUDE,…"). Never empty. */
object StripCells {
    val ALL: Set<StripCell> = setOf(StripCell.SPEED, StripCell.ALTITUDE, StripCell.HEADING)

    /** Unknown/missing → all metrics (the pre-choice behaviour). [StripCell.TURN] is ignored. */
    fun parse(s: String?): Set<StripCell> {
        val set = s.orEmpty().split(',')
            .mapNotNull { runCatching { StripCell.valueOf(it.trim()) }.getOrNull() }
            .filter { it != StripCell.TURN }
            .toSet()
        return set.ifEmpty { ALL }
    }

    fun serialize(set: Set<StripCell>): String = set.sortedBy { it.ordinal }.joinToString(",") { it.name }

    /** [set] with [cell] flipped, or null when that would leave no cells (use Off to hide the strip). */
    fun toggle(set: Set<StripCell>, cell: StripCell): Set<StripCell>? {
        val next = if (cell in set) set - cell else set + cell
        return next.ifEmpty { null }
    }
}

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
     * Cell rects (strip-local) for the [shown] cells, in [StripCell] order. Cells share the width
     * equally, except speed takes two units when there's no turn cell (and it isn't alone). The last
     * cell absorbs the rounding remainder.
     */
    fun layout(stripW: Int, stripH: Int, shown: List<StripCell>): List<Pair<StripCell, StripRect>> {
        val cells = shown.distinct().sortedBy { it.ordinal }
        if (cells.isEmpty()) return emptyList()
        val hasTurn = StripCell.TURN in cells
        val units = cells.map { if (it == StripCell.SPEED && !hasTurn && cells.size > 1) 2 else 1 }
        val unitW = stripW / units.sum()
        var x = 0
        return cells.mapIndexed { i, cell ->
            val w = if (i == cells.lastIndex) stripW - x else units[i] * unitW
            (cell to StripRect(x, 0, w, stripH)).also { x += w }
        }
    }
}
