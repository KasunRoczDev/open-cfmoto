package dev.zanderp.opencfmoto.maintenance

import dev.zanderp.opencfmoto.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MaintenanceEstimateTest {
    private fun trip(endMs: Long, km: Double, bikeRaw: String? = null) =
        Trip("t$endMs-$bikeRaw", endMs - 60_000, endMs, km * 1000, 60_000, 0f, emptyList(), bikeRaw)

    private fun addMonthsUtc(fromMs: Long, months: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = fromMs
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    private val baseline = MaintenanceState(baselineKm = 10_000.0, baselineSetAtMs = 1_000_000L)

    @Test
    fun estimateIsBaselineWhenNoTrips() {
        assertEquals(10_000.0, MaintenanceEstimate.estimateKm(baseline, emptyList(), "bikeA"), 1e-9)
    }

    @Test
    fun estimateSumsTripsAfterBaselineForThisBikeOrUntagged() {
        val trips = listOf(
            trip(2_000_000L, 50.0, "bikeA"),   // after baseline, this bike: counts
            trip(500_000L, 999.0, "bikeA"),    // before baseline: excluded
            trip(2_100_000L, 30.0, "bikeB"),   // another bike: excluded
            trip(2_200_000L, 20.0, null),      // untagged/legacy: counts for any bike
        )
        assertEquals(10_000.0 + 50.0 + 20.0, MaintenanceEstimate.estimateKm(baseline, trips, "bikeA"), 1e-9)
    }

    @Test
    fun dueKmNeverServicedUsesFirstDueOverrideWhenPresent() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        assertEquals(1000.0, MaintenanceEstimate.dueKm(item, null)!!, 1e-9)
    }

    @Test
    fun dueKmNeverServicedFallsBackToIntervalWithoutOverride() {
        val item = MaintenanceItem("x", "Engine", "Oil filter", "Replace", kmInterval = 3000, monthInterval = 3)
        assertEquals(3000.0, MaintenanceEstimate.dueKm(item, null)!!, 1e-9)
    }

    @Test
    fun dueKmAfterServiceUsesLastServicedPlusInterval() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        val state = ItemState(lastServicedKm = 4000.0, lastServicedAtMs = 0L)
        assertEquals(7000.0, MaintenanceEstimate.dueKm(item, state)!!, 1e-9)
    }

    @Test
    fun dueKmNullWhenNoKmInterval() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 24)
        assertNull(MaintenanceEstimate.dueKm(item, null))
    }

    @Test
    fun dueAtNeverServicedCountsFromBaselineDate() {
        val item = MaintenanceItem("x", "Engine", "Air filter elements", "Clean", kmInterval = 5000, monthInterval = 12)
        val expected = addMonthsUtc(1_000_000L, 12)
        assertEquals(expected, MaintenanceEstimate.dueAtMs(item, null, baselineSetAtMs = 1_000_000L))
    }

    @Test
    fun dueAtAfterServiceCountsFromServiceDate() {
        val item = MaintenanceItem("x", "Engine", "Air filter elements", "Clean", kmInterval = 5000, monthInterval = 12)
        val state = ItemState(lastServicedKm = 0.0, lastServicedAtMs = 2_000_000L)
        val expected = addMonthsUtc(2_000_000L, 12)
        assertEquals(expected, MaintenanceEstimate.dueAtMs(item, state, baselineSetAtMs = 1_000_000L))
    }

    @Test
    fun statusOverdueByKmAlone() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        assertEquals(
            MaintenanceStatus.OVERDUE,
            MaintenanceEstimate.status(item, null, estimateKm = 1000.0, nowMs = 0L, baselineSetAtMs = 1_000_000_000_000L),
        )
    }

    @Test
    fun statusOverdueByDateAloneWhenKmNotYetDue() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 24)
        val dueAt = addMonthsUtc(0L, 24)
        assertEquals(
            MaintenanceStatus.OVERDUE,
            MaintenanceEstimate.status(item, null, estimateKm = 0.0, nowMs = dueAt + 86_400_000L, baselineSetAtMs = 0L),
        )
    }

    @Test
    fun statusDueSoonWithinThreeHundredKm() {
        val item = MaintenanceItem("x", "Engine", "Oil filter", "Replace", kmInterval = 3000, monthInterval = null)
        assertEquals(MaintenanceStatus.DUE_SOON, MaintenanceEstimate.status(item, null, estimateKm = 2750.0, nowMs = 0L, baselineSetAtMs = 0L))
        assertEquals(MaintenanceStatus.OK, MaintenanceEstimate.status(item, null, estimateKm = 2600.0, nowMs = 0L, baselineSetAtMs = 0L))
    }

    @Test
    fun remainingKmIsDuePointMinusEstimate() {
        val item = MaintenanceItem("x", "Engine", "Spark plug", "Replace", kmInterval = 10_000, monthInterval = null)
        assertEquals(2_000.0, MaintenanceEstimate.remainingKm(item, null, estimateKm = 8_000.0)!!, 1e-9)
        assertEquals(-500.0, MaintenanceEstimate.remainingKm(item, null, estimateKm = 10_500.0)!!, 1e-9)
        assertNull(MaintenanceEstimate.remainingKm(MaintenanceItem("y", "Brake", "Brake fluid", "Replace", null, 24), null, 0.0))
    }

    @Test
    fun remainingDaysIsDuePointMinusNow() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 1)
        val dueAt = addMonthsUtc(0L, 1)
        assertEquals(0L, MaintenanceEstimate.remainingDays(item, null, baselineSetAtMs = 0L, nowMs = dueAt)!!)
        assertEquals(-1L, MaintenanceEstimate.remainingDays(item, null, baselineSetAtMs = 0L, nowMs = dueAt + 86_400_000L + 1)!!)
    }

    @Test
    fun visibleItemsDropsServicedOneTimeItemsOnly() {
        val oneTime = MaintenanceItem("coarse", "Engine", "Coarse oil filter", "Clean", kmInterval = 1000, monthInterval = null, oneTime = true)
        val recurring = MaintenanceItem("oil", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        val servicedOneTime = mapOf("coarse" to ItemState(1000.0, 0L))
        assertEquals(listOf(recurring), MaintenanceEstimate.visibleItems(listOf(oneTime, recurring), servicedOneTime))
        assertEquals(listOf(oneTime, recurring), MaintenanceEstimate.visibleItems(listOf(oneTime, recurring), emptyMap()))
    }

    @Test
    fun itemsToNotifySkipsAlreadyNotifiedAndNonOverdue() {
        val due = MaintenanceItem("due", "Engine", "Spark plug", "Replace", kmInterval = 10_000, monthInterval = null)
        val notifiedAlready = MaintenanceItem("notified", "Engine", "Valve clearance", "Inspect and adjust if necessary", kmInterval = 40_000, monthInterval = null)
        val state = MaintenanceState(
            baselineKm = 0.0, baselineSetAtMs = 0L,
            items = mapOf("notified" to ItemState(0.0, 0L, notified = true)),
        )
        val result = MaintenanceEstimate.itemsToNotify(listOf(due, notifiedAlready), state, estimateKm = 45_000.0, nowMs = 0L)
        assertEquals(listOf(due), result)
    }

    @Test
    fun itemsToNotifySkipsNeverServicedItemAlreadyNotified() {
        val due = MaintenanceItem("due", "Engine", "Spark plug", "Replace", kmInterval = 10_000, monthInterval = null)
        val state = MaintenanceState(baselineKm = 0.0, baselineSetAtMs = 0L).withNotified("due")
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceEstimate.itemsToNotify(listOf(due), state, estimateKm = 45_000.0, nowMs = 0L))
    }

    @Test
    fun withNotifiedOnServicedItemSetsItsFlag() {
        val state = MaintenanceState(0.0, 0L).withServiced("x", 1.0, 1L).withNotified("x")
        assertEquals(ItemState(1.0, 1L, notified = true), state.items["x"])
        assertEquals(emptySet<String>(), state.notifiedUnserviced)
    }

    @Test
    fun servicingClearsNotifiedForTheNextCycle() {
        val neverServiced = MaintenanceState(0.0, 0L).withNotified("x").withServiced("x", 45_000.0, 5L)
        assertEquals(ItemState(45_000.0, 5L, notified = false), neverServiced.items["x"])
        assertEquals(emptySet<String>(), neverServiced.notifiedUnserviced)
        val serviced = MaintenanceState(0.0, 0L).withServiced("x", 1.0, 1L).withNotified("x").withServiced("x", 2.0, 2L)
        assertEquals(ItemState(2.0, 2L, notified = false), serviced.items["x"])
    }
}
