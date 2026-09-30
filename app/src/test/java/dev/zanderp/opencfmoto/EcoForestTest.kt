package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EcoForestTest {
    @Test
    fun stagesEveryFourKm() {
        assertEquals(1, EcoForest(currentMeters = 0.0).stage)
        assertEquals(1, EcoForest(currentMeters = 3_999.0).stage)
        assertEquals(2, EcoForest(currentMeters = 4_000.0).stage)
        assertEquals(5, EcoForest(currentMeters = 16_000.0).stage)
        assertEquals(5, EcoForest(currentMeters = 19_999.0).stage)
        assertEquals(0.5f, EcoForest(currentMeters = 10_000.0).progress, 1e-6f)
    }

    @Test
    fun treeCompletesAtTwentyKmWithCarryOver() {
        val (f, done) = EcoForest(trees = 3, currentMeters = 19_500.0).add(1_000.0, 1_000.0, "2026-09")
        assertEquals(1, done)
        assertEquals(4, f.trees)
        assertEquals(500.0, f.currentMeters, 1e-9)
        assertEquals(1, f.stage)
    }

    @Test
    fun multipleTreesInOneAdd() {
        val (f, done) = EcoForest().add(45_000.0, 50_000.0, "2026-09")
        assertEquals(2, done)
        assertEquals(2, f.trees)
        assertEquals(5_000.0, f.currentMeters, 1e-9)
        assertEquals(45_000.0, f.allTimeMeters, 1e-9)
    }

    @Test
    fun neverGoesBackwards() {
        val start = EcoForest(trees = 1, currentMeters = 2_000.0)
        assertEquals(start.copy(month = "2026-09"), start.add(-50.0, 10.0, "2026-09").first.copy(monthMovingMeters = 0.0))
        assertEquals(0, start.add(Double.NaN, 0.0, "2026-09").second)
    }

    @Test
    fun monthTotalsAndPercent() {
        var f = EcoForest()
        f = f.add(800.0, 1_000.0, "2026-09").first
        f = f.add(100.0, 1_000.0, "2026-09").first
        assertEquals(45, f.monthEcoPercent)
        assertNull(EcoForest().monthEcoPercent)
    }

    @Test
    fun newMonthResetsMonthTotals() {
        val f = EcoForest(month = "2026-09", monthEcoMeters = 5_000.0, monthMovingMeters = 6_000.0)
            .add(300.0, 400.0, "2026-10").first
        assertEquals("2026-10", f.month)
        assertEquals(300.0, f.monthEcoMeters, 1e-9)
        assertEquals(400.0, f.monthMovingMeters, 1e-9)
    }

    @Test
    fun monthKeyInPhoneZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = Calendar.getInstance(utc).apply { clear(); set(2026, 8, 30, 20, 0) }.timeInMillis
        assertEquals("2026-10", EcoForest.monthKey(t, TimeZone.getTimeZone("Asia/Colombo")))
        assertEquals("2026-09", EcoForest.monthKey(t, utc))
    }
}
