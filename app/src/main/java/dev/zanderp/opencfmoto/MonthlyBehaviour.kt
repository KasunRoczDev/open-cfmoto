// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Calendar
import java.util.TimeZone

/** A calendar month in the phone's time zone; [month] is 1–12. */
data class MonthKey(val year: Int, val month: Int)

data class MonthSummary(
    val key: MonthKey,
    val rides: Int,
    val distanceKm: Double,
    val ridingTimeMs: Long,
    val topSpeedMs: Float,
    val avgMovingMs: Float,
    val hardBrakes: Int,
    val hardAccels: Int,
    val stops: Int,
    val score: Int?,
    /** Distance, riding time, top speed, smooth braking, smooth acceleration — each 0–1. */
    val radar: List<Float>,
)

/** Month-level riding behaviour for the Trips screen. Pure — unit-tested on the JVM. */
object MonthlyBehaviour {
    fun monthOf(epochMs: Long, zone: TimeZone): MonthKey {
        val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return MonthKey(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** Months that have rides, newest first. */
    fun months(trips: List<Trip>, zone: TimeZone): List<MonthKey> =
        trips.map { monthOf(it.start, zone) }.distinct()
            .sortedWith(compareByDescending<MonthKey> { it.year }.thenByDescending { it.month })

    fun summarize(trips: List<Trip>, key: MonthKey, zone: TimeZone): MonthSummary? {
        // Skip unreadable/corrupt files (non-finite numbers) — spec §5.
        val inMonth = trips.filter { it.distanceMeters.isFinite() && monthOf(it.start, zone) == key }
        if (inMonth.isEmpty()) return null
        val analyses = inMonth.map { RideAnalysis.analyze(it) }
        val km = inMonth.sumOf { it.distanceKm }
        val movingMs = inMonth.sumOf { it.movingTimeMs }
        val top = analyses.maxOf { it.maxSpeedMs }
        val brakes = analyses.sumOf { it.hardBrakes.size }
        val accels = analyses.sumOf { it.hardAccels.size }
        return MonthSummary(
            key = key,
            rides = inMonth.size,
            distanceKm = km,
            ridingTimeMs = movingMs,
            topSpeedMs = top,
            avgMovingMs = if (movingMs > 0) (inMonth.sumOf { it.distanceMeters } / (movingMs / 1000.0)).toFloat() else 0f,
            hardBrakes = brakes,
            hardAccels = accels,
            stops = analyses.sumOf { it.stops.size },
            score = RideAnalysis.score(brakes + accels, km),
            radar = radar(km, movingMs, top, brakes, accels),
        )
    }

    fun radar(km: Double, movingMs: Long, topMs: Float, brakes: Int, accels: Int): List<Float> {
        fun clamp(x: Double) = x.coerceIn(0.0, 1.0).toFloat()
        fun per100(e: Int) = if (km > 0) e / (km / 100.0) else 0.0
        return listOf(
            clamp(km / 500.0),
            clamp(movingMs / 3_600_000.0 / 20.0),
            clamp(topMs * 3.6 / 120.0),
            clamp(1.0 - minOf(1.0, per100(brakes) / 10.0)),
            clamp(1.0 - minOf(1.0, per100(accels) / 10.0)),
        )
    }
}
