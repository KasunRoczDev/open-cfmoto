// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Trip
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class RideDay { TODAY, YESTERDAY, EARLIER }

data class LastRide(val day: RideDay, val endMs: Long, val km: Double, val movingMs: Long)

/** The Ride hero's card: the latest ride and this calendar month's distance (spec §2.2). */
data class RideSummary(val last: LastRide, val monthKm: Int) {
    companion object {
        fun of(trips: List<Trip>, nowMs: Long, zone: ZoneId): RideSummary? {
            val latest = trips.maxByOrNull { it.end } ?: return null
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val rideDay = Instant.ofEpochMilli(latest.end).atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(rideDay, today)
            val day = when {
                daysAgo <= 0L -> RideDay.TODAY
                daysAgo == 1L -> RideDay.YESTERDAY
                else -> RideDay.EARLIER
            }
            val month = YearMonth.from(today)
            val monthKm = trips
                .filter { YearMonth.from(Instant.ofEpochMilli(it.start).atZone(zone)) == month }
                .sumOf { it.distanceKm }
            return RideSummary(
                LastRide(day, latest.end, (latest.distanceKm * 10).roundToLong() / 10.0, latest.movingTimeMs.coerceAtLeast(0)),
                monthKm.roundToInt(),
            )
        }

        /** "28 min" or "1 h 05 min". */
        fun durationText(ms: Long): String {
            val min = ms.coerceAtLeast(0) / 60_000
            return if (min < 60) "$min min" else "${min / 60} h ${String.format(Locale.US, "%02d", min % 60)} min"
        }
    }
}
