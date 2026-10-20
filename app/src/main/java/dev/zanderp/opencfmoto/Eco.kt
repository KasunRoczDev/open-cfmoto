// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** What one GPS fix added: distance ridden eco, and distance moved. */
data class EcoStep(val ecoMeters: Double, val movingMeters: Double)

/**
 * Live eco-riding decision per GPS fix (spec §2.2). Pure — unit-tested. One instance per ride/session.
 * A second is eco when moving, gentle (|a| < 1.5 m/s²), steady (swing < 10 km/h over 10 s) and inside [band]
 * (the bike model's [EcoBand]; 30–70 km/h when we have none).
 */
class EcoScorer(private val band: EcoBand = EcoBands.DEFAULT) {
    var ecoMeters = 0.0
        private set
    var movingMeters = 0.0
        private set

    private var prevSpeed = Float.NaN
    private var prevTime = 0L
    private val window = ArrayDeque<Pair<Long, Float>>()

    val ecoPercent: Int?
        get() = if (movingMeters < MIN_PERCENT_M) null else (100.0 * ecoMeters / movingMeters).roundToInt()

    fun onFix(speedMs: Float, timeMs: Long): EcoStep {
        if (!speedMs.isFinite() || speedMs < 0f) return EcoStep(0.0, 0.0)
        val first = prevSpeed.isNaN()
        val dt = (timeMs - prevTime) / 1000.0
        if (!first && dt <= 0) return EcoStep(0.0, 0.0)
        if (first || dt > GAP_S) {
            window.clear()
            window.addLast(timeMs to speedMs)
            prevSpeed = speedMs
            prevTime = timeMs
            return EcoStep(0.0, 0.0)
        }
        val acc = (speedMs - prevSpeed) / maxOf(dt, MIN_DT_S)
        window.addLast(timeMs to speedMs)
        while (window.isNotEmpty() && timeMs - window.first().first > WINDOW_MS) window.removeFirst()
        val swing = window.maxOf { it.second } - window.minOf { it.second }
        prevSpeed = speedMs
        prevTime = timeMs

        val dist = speedMs * dt
        val moving = speedMs >= MOVING_MS
        if (!moving) return EcoStep(0.0, 0.0)
        movingMeters += dist
        val eco = abs(acc) < MAX_ACC && swing < MAX_SWING_MS && speedMs >= band.lowMs - EPS && speedMs <= band.highMs + EPS
        if (!eco) return EcoStep(0.0, dist)
        ecoMeters += dist
        return EcoStep(dist, dist)
    }

    companion object {
        const val MOVING_MS = 3f / 3.6f
        const val MAX_ACC = 1.5
        const val MAX_SWING_MS = 10f / 3.6f
        const val WINDOW_MS = 10_000L
        const val GAP_S = 5.0
        const val MIN_DT_S = 1.0
        const val MIN_PERCENT_M = 200.0
        private const val EPS = 1e-4f
    }
}

/**
 * The rider's growing tree and forest (spec §2.2–2.3). Pure — unit-tested. A tree is 20 eco km in five
 * 4 km stages; completing it adds to [trees] and a new seed starts with the remainder. Never shrinks.
 */
data class EcoForest(
    val trees: Int = 0,
    val currentMeters: Double = 0.0,
    val allTimeMeters: Double = 0.0,
    val month: String = "",
    val monthEcoMeters: Double = 0.0,
    val monthMovingMeters: Double = 0.0,
) {
    /** 1 seed · 2 sprout · 3 sapling · 4 young · 5 full. */
    val stage: Int get() = (currentMeters / STAGE_METERS).toInt().coerceIn(0, 4) + 1

    val progress: Float get() = (currentMeters / TREE_METERS).toFloat().coerceIn(0f, 1f)

    val monthEcoPercent: Int?
        get() = if (monthMovingMeters < EcoScorer.MIN_PERCENT_M) null
        else (100.0 * monthEcoMeters / monthMovingMeters).roundToInt()

    /** This month's eco % only if the stored month is [currentMonth] (else "no ride yet this month"). */
    fun monthEcoPercentFor(currentMonth: String): Int? = if (month == currentMonth) monthEcoPercent else null

    /** Adds one step's distances; returns the new state and how many trees were completed. */
    fun add(ecoMeters: Double, movingMeters: Double, month: String): Pair<EcoForest, Int> {
        val eco = if (ecoMeters.isFinite() && ecoMeters > 0) ecoMeters else 0.0
        val moving = if (movingMeters.isFinite() && movingMeters > 0) movingMeters else 0.0
        var cur = currentMeters + eco
        var done = 0
        while (cur >= TREE_METERS) {
            cur -= TREE_METERS
            done++
        }
        val sameMonth = month == this.month
        return copy(
            trees = trees + done,
            currentMeters = cur,
            allTimeMeters = allTimeMeters + eco,
            month = month,
            monthEcoMeters = (if (sameMonth) monthEcoMeters else 0.0) + eco,
            monthMovingMeters = (if (sameMonth) monthMovingMeters else 0.0) + moving,
        ) to done
    }

    companion object {
        const val TREE_METERS = 20_000.0
        const val STAGE_METERS = 4_000.0

        fun monthKey(epochMs: Long, zone: TimeZone): String {
            val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
            return String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }
    }
}
