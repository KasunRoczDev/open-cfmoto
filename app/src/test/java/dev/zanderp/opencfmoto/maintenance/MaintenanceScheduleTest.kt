package dev.zanderp.opencfmoto.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceScheduleTest {
    @Test
    fun unknownOrNullModelIsEmpty() {
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel(null))
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel(""))
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel("CF450NK"))
    }

    @Test
    fun modelTextNormalizesToTheSameSchedule() {
        val plain = MaintenanceSchedule.forModel("150SC")
        assertTrue(plain.isNotEmpty())
        assertEquals(plain, MaintenanceSchedule.forModel("150 SC"))
        assertEquals(plain, MaintenanceSchedule.forModel(" 150-sc "))
        assertEquals(plain, MaintenanceSchedule.forModel("150Sc"))
    }

    @Test
    fun has42UniqueItemsWithOneOneTimeEntry() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertEquals(42, items.size)
        assertEquals(42, items.map { it.id }.toSet().size)
        assertEquals(listOf("engine_coarse_oil_filter"), items.filter { it.oneTime }.map { it.id })
    }

    @Test
    fun everyItemHasAtLeastOneInterval() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertTrue(items.all { it.kmInterval != null || it.monthInterval != null })
    }

    @Test
    fun seventeenItemsHaveABreakInFirstDuePoint() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertEquals(17, items.count { it.firstDueKm != null })
        assertTrue(items.filter { it.firstDueKm != null }.all { it.firstDueKm == 1000 })
    }
}
