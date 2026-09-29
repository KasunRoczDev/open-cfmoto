package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsStripSessionTest {
    @Test
    fun snapshotCarriesChosenCells() {
        val cells = setOf(StripCell.ALTITUDE, StripCell.HEADING)
        assertEquals(cells, StripSnapshot.of(StripHeight.SMALL, turnInfo = true, cells = cells).cells)
        assertEquals(StripCells.ALL, StripSnapshot.of(StripHeight.SMALL, turnInfo = false).cells)
    }

    @Test
    fun snapshotTiesTurnInfoToVisibleStrip() {
        assertEquals(StripSnapshot(0, false), StripSnapshot.of(StripHeight.OFF, turnInfo = true))
        assertEquals(StripSnapshot(64, true), StripSnapshot.of(StripHeight.MEDIUM, turnInfo = true))
        assertEquals(StripSnapshot(48, false), StripSnapshot.of(StripHeight.SMALL, turnInfo = false))
    }

    @Test
    fun guardedSwallowsAndLogsFailures() {
        val logs = mutableListOf<String>()
        val ok = stripGuarded({ logs += it }, "start") { throw IllegalArgumentException("provider gone") }
        assertFalse(ok)
        assertEquals(1, logs.size)
        assertTrue(logs[0].startsWith("[STRIP] start failed"))
    }

    @Test
    fun guardedRunsBlock() {
        var ran = false
        assertTrue(stripGuarded({}, "start") { ran = true })
        assertTrue(ran)
    }
}
