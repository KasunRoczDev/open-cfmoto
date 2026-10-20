// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * The steady-cruise speed range where a model burns least fuel per km, plus the engine speed that
 * range roughly maps to. We can't read RPM from the bike (no telemetry without a T-Box, see
 * docs/RE-VEHICLE-TELEMETRY.md), so the rider is coached on GPS speed and [rpmLow]–[rpmHigh] is a
 * spec-derived reference only. Pure — unit-tested.
 */
data class EcoBand(val lowKmh: Int, val highKmh: Int, val rpmLow: Int? = null, val rpmHigh: Int? = null) {
    val lowMs: Float get() = lowKmh / 3.6f
    val highMs: Float get() = highKmh / 3.6f

    /**
     * Where [speedMs] sits against the band; null when there's no speed or the bike is stopped. [previous]
     * adds [HYSTERESIS_KMH] of slack at the edge it's leaving, so GPS jitter at 60 km/h doesn't flicker.
     */
    fun hint(speedMs: Float?, previous: EcoHint? = null): EcoHint? {
        if (speedMs == null || !speedMs.isFinite() || speedMs < EcoScorer.MOVING_MS) return null
        val kmh = speedMs * 3.6f
        val slack = HYSTERESIS_KMH
        val low = if (previous == EcoHint.IN_BAND) lowKmh - slack else if (previous == EcoHint.BELOW) lowKmh + slack else lowKmh.toFloat()
        val high = if (previous == EcoHint.IN_BAND) highKmh + slack else if (previous == EcoHint.ABOVE) highKmh - slack else highKmh.toFloat()
        return when {
            kmh < low - EPS_KMH -> EcoHint.BELOW
            kmh > high + EPS_KMH -> EcoHint.ABOVE
            else -> EcoHint.IN_BAND
        }
    }

    /** "40–60 km/h" or "25–37 mph". */
    fun speedLabel(units: MapUnits): String =
        if (units == MapUnits.IMPERIAL) "${(lowKmh / KM_PER_MI).toInt()}–${(highKmh / KM_PER_MI).toInt()} mph"
        else "$lowKmh–$highKmh km/h"

    /** The band's top speed alone, "60 km/h" or "37 mph" — for the short "Ease off" line. */
    fun maxLabel(units: MapUnits): String =
        if (units == MapUnits.IMPERIAL) "${(highKmh / KM_PER_MI).toInt()} mph" else "$highKmh km/h"

    /** "~5–6.5k rpm", or null when the model has no RPM reference. */
    fun rpmLabel(): String? {
        val lo = rpmLow ?: return null
        val hi = rpmHigh ?: return null
        fun k(r: Int) = if (r % 1000 == 0) "${r / 1000}" else "${r / 1000}.${r % 1000 / 100}"
        return "~${k(lo)}–${k(hi)}k rpm"
    }

    companion object {
        const val HYSTERESIS_KMH = 2f
        private const val EPS_KMH = 0.01f   // m/s ↔ km/h float round-trip
        private const val KM_PER_MI = 1.609344
    }
}

enum class EcoHint { BELOW, IN_BAND, ABOVE }

object EcoBands {
    /** The generic band [EcoScorer] has always used. */
    val DEFAULT = EcoBand(30, 70)

    /**
     * CFMoto 150SC: 149 cc single, CVT, 10.5 kW @ 8,250 rpm, 14.2 Nm @ 6,500 rpm. Cheapest per km is a
     * steady cruise below the torque peak — on a 150 cc CVT that's about 40–60 km/h, ~5,000–6,500 rpm.
     */
    private val CFMOTO_150SC = EcoBand(40, 60, rpmLow = 5_000, rpmHigh = 6_500)

    private val BY_MODEL = mapOf("150sc" to CFMOTO_150SC)

    /** The rider's model band, else [DEFAULT]. Any spelling: "150 SC", "150-sc", "150SC". */
    fun forModel(model: String?): EcoBand {
        val key = model?.lowercase()?.filter { it.isLetterOrDigit() }
        return BY_MODEL[key] ?: DEFAULT
    }
}
