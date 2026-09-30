package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RideSummaryTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun trip(start: Long, end: Long, km: Double, movingMin: Long = 10) =
        Trip("t$start", start, end, km * 1000, movingMin * 60_000, 0f, emptyList())

    @Test
    fun emptyIsNull() {
        assertNull(RideSummary.of(emptyList(), at(2026, 10, 1, 12, 0), zone))
    }

    @Test
    fun latestEndWins() {
        val a = trip(at(2026, 10, 1, 10, 0), at(2026, 10, 1, 12, 0), 30.0)
        val b = trip(at(2026, 10, 1, 11, 0), at(2026, 10, 1, 11, 30), 5.0)
        assertEquals(30.0, RideSummary.of(listOf(b, a), at(2026, 10, 1, 13, 0), zone)!!.last.km, 0.0)
    }

    @Test
    fun dayLabelsAcrossMidnight() {
        val now = at(2026, 10, 1, 0, 10)
        fun dayOf(end: Long) = RideSummary.of(listOf(trip(end - 60_000, end, 1.0)), now, zone)!!.last.day
        assertEquals(RideDay.TODAY, dayOf(at(2026, 10, 1, 0, 5)))
        assertEquals(RideDay.YESTERDAY, dayOf(at(2026, 9, 30, 23, 50)))
        assertEquals(RideDay.EARLIER, dayOf(at(2026, 9, 29, 12, 0)))
    }

    @Test
    fun clockBehindRideCountsAsToday() {
        val now = at(2026, 10, 1, 9, 0)
        val s = RideSummary.of(listOf(trip(at(2026, 10, 2, 8, 0), at(2026, 10, 2, 9, 0), 1.0)), now, zone)!!
        assertEquals(RideDay.TODAY, s.last.day)
    }

    @Test
    fun monthSumUsesStartInZone() {
        val now = at(2026, 10, 15, 12, 0)
        val trips = listOf(
            trip(at(2026, 10, 1, 0, 30), at(2026, 10, 1, 1, 0), 10.4),
            trip(at(2026, 9, 30, 23, 30), at(2026, 10, 1, 0, 20), 50.0),   // started in September
            trip(at(2026, 10, 14, 8, 0), at(2026, 10, 14, 9, 0), 5.3),
        )
        assertEquals(16, RideSummary.of(trips, now, zone)!!.monthKm)   // 15.7 → 16
    }

    @Test
    fun kmRoundsToTenths() {
        val now = at(2026, 10, 1, 12, 0)
        fun kmOf(km: Double) = RideSummary.of(listOf(trip(now - 120_000, now - 60_000, km)), now, zone)!!.last.km
        assertEquals(12.4, kmOf(12.44), 0.0)
        assertEquals(12.5, kmOf(12.46), 0.0)
    }

    @Test
    fun durationText() {
        assertEquals("28 min", RideSummary.durationText(28 * 60_000L))
        assertEquals("1 h 05 min", RideSummary.durationText(65 * 60_000L))
        assertEquals("0 min", RideSummary.durationText(30_000L))
        assertEquals("0 min", RideSummary.durationText(-5L))
    }
}
