// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.report

import dev.zanderp.opencfmoto.EcoBand
import dev.zanderp.opencfmoto.EcoScorer
import dev.zanderp.opencfmoto.Trip
import dev.zanderp.opencfmoto.fuel.FuelEntry
import dev.zanderp.opencfmoto.fuel.FuelStatus
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** One ride as the report sees it; [ecoPercent] is null for rides too short to score. */
data class RideScore(val id: String, val startMs: Long, val km: Double, val movingMs: Long, val ecoPercent: Int?)

/** A Monday-to-Sunday summary of the rider's own rides — the open-cfmoto take on a driving report. */
data class WeekReport(
    val weekStartMs: Long,
    val rides: List<RideScore>,
    val km: Double,
    val movingMs: Long,
    val maxKmh: Int,
    /** Share of moving distance ridden in the bike's eco band, or null with too little riding. */
    val ecoPercent: Int?,
    /** Fuel the week's riding burned and what it cost, from the fuel log's consumption and price. */
    val fuelLitres: Double?,
    val fuelCost: Double?,
    val fillUps: Int,
    val fillUpSpend: Double,
    val best: RideScore?,
    val prevKm: Double,
    /** km per day, Monday first. */
    val dailyKm: List<Double>,
) {
    val avgKmh: Int? get() = if (movingMs > 0) (km / (movingMs / 3_600_000.0)).roundToInt() else null

    /** Change vs the previous week in %, or null when that week had (almost) no riding. */
    val vsPrevPercent: Int? get() = if (prevKm >= 1.0) ((km - prevKm) / prevKm * 100).roundToInt() else null
}

/** Builds [WeekReport]s from saved rides + the fuel log. Pure — unit-tested. */
object WeeklyReport {
    /** A "best ride" has to be a real ride, not a hop to the shop. */
    const val BEST_MIN_KM = 3.0
    private const val MIN_ECO_METERS = 200.0

    /** Monday 00:00 (local) of the week holding [ms]. */
    fun weekStart(ms: Long, zone: TimeZone): Long = Calendar.getInstance(zone).run {
        firstDayOfWeek = Calendar.MONDAY
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
        timeInMillis
    }

    /** The Monday [weeks] weeks after (negative = before) [weekStartMs]; DST-safe. */
    fun shiftWeeks(weekStartMs: Long, weeks: Int, zone: TimeZone): Long = Calendar.getInstance(zone).run {
        timeInMillis = weekStartMs
        add(Calendar.DAY_OF_MONTH, 7 * weeks)
        timeInMillis
    }

    /** Eco metres and moving metres for [trip], replaying its GPS track through the live eco rules. */
    fun ecoOf(trip: Trip, band: EcoBand): Pair<Double, Double> {
        val scorer = EcoScorer(band)
        for (p in trip.points) scorer.onFix(p.speedMs, p.time)
        return scorer.ecoMeters to scorer.movingMeters
    }

    fun build(
        trips: List<Trip>,
        bikeRaw: String?,
        fills: List<FuelEntry>,
        fuel: FuelStatus,
        band: EcoBand,
        weekStartMs: Long,
        zone: TimeZone,
    ): WeekReport {
        val end = shiftWeeks(weekStartMs, 1, zone)
        val prevStart = shiftWeeks(weekStartMs, -1, zone)
        val mine = trips.filter { it.bikeRaw == null || it.bikeRaw == bikeRaw }
        val week = mine.filter { it.start in weekStartMs until end }.sortedBy { it.start }

        var ecoM = 0.0
        var movingM = 0.0
        val scores = week.map { t ->
            val (e, m) = ecoOf(t, band)
            ecoM += e
            movingM += m
            RideScore(t.id, t.start, t.distanceKm, t.movingTimeMs, if (m >= MIN_ECO_METERS) (100 * e / m).roundToInt() else null)
        }
        val km = week.sumOf { it.distanceKm }
        val daily = DoubleArray(7)
        for (t in week) {
            var day = 0
            while (day < 6 && t.start >= shiftDays(weekStartMs, day + 1, zone)) day++
            daily[day] += t.distanceKm
        }
        val best = scores.filter { it.km >= BEST_MIN_KM }.let { long ->
            long.filter { it.ecoPercent != null }.maxWithOrNull(compareBy<RideScore>({ it.ecoPercent }, { it.km }))
                ?: long.maxByOrNull { it.km }
        }
        val weekFills = fills.filter { it.atMs in weekStartMs until end }
        return WeekReport(
            weekStartMs = weekStartMs,
            rides = scores,
            km = km,
            movingMs = week.sumOf { it.movingTimeMs },
            maxKmh = week.maxOfOrNull { it.maxKmh } ?: 0,
            ecoPercent = if (movingM >= MIN_ECO_METERS) (100 * ecoM / movingM).roundToInt() else null,
            fuelLitres = fuel.l100?.let { km * it / 100 },
            fuelCost = fuel.costPerKm?.let { km * it },
            fillUps = weekFills.size,
            fillUpSpend = weekFills.sumOf { it.cost ?: 0.0 },
            best = best,
            prevKm = mine.filter { it.start in prevStart until weekStartMs }.sumOf { it.distanceKm },
            dailyKm = daily.toList(),
        )
    }

    private fun shiftDays(ms: Long, days: Int, zone: TimeZone): Long = Calendar.getInstance(zone).run {
        timeInMillis = ms
        add(Calendar.DAY_OF_MONTH, days)
        timeInMillis
    }
}
