package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidePanelLayoutTest {
    @Test
    fun percentFromSize() {
        assertEquals(25, SidePanelLayout.percentFor(StripHeight.SMALL))
        assertEquals(30, SidePanelLayout.percentFor(StripHeight.MEDIUM))
        assertEquals(35, SidePanelLayout.percentFor(StripHeight.LARGE))
        assertEquals(0, SidePanelLayout.percentFor(StripHeight.OFF))
    }

    @Test
    fun panelPixels() {
        assertEquals(240, SidePanelLayout.panelPx(800, 30))
        assertEquals(200, SidePanelLayout.panelPx(800, 25))
        assertEquals(350, SidePanelLayout.panelPx(1000, 35))
        assertEquals(0, SidePanelLayout.panelPx(800, 0))
    }

    @Test
    fun tooNarrowTurnsOff() {
        assertEquals(0, SidePanelLayout.panelPx(340, 30))     // 102 px < 120
        assertEquals(0, SidePanelLayout.panelPx(310, 35))     // 109 px < 120
    }

    @Test
    fun aaGetsTheRest() {
        assertEquals(560, SidePanelLayout.aaWidth(800, 240))
        assertEquals(800, SidePanelLayout.aaWidth(800, 0))
    }

    @Test
    fun panelPosition() {
        assertEquals(560, SidePanelLayout.panelX(800, 240, left = false))
        assertEquals(0, SidePanelLayout.panelX(800, 240, left = true))
        assertTrue(SidePanelLayout.isInPanel(560, 800, 240, left = false))
        assertFalse(SidePanelLayout.isInPanel(559, 800, 240, left = false))
        assertTrue(SidePanelLayout.isInPanel(239, 800, 240, left = true))
        assertFalse(SidePanelLayout.isInPanel(240, 800, 240, left = true))
        assertFalse(SidePanelLayout.isInPanel(700, 800, 0, left = false))
    }

    @Test
    fun touchInPanelDownDropped() {
        assertNull(SidePanelLayout.touchX(700, 800, 240, left = false, isDown = true))
        assertNull(SidePanelLayout.touchX(10, 800, 240, left = true, isDown = true))
    }

    @Test
    fun touchInPanelMoveUpClampedToEdge() {
        assertEquals(559, SidePanelLayout.touchX(700, 800, 240, left = false, isDown = false))
        assertEquals(240, SidePanelLayout.touchX(10, 800, 240, left = true, isDown = false))
        assertEquals(300, SidePanelLayout.touchX(300, 800, 240, left = false, isDown = true))
    }

    @Test
    fun panelSitsInsideScreenMargins() {
        // 800×400 dash, 20 px right margin (hidden bezel), 22 px top margin: panel right, inside both.
        assertEquals(StripRect(540, 22, 240, 378), SidePanelLayout.panelRectGl(800, 400, 240, left = false, mL = 0, mR = 20, mT = 22, mB = 0))
        assertEquals(StripRect(10, 0, 240, 400), SidePanelLayout.panelRectGl(800, 400, 240, left = true, mL = 10, mR = 0, mT = 0, mB = 0))
    }

    @Test
    fun touchWithMarginsClampsToAaEdge() {
        // Panel 540..779 with a 20 px right margin: drag release on the panel → last AA column 539.
        assertEquals(539, SidePanelLayout.touchX(700, 800, 240, left = false, isDown = false, mL = 0, mR = 20))
        assertNull(SidePanelLayout.touchX(700, 800, 240, left = false, isDown = true, mL = 0, mR = 20))
        assertEquals(790, SidePanelLayout.touchX(790, 800, 240, left = false, isDown = false, mL = 0, mR = 20))   // margin, not panel
        assertEquals(250, SidePanelLayout.touchX(100, 800, 240, left = true, isDown = false, mL = 10, mR = 0))
    }

    @Test
    fun panelOnlyWhenAaWasLaidOutForIt() {
        assertEquals(0, SidePanelLayout.pipelinePanelPx(800, 30, appliedAtConnect = false))
        assertEquals(240, SidePanelLayout.pipelinePanelPx(800, 30, appliedAtConnect = true))
    }

    @Test
    fun textSizeLimitedByNarrowWidth() {
        // Tall narrow panel (240×951): width, not height, decides.
        assertEquals(240 * 0.12f, SidePanelLayout.textPx(240, 951, heightFrac = 0.08f, widthFrac = 0.12f), 1e-3f)
        assertEquals(400 * 0.08f, SidePanelLayout.textPx(400, 400, heightFrac = 0.08f, widthFrac = 0.12f), 1e-3f)
    }
}
