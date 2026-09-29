package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RiderMetricsTest {
    private val now = 100_000L

    @Test
    fun metricSpeedAltitudeHeading() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 412.4, headingDeg = 45f, fixAtMs = now - 1_000)
        val v = MetricsFormat.values(m, MapUnits.METRIC, now)
        assertEquals("72", v.speed)
        assertEquals("km/h", v.speedUnit)
        assertEquals("410", v.altitude)
        assertEquals("m", v.altitudeUnit)
        assertEquals("NE", v.compass)
        assertEquals("45°", v.degrees)
    }

    @Test
    fun imperialUnits() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 412.4, headingDeg = 45f, fixAtMs = now)
        val v = MetricsFormat.values(m, MapUnits.IMPERIAL, now)
        assertEquals("45", v.speed)          // 20 m/s = 44.7 mph
        assertEquals("mph", v.speedUnit)
        assertEquals("1350", v.altitude)     // 1353 ft → nearest 10
        assertEquals("ft", v.altitudeUnit)
    }

    @Test
    fun noFixShowsDashes() {
        val v = MetricsFormat.values(RiderMetrics(), MapUnits.METRIC, now)
        assertEquals("--", v.speed)
        assertEquals("--", v.altitude)
        assertEquals("--", v.compass)
        assertEquals("", v.degrees)
    }

    @Test
    fun staleFixShowsDashesButKeepsHeading() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 100.0, headingDeg = 90f, fixAtMs = now - 5_001)
        val v = MetricsFormat.values(m, MapUnits.METRIC, now)
        assertEquals("--", v.speed)
        assertEquals("--", v.altitude)
        assertEquals("E", v.compass)
    }

    @Test
    fun fixExactlyAtStaleLimitIsFresh() {
        val m = RiderMetrics(speedMps = 10f, fixAtMs = now - 5_000)
        assertEquals("36", MetricsFormat.values(m, MapUnits.METRIC, now).speed)
    }

    @Test
    fun altitudeRoundsNegativeAndLarge() {
        assertEquals("-30", MetricsFormat.altitude(-32.0, imperial = false))
        assertEquals("0", MetricsFormat.altitude(-2.0, imperial = false))
        assertEquals("8850", MetricsFormat.altitude(8848.9, imperial = false))
        assertEquals("-100", MetricsFormat.altitude(-30.0, imperial = true))   // -98.4 ft
    }

    @Test
    fun speedNeverNegative() {
        assertEquals("0", MetricsFormat.speed(-0.4f, imperial = false))
    }

    @Test
    fun compassPointsAndWrap() {
        assertEquals("N", MetricsFormat.compass(0f))
        assertEquals("N", MetricsFormat.compass(359f))
        assertEquals("N", MetricsFormat.compass(22.4f))
        assertEquals("NE", MetricsFormat.compass(22.5f))
        assertEquals("S", MetricsFormat.compass(180f))
        assertEquals("NW", MetricsFormat.compass(-45f))
        assertEquals("0°", MetricsFormat.degrees(359.7f))
    }

    @Test
    fun headingHoldsWhenSlow() {
        val moving = RiderMetrics().withFix(speedMps = 10f, altitudeM = 5.0, bearingDeg = 90f, atMs = 1L)
        assertEquals(90f, moving.headingDeg!!, 0f)
        val slow = moving.withFix(speedMps = 1f, altitudeM = 5.0, bearingDeg = 270f, atMs = 2L)
        assertEquals(90f, slow.headingDeg!!, 0f)
        assertEquals(2L, slow.fixAtMs)
        val noBearing = moving.withFix(speedMps = 10f, altitudeM = 5.0, bearingDeg = null, atMs = 3L)
        assertEquals(90f, noBearing.headingDeg!!, 0f)
    }

    @Test
    fun turnDistanceMetric() {
        assertEquals("350 m", MetricsFormat.turnDistance(347, imperial = false))
        assertEquals("990 m", MetricsFormat.turnDistance(994, imperial = false))
        assertEquals("1.0 km", MetricsFormat.turnDistance(996, imperial = false))
        assertEquals("1.2 km", MetricsFormat.turnDistance(1234, imperial = false))
    }

    @Test
    fun turnDistanceImperial() {
        assertEquals("500 ft", MetricsFormat.turnDistance(150, imperial = true))   // 492 ft
        assertEquals("0.3 mi", MetricsFormat.turnDistance(483, imperial = true))
    }

    @Test
    fun turnFieldsAndEquality() {
        val t = NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 347, road = "Main St")
        val a = MetricsFormat.values(RiderMetrics(turn = t), MapUnits.METRIC, now)
        assertEquals("350 m", a.turnDistance)
        assertEquals("Main St", a.turnRoad)
        assertEquals(a, MetricsFormat.values(RiderMetrics(turn = t), MapUnits.METRIC, now))
        assertNotEquals(a, MetricsFormat.values(RiderMetrics(turn = t.copy(distanceM = 200)), MapUnits.METRIC, now))
        val none = MetricsFormat.values(RiderMetrics(), MapUnits.METRIC, now)
        assertEquals("", none.turnDistance)
        assertEquals("", none.turnRoad)
    }
}
