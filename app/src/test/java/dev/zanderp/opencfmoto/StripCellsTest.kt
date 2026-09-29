package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StripCellsTest {
    private val all = setOf(StripCell.SPEED, StripCell.ALTITUDE, StripCell.HEADING)

    @Test
    fun parseDefaultsToAllMetrics() {
        assertEquals(all, StripCells.parse(null))
        assertEquals(all, StripCells.parse(""))
        assertEquals(all, StripCells.parse("BOGUS"))
    }

    @Test
    fun parseRoundTripsAndIgnoresTurn() {
        val s = setOf(StripCell.ALTITUDE, StripCell.HEADING)
        assertEquals(s, StripCells.parse(StripCells.serialize(s)))
        assertEquals(setOf(StripCell.HEADING), StripCells.parse("TURN,HEADING"))   // turn is Turn info's job
    }

    @Test
    fun toggleRemovesAndAdds() {
        assertEquals(setOf(StripCell.ALTITUDE, StripCell.HEADING), StripCells.toggle(all, StripCell.SPEED))
        assertEquals(all, StripCells.toggle(setOf(StripCell.ALTITUDE, StripCell.HEADING), StripCell.SPEED))
    }

    @Test
    fun toggleRefusesToEmpty() {
        assertNull(StripCells.toggle(setOf(StripCell.HEADING), StripCell.HEADING))
    }

    @Test
    fun layoutWithoutSpeedIsEqualWidths() {
        // 150SC: speed is on the dash already.
        val l = MetricsStripLayout.layout(800, 48, listOf(StripCell.ALTITUDE, StripCell.HEADING))
        assertEquals(listOf(StripCell.ALTITUDE, StripCell.HEADING), l.map { it.first })
        assertEquals(listOf(StripRect(0, 0, 400, 48), StripRect(400, 0, 400, 48)), l.map { it.second })
    }

    @Test
    fun layoutWithTurnAndNoSpeed() {
        val l = MetricsStripLayout.layout(800, 48, listOf(StripCell.HEADING, StripCell.TURN, StripCell.ALTITUDE))
        assertEquals(listOf(StripCell.TURN, StripCell.ALTITUDE, StripCell.HEADING), l.map { it.first })   // fixed order
        assertEquals(listOf(266, 266, 268), l.map { it.second.w })
    }

    @Test
    fun speedDoubleOnlyWithoutTurn() {
        val noTurn = MetricsStripLayout.layout(900, 48, listOf(StripCell.SPEED, StripCell.HEADING))
        assertEquals(listOf(600, 300), noTurn.map { it.second.w })
        val withTurn = MetricsStripLayout.layout(900, 48, listOf(StripCell.TURN, StripCell.SPEED))
        assertEquals(listOf(450, 450), withTurn.map { it.second.w })
    }

    @Test
    fun singleCellFillsStrip() {
        val l = MetricsStripLayout.layout(800, 48, listOf(StripCell.HEADING))
        assertEquals(listOf(StripCell.HEADING to StripRect(0, 0, 800, 48)), l)
    }

    @Test
    fun emptyLayoutIsEmpty() {
        assertEquals(emptyList<Pair<StripCell, StripRect>>(), MetricsStripLayout.layout(800, 48, emptyList()))
    }
}
