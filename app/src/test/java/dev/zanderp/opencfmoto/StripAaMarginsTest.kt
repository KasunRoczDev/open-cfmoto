package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class StripAaMarginsTest {
    private val landscape = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    private val portrait = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    private fun marginsFor(spec: AaVideoSpec, panelW: Int, panelH: Int, requestedPx: Int): AaMargins {
        val stripPx = MetricsStripLayout.resolvePx(requestedPx, panelH)
        val (w, h) = MetricsStripLayout.panelForAa(panelW, panelH, stripPx)
        return AaMargins.forAspect(spec, w, h)
    }

    @Test
    fun landscapeWithoutStripUnchanged() {
        assertEquals(AaMargins(0, 96), marginsFor(landscape, 800, 384, 0))
    }

    @Test
    fun landscapeMediumStripShortensAa() {
        // 800x384 panel - 64 band = 800x320 → AA usable 800x320 → 160 px height margin.
        assertEquals(AaMargins(0, 160), marginsFor(landscape, 800, 384, 64))
    }

    @Test
    fun portraitMediumStrip() {
        // 800x944 - 64 = 800x880; coded 720x1280 → usable height round(720*880/800)=792 → margin 488.
        assertEquals(AaMargins(0, 488), marginsFor(portrait, 800, 944, 64))
    }

    @Test
    fun oversizedRequestIsClampedByPanel() {
        // Large 80 on a 200-tall panel clamps to 50 → 800x150.
        assertEquals(800 to 150, MetricsStripLayout.panelForAa(800, 200, 50))
        assertEquals(AaMargins.forAspect(landscape, 800, 150), marginsFor(landscape, 800, 200, 80))
    }
}
