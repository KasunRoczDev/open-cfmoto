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
