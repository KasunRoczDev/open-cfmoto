// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Latest rider values for the metrics strip. Immutable; [fixAtMs] is `SystemClock.elapsedRealtime`
 * of the last GPS fix (0 = none yet). Pure — no Android calls, so it is unit-tested on the JVM.
 */
data class RiderMetrics(
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
    val headingDeg: Float? = null,
    val fixAtMs: Long = 0L,
    val turn: NextTurn? = null,
) {
    /** Apply a GPS fix. Heading only follows the GPS bearing while moving; otherwise it holds. */
    fun withFix(speedMps: Float?, altitudeM: Double?, bearingDeg: Float?, atMs: Long): RiderMetrics {
        val moving = speedMps != null && speedMps * 3.6f > MetricsFormat.HEADING_MIN_KMH
        return copy(
            speedMps = speedMps,
            altitudeM = altitudeM,
            headingDeg = if (moving && bearingDeg != null) bearingDeg else headingDeg,
            fixAtMs = atMs,
        )
    }
}

/** Display strings for one strip frame. Data class equality = "nothing visible changed". */
data class StripValues(
    val speed: String,
    val speedUnit: String,
    val altitude: String,
    val altitudeUnit: String,
    val compass: String,
    val degrees: String,
    val turn: NextTurn?,
    val turnDistance: String,
    val turnRoad: String,
)

object MetricsFormat {
    const val STALE_MS = 5_000L
    const val HEADING_MIN_KMH = 5f
    const val NONE = "--"
    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun values(m: RiderMetrics, units: MapUnits, nowMs: Long): StripValues {
        val fresh = m.fixAtMs > 0 && nowMs - m.fixAtMs <= STALE_MS
        val imperial = units == MapUnits.IMPERIAL
        val turn = m.turn
        return StripValues(
            speed = if (fresh && m.speedMps != null) speed(m.speedMps, imperial) else NONE,
            speedUnit = if (imperial) "mph" else "km/h",
            altitude = if (fresh && m.altitudeM != null) altitude(m.altitudeM, imperial) else NONE,
            altitudeUnit = if (imperial) "ft" else "m",
            compass = m.headingDeg?.let { compass(it) } ?: NONE,
            degrees = m.headingDeg?.let { degrees(it) } ?: "",
            turn = turn,
            turnDistance = turn?.distanceM?.let { turnDistance(it, imperial) } ?: "",
            turnRoad = turn?.road.orEmpty(),
        )
    }

    fun speed(mps: Float, imperial: Boolean): String =
        (mps * if (imperial) 2.23694f else 3.6f).roundToInt().coerceAtLeast(0).toString()

    fun altitude(meters: Double, imperial: Boolean): String =
        if (imperial) ((meters * 3.28084 / 10.0).roundToLong() * 10).toString()
        else ((meters / 5.0).roundToLong() * 5).toString()

    private fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun compass(deg: Float): String = COMPASS[((normalize(deg) + 22.5f) / 45f).toInt() % 8]

    fun degrees(deg: Float): String = "${normalize(deg).roundToInt() % 360}°"

    fun turnDistance(meters: Int, imperial: Boolean): String {
        if (!imperial) {
            val rounded = (meters / 10.0).roundToInt() * 10
            return if (rounded < 1000) "$rounded m" else String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
        val miles = meters / 1609.344
        return if (miles < 0.1) "${(meters * 3.28084 / 50.0).roundToInt() * 50} ft"
        else String.format(Locale.US, "%.1f mi", miles)
    }
}
