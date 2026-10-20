package dev.zanderp.opencfmoto.report

import dev.zanderp.opencfmoto.EcoBands
import dev.zanderp.opencfmoto.TrackPoint
import dev.zanderp.opencfmoto.Trip
import dev.zanderp.opencfmoto.fuel.FuelEntry
import dev.zanderp.opencfmoto.fuel.FuelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeeklyReportTest {
    private val zone = TimeZone.getTimeZone("Asia/Colombo")
    private val band = EcoBands.forModel("150SC")   // 40–60 km/h
    private val hour = 3_600_000L

    /** Monday 12 Oct 2026, 00:00 Colombo. */
    private val monday = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, 12) }.timeInMillis

    /** A steady ride at [kmh] for [minutes], one GPS point a second. */
    private fun ride(startMs: Long, kmh: Float, minutes: Int, bikeRaw: String? = "B"): Trip {
        val ms = kmh / 3.6f
        val pts = (0..minutes * 60).map { TrackPoint(0.0, 0.0, startMs + it * 1000L, ms) }
        val km = kmh * minutes / 60.0
        return Trip(startMs.toString(), startMs, startMs + minutes * 60_000L, km * 1000, minutes * 60_000L, ms, pts, bikeRaw)
    }

    private val fuel = FuelStatus(null, null, 8.5, 2.5, true, null, 0.10, 0.0, 0.0, 0)

    @Test
    fun weekStartsMondayMidnight() {
        val wed = monday + 2 * 24 * hour + 15 * hour
        assertEquals(monday, WeeklyReport.weekStart(wed, zone))
        assertEquals(monday, WeeklyReport.weekStart(monday, zone))
        assertEquals(monday - 7 * 24 * hour, WeeklyReport.weekStart(monday - 1, zone))
    }

    @Test
    fun summarisesOnlyThisWeeksRidesOfThisBike() {
        val trips = listOf(
            ride(monday + 8 * hour, 50f, 30),                  // Mon: 25 km, all eco
            ride(monday + 2 * 24 * hour + 9 * hour, 80f, 15),  // Wed: 20 km, too fast
            ride(monday - 24 * hour, 50f, 12),                 // previous Sunday: 10 km
            ride(monday + 9 * hour, 50f, 60, bikeRaw = "OTHER"),
        )
        val r = WeeklyReport.build(trips, "B", emptyList(), fuel, band, monday, zone)
        assertEquals(2, r.rides.size)
        assertEquals(45.0, r.km, 1e-6)
        assertEquals(10.0, r.prevKm, 1e-6)
        assertEquals(350, r.vsPrevPercent)
        assertEquals(listOf(25.0, 0.0, 20.0, 0.0, 0.0, 0.0, 0.0), r.dailyKm.map { Math.round(it * 10) / 10.0 })
        assertEquals(80, r.maxKmh)
        assertEquals(60, r.avgKmh)                                   // 45 km in 45 min
        // Eco: ~25 of 45 km in band (the first few seconds of each ride warm the scorer up).
        assertEquals(55.0, r.ecoPercent!!.toDouble(), 2.0)
        assertEquals(45 * 2.5 / 100, r.fuelLitres!!, 1e-9)
        assertEquals(4.5, r.fuelCost!!, 1e-9)
    }

    @Test
    fun bestRideIsTheMostEcoRealRide() {
        val trips = listOf(
            ride(monday + 8 * hour, 50f, 2),                   // 1.7 km — too short to be "best"
            ride(monday + 10 * hour, 80f, 30),                 // 40 km, not eco
            ride(monday + 12 * hour, 45f, 20),                 // 15 km, eco
        )
        val best = WeeklyReport.build(trips, "B", emptyList(), fuel, band, monday, zone).best!!
        assertEquals(15.0, best.km, 1e-6)
    }

    @Test
    fun fillUpsInTheWeekAreCounted() {
        val fills = listOf(
            FuelEntry("a", monday + hour, 7.0, 2800.0, true),
            FuelEntry("b", monday - hour, 6.0, 2400.0, true),
        )
        val r = WeeklyReport.build(emptyList(), "B", fills, FuelStatus.EMPTY, band, monday, zone)
        assertEquals(1, r.fillUps)
        assertEquals(2800.0, r.fillUpSpend, 0.0)
        assertNull(r.ecoPercent)
        assertNull(r.best)
        assertNull(r.fuelLitres)
        assertNull(r.vsPrevPercent)
    }
}
