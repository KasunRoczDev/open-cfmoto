// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.fuel

import dev.zanderp.opencfmoto.Trip
import java.util.Calendar
import java.util.TimeZone

/**
 * Where the tank stands. [rangeKm]/[litresLeft] are null until a full-tank fill-up is logged (or the
 * model's tank size is unknown). [l100] is the rider's measured consumption when [measured], else
 * the model spec; null when neither exists.
 */
data class FuelStatus(
    val rangeKm: Double?,
    val litresLeft: Double?,
    val tankL: Double?,
    val l100: Double?,
    val measured: Boolean,
    val kmSinceFill: Double?,
    val costPerKm: Double?,
    val monthCost: Double,
    val monthLitres: Double,
    val fillCount: Int,
) {
    /** km per litre, the way riders usually quote it. */
    val kmPerLitre: Double? get() = l100?.let { 100.0 / it }

    companion object {
        val EMPTY = FuelStatus(null, null, null, null, false, null, null, 0.0, 0.0, 0)
    }
}

/**
 * Fuel range and consumption from fill-ups + logged rides. Pure — unit-tested.
 *
 * Consumption: litres poured between two full-tank fills ÷ km the logged rides covered in between,
 * over the last [MAX_INTERVALS] intervals. Range: start full at the first full fill, burn each
 * stretch of riding at that rate, top up on partial fills, and divide what's left by the rate.
 * Rides only count when OpenCfMoto logged them, so an unlogged ride makes the range read high.
 */
object FuelEstimate {
    const val LOW_RANGE_KM = 50.0
    const val CRITICAL_RANGE_KM = 20.0
    const val MIN_INTERVAL_KM = 20.0
    const val MAX_INTERVALS = 5
    // Outside this an interval almost surely missed rides or holds a typo — skip it.
    private const val MIN_L100 = 1.0
    private const val MAX_L100 = 10.0

    /** km of [bikeRaw]'s rides (plus untagged legacy rides) that ended in (afterMs, untilMs]. */
    fun rideKm(trips: List<Trip>, bikeRaw: String?, afterMs: Long, untilMs: Long): Double =
        trips.filter { (it.bikeRaw == null || it.bikeRaw == bikeRaw) && it.end > afterMs && it.end <= untilMs }
            .sumOf { it.distanceMeters } / 1000.0

    /** Litres per 100 km measured from full-to-full intervals, or null with too little data. */
    fun measuredL100(entries: List<FuelEntry>, trips: List<Trip>, bikeRaw: String?): Double? {
        val sorted = entries.sortedBy { it.atMs }
        val fulls = sorted.indices.filter { sorted[it].fullTank }
        var litres = 0.0
        var km = 0.0
        var used = 0
        for (k in fulls.size - 1 downTo 1) {
            if (used == MAX_INTERVALS) break
            val a = fulls[k - 1]
            val b = fulls[k]
            val poured = (a + 1..b).sumOf { sorted[it].litres }
            val ridden = rideKm(trips, bikeRaw, sorted[a].atMs, sorted[b].atMs)
            if (ridden < MIN_INTERVAL_KM) continue
            if (poured / ridden * 100 !in MIN_L100..MAX_L100) continue
            litres += poured
            km += ridden
            used++
        }
        return if (km > 0) litres / km * 100 else null
    }

    fun status(
        entries: List<FuelEntry>,
        trips: List<Trip>,
        bikeRaw: String?,
        spec: FuelSpec?,
        nowMs: Long,
        zone: TimeZone,
    ): FuelStatus {
        val sorted = entries.sortedBy { it.atMs }
        val measured = measuredL100(sorted, trips, bikeRaw)
        val l100 = measured ?: spec?.specL100

        val tank = spec?.tankL
        var level: Double? = null
        var lastAt = 0L
        if (tank != null && l100 != null) {
            for (e in sorted) {
                level?.let { level = (it - rideKm(trips, bikeRaw, lastAt, e.atMs) * l100 / 100).coerceAtLeast(0.0) }
                level = when {
                    e.fullTank -> tank
                    level != null -> minOf(tank, level!! + e.litres)
                    else -> null   // partial fill before we've ever known the level
                }
                lastAt = e.atMs
            }
            level?.let { level = (it - rideKm(trips, bikeRaw, lastAt, nowMs) * l100 / 100).coerceAtLeast(0.0) }
        }
        val left = level
        val range = if (left != null && l100 != null) left / l100 * 100 else null
        val sinceFill = sorted.lastOrNull()?.let { rideKm(trips, bikeRaw, it.atMs, nowMs) }

        val priced = sorted.filter { it.cost != null && it.litres > 0 }
        val pricePerL = if (priced.isNotEmpty()) priced.sumOf { it.cost!! } / priced.sumOf { it.litres } else null
        val costPerKm = if (pricePerL != null && l100 != null) pricePerL * l100 / 100 else null

        val month = monthKey(nowMs, zone)
        val thisMonth = sorted.filter { monthKey(it.atMs, zone) == month }
        return FuelStatus(
            rangeKm = range,
            litresLeft = left,
            tankL = tank,
            l100 = l100,
            measured = measured != null,
            kmSinceFill = sinceFill,
            costPerKm = costPerKm,
            monthCost = thisMonth.sumOf { it.cost ?: 0.0 },
            monthLitres = thisMonth.sumOf { it.litres },
            fillCount = sorted.size,
        )
    }

    private fun monthKey(ms: Long, zone: TimeZone): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }
}
