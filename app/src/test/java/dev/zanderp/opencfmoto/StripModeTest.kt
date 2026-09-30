package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StripModeTest {
    @Test
    fun metricsReservesBandForAa() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false)
        assertEquals(StripMode.METRICS, s.mode)
        assertFalse(s.overlay)
        assertEquals(64, s.aaReservePx)
    }

    @Test
    fun directionsOverlaysAndForcesTurnInfo() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false, mode = StripMode.DIRECTIONS)
        assertTrue(s.overlay)
        assertTrue(s.turnInfo)
        assertEquals(0, s.aaReservePx)
        assertEquals(64, s.requestPx)
    }

    @Test
    fun stripOffMeansNothingInEitherMode() {
        val s = StripSnapshot.of(StripHeight.OFF, turnInfo = true, mode = StripMode.DIRECTIONS)
        assertFalse(s.turnInfo)
        assertEquals(0, s.requestPx)
        assertEquals(0, s.aaReservePx)
    }

    @Test
    fun sideReservesWidthNotHeight() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false, mode = StripMode.SIDE, sideLeft = true)
        assertTrue(s.side)
        assertTrue(s.turnInfo)
        assertEquals(30, s.sidePercent)
        assertTrue(s.sideLeft)
        assertEquals(0, s.aaReservePx)
        assertFalse(s.overlay)
    }

    @Test
    fun sideOffWhenStripOff() {
        val s = StripSnapshot.of(StripHeight.OFF, turnInfo = true, mode = StripMode.SIDE)
        assertFalse(s.side)
        assertFalse(s.turnInfo)
    }
}
