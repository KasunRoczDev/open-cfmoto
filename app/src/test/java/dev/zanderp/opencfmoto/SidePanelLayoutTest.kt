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
}
