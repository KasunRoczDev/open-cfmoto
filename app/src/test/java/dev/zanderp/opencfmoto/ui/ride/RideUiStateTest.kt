package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.design.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideUiStateTest {
    @Test
    fun idleWithSavedBikeOffersConnect() {
        val s = rideUiState(Phase.IDLE, "", "CFMOTO4288", hasSavedBike = true)
        assertEquals(PrimaryAction.CONNECT, s.primary)
        assertEquals(StatusKind.IDLE, s.statusKind)
        assertTrue(s.scanEnabled); assertTrue(s.mirrorEnabled); assertTrue(s.controlsEnabled)
        assertFalse(s.dashViewEnabled)
    }

    @Test
    fun noSavedBikeOffersScan() {
        assertEquals(PrimaryAction.SCAN, rideUiState(Phase.IDLE, "", null, hasSavedBike = false).primary)
    }

    @Test
    fun busyPhasesOfferStopAndBlockScan() {
        for (p in Phase.values().filter { it.busy }) {
            val s = rideUiState(p, "", "Bike", hasSavedBike = true)
            assertEquals(p.name, PrimaryAction.STOP, s.primary)
            assertEquals(p.name, StatusKind.BUSY, s.statusKind)
            assertFalse(p.name, s.scanEnabled)
            assertFalse(p.name, s.mirrorEnabled)
        }
    }

    @Test
    fun liveOffersStopAndDashView() {
        for (p in listOf(Phase.STREAMING, Phase.MIRRORING)) {
            val s = rideUiState(p, "", "Bike", hasSavedBike = true)
            assertEquals(PrimaryAction.STOP, s.primary)
            assertEquals(StatusKind.LIVE, s.statusKind)
            assertTrue(s.dashViewEnabled)
            assertTrue(s.mirrorEnabled)      // switch dash content while live
        }
    }

    @Test
    fun errorAndStoppedOfferConnect() {
        assertEquals(StatusKind.ERROR, rideUiState(Phase.ERROR, "boom", "Bike", true).statusKind)
        assertEquals(PrimaryAction.CONNECT, rideUiState(Phase.ERROR, "boom", "Bike", true).primary)
        assertEquals(StatusKind.IDLE, rideUiState(Phase.STOPPED, "", "Bike", true).statusKind)
    }

    @Test
    fun detailPassesThrough() {
        assertEquals("Linking…", rideUiState(Phase.PXC_CONNECTING, "Linking…", "Bike", true).detail)
    }

    @Test
    fun openMapBlockedWhileConnectingOrReconnecting() {
        // Opening the Map hub mid-connect abandons an active NAV_TO route (review finding).
        for (p in Phase.values().filter { it.busy }) assertFalse(p.name, rideUiState(p, "", "Bike", true).mapEnabled)
        assertTrue(rideUiState(Phase.IDLE, "", "Bike", true).mapEnabled)
        assertTrue(rideUiState(Phase.MIRRORING, "", "Bike", true).mapEnabled)
    }
}
