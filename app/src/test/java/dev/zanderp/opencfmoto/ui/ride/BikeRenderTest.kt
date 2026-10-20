package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeRenderTest {
    @Test
    fun anySpellingOf150scFindsTheRender() {
        for (m in listOf("150SC", "150 SC", "150-sc", " 150sc ")) assertEquals(R.drawable.bike_150sc, stockBikeRender(m))
    }

    @Test
    fun unknownOrMissingModelHasNoRender() {
        assertNull(stockBikeRender("800NK"))
        assertNull(stockBikeRender(null))
        assertNull(stockBikeRender("  "))
    }

    @Test
    fun heroLinesSplitNumberFromLetters() {
        assertEquals("150" to "SC", heroLines("150 SC"))
        assertEquals("800" to "NK", heroLines("800nk"))
        assertEquals("675" to "SRR", heroLines("675SR-R"))
        assertNull(heroLines("CFMOTO-9df992"))
        assertNull(heroLines("150"))
    }
}
