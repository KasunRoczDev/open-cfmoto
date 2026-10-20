package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeBtRideTest {
    private val buds = "AA:AA:AA:AA:3A:5B" to "Galaxy Buds4 Pro"
    private val bike = "6C:53:4A:30:FB:D3" to "CFMOTO-9df992"
    private val otherBike = "11:22:33:44:55:66" to "CFMOTO-123456"

    @Test
    fun picksTheBondedDeviceNamedLikeTheSavedBike() {
        assertEquals(bike, BikeBtRide.autoPick(listOf(buds, otherBike, bike), listOf("CFMOTO-9df992")))
        assertEquals(bike, BikeBtRide.autoPick(listOf(bike, otherBike), listOf(null, " cfmoto-9DF992 ")))
    }

    @Test
    fun fallsBackToTheOnlyCfmotoDevice() {
        assertEquals(bike, BikeBtRide.autoPick(listOf(buds, bike), listOf("My scooter")))
        assertEquals(bike, BikeBtRide.autoPick(listOf(buds, bike), emptyList()))
    }

    @Test
    fun noPickWhenAmbiguousOrAbsent() {
        assertNull(BikeBtRide.autoPick(listOf(bike, otherBike), listOf("My scooter")))
        assertNull(BikeBtRide.autoPick(listOf(buds), listOf("CFMOTO-9df992")))
        assertNull(BikeBtRide.autoPick(emptyList(), listOf("CFMOTO-9df992")))
    }

    @Test
    fun isBikeComparesAddressesIgnoringCase() {
        assertTrue(BikeBtRide.isBike("6c:53:4a:30:fb:d3", bike.first))
        assertFalse(BikeBtRide.isBike(buds.first, bike.first))
        assertFalse(BikeBtRide.isBike(null, bike.first))
        assertFalse(BikeBtRide.isBike(bike.first, null))
    }
}
