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
