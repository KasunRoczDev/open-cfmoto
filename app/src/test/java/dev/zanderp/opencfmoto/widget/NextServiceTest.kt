package dev.zanderp.opencfmoto.widget

import dev.zanderp.opencfmoto.maintenance.MaintenanceItem
import dev.zanderp.opencfmoto.maintenance.MaintenanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextServiceTest {
    private val oil = MaintenanceItem("oil", "Engine", "Engine oil", "Replace", kmInterval = 3000, monthInterval = 12)
    private val chain = MaintenanceItem("belt", "Drive", "Drive belt", "Inspect", kmInterval = 6000, monthInterval = null)
    private val coolant = MaintenanceItem("coolant", "Engine", "Coolant", "Replace", kmInterval = null, monthInterval = 24)
    private val day = 86_400_000L

    @Test
    fun countsOverdueItemsFirst() {
        val s = MaintenanceState(baselineKm = 7000.0, baselineSetAtMs = 0L)
        assertEquals(NextService(2, null, null, null), NextService.of(listOf(oil, chain, coolant), s, 7000.0, 0L))
    }

    @Test
    fun otherwiseNamesTheClosestItemInKm() {
        val s = MaintenanceState(baselineKm = 2000.0, baselineSetAtMs = 0L).withServiced("belt", 1000.0, 0L)
        val n = NextService.of(listOf(oil, chain), s, 2800.0, 10 * day)!!
        assertEquals("Engine oil", n.name)
        assertEquals(200, n.inKm)
        assertNull(n.inDays)
    }

    @Test
    fun calendarOnlyItemsReportDays() {
        val s = MaintenanceState(baselineKm = 0.0, baselineSetAtMs = 0L)
        val n = NextService.of(listOf(coolant), s, 0.0, 700 * day)!!
        assertEquals("Coolant", n.name)
        assertNull(n.inKm)
        assertTrue(n.inDays!! in 29..32)   // 24 months from day 0 is ~730 days
    }

    @Test
    fun noItemsMeansNothingToShow() {
        assertNull(NextService.of(emptyList(), MaintenanceState(0.0, 0L), 0.0, 0L))
    }
}
