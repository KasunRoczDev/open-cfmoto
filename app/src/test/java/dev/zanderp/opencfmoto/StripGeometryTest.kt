package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class StripGeometryTest {
    @Test
    fun metricsModeUnchanged() {
        // Reserved under AA, always drawn, taps on it excluded — visible flag ignored.
        assertEquals(StripGeometry(64, 64, 64), StripGeometry.of(64, overlay = false, visible = false))
        assertEquals(StripGeometry(64, 64, 64), StripGeometry.of(64, overlay = false, visible = true))
    }

    @Test
    fun visibleOverlayDrawsOverFullScreenAa() {
        assertEquals(StripGeometry(0, 64, 64), StripGeometry.of(64, overlay = true, visible = true))
    }

    @Test
    fun hiddenOverlayExcludesNothing() {
        assertEquals(StripGeometry(0, 0, 0), StripGeometry.of(64, overlay = true, visible = false))
    }

    @Test
    fun noStripNoGeometry() {
        assertEquals(StripGeometry(0, 0, 0), StripGeometry.of(0, overlay = false, visible = true))
    }
}
