// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/** Saves the eco forest (shared across bikes — not BikeScope). Unreadable data → an empty forest. */
object EcoStore {
    private const val PREFS = "opencfmoto_eco"

    fun load(ctx: Context): EcoForest = try {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        EcoForest(
            trees = p.getInt("trees", 0).coerceAtLeast(0),
            currentMeters = p.getFloat("current_m", 0f).toDouble().coerceIn(0.0, EcoForest.TREE_METERS),
            allTimeMeters = p.getFloat("all_time_m", 0f).toDouble().coerceAtLeast(0.0),
            month = p.getString("month", "") ?: "",
            monthEcoMeters = p.getFloat("month_eco_m", 0f).toDouble().coerceAtLeast(0.0),
            monthMovingMeters = p.getFloat("month_moving_m", 0f).toDouble().coerceAtLeast(0.0),
        )
    } catch (e: Exception) {
        LogBus.log("[ECO] saved forest unreadable ($e) — starting fresh")
        EcoForest()
    }

    fun save(ctx: Context, f: EcoForest) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("trees", f.trees)
            .putFloat("current_m", f.currentMeters.toFloat())
            .putFloat("all_time_m", f.allTimeMeters.toFloat())
            .putString("month", f.month)
            .putFloat("month_eco_m", f.monthEcoMeters.toFloat())
            .putFloat("month_moving_m", f.monthMovingMeters.toFloat())
            .apply()
    }
}
