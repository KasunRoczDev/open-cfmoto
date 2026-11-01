package dev.zanderp.opencfmoto.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStatusPolicyTest {
    private fun kmh(v: Float) = v / 3.6f

    @Test
    fun startsParkedAndUnrestricted() {
        val p = DrivingStatusPolicy()
        assertFalse(p.moving)
        assertEquals(0, p.status)
    }

    @Test
    fun movingStatusKeepsVideoAndVoice() {
        // NO_VIDEO (1) would blank the dash; NO_VOICE_INPUT (4) would kill the Assistant.
        assertEquals(26, DrivingStatusPolicy.STATUS_MOVING)
        assertEquals(0, DrivingStatusPolicy.STATUS_MOVING and 1)
        assertEquals(0, DrivingStatusPolicy.STATUS_MOVING and 4)
    }

    @Test
    fun walkingPaceStaysParked() {
        val p = DrivingStatusPolicy()
        assertFalse(p.onFix(kmh(6f), 0))
        assertFalse(p.onFix(kmh(9.9f), 1_000))
        assertFalse(p.moving)
    }

    @Test
    fun ridingAwayRestrictsImmediately() {
        val p = DrivingStatusPolicy()
        assertTrue(p.onFix(kmh(12f), 0))
        assertTrue(p.moving)
        assertEquals(DrivingStatusPolicy.STATUS_MOVING, p.status)
    }

    @Test
    fun briefStopKeepsRestriction() {
        val p = DrivingStatusPolicy()
        p.onFix(kmh(40f), 0)
        assertFalse(p.onFix(kmh(0f), 1_000))
        assertFalse(p.onFix(kmh(0f), 5_999))
        assertFalse(p.onFix(kmh(15f), 6_500))   // pulled away again: timer resets
        assertFalse(p.onFix(kmh(0f), 7_000))
        assertFalse(p.onFix(kmh(0f), 11_999))
        assertTrue(p.moving)
    }

    @Test
    fun stoppedForHoldTimeUnlocks() {
        val p = DrivingStatusPolicy()
        p.onFix(kmh(30f), 0)
        p.onFix(kmh(2f), 1_000)
        assertTrue(p.onFix(kmh(0f), 6_000))
        assertFalse(p.moving)
        assertEquals(0, p.status)
    }

    @Test
    fun crawlBetweenThresholdsStaysMoving() {
        val p = DrivingStatusPolicy()
        p.onFix(kmh(20f), 0)
        for (t in 1..20) assertFalse(p.onFix(kmh(6f), t * 1_000L))
        assertTrue(p.moving)
    }

    @Test
    fun fixWithoutSpeedChangesNothing() {
        val p = DrivingStatusPolicy()
        p.onFix(kmh(30f), 0)
        assertFalse(p.onFix(null, 60_000))
        assertFalse(p.onFix(Float.NaN, 61_000))
        assertTrue(p.moving)
    }
}
