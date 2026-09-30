package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DirectionsFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val turn = NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 350, road = "Galle Rd")
    private val lanes = listOf(Lane(listOf(TurnKind.STRAIGHT to TurnSide.NONE), true))

    @Test
    fun staleAfterSixtySeconds() {
        val d = Directions(turn)
        assertTrue(DirectionsFormat.active(d, lastMsgElapsedMs = 1_000, nowElapsedMs = 61_000))
        assertFalse(DirectionsFormat.active(d, lastMsgElapsedMs = 1_000, nowElapsedMs = 61_001))
        assertFalse(DirectionsFormat.active(Directions.NONE, 1_000, 1_000))
        assertFalse(DirectionsFormat.active(null, 1_000, 1_000))
    }

    @Test
    fun etaPrefersMapsText() {
        assertEquals("ETA 18:42", DirectionsFormat.etaText("18:42", 1320, 0, utc, true))
    }

    @Test
    fun etaFromSecondsInClockFormat() {
        // 1970-01-01 00:00 UTC + 18h42m
        val secs = 18L * 3600 + 42 * 60
        assertEquals("ETA 18:42", DirectionsFormat.etaText(null, secs, 0, utc, is24h = true))
        assertEquals("ETA 6:42 PM", DirectionsFormat.etaText(null, secs, 0, utc, is24h = false))
        assertNull(DirectionsFormat.etaText(null, null, 0, utc, true))
    }

    @Test
    fun everythingFitsOnAWideBand() {
        assertEquals(DirectionsPlan(road = true, then = true, lanes = true, eta = true),
            DirectionsFormat.plan(640, 64, hasRoad = true, hasThen = true, laneCount = 3, hasEta = true))
    }

    @Test
    fun dropOrderLanesThenRoadThenEta() {
        // Widths at h=64: turn 166, road +52 (218), then 90, 3 lanes 115, eta 154.
        assertEquals(DirectionsPlan(true, true, false, true), DirectionsFormat.plan(480, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(true, false, false, true), DirectionsFormat.plan(380, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(false, false, false, true), DirectionsFormat.plan(330, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(false, false, false, false), DirectionsFormat.plan(200, 64, true, true, 3, true))
    }

    @Test
    fun missingPartsAreNotPlanned() {
        assertEquals(DirectionsPlan(false, false, false, false), DirectionsFormat.plan(640, 64, false, false, 0, false))
    }

    @Test
    fun thenOnlyWithinOneKilometre() {
        val then = NextTurn(TurnKind.NORMAL, TurnSide.LEFT)
        val near = DirectionsFormat.view(Directions(turn.copy(distanceM = 1000), then), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        val far = DirectionsFormat.view(Directions(turn.copy(distanceM = 1001), then), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals(then, near.then)
        assertNull(far.then)
    }

    @Test
    fun viewFormatsDistancesPerUnits() {
        val d = Directions(turn, lanes = lanes, etaText = "18:42", remainingM = 12400)
        val m = DirectionsFormat.view(d, MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals("350 m", m.distance)
        assertEquals("Galle Rd", m.road)
        assertEquals("12.4 km", m.remaining)
        assertEquals("ETA 18:42", m.eta)
        assertEquals(lanes, m.lanes)
        val i = DirectionsFormat.view(d, MapUnits.IMPERIAL, 640, 64, 0, utc, true)!!
        assertEquals("0.2 mi", i.distance)
        assertEquals("7.7 mi", i.remaining)
    }

    @Test
    fun noTurnNoView() {
        assertNull(DirectionsFormat.view(Directions.NONE, MapUnits.METRIC, 640, 64, 0, utc, true))
    }

    @Test
    fun unknownDistanceShowsDashes() {
        val v = DirectionsFormat.view(Directions(turn.copy(distanceM = null)), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals(MetricsFormat.NONE, v.distance)
    }

    @Test
    fun textShrinksToFitInsteadOfEllipsizing() {
        val widthAt = { size: Float -> size * 3f }            // text 3× as wide as its size
        val s = DirectionsFormat.shrinkToFit(start = 40f, min = 20f, maxW = 100f, widthAt = widthAt)
        assertTrue(widthAt(s) <= 100f)
        assertTrue(s > 30f)                                    // shrinks only as much as needed
        assertEquals(40f, DirectionsFormat.shrinkToFit(40f, 20f, 500f, widthAt), 0f)
        assertEquals(20f, DirectionsFormat.shrinkToFit(40f, 20f, 10f, widthAt), 0f)   // floor
    }
}
