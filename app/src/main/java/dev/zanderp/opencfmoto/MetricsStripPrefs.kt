// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/** Rider choice for the metrics strip under Android Auto. [px] is the requested band in dash pixels. */
enum class StripHeight(val px: Int, @StringRes val labelRes: Int) {
    OFF(0, R.string.pref_strip_off),
    SMALL(48, R.string.pref_strip_small),
    MEDIUM(64, R.string.pref_strip_medium),
    LARGE(80, R.string.pref_strip_large),
}

/**
 * What the strip shows: speed/altitude/heading (band reserved under AA), turn-by-turn directions (overlay),
 * or a side panel beside Android Auto (directions + eco tree).
 */
enum class StripMode { METRICS, DIRECTIONS, SIDE }

/**
 * Strip settings frozen at Connect ([MetricsStripPrefs.snapshot] → [BikeProfileHolder.stripSession]),
 * so AA margins, the reserved band and the nav service all agree for the whole connection — including
 * a park/resume after the rider changed Setup mid-ride.
 */
data class StripSnapshot(
    val requestPx: Int,
    val turnInfo: Boolean,
    val cells: Set<StripCell> = StripCells.ALL,
    val mode: StripMode = StripMode.METRICS,
    val sidePercent: Int = 0,
    val sideLeft: Boolean = false,
) {
    /** Directions: the band is drawn over Android Auto (which keeps the full screen) only while navigating. */
    val overlay: Boolean get() = mode == StripMode.DIRECTIONS && requestPx > 0

    /** Side panel: a column beside Android Auto (directions + eco tree). */
    val side: Boolean get() = mode == StripMode.SIDE && sidePercent > 0

    /** Dash rows Android Auto must be laid out without (only Metrics mode reserves the bottom band). */
    val aaReservePx: Int get() = if (overlay || side) 0 else requestPx

    companion object {
        val OFF = StripSnapshot(0, false)

        /** Turn info only makes sense with a visible strip; Directions and Side panel require it. */
        fun of(
            height: StripHeight,
            turnInfo: Boolean,
            cells: Set<StripCell> = StripCells.ALL,
            mode: StripMode = StripMode.METRICS,
            sideLeft: Boolean = false,
        ): StripSnapshot = StripSnapshot(
            requestPx = height.px,
            turnInfo = (turnInfo || mode != StripMode.METRICS) && height != StripHeight.OFF,
            cells = cells,
            mode = mode,
            sidePercent = if (mode == StripMode.SIDE) SidePanelLayout.percentFor(height) else 0,
            sideLeft = sideLeft,
        )
    }
}

/** Per-bike metrics-strip settings (scoped via [BikeScope]). Read at Connect; changes apply next connect. */
object MetricsStripPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_HEIGHT = "metrics_strip_height"
    private const val KEY_TURN_INFO = "metrics_strip_turn_info"
    private const val KEY_CELLS = "metrics_strip_cells"
    private const val KEY_MODE = "metrics_strip_mode"
    private const val KEY_SIDE_LEFT = "metrics_strip_side_left"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun height(ctx: Context): StripHeight {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_HEIGHT, StripHeight.OFF.name)
        return runCatching { StripHeight.valueOf(name!!) }.getOrDefault(StripHeight.OFF)
    }

    fun setHeight(ctx: Context, h: StripHeight) {
        BikeScope.putString(prefs(ctx), ctx, KEY_HEIGHT, h.name)
    }

    /** Experimental: ask Android Auto for next-turn data (instrument cluster service). */
    fun turnInfo(ctx: Context): Boolean = BikeScope.getBoolean(prefs(ctx), ctx, KEY_TURN_INFO, false)

    fun setTurnInfo(ctx: Context, on: Boolean) {
        BikeScope.putBoolean(prefs(ctx), ctx, KEY_TURN_INFO, on)
    }

    /** Which metric cells the strip shows (e.g. no speed on a 150SC, whose dash shows it already). */
    fun cells(ctx: Context): Set<StripCell> =
        StripCells.parse(BikeScope.getString(prefs(ctx), ctx, KEY_CELLS, null))

    fun setCells(ctx: Context, cells: Set<StripCell>) {
        BikeScope.putString(prefs(ctx), ctx, KEY_CELLS, StripCells.serialize(cells))
    }

    fun mode(ctx: Context): StripMode {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_MODE, StripMode.METRICS.name)
        return runCatching { StripMode.valueOf(name!!) }.getOrDefault(StripMode.METRICS)
    }

    fun setMode(ctx: Context, m: StripMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_MODE, m.name)
    }

    /** Side panel on the left (default right, where Android Auto would put its media card). */
    fun sideLeft(ctx: Context): Boolean = BikeScope.getBoolean(prefs(ctx), ctx, KEY_SIDE_LEFT, false)

    fun setSideLeft(ctx: Context, left: Boolean) {
        BikeScope.putBoolean(prefs(ctx), ctx, KEY_SIDE_LEFT, left)
    }

    /** Settings to freeze into [BikeProfileHolder.stripSession] at Connect. */
    fun snapshot(ctx: Context): StripSnapshot = StripSnapshot.of(height(ctx), turnInfo(ctx), cells(ctx), mode(ctx), sideLeft(ctx))
}
