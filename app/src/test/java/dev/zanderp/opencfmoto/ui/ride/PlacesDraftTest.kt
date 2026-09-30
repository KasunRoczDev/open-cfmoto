package dev.zanderp.opencfmoto.ui.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesDraftTest {
    @Test
    fun typedPlacesSurviveSaveAndRestore() {
        val d = MappingDialog.Places(listOf("Home", "", "Gym"), listOf("Colombo Fort", "Work St", ""))
        assertEquals(d, MappingDialog.Places.restore(d.save()))
    }

    @Test
    fun malformedSavedStateIsIgnored() {
        assertNull(MappingDialog.Places.restore(arrayListOf("only-one")))
        assertNull(MappingDialog.Places.restore(null))
    }
}
