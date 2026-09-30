package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeModelTest {
    @Test
    fun modelIsTrimmedAndBlankIsNull() {
        assertEquals("150 SC", SavedBike.normalizeModel("  150 SC "))
        assertNull(SavedBike.normalizeModel("   "))
        assertNull(SavedBike.normalizeModel(null))
    }

    @Test
    fun defaultModelIsNullAndRenameKeepsIt() {
        assertNull(SavedBike("raw", "Bike").model)
        assertEquals("150 SC", SavedBike("raw", "Bike", null, "150 SC").copy(name = "Mine").model)
    }
}
