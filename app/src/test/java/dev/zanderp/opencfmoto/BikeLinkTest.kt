package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkTest {
    @Test
    fun restartsAaOnlyAfterItHadSteadyVideo() {
        // Before steady video the older one-shot handshake retry handles it.
        assertFalse(BikeLink.shouldRestartAfterDrop(wasSteady = false, restartsSoFar = 0))
        assertTrue(BikeLink.shouldRestartAfterDrop(wasSteady = true, restartsSoFar = 0))
    }

    @Test
    fun restartBudgetStopsAFlappingAndroidAuto() {
        for (n in 0 until BikeLink.MAX_AA_RESTARTS) assertTrue(BikeLink.shouldRestartAfterDrop(true, n))
        assertFalse(BikeLink.shouldRestartAfterDrop(true, BikeLink.MAX_AA_RESTARTS))
    }
}
