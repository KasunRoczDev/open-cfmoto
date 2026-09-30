package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonMapCountTest {
    @Test
    fun countsEverythingButDoNothing() {
        assertEquals(0, ButtonMap.mappedCount(emptyList()))
        assertEquals(
            2,
            ButtonMap.mappedCount(listOf(ButtonAction.SELECT, ButtonAction.NONE, ButtonAction.HOME, ButtonAction.NONE)),
        )
    }

    @Test
    fun shippedDefaultsMapSevenOfNine() {
        assertEquals(7, ButtonMap.mappedCount(ButtonGesture.entries.map { it.default }))
    }
}
