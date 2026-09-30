# Ride Behaviour Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show per-ride behaviour (top / slowest / average speed, stops, hard brakes, hard accelerations, score) on the ride map with markers and a speed-coloured route, and a monthly behaviour summary with a radar on the Trips screen.

**Architecture:** Two pure objects do all the maths on the points every trip already stores — `RideAnalysis` (one ride) and `MonthlyBehaviour` (a month) — fully unit-tested. The existing View screens (`TripMapActivity`, `TripsListActivity`) run them off the main thread and draw the results: osmdroid polylines/markers, a stats grid, and a small Canvas `RadarView`.

**Tech Stack:** Kotlin, Android Views, osmdroid 6.1.20 (existing), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-ride-behaviour-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto`. New files start with the 3-line SPDX/AGPL header (copy from `BikeScope.kt`).
- Thresholds (exact): moving ≥ **3 km/h**; stop = below that for ≥ **20 s**; hard brake ≥ **3.3 m/s²** deceleration starting from ≥ **15 km/h**; hard acceleration ≥ **2.8 m/s²**; |a| > **8 m/s²** = GPS glitch (ignored); time gap > **5 s** breaks acceleration; spike = speed differs from both neighbours by > **30 km/h** (ignored for top/slowest).
- Score = `clamp(round(100 − 15 × events ÷ (km ÷ 10)), 0, 100)`, `null` below 1 km. Radar = [km/500, hours/20, top km/h ÷ 120, 1 − min(1, brakes per 100 km ÷ 10), 1 − min(1, accels per 100 km ÷ 10)], each clamped 0–1.
- Speed bands for the route colour: metric < 30 / < 60 / < 90 / ≥ 90 km/h; imperial < 20 / < 40 / < 55 / ≥ 55 mph. Colours: `#34D399`, `#FBBF24`, `#F97316`, `#F87171`.
- Marker colours: top speed `#D93A3A`, slowest `#0B8C9B`, stop `#5B6773`, hard brake `#F97316`, hard accel `#8B5CF6`.
- No change to recording, the trip file format or GPX export. Pure objects make no Android calls.
- Build/test from repo root in Git Bash: `export JAVA_HOME="/d/Program Files/Java/jdk-21" GRADLE_USER_HOME="F:/opencfmoto-gradle/home" TMP="F:\\opencfmoto-gradle\\tmp" TEMP="F:\\opencfmoto-gradle\\tmp" GRADLE_OPTS="-Djava.io.tmpdir=F:/opencfmoto-gradle/tmp"; F="--console=plain --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process"` then `./gradlew <task> $F`.
- "Suite green" = only the known pre-existing failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` (exactly 1).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **GPS spike (one 120 km/h point in a 40 km/h ride)** → must not become the top speed or a hard brake/accel. Pinned by `RideAnalysisTest.singleSpikeIgnored`.
2. **Recording gap (tunnel, app paused) with a big speed change across it** → no fake hard event. Pinned by `RideAnalysisTest.gapBreaksAcceleration`.
3. **Stop-and-go traffic at 2–3 km/h** → only continuous ≥ 20 s under 3 km/h is a stop; slowest-moving never reports a stop. Pinned by `RideAnalysisTest.stopNeedsTwentySeconds` + `slowestMovingIgnoresStops`.
4. **A ride right after midnight at the month boundary** (phone time zone) → counted in the right month. Pinned by `MonthlyBehaviourTest.monthUsesPhoneTimeZone`.
5. **Very short ride (< 1 km) or a trip with 0–1 points** → no score ("—"), no crash, no markers. Pinned by `RideAnalysisTest.tinyRides`.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `RideAnalysis.kt` | Create | `SpeedBand`, `StopEvent`, `SpeedEvent`, `RideBehaviour`, `RideAnalysis` (pure) |
| `MonthlyBehaviour.kt` | Create | `MonthKey`, `MonthSummary`, `MonthlyBehaviour` (pure) |
| `RideBehaviourUi.kt` | Create | marker icons, colours, speed/duration text (Android) |
| `RadarView.kt` | Create | 5-axis radar Canvas View |
| `TripMapActivity.kt`, `res/layout/activity_trip_map.xml` | Modify | coloured route, markers, legend, stats |
| `TripsListActivity.kt`, `res/layout/activity_trips.xml` | Modify | "Riding behaviour" month card |
| `res/values/strings.xml` | Modify | `behaviour_*` strings |
| Tests: `app/src/test/java/dev/zanderp/opencfmoto/RideAnalysisTest.kt`, `MonthlyBehaviourTest.kt` | Create | |

---

### Task 1: RideAnalysis (one ride)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/RideAnalysis.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/RideAnalysisTest.kt`

**Interfaces:**
- Consumes: `Trip(id, start, end, distanceMeters, movingTimeMs, maxSpeedMs, points)`, `TrackPoint(lat, lon, time, speedMs)` (existing, `TripModel.kt`).
- Produces: `enum class SpeedBand { SLOW, MEDIUM, FAST, VERY_FAST }`; `data class StopEvent(val index: Int, val durationMs: Long)`; `data class SpeedEvent(val index: Int, val fromMs: Float, val toMs: Float)`; `data class RideBehaviour(maxIndex: Int?, maxSpeedMs: Float, slowestIndex: Int?, slowestMovingMs: Float?, avgMs: Float, movingAvgMs: Float, stops: List<StopEvent>, hardBrakes: List<SpeedEvent>, hardAccels: List<SpeedEvent>, score: Int?, bands: List<SpeedBand>)` with `val stopTimeMs: Long`; `object RideAnalysis { fun analyze(trip: Trip, imperial: Boolean = false): RideBehaviour; fun score(events: Int, km: Double): Int?; fun band(speedMs: Float, imperial: Boolean): SpeedBand }`. Indices refer to `trip.points`; `bands[i]` is the segment `points[i] → points[i+1]`.

- [ ] **Step 1: Failing test.**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideAnalysisTest {
    /** Points 1 s apart (or [dtMs]) from km/h values; distance/moving time filled plausibly. */
    private fun trip(kmh: List<Float>, dtMs: List<Long>? = null, distanceM: Double = 10_000.0, movingMs: Long = 600_000): Trip {
        var t = 1_000_000L
        val pts = kmh.mapIndexed { i, v ->
            if (i > 0) t += dtMs?.get(i - 1) ?: 1000L
            TrackPoint(6.9 + i * 1e-5, 79.8, t, v / 3.6f)
        }
        return Trip("1", pts.firstOrNull()?.time ?: 0L, pts.lastOrNull()?.time ?: 0L, distanceM, movingMs,
            pts.maxOfOrNull { it.speedMs } ?: 0f, pts)
    }

    @Test
    fun topAndSlowestMoving() {
        val b = RideAnalysis.analyze(trip(listOf(20f, 40f, 60f, 84f, 70f, 50f, 30f, 9f, 20f)))
        assertEquals(3, b.maxIndex)
        assertEquals(84f / 3.6f, b.maxSpeedMs, 1e-4f)
        assertEquals(7, b.slowestIndex)
        assertEquals(9f / 3.6f, b.slowestMovingMs!!, 1e-4f)
    }

    @Test
    fun slowestMovingIgnoresStops() {
        val b = RideAnalysis.analyze(trip(listOf(30f, 2f, 0f, 1f, 12f, 40f)))
        assertEquals(4, b.slowestIndex)                      // 12 km/h, not the 0–2 km/h crawl
    }

    @Test
    fun stopNeedsTwentySeconds() {
        val short = RideAnalysis.analyze(trip(listOf(30f) + List(20) { 0f } + listOf(30f)))   // 19 s under 3 km/h
        assertEquals(0, short.stops.size)
        val long = RideAnalysis.analyze(trip(listOf(30f) + List(21) { 0f } + listOf(30f)))    // 20 s
        assertEquals(listOf(StopEvent(1, 20_000)), long.stops)
        assertEquals(20_000L, long.stopTimeMs)
    }

    @Test
    fun hardBrakeThresholdAndMerge() {
        // 3.2 m/s² is not hard; 3.3 is. 20.0 → 16.7 m/s in 1 s.
        val soft = RideAnalysis.analyze(trip(listOf(72f, 72f - 3.2f * 3.6f, 50f)))
        assertEquals(0, soft.hardBrakes.size)
        val kmh = listOf(60f, 60f, 45f, 30f, 29f)            // two hard steps in a row → one event 60 → 30
        val hard = RideAnalysis.analyze(trip(kmh))
        assertEquals(1, hard.hardBrakes.size)
        assertEquals(SpeedEvent(1, 60f / 3.6f, 30f / 3.6f), hard.hardBrakes[0])
    }

    @Test
    fun hardBrakeNeedsFifteenKmhStart() {
        val b = RideAnalysis.analyze(trip(listOf(14f, 0f, 0f)))       // −3.9 m/s² but from 14 km/h
        assertEquals(0, b.hardBrakes.size)
    }

    @Test
    fun hardAccelerationThreshold() {
        val none = RideAnalysis.analyze(trip(listOf(10f, 10f + 2.7f * 3.6f)))
        assertEquals(0, none.hardAccels.size)
        val one = RideAnalysis.analyze(trip(listOf(10f, 10f + 2.9f * 3.6f, 30f)))
        assertEquals(1, one.hardAccels.size)
        assertEquals(0, one.hardAccels[0].index)
    }

    @Test
    fun singleSpikeIgnored() {
        val b = RideAnalysis.analyze(trip(listOf(40f, 41f, 120f, 40f, 39f)))
        assertEquals(41f / 3.6f, b.maxSpeedMs, 1e-4f)
        assertEquals(0, b.hardBrakes.size)
        assertEquals(0, b.hardAccels.size)
    }

    @Test
    fun gapBreaksAcceleration() {
        val gap = RideAnalysis.analyze(trip(listOf(10f, 22f), dtMs = listOf(6_000L)))
        assertEquals(0, gap.hardAccels.size)
        val noGap = RideAnalysis.analyze(trip(listOf(10f, 22f)))
        assertEquals(1, noGap.hardAccels.size)
    }

    @Test
    fun averages() {
        val t = trip(listOf(30f, 30f, 30f), distanceM = 3000.0, movingMs = 100_000)   // 2 s total
        val b = RideAnalysis.analyze(t)
        assertEquals(1500f, b.avgMs, 1e-3f)
        assertEquals(30f, b.movingAvgMs, 1e-3f)
    }

    @Test
    fun scoreFormula() {
        assertEquals(100, RideAnalysis.score(0, 10.0))
        assertEquals(85, RideAnalysis.score(1, 10.0))
        assertEquals(0, RideAnalysis.score(10, 1.0))
        assertNull(RideAnalysis.score(0, 0.9))
    }

    @Test
    fun tinyRides() {
        val empty = RideAnalysis.analyze(trip(emptyList(), distanceM = 0.0, movingMs = 0))
        assertNull(empty.maxIndex); assertNull(empty.slowestIndex); assertNull(empty.score)
        assertTrue(empty.bands.isEmpty())
        val one = RideAnalysis.analyze(trip(listOf(20f), distanceM = 0.0, movingMs = 0))
        assertEquals(0, one.maxIndex)
        assertTrue(one.bands.isEmpty())
    }

    @Test
    fun speedBands() {
        // Values clear of the band edges (float rounding at exactly 30.0 could go either way).
        assertEquals(SpeedBand.SLOW, RideAnalysis.band(29f / 3.6f, imperial = false))
        assertEquals(SpeedBand.MEDIUM, RideAnalysis.band(31f / 3.6f, imperial = false))
        assertEquals(SpeedBand.FAST, RideAnalysis.band(89f / 3.6f, imperial = false))
        assertEquals(SpeedBand.VERY_FAST, RideAnalysis.band(91f / 3.6f, imperial = false))
        assertEquals(SpeedBand.VERY_FAST, RideAnalysis.band(56f / 2.23694f, imperial = true))
        assertEquals(SpeedBand.MEDIUM, RideAnalysis.band(21f / 2.23694f, imperial = true))
        val bands = RideAnalysis.analyze(trip(listOf(10f, 20f, 45f))).bands
        assertEquals(listOf(SpeedBand.SLOW, SpeedBand.MEDIUM), bands)   // segment averages 15 and 32.5 km/h
    }
}
```

- [ ] **Step 2: Run to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.RideAnalysisTest" $F`
Expected: compile error `Unresolved reference 'RideAnalysis'`.

- [ ] **Step 3: Implement `RideAnalysis.kt`.**

```kotlin
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
    private const val EPS = 1e-3

    fun analyze(trip: Trip, imperial: Boolean = false): RideBehaviour {
        val p = trip.points
        val n = p.size
        var maxI: Int? = null
        var slowI: Int? = null
        for (i in 0 until n) {
            if (isSpike(p, i)) continue
            val v = p[i].speedMs
            val m = maxI
            if (m == null || v > p[m].speedMs) maxI = i
            val s = slowI
            if (v >= MOVING_MS && (s == null || v < p[s].speedMs)) slowI = i
        }
        val brakes = events(p, brake = true)
        val accels = events(p, brake = false)
        val totalS = (trip.end - trip.start) / 1000.0
        val movingS = trip.movingTimeMs / 1000.0
        return RideBehaviour(
            maxIndex = maxI,
            maxSpeedMs = maxI?.let { p[it].speedMs } ?: 0f,
            slowestIndex = slowI,
            slowestMovingMs = slowI?.let { p[it].speedMs },
            avgMs = if (totalS > 0) (trip.distanceMeters / totalS).toFloat() else 0f,
            movingAvgMs = if (movingS > 0) (trip.distanceMeters / movingS).toFloat() else 0f,
            stops = stops(p),
            hardBrakes = brakes,
            hardAccels = accels,
            score = score(brakes.size + accels.size, trip.distanceMeters / 1000.0),
            bands = (0 until (n - 1).coerceAtLeast(0)).map { band((p[it].speedMs + p[it + 1].speedMs) / 2f, imperial) },
        )
    }

    /** 100 − 15 per hard event per 10 km, clamped 0–100; null under 1 km. */
    fun score(events: Int, km: Double): Int? {
        if (km < 1.0) return null
        return (100.0 - 15.0 * events / (km / 10.0)).roundToInt().coerceIn(0, 100)
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
            val dt = (p[i].time - p[i - 1].time) / 1000.0
            val a = if (dt > 0 && dt <= GAP_MAX_S) (p[i].speedMs - p[i - 1].speedMs) / dt else Double.NaN
            val plausible = !a.isNaN() && abs(a) <= IMPLAUSIBLE_MS2
            val hit = plausible && if (brake) {
                a <= -(BRAKE_MS2 - EPS) && (runStart >= 0 || p[i - 1].speedMs >= BRAKE_MIN_START_MS - EPS)
            } else {
                a >= ACCEL_MS2 - EPS
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
```

- [ ] **Step 4: Run tests.** Same command as Step 2. Expected: PASS (12 tests). A failure is a code bug — fix the code (the thresholds in Global Constraints are binding).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/RideAnalysis.kt app/src/test/java/dev/zanderp/opencfmoto/RideAnalysisTest.kt
git commit -m "feat(trips): ride behaviour analysis — top/slowest/avg speed, stops, hard brakes/accels, score

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: MonthlyBehaviour

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MonthlyBehaviour.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/MonthlyBehaviourTest.kt`

**Interfaces:**
- Consumes: `RideAnalysis.analyze`, `RideAnalysis.score` (Task 1).
- Produces: `data class MonthKey(val year: Int, val month: Int)` (month 1–12); `data class MonthSummary(key, rides: Int, distanceKm: Double, ridingTimeMs: Long, topSpeedMs: Float, avgMovingMs: Float, hardBrakes: Int, hardAccels: Int, stops: Int, score: Int?, radar: List<Float>)`; `object MonthlyBehaviour { fun monthOf(epochMs: Long, zone: TimeZone): MonthKey; fun months(trips: List<Trip>, zone: TimeZone): List<MonthKey>; fun summarize(trips: List<Trip>, key: MonthKey, zone: TimeZone): MonthSummary?; fun radar(km: Double, movingMs: Long, topMs: Float, brakes: Int, accels: Int): List<Float> }`.

- [ ] **Step 1: Failing test.**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MonthlyBehaviourTest {
    private val colombo = TimeZone.getTimeZone("Asia/Colombo")   // UTC+5:30

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun trip(start: Long, km: Double, kmh: List<Float>): Trip {
        val pts = kmh.mapIndexed { i, v -> TrackPoint(6.9, 79.8, start + i * 1000L, v / 3.6f) }
        return Trip(start.toString(), start, start + kmh.size * 1000L, km * 1000, (kmh.size * 1000).toLong(),
            pts.maxOfOrNull { it.speedMs } ?: 0f, pts)
    }

    @Test
    fun monthUsesPhoneTimeZone() {
        val utcEvening = at(TimeZone.getTimeZone("UTC"), 2026, 9, 30, 20, 0)   // 1 Oct 01:30 in Colombo
        assertEquals(MonthKey(2026, 10), MonthlyBehaviour.monthOf(utcEvening, colombo))
        assertEquals(MonthKey(2026, 9), MonthlyBehaviour.monthOf(utcEvening, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun monthsNewestFirstWithoutDuplicates() {
        val trips = listOf(
            trip(at(colombo, 2026, 8, 3, 9, 0), 5.0, listOf(20f)),
            trip(at(colombo, 2026, 9, 1, 9, 0), 5.0, listOf(20f)),
            trip(at(colombo, 2026, 9, 20, 9, 0), 5.0, listOf(20f)),
        )
        assertEquals(listOf(MonthKey(2026, 9), MonthKey(2026, 8)), MonthlyBehaviour.months(trips, colombo))
    }

    @Test
    fun summarizeTotals() {
        val sep = listOf(
            trip(at(colombo, 2026, 9, 1, 9, 0), 10.0, listOf(60f, 60f, 45f, 30f, 29f)),   // 1 hard brake, top 60
            trip(at(colombo, 2026, 9, 2, 9, 0), 10.0, listOf(20f, 84f / 1f, 84f)),        // top 84
            trip(at(colombo, 2026, 8, 2, 9, 0), 99.0, listOf(120f, 120f)),                 // other month
        )
        val s = MonthlyBehaviour.summarize(sep, MonthKey(2026, 9), colombo)!!
        assertEquals(2, s.rides)
        assertEquals(20.0, s.distanceKm, 1e-9)
        assertEquals(84f / 3.6f, s.topSpeedMs, 1e-4f)
        assertEquals(1, s.hardBrakes)
        assertEquals(RideAnalysis.score(s.hardBrakes + s.hardAccels, 20.0), s.score)
        assertNull(MonthlyBehaviour.summarize(sep, MonthKey(2026, 7), colombo))
    }

    @Test
    fun radarClamped() {
        val r = MonthlyBehaviour.radar(km = 1000.0, movingMs = 50L * 3_600_000, topMs = 200f / 3.6f, brakes = 0, accels = 1000)
        assertEquals(listOf(1f, 1f, 1f, 1f, 0f), r)
        val half = MonthlyBehaviour.radar(km = 250.0, movingMs = 10L * 3_600_000, topMs = 60f / 3.6f, brakes = 5, accels = 0)
        assertEquals(0.5f, half[0], 1e-6f); assertEquals(0.5f, half[1], 1e-6f); assertEquals(0.5f, half[2], 1e-4f)
        assertEquals(0.8f, half[3], 1e-6f)   // 2 brakes per 100 km → 1 − 0.2
        assertEquals(1f, MonthlyBehaviour.radar(0.0, 0, 0f, 0, 0)[4], 0f)
    }
}
```

Note: the second `summarizeTotals` trip's `84f / 1f` jump from 20 → 84 km/h is an implausible step (≈ 17.8 m/s²) and 84 differs from 20 by > 30 but equals its next neighbour (84), so it is not a spike — top speed 84 stands and no hard accel is counted.

- [ ] **Step 2: Run to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.MonthlyBehaviourTest" $F`
Expected: compile error `Unresolved reference 'MonthKey'`.

- [ ] **Step 3: Implement `MonthlyBehaviour.kt`.**

```kotlin
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
        val inMonth = trips.filter { monthOf(it.start, zone) == key }
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
```

- [ ] **Step 4: Run tests.** Same command. Expected: PASS (4 tests).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MonthlyBehaviour.kt app/src/test/java/dev/zanderp/opencfmoto/MonthlyBehaviourTest.kt
git commit -m "feat(trips): monthly riding behaviour summary and radar values

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Ride detail — coloured route, markers, stats

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/RideBehaviourUi.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/TripMapActivity.kt`, `app/src/main/res/layout/activity_trip_map.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RideAnalysis`, `RideBehaviour`, `SpeedBand` (Task 1); `MapPrefs.units(ctx)`, `MapUnits` (existing).
- Produces: `object RideBehaviourUi { const val TOP, SLOW, STOP, BRAKE, ACCEL: Int (colours); fun bandColor(b: SpeedBand): Int; fun markerIcon(ctx: Context, symbol: String, color: Int): Drawable; fun speed(ms: Float, imperial: Boolean): String; fun speedValue(ms: Float, imperial: Boolean): String; fun duration(ms: Long): String; fun clock(epochMs: Long): String }`.

No JVM test (Android Views/osmdroid); verified by build and the emulator check with a fixture ride.

- [ ] **Step 1: Strings** (append inside `<resources>`):

```xml
    <string name="behaviour_top_speed">Top speed</string>
    <string name="behaviour_slowest">Slowest moving</string>
    <string name="behaviour_stop">Stop</string>
    <string name="behaviour_hard_brake">Hard brake</string>
    <string name="behaviour_hard_accel">Hard acceleration</string>
    <string name="behaviour_legend">▲ top speed · ▼ slowest moving · P stop · ! hard brake · » hard acceleration · route colour = speed</string>
    <string name="behaviour_max">max</string>
    <string name="behaviour_avg">avg</string>
    <string name="behaviour_moving_avg">moving avg</string>
    <string name="behaviour_slowest_short">slowest moving</string>
    <string name="behaviour_stops_short">stops · %1$s</string>
    <string name="behaviour_brakes_accels">hard brakes / accel</string>
    <string name="behaviour_distance">Distance</string>
    <string name="behaviour_riding_time">Riding time</string>
    <string name="behaviour_riding_time_value">%1$s (%2$s total)</string>
    <string name="behaviour_score">Behaviour score</string>
    <string name="behaviour_score_value">%1$d / 100</string>
    <string name="behaviour_none">—</string>
```

- [ ] **Step 2: `RideBehaviourUi.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Colours, marker icons and text for the ride-behaviour screens (fixed colours: readable on map tiles). */
object RideBehaviourUi {
    const val TOP = 0xFFD93A3A.toInt()
    const val SLOW = 0xFF0B8C9B.toInt()
    const val STOP = 0xFF5B6773.toInt()
    const val BRAKE = 0xFFF97316.toInt()
    const val ACCEL = 0xFF8B5CF6.toInt()

    fun bandColor(b: SpeedBand): Int = when (b) {
        SpeedBand.SLOW -> 0xFF34D399.toInt()
        SpeedBand.MEDIUM -> 0xFFFBBF24.toInt()
        SpeedBand.FAST -> 0xFFF97316.toInt()
        SpeedBand.VERY_FAST -> 0xFFF87171.toInt()
    }

    /** Round badge with a white ring and a bold [symbol]. */
    fun markerIcon(ctx: Context, symbol: String, color: Int): Drawable {
        val d = ctx.resources.displayMetrics.density
        val size = (26 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        c.drawCircle(r, r, r - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 13 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(symbol, r, r - (text.descent() + text.ascent()) / 2f, text)
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun speedValue(ms: Float, imperial: Boolean): String =
        (if (imperial) ms * 2.23694f else ms * 3.6f).roundToInt().toString()

    fun speed(ms: Float, imperial: Boolean): String =
        speedValue(ms, imperial) + if (imperial) " mph" else " km/h"

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s s"
        }
    }

    fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}
```

- [ ] **Step 3: Layout.** In `activity_trip_map.xml`, after the `<org.osmdroid.views.MapView … />` element (before the closing `</LinearLayout>` of the root) add:

```xml
    <LinearLayout
        android:id="@+id/map_behaviour"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingHorizontal="16dp"
        android:paddingTop="8dp"
        android:paddingBottom="12dp" />
```

- [ ] **Step 4: `TripMapActivity`.**
  1. Add imports `android.view.Gravity`, `android.view.View`, `android.widget.LinearLayout`.
  2. Replace `renderRoute(trip)` in `onCreate` with:

```kotlin
        val imperial = MapPrefs.units(this) == MapUnits.IMPERIAL
        // A ride has at most a few thousand points; analyse off the main thread anyway.
        Thread {
            val b = RideAnalysis.analyze(trip, imperial)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderRoute(trip, b, imperial)
                renderStats(trip, b, imperial)
            }
        }.start()
```

  3. Replace `renderRoute(trip: Trip)` entirely with:

```kotlin
    private fun renderRoute(trip: Trip, b: RideBehaviour, imperial: Boolean) {
        val pts = trip.points
        val geo = pts.map { GeoPoint(it.lat, it.lon) }
        if (geo.isEmpty()) {
            map.controller.setZoom(4.0)
            return
        }

        // Route coloured by speed: one polyline per run of segments in the same band.
        var runStart = 0
        for (i in 1..b.bands.size) {
            if (i == b.bands.size || b.bands[i] != b.bands[runStart]) {
                val band = b.bands[runStart]
                map.overlays.add(Polyline(map).apply {
                    setPoints(geo.subList(runStart, i + 1))
                    outlinePaint.color = RideBehaviourUi.bandColor(band)
                    outlinePaint.strokeWidth = 10f
                })
                runStart = i
            }
        }

        addMarker(geo.first(), getString(R.string.map_start_label), Color.GREEN)
        if (geo.size > 1) addMarker(geo.last(), getString(R.string.map_end_label), Color.RED)

        fun at(i: Int) = RideBehaviourUi.clock(pts[i].time)
        b.maxIndex?.let { i ->
            behaviourMarker(geo[i], "▲", RideBehaviourUi.TOP,
                "${getString(R.string.behaviour_top_speed)} · ${RideBehaviourUi.speed(pts[i].speedMs, imperial)} · ${at(i)}")
        }
        b.slowestIndex?.let { i ->
            behaviourMarker(geo[i], "▼", RideBehaviourUi.SLOW,
                "${getString(R.string.behaviour_slowest)} · ${RideBehaviourUi.speed(pts[i].speedMs, imperial)} · ${at(i)}")
        }
        for (s in b.stops) {
            behaviourMarker(geo[s.index], "P", RideBehaviourUi.STOP,
                "${getString(R.string.behaviour_stop)} · ${RideBehaviourUi.duration(s.durationMs)} · ${at(s.index)}")
        }
        for (e in b.hardBrakes) {
            behaviourMarker(geo[e.index], "!", RideBehaviourUi.BRAKE,
                "${getString(R.string.behaviour_hard_brake)} · ${RideBehaviourUi.speedValue(e.fromMs, imperial)} → " +
                    "${RideBehaviourUi.speed(e.toMs, imperial)} · ${at(e.index)}")
        }
        for (e in b.hardAccels) {
            behaviourMarker(geo[e.index], "»", RideBehaviourUi.ACCEL,
                "${getString(R.string.behaviour_hard_accel)} · ${RideBehaviourUi.speedValue(e.fromMs, imperial)} → " +
                    "${RideBehaviourUi.speed(e.toMs, imperial)} · ${at(e.index)}")
        }
        map.invalidate()

        // Frame the whole route once the map has been laid out.
        val bbox = BoundingBox.fromGeoPoints(geo)
        map.post {
            try {
                map.zoomToBoundingBox(bbox.increaseByScale(1.4f), false, 48)
            } catch (_: Exception) {
                map.controller.setZoom(15.0)
                map.controller.setCenter(geo.first())
            }
        }
    }

    private fun behaviourMarker(point: GeoPoint, symbol: String, color: Int, label: String) {
        map.overlays.add(Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = RideBehaviourUi.markerIcon(this@TripMapActivity, symbol, color)
            title = label
        })
    }

    /** Legend + 3×2 stat cells + distance / time / score rows under the map. */
    private fun renderStats(trip: Trip, b: RideBehaviour, imperial: Boolean) {
        val box = findViewById<LinearLayout>(R.id.map_behaviour)
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = getString(R.string.behaviour_legend)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
        })
        val none = getString(R.string.behaviour_none)
        val cells = listOf(
            RideBehaviourUi.speedValue(b.maxSpeedMs, imperial) to getString(R.string.behaviour_max),
            RideBehaviourUi.speedValue(b.avgMs, imperial) to getString(R.string.behaviour_avg),
            RideBehaviourUi.speedValue(b.movingAvgMs, imperial) to getString(R.string.behaviour_moving_avg),
            (b.slowestMovingMs?.let { RideBehaviourUi.speedValue(it, imperial) } ?: none) to getString(R.string.behaviour_slowest_short),
            b.stops.size.toString() to getString(R.string.behaviour_stops_short, RideBehaviourUi.duration(b.stopTimeMs)),
            "${b.hardBrakes.size} / ${b.hardAccels.size}" to getString(R.string.behaviour_brakes_accels),
        )
        for (row in cells.chunked(3)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((value, label) in row) line.addView(statCell(value, label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(line)
        }
        val totalMs = trip.end - trip.start
        box.addView(statRow(getString(R.string.behaviour_distance), trip.distanceText()))
        box.addView(statRow(getString(R.string.behaviour_riding_time),
            getString(R.string.behaviour_riding_time_value, RideBehaviourUi.duration(trip.movingTimeMs), RideBehaviourUi.duration(totalMs))))
        box.addView(statRow(getString(R.string.behaviour_score),
            b.score?.let { getString(R.string.behaviour_score_value, it) } ?: none))
    }

    private fun statCell(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(TextView(context).apply {
            text = value
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
        })
    }

    private fun statRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = value
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
```

  4. Also add the two label strings used above to `strings.xml`: `<string name="map_start_label">Start</string>` and `<string name="map_end_label">End</string>` (the old code used literals "Start"/"End").

- [ ] **Step 5: Build.** Run: `./gradlew :app:assembleDebug $F` — Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Fixture + emulator check.** Create the fixture generator `docs/superpowers/fixtures/make_ride_fixture.py` (committed; used by Task 4 too):

```python
"""Writes a synthetic 1 Hz ride (Colombo) with a stop, a hard brake, a hard acceleration and a GPS spike.
Usage: python docs/superpowers/fixtures/make_ride_fixture.py OUT_DIR [START_EPOCH_MS]"""
import json, sys
out = sys.argv[1]
start = int(sys.argv[2]) if len(sys.argv) > 2 else 1790000000000
kmh = ([0] * 3 + [5, 12, 20, 28, 36, 44, 50] + [50] * 30 + [50, 36, 24] + [24] * 10 + [0] * 30 +
       [4, 18, 30, 40] + [40] * 20 + [40, 120, 40] + [40] * 20 + [70, 80, 84, 84, 70, 55] + [40] * 20 + [9, 20, 30] + [0] * 3)
lat, lon, pts, dist = 6.9271, 79.8612, [], 0.0
for i, v in enumerate(kmh):
    ms = v / 3.6
    lat += ms / 111_000 * 0.8
    lon += ms / 111_000 * 0.6
    dist += ms
    pts.append([lat, lon, start + i * 1000, ms])
moving = sum(1000 for v in kmh if v >= 3)
trip = {"id": str(start), "start": start, "end": start + (len(kmh) - 1) * 1000, "distanceMeters": dist,
        "movingTimeMs": moving, "maxSpeedMs": 84 / 3.6, "points": pts}
open(f"{out}/{start}.json", "w").write(json.dumps(trip))
print(f"{out}/{start}.json")
```

Then (Git Bash, `export MSYS_NO_PATHCONV=1`; emulator AVD `ocm_test` with `ANDROID_AVD_HOME=F:\opencfmoto-gradle\avd`; x86_64 **debug** build so `run-as` works):

```bash
./gradlew :app:assembleDebug -Pabi=x86_64 $F
ADB=/d/Users/user/AppData/Local/Android/platform-tools/adb.exe
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
F1=$(python docs/superpowers/fixtures/make_ride_fixture.py /f/opencfmoto-gradle/out)
$ADB push "$F1" /data/local/tmp/ride.json
$ADB shell run-as dev.zanderp.opencfmoto sh -c 'mkdir -p files/trips && cp /data/local/tmp/ride.json files/trips/1790000000000.json'
$ADB shell am start -n dev.zanderp.opencfmoto/.TripsListActivity
```

Navigate to the ride's day (26 Sep 2026 in UTC+5:30 — use ‹ › or the first-open jump), open it. Expected (screenshot): route in green→yellow→orange→red bands; markers ▲ (84 km/h), ▼, P (30 s stop), ! (50 → 24), » (4 → 30 or 9 → 30), no marker at the 120 km/h spike; stats grid: max 84, stops 1, hard brakes / accel ≥ 1 / ≥ 1; score shown. Tap ▲ → label "Top speed · 84 km/h · HH:mm:ss". `adb logcat -d -b crash | grep -c opencfmoto` → `0`.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/RideBehaviourUi.kt app/src/main/java/dev/zanderp/opencfmoto/TripMapActivity.kt app/src/main/res/layout/activity_trip_map.xml app/src/main/res/values/strings.xml docs/superpowers/fixtures/make_ride_fixture.py
git commit -m "feat(trips): ride map shows speed-coloured route and behaviour markers with stats

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Monthly "Riding behaviour" card with radar

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/RadarView.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/TripsListActivity.kt`, `app/src/main/res/layout/activity_trips.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MonthlyBehaviour`, `MonthKey`, `MonthSummary` (Task 2); `RideBehaviourUi.speed/duration` (Task 3).
- Produces: `class RadarView(ctx, attrs) : View` with `fun set(values: List<Float>, labels: List<String>, center: String)`.

No JVM test (Canvas/Views); verified by build and emulator.

- [ ] **Step 1: Strings** (append):

```xml
    <string name="behaviour_title">Riding behaviour</string>
    <string name="behaviour_rides">Rides</string>
    <string name="behaviour_top_speed_month">Top speed</string>
    <string name="behaviour_avg_moving">Avg moving speed</string>
    <string name="behaviour_hard_brakes">Hard brakes</string>
    <string name="behaviour_hard_accels">Hard accelerations</string>
    <string name="behaviour_stops">Stops</string>
    <string name="behaviour_axis_distance">Distance</string>
    <string name="behaviour_axis_time">Time</string>
    <string name="behaviour_axis_top">Top speed</string>
    <string name="behaviour_axis_braking">Smooth braking</string>
    <string name="behaviour_axis_accel">Smooth accel</string>
    <string name="behaviour_prev_month">Previous month</string>
    <string name="behaviour_next_month">Next month</string>
```

- [ ] **Step 2: `RadarView.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A small N-axis radar (values 0–1) with a big number in the middle. Colours follow light/dark. */
class RadarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var center: String = ""
    private val d = resources.displayMetrics.density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * d; color = ContextCompat.getColor(ctx, R.color.outline)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = ContextCompat.getColor(ctx, R.color.accent); alpha = 70
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = ContextCompat.getColor(ctx, R.color.accent)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * d; color = ContextCompat.getColor(ctx, R.color.text_secondary); textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24 * d; color = ContextCompat.getColor(ctx, R.color.text_primary)
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    fun set(values: List<Float>, labels: List<String>, center: String) {
        this.values = values
        this.labels = labels
        this.center = center
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n < 3) return
        val cx = width / 2f
        val cy = height / 2f + 4 * d
        val r = min(width, height) / 2f - 26 * d
        fun pt(i: Int, f: Float): Pair<Float, Float> {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            return (cx + cos(a) * r * f).toFloat() to (cy + sin(a) * r * f).toFloat()
        }
        for (ring in listOf(1f / 3, 2f / 3, 1f)) {
            val p = Path()
            for (i in 0 until n) {
                val (x, y) = pt(i, ring)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            c.drawPath(p, grid)
        }
        val shape = Path()
        for (i in 0 until n) {
            val (x, y) = pt(i, values[i].coerceIn(0.05f, 1f))
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        c.drawPath(shape, fill)
        c.drawPath(shape, edge)
        for (i in 0 until n) {
            val (x, y) = pt(i, 1f + 16 * d / r)
            c.drawText(labels.getOrElse(i) { "" }, x, y + 4 * d, label)
        }
        c.drawText(center, cx, cy - (big.descent() + big.ascent()) / 2f, big)
    }
}
```

- [ ] **Step 3: Layout.** In `activity_trips.xml`, replace the `ScrollView`'s single child (`<LinearLayout android:id="@+id/trips_container" … />`) with:

```xml
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <LinearLayout
                android:id="@+id/trips_behaviour_host"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical" />

            <LinearLayout
                android:id="@+id/trips_container"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingBottom="16dp" />
        </LinearLayout>
```

- [ ] **Step 4: `TripsListActivity`.**
  1. Add fields:

```kotlin
    private lateinit var behaviourHost: LinearLayout
    private var monthKey: MonthKey? = null
```

  and in `onCreate` after `container = …`: `behaviourHost = findViewById(R.id.trips_behaviour_host)`.
  2. At the end of `onResume()` (after `renderDay()`) add `renderBehaviour()`.
  3. Add:

```kotlin
    /** "Riding behaviour" month card above the day's rides (analysis off the main thread). */
    private fun renderBehaviour() {
        val zone = java.util.TimeZone.getDefault()
        val trips = allTrips
        val months = MonthlyBehaviour.months(trips, zone)
        if (months.isEmpty()) {
            behaviourHost.removeAllViews()
            return
        }
        val key = monthKey?.takeIf { it in months } ?: months.first()
        monthKey = key
        val imperial = MapPrefs.units(this) == MapUnits.IMPERIAL
        Thread {
            val s = MonthlyBehaviour.summarize(trips, key, zone)
            runOnUiThread {
                if (isFinishing || isDestroyed || monthKey != key) return@runOnUiThread
                behaviourHost.removeAllViews()
                if (s != null) behaviourHost.addView(behaviourCard(s, months, imperial))
            }
        }.start()
    }

    private fun behaviourCard(s: MonthSummary, months: List<MonthKey>, imperial: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(ContextCompat.getColor(context, R.color.surface))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.outline))
            }
        }
        val idx = months.indexOf(s.key)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.behaviour_title)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Plain tappable arrows (48 dp) — no style dependency on the old View screen.
        fun arrow(label: String, desc: Int, target: Int?) = TextView(this).apply {
            text = label
            contentDescription = getString(desc)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            alpha = if (target != null) 1f else 0.3f
            isEnabled = target != null
            setOnClickListener { target?.let { monthKey = months[it]; renderBehaviour() } }
        }
        header.addView(arrow("‹", R.string.behaviour_prev_month, (idx + 1).takeIf { it < months.size }))
        header.addView(TextView(this).apply {
            val cal = Calendar.getInstance().apply { clear(); set(s.key.year, s.key.month - 1, 1) }
            text = java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
        })
        header.addView(arrow("›", R.string.behaviour_next_month, (idx - 1).takeIf { it >= 0 }))
        card.addView(header)

        card.addView(RadarView(this).apply {
            set(s.radar, listOf(
                getString(R.string.behaviour_axis_distance), getString(R.string.behaviour_axis_time),
                getString(R.string.behaviour_axis_top), getString(R.string.behaviour_axis_braking),
                getString(R.string.behaviour_axis_accel),
            ), s.score?.toString() ?: getString(R.string.behaviour_none))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)))

        val rows = listOf(
            getString(R.string.behaviour_rides) to s.rides.toString(),
            getString(R.string.behaviour_distance) to String.format(Locale.US, "%.1f km", s.distanceKm),
            getString(R.string.behaviour_riding_time) to RideBehaviourUi.duration(s.ridingTimeMs),
            getString(R.string.behaviour_top_speed_month) to RideBehaviourUi.speed(s.topSpeedMs, imperial),
            getString(R.string.behaviour_avg_moving) to RideBehaviourUi.speed(s.avgMovingMs, imperial),
            getString(R.string.behaviour_hard_brakes) to s.hardBrakes.toString(),
            getString(R.string.behaviour_hard_accels) to s.hardAccels.toString(),
            getString(R.string.behaviour_stops) to s.stops.toString(),
        )
        for ((label, value) in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply {
                text = value
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(line)
        }
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
    }
```

  (`Calendar`, `Locale`, `MaterialButton`, `ContextCompat`, `LinearLayout`, `TextView`, `View` are already imported by this file — add any that the compiler reports missing.)

  Note: MonthKey is newest-first in `months`, so "‹" (previous/older) is `idx + 1` and "›" (newer) is `idx - 1`.

- [ ] **Step 5: Build + emulator.** `./gradlew :app:assembleDebug -Pabi=x86_64 $F`; install; generate two more fixture rides so two months exist:

```bash
F2=$(python docs/superpowers/fixtures/make_ride_fixture.py /f/opencfmoto-gradle/out 1787400000000)
$ADB push "$F2" /data/local/tmp/ride2.json
$ADB shell run-as dev.zanderp.opencfmoto sh -c 'cp /data/local/tmp/ride2.json files/trips/1787400000000.json'
$ADB shell am start -n dev.zanderp.opencfmoto/.TripsListActivity
```

Expected (screenshots, light and dark via `adb shell cmd uimode night yes|no`): "Riding behaviour · Sep 2026" card with radar (score in the middle) and the 8 rows; "‹" switches to Aug 2026, "›" back; rides list below unchanged. `adb logcat -d -b crash | grep -c opencfmoto` → `0`.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/RadarView.kt app/src/main/java/dev/zanderp/opencfmoto/TripsListActivity.kt app/src/main/res/layout/activity_trips.xml app/src/main/res/values/strings.xml
git commit -m "feat(trips): monthly riding behaviour card with radar

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Docs and bike checklist

**Files:**
- Modify: `docs/02-CURRENT-APP.md`, `README.md`

- [ ] **Step 1: Docs.** Append to `docs/02-CURRENT-APP.md`:

```markdown

## Ride behaviour

Spec: `docs/superpowers/specs/2026-09-30-ride-behaviour-design.md`.

- `RideAnalysis` (pure) analyses a saved trip's 1 Hz GPS points: top / slowest-moving / average / moving-average
  speed, stops (< 3 km/h ≥ 20 s), hard brakes (≥ 3.3 m/s² from ≥ 15 km/h), hard accelerations (≥ 2.8 m/s²),
  GPS-glitch rejection (spikes, |a| > 8 m/s², gaps > 5 s), score and per-segment speed bands.
- `MonthlyBehaviour` (pure) groups trips by month (phone time zone) → totals, score, radar values.
- `TripMapActivity`: speed-coloured route, ▲ ▼ P ! » markers with labels, stats under the map.
  `TripsListActivity`: "Riding behaviour" month card with `RadarView`.
- Test fixture: `docs/superpowers/fixtures/make_ride_fixture.py` (synthetic ride; push with `adb … run-as`).
```

In `README.md` add a feature row after the Trips row (or at the end of the feature table):

```markdown
| 🧭 **Ride behaviour** | Per ride: top / slowest / average speed, stops, hard brakes and hard accelerations marked on a speed-coloured route map, plus a score. Per month: totals and a behaviour radar on the Trips screen. Works on past rides. |
```

Commit: `git add docs/02-CURRENT-APP.md README.md && git commit -m "docs: ride behaviour"` (with trailer).

- [ ] **Step 2: Bike checklist (owner).** After a real ride with trip logging on: open the ride → markers where you remember stopping, braking hard and your top speed; the route colours match fast and slow stretches; Trips → the month card shows the ride. Report any marker in a wrong place (with the ride's GPX export).
