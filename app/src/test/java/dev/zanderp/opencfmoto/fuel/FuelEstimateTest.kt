package dev.zanderp.opencfmoto.fuel

import dev.zanderp.opencfmoto.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FuelEstimateTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val bike = "BIKE"
    private val spec = FuelSpec(tankL = 8.5, specL100 = 2.6)
    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L   // some October 2026 day

    private fun trip(endMs: Long, km: Double, bikeRaw: String? = bike) =
        Trip("t$endMs", endMs - 60_000, endMs, km * 1000, 60_000, 0f, emptyList(), bikeRaw)

    private fun fill(atMs: Long, litres: Double, full: Boolean = true, cost: Double? = null) =
        FuelEntry("f$atMs", atMs, litres, cost, full)

    private fun status(entries: List<FuelEntry>, trips: List<Trip>, now: Long = t0 + 30 * day, s: FuelSpec? = spec) =
        FuelEstimate.status(entries, trips, bike, s, now, utc)

    @Test
    fun noFillUpsMeansNoRangeButSpecConsumption() {
        val s = status(emptyList(), listOf(trip(t0, 50.0)))
        assertNull(s.rangeKm)
        assertEquals(2.6, s.l100!!, 1e-9)
        assertFalse(s.measured)
    }

    @Test
    fun rangeAfterAFullTankUsesSpecUntilMeasured() {
        val s = status(listOf(fill(t0, 7.0)), listOf(trip(t0 + day, 100.0)))
        assertEquals(8.5 - 2.6, s.litresLeft!!, 1e-9)
        assertEquals((8.5 - 2.6) / 2.6 * 100, s.rangeKm!!, 1e-6)
        assertEquals(100.0, s.kmSinceFill!!, 1e-9)
    }

    @Test
    fun fullToFullMeasuresConsumption() {
        val entries = listOf(fill(t0, 8.0), fill(t0 + 5 * day, 5.0))
        val trips = listOf(trip(t0 + day, 120.0), trip(t0 + 2 * day, 80.0))
        val s = status(entries, trips)
        assertTrue(s.measured)
        assertEquals(2.5, s.l100!!, 1e-9)              // 5 L over 200 km
        assertEquals(40.0, s.kmPerLitre!!, 1e-9)
        assertEquals(8.5 / 2.5 * 100, s.rangeKm!!, 1e-6)   // just filled
    }

    @Test
    fun partialFillTopsUpTheEstimatedLevel() {
        val entries = listOf(fill(t0, 8.0), fill(t0 + 3 * day, 2.0, full = false))
        val trips = listOf(trip(t0 + day, 150.0), trip(t0 + 4 * day, 50.0))
        val s = status(entries, trips)
        // 8.5 − 3.9 = 4.6, + 2.0 = 6.6, − 1.3 = 5.3 L
        assertEquals(5.3, s.litresLeft!!, 1e-9)
    }

    @Test
    fun partialFillBeforeAnyFullTankGivesNoRange() {
        assertNull(status(listOf(fill(t0, 3.0, full = false)), emptyList()).rangeKm)
    }

    @Test
    fun levelNeverGoesNegativeAndPartialCannotOverfill() {
        assertEquals(0.0, status(listOf(fill(t0, 8.0)), listOf(trip(t0 + day, 900.0))).litresLeft!!, 1e-9)
        val topped = status(listOf(fill(t0, 8.0), fill(t0 + day, 6.0, full = false)), emptyList())
        assertEquals(8.5, topped.litresLeft!!, 1e-9)
    }

    @Test
    fun shortOrImplausibleIntervalsAreIgnored() {
        // 10 km between fulls: too short to trust.
        val short = status(listOf(fill(t0, 8.0), fill(t0 + day, 1.0)), listOf(trip(t0 + 1, 10.0)))
        assertFalse(short.measured)
        // 6 L over 30 km = 20 L/100 km: rides were missed or a typo.
        val silly = status(listOf(fill(t0, 8.0), fill(t0 + day, 6.0)), listOf(trip(t0 + 1, 30.0)))
        assertFalse(silly.measured)
    }

    @Test
    fun otherBikesRidesDoNotCount() {
        val s = status(listOf(fill(t0, 8.0)), listOf(trip(t0 + day, 100.0, bikeRaw = "OTHER")))
        assertEquals(8.5, s.litresLeft!!, 1e-9)
    }

    @Test
    fun unknownModelHasNoRangeWithoutATankSize() {
        val s = status(listOf(fill(t0, 8.0)), listOf(trip(t0 + day, 100.0)), s = null)
        assertNull(s.rangeKm)
        assertNull(s.l100)
    }

    @Test
    fun costPerKmAndThisMonth() {
        val now = t0 + 2 * day
        val entries = listOf(fill(t0 - 60 * day, 8.0, cost = 3000.0), fill(t0, 5.0, cost = 2000.0), fill(t0 + day, 2.0, full = false, cost = 800.0))
        val s = status(entries, emptyList(), now = now)
        // 5800 over 15 L ≈ 386.67 per litre × 2.6 L/100 km
        assertEquals(5800.0 / 15.0 * 2.6 / 100, s.costPerKm!!, 1e-9)
        assertEquals(2800.0, s.monthCost, 1e-9)
        assertEquals(7.0, s.monthLitres, 1e-9)
        assertEquals(3, s.fillCount)
    }

    @Test
    fun codecRoundTripsAndSkipsBadRows() {
        val entries = listOf(fill(t0, 7.25, cost = 2500.0), fill(t0 + day, 2.0, full = false))
        assertEquals(entries, FuelCodec.decode(FuelCodec.encode(entries)))
        assertEquals(1, FuelCodec.decode("""[{"id":"a","atMs":1,"litres":2.0,"full":true},{"id":"b"}]""").size)
        assertTrue(FuelCodec.decode("not json").isEmpty())
    }

    @Test
    fun specLookupIgnoresSpelling() {
        assertEquals(8.5, FuelSpecs.forModel("150 SC")!!.tankL, 0.0)
        assertNull(FuelSpecs.forModel("800NK"))
    }
}
