package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcoScorerTest {
    /** Feed km/h values 1 s apart (or [dts]); returns the scorer. */
    private fun feed(kmh: List<Float>, dts: List<Long>? = null): EcoScorer {
        val s = EcoScorer()
        var t = 0L
        kmh.forEachIndexed { i, v ->
            if (i > 0) t += dts?.get(i - 1) ?: 1000L
            s.onFix(v / 3.6f, t)
        }
        return s
    }

    @Test
    fun steadyInBandIsEco() {
        val s = feed(List(20) { 50f })
        assertEquals(19 * 50 / 3.6, s.ecoMeters, 0.01)
        assertEquals(s.movingMeters, s.ecoMeters, 0.01)
    }

    @Test
    fun outsideSpeedBandIsNotEco() {
        assertEquals(0.0, feed(List(20) { 25f }).ecoMeters, 0.0)
        assertEquals(0.0, feed(List(20) { 75f }).ecoMeters, 0.0)
        assertEquals(19 * 30 / 3.6, feed(List(20) { 30f }).ecoMeters, 0.01)
    }

    @Test
    fun hardAccelerationStepIsNotEco() {
        // 40 → 46 km/h in 1 s = 1.67 m/s² (≥ 1.5): that step earns nothing.
        val s = feed(listOf(40f, 40f, 46f))
        assertEquals(40 / 3.6, s.ecoMeters, 0.01)
    }

    @Test
    fun gentleChangeIsEco() {
        val s = feed(listOf(40f, 44f))          // 1.11 m/s²
        assertEquals(44 / 3.6, s.ecoMeters, 0.01)
    }

    @Test
    fun unsteadySpeedIsNotEco() {
        // Gentle steps but a 12 km/h swing within 10 s → not steady.
        val kmh = listOf(40f, 44f, 48f, 52f, 48f, 44f, 40f)
        val s = feed(kmh)
        assertEquals(44 / 3.6 + 48 / 3.6, s.ecoMeters, 0.01)   // only while the swing is < 10 km/h
    }

    @Test
    fun jitterFloor() {
        // 1.0 km/h change delivered 0.4 s apart is 0.28 m/s² with the 1 s floor, not 0.69.
        val s = feed(listOf(50f, 51f), dts = listOf(400L))
        assertEquals(51 / 3.6 * 0.4, s.ecoMeters, 0.01)
    }

    @Test
    fun gapResetsWindow() {
        val s = feed(listOf(50f, 50f, 50f), dts = listOf(1000L, 6000L))
        assertEquals(50 / 3.6, s.ecoMeters, 0.01)       // the step across the 6 s gap earns nothing
    }

    @Test
    fun ecoPercentNeeds200m() {
        assertNull(feed(listOf(50f, 50f, 50f)).ecoPercent)               // ~28 m
        assertEquals(100, feed(List(20) { 50f }).ecoPercent)              // ~264 m, all eco
        val mixed = feed(List(10) { 50f } + List(10) { 75f })            // eco then too fast
        // eco 9 × 13.89 m = 125 m; moving 125 m + 10 × 20.83 m = 333 m → 37.5 % → 38
        assertEquals(38, mixed.ecoPercent)
    }
}
