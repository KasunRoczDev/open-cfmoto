package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommuteNavTest {
    @Before
    fun reset() = CommuteNav.disarm()

    @Test
    fun morningPlaceBeforeNoon() {
        assertEquals(0, CommuteNav.pickSlot(hour = 0, morningSlot = 0, eveningSlot = 1))
        assertEquals(0, CommuteNav.pickSlot(hour = 11, morningSlot = 0, eveningSlot = 1))
    }

    @Test
    fun eveningPlaceFromNoon() {
        assertEquals(1, CommuteNav.pickSlot(hour = 12, morningSlot = 0, eveningSlot = 1))
        assertEquals(1, CommuteNav.pickSlot(hour = 23, morningSlot = 0, eveningSlot = 1))
    }

    @Test
    fun firesOncePerConnect() {
        assertFalse(CommuteNav.consume())          // never armed: auto-reconnects don't navigate
        CommuteNav.arm()
        assertTrue(CommuteNav.consume())           // first stream after Connect
        assertFalse(CommuteNav.consume())          // reconnect / park-resume later in the session
    }

    @Test
    fun rearmOnNextConnect() {
        CommuteNav.arm()
        assertTrue(CommuteNav.consume())
        CommuteNav.arm()
        assertTrue(CommuteNav.consume())
    }

    @Test
    fun disarmCancels() {
        CommuteNav.arm()
        CommuteNav.disarm()
        assertFalse(CommuteNav.consume())
    }

    @Test
    fun labelSaysWhichHalfOfDay() {
        assertEquals("before 12:00", CommuteNav.whenLabel(9))
        assertEquals("after 12:00", CommuteNav.whenLabel(18))
    }
}
