// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import kotlin.math.abs
import kotlin.math.roundToInt

/** Route-colour band for one segment. */
enum class SpeedBand { SLOW, MEDIUM, FAST, VERY_FAST }

/** A stop starting at points[[index]], lasting [durationMs]. */
data class StopEvent(val index: Int, val durationMs: Long)

/** A hard brake / acceleration starting at points[[index]], from [fromMs] to [toMs] (m/s). */
data class SpeedEvent(val index: Int, val fromMs: Float, val toMs: Float)

/** Everything the ride-detail screen shows. Indices refer to `Trip.points`. Speeds in m/s. */
data class RideBehaviour(
    val maxIndex: Int?,
    val maxSpeedMs: Float,
    val slowestIndex: Int?,
    val slowestMovingMs: Float?,
    val avgMs: Float,
    val movingAvgMs: Float,
    val stops: List<StopEvent>,
    val hardBrakes: List<SpeedEvent>,
    val hardAccels: List<SpeedEvent>,
    val score: Int?,
    /** bands[i] = colour band of the segment points[i] → points[i+1]. */
    val bands: List<SpeedBand>,
) {
    val stopTimeMs: Long get() = stops.sumOf { it.durationMs }
}

/**
 * Riding behaviour from a saved trip's 1 Hz GPS points (speed from GPS). Pure — unit-tested on the JVM.
 * See docs/superpowers/specs/2026-09-30-ride-behaviour-design.md §3 for the rules.
 */
object RideAnalysis {
    const val MOVING_MS = 3f / 3.6f
    const val STOP_MIN_MS = 20_000L
    const val GAP_MAX_S = 5.0
    const val BRAKE_MS2 = 3.3
    const val BRAKE_MIN_START_MS = 15f / 3.6f
    const val ACCEL_MS2 = 2.8
    const val IMPLAUSIBLE_MS2 = 8.0
    const val SPIKE_MS = 30f / 3.6f
    /** Fixes are requested ≤ 1/s, so a shorter gap is callback delivery jitter, not physics. */
    const val MIN_DT_S = 1.0
    /** Phones clamp crawling speeds to 0; a braking step ending below this only counts inside a hard run. */
    const val BRAKE_MIN_END_MS = 5f / 3.6f
    /** "Slowest moving" ignores points this close in time to a sub-3 km/h point (the edges of a stop). */
    const val STOP_EDGE_MS = 5_000L
    private const val EPS = 1e-3

    fun analyze(trip: Trip, imperial: Boolean = false): RideBehaviour {
        val p = trip.points
        val n = p.size
        var maxI: Int? = null
        var slowI: Int? = null
        for (i in 0 until n) {
            // A GPS glitch: a spike, or the higher end of a physically impossible step (glitches jump up).
            if (isSpike(p, i) || glitchHigh(p, i)) continue
            val v = p[i].speedMs
            if (!v.isFinite()) continue
            val m = maxI
            if (m == null || v > p[m].speedMs) maxI = i
            val s = slowI
            if (v >= MOVING_MS && !nearStop(p, i) && (s == null || v < p[s].speedMs)) slowI = i
        }
        val brakes = events(p, brake = true)
        val accels = events(p, brake = false)
        val dist = trip.distanceMeters.takeIf { it.isFinite() } ?: 0.0
        val totalS = (trip.end - trip.start) / 1000.0
        val movingS = trip.movingTimeMs / 1000.0
        return RideBehaviour(
            maxIndex = maxI,
            maxSpeedMs = maxI?.let { p[it].speedMs } ?: 0f,
            slowestIndex = slowI,
            slowestMovingMs = slowI?.let { p[it].speedMs },
            avgMs = if (totalS > 0) (dist / totalS).toFloat() else 0f,
            movingAvgMs = if (movingS > 0) (dist / movingS).toFloat() else 0f,
            stops = stops(p),
            hardBrakes = brakes,
            hardAccels = accels,
            score = score(brakes.size + accels.size, dist / 1000.0),
            bands = (0 until (n - 1).coerceAtLeast(0)).map { band((p[it].speedMs + p[it + 1].speedMs) / 2f, imperial) },
        )
    }

    /**
     * 100 − 15 per hard event per 10 km, clamped 0–100; null under 1 km. Rides shorter than 10 km are
     * judged per 10 km (not scaled up), so one brake on a 2 km errand doesn't zero the score.
     */
    fun score(events: Int, km: Double): Int? {
        if (!km.isFinite() || km < 1.0) return null
        return (100.0 - 15.0 * events / (maxOf(km, 10.0) / 10.0)).roundToInt().coerceIn(0, 100)
    }

    /** Effective step time: gaps > 5 s break the chain (null); < 1 s is delivery jitter (use 1 s). */
    private fun stepSeconds(p: List<TrackPoint>, a: Int, b: Int): Double? {
        val dt = (p[b].time - p[a].time) / 1000.0
        return if (dt <= 0 || dt > GAP_MAX_S) null else maxOf(dt, MIN_DT_S)
    }

    private fun implausible(p: List<TrackPoint>, a: Int, b: Int): Boolean {
        if (a < 0 || b >= p.size) return false
        val dt = stepSeconds(p, a, b) ?: return false
        val acc = (p[b].speedMs - p[a].speedMs) / dt
        return !acc.isFinite() || abs(acc) > IMPLAUSIBLE_MS2
    }

    private fun glitchHigh(p: List<TrackPoint>, i: Int): Boolean {
        val v = p[i].speedMs
        return (implausible(p, i - 1, i) && v > p[i - 1].speedMs) ||
            (implausible(p, i, i + 1) && v > p[i + 1].speedMs)
    }

    private fun nearStop(p: List<TrackPoint>, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && p[i].time - p[j].time <= STOP_EDGE_MS) { if (p[j].speedMs < MOVING_MS) return true; j-- }
        j = i + 1
        while (j < p.size && p[j].time - p[i].time <= STOP_EDGE_MS) { if (p[j].speedMs < MOVING_MS) return true; j++ }
        return false
    }

    fun band(speedMs: Float, imperial: Boolean): SpeedBand {
        val v = if (imperial) speedMs * 2.23694f else speedMs * 3.6f
        val (a, b, c) = if (imperial) Triple(20f, 40f, 55f) else Triple(30f, 60f, 90f)
        return when {
            v < a -> SpeedBand.SLOW
            v < b -> SpeedBand.MEDIUM
            v < c -> SpeedBand.FAST
            else -> SpeedBand.VERY_FAST
        }
    }

    /** Speed differs from every neighbour by > 30 km/h → a GPS glitch, not a real speed. */
    private fun isSpike(p: List<TrackPoint>, i: Int): Boolean {
        val v = p[i].speedMs
        val prev = if (i > 0) abs(v - p[i - 1].speedMs) > SPIKE_MS else null
        val next = if (i < p.size - 1) abs(v - p[i + 1].speedMs) > SPIKE_MS else null
        return when {
            prev == null && next == null -> false
            prev == null -> next == true
            next == null -> prev
            else -> prev && next
        }
    }

    private fun stops(p: List<TrackPoint>): List<StopEvent> {
        val out = ArrayList<StopEvent>()
        var i = 0
        while (i < p.size) {
            if (p[i].speedMs >= MOVING_MS) { i++; continue }
            var j = i
            while (j + 1 < p.size && p[j + 1].speedMs < MOVING_MS) j++
            val dur = p[j].time - p[i].time
            if (dur >= STOP_MIN_MS) out += StopEvent(i, dur)
            i = j + 1
        }
        return out
    }

    /** Runs of consecutive hard steps merge into one event (from the run's first speed to its last). */
    private fun events(p: List<TrackPoint>, brake: Boolean): List<SpeedEvent> {
        val out = ArrayList<SpeedEvent>()
        var runStart = -1
        var runEnd = -1
        fun flush() {
            if (runStart >= 0) out += SpeedEvent(runStart, p[runStart].speedMs, p[runEnd].speedMs)
            runStart = -1
        }
        for (i in 1 until p.size) {
            val dt = stepSeconds(p, i - 1, i)
            val a = if (dt != null) (p[i].speedMs - p[i - 1].speedMs) / dt else Double.NaN
            val plausible = a.isFinite() && abs(a) <= IMPLAUSIBLE_MS2
            val hit = plausible && if (brake) {
                a <= -(BRAKE_MS2 - EPS) && (runStart >= 0 ||
                    (p[i - 1].speedMs >= BRAKE_MIN_START_MS - EPS && p[i].speedMs >= BRAKE_MIN_END_MS - EPS))
            } else {
                // The recorder writes 0 for the first fix; a jump from it is a cold GPS start, not a launch.
                a >= ACCEL_MS2 - EPS && !(i == 1 && p[0].speedMs == 0f)
            }
            if (hit) {
                if (runStart < 0) runStart = i - 1
                runEnd = i
            } else {
                flush()
            }
        }
        flush()
        return out
    }
}
