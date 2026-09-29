package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsStripLayoutTest {
    @Test
    fun resolvePxKeepsRequestedWhenItFits() {
        assertEquals(64, MetricsStripLayout.resolvePx(64, 384))
        assertEquals(80, MetricsStripLayout.resolvePx(80, 944))
    }

    @Test
    fun resolvePxClampsToQuarterAndMinimum() {
        assertEquals(50, MetricsStripLayout.resolvePx(80, 200))   // 200 / 4
        assertEquals(32, MetricsStripLayout.resolvePx(20, 384))   // below MIN_PX
        assertEquals(32, MetricsStripLayout.resolvePx(64, 128))   // exactly MIN_CANVAS_H
    }

    @Test
    fun resolvePxZeroWhenOffOrCanvasTooSmall() {
        assertEquals(0, MetricsStripLayout.resolvePx(0, 384))
        assertEquals(0, MetricsStripLayout.resolvePx(64, 127))
    }

    @Test
    fun stripRectSitsAtBottomInTopOriginCoords() {
        assertEquals(StripRect(0, 320, 800, 64), MetricsStripLayout.stripRect(800, 384, 64))
    }

    @Test
    fun glPlacementPutsBandAtGlBottomAndShiftsAa() {
        assertTrue(MetricsStripLayout.STRIP_AT_GL_BOTTOM)
        assertEquals(0, MetricsStripLayout.glStripY(384, 64))
        assertEquals(64, MetricsStripLayout.glAaShift(64))
        assertEquals(0, MetricsStripLayout.glAaShift(0))
    }

    @Test
    fun touchInsideBandIsInStrip() {
        assertTrue(MetricsStripLayout.isInStrip(320, 384, 64))
        assertTrue(MetricsStripLayout.isInStrip(383, 384, 64))
        assertFalse(MetricsStripLayout.isInStrip(319, 384, 64))
        assertFalse(MetricsStripLayout.isInStrip(383, 384, 0))
    }

    @Test
    fun downInBandIsDropped() {
        assertEquals(null, MetricsStripLayout.touchY(350, 384, 64, isDown = true))
    }

    @Test
    fun moveOrUpInBandClampsToLastAaRow() {
        // A drag that ends in the band must still reach AA, or the pointer stays stuck down.
        assertEquals(319, MetricsStripLayout.touchY(350, 384, 64, isDown = false))
        assertEquals(319, MetricsStripLayout.touchY(383, 384, 64, isDown = false))
    }

    @Test
    fun touchAboveBandOrStripOffUnchanged() {
        assertEquals(200, MetricsStripLayout.touchY(200, 384, 64, isDown = true))
        assertEquals(200, MetricsStripLayout.touchY(200, 384, 64, isDown = false))
        assertEquals(383, MetricsStripLayout.touchY(383, 384, 0, isDown = true))
    }

    @Test
    fun panelForAaSubtractsBandWithFloor() {
        assertEquals(800 to 320, MetricsStripLayout.panelForAa(800, 384, 64))
        assertEquals(800 to 384, MetricsStripLayout.panelForAa(800, 384, 0))
        assertEquals(800 to 16, MetricsStripLayout.panelForAa(800, 20, 64))
    }

    private val allWithTurn = listOf(StripCell.TURN, StripCell.SPEED, StripCell.ALTITUDE, StripCell.HEADING)
    private val allNoTurn = listOf(StripCell.SPEED, StripCell.ALTITUDE, StripCell.HEADING)

    @Test
    fun fourEqualCellsWithTurn() {
        val c = MetricsStripLayout.layout(800, 64, allWithTurn).map { it.second }
        assertEquals(
            listOf(StripRect(0, 0, 200, 64), StripRect(200, 0, 200, 64), StripRect(400, 0, 200, 64), StripRect(600, 0, 200, 64)),
            c,
        )
    }

    @Test
    fun speedTakesTwoUnitsWithoutTurn() {
        val c = MetricsStripLayout.layout(800, 64, allNoTurn).map { it.second }
        assertEquals(listOf(StripRect(0, 0, 400, 64), StripRect(400, 0, 200, 64), StripRect(600, 0, 200, 64)), c)
    }

    @Test
    fun lastCellAbsorbsRemainder() {
        val c = MetricsStripLayout.layout(803, 48, allWithTurn).map { it.second }
        assertEquals(803, c.sumOf { it.w })
        assertEquals(203, c.last().w)
    }
}
