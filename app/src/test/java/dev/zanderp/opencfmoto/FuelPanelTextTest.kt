package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelPanelTextTest {
    @Test
    fun rangeTextInBothUnits() {
        assertEquals("⛽ ~185 km", MetricsStrip.fuelText(185.9, MapUnits.METRIC))
        assertEquals("⛽ ~115 mi", MetricsStrip.fuelText(185.9, MapUnits.IMPERIAL))
    }

    @Test
    fun rangeTurnsAmberThenRed() {
        assertEquals(FuelLevel.OK, MetricsStrip.fuelLevel(50.0))
        assertEquals(FuelLevel.LOW, MetricsStrip.fuelLevel(49.9))
        assertEquals(FuelLevel.CRITICAL, MetricsStrip.fuelLevel(19.9))
    }
}
