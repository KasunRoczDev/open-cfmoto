package dev.zanderp.opencfmoto.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAheadTest {
    private val now = 1_792_480_000_000L
    private val q = 15 * 60_000L

    private fun point(rain: List<Double>, precipNow: Double = 0.0, code: Int = 3, temp: Double = 30.6) =
        WeatherPoint(temp, precipNow, code, rain.mapIndexed { i, mm -> now + i * q to mm })

    @Test
    fun rainingNowWins() {
        val h = RainAhead.decide(point(listOf(0.0), precipNow = 0.4, code = 61), null, now)
        assertEquals(WeatherKind.RAINING, h.kind)
        assertEquals("🌧 Raining · 31°", RainAhead.text(h))
    }

    @Test
    fun lightDrizzleSaysDrizzle() {
        // What Colombo reported while this was written: code 51 at 0.1 mm.
        val h = RainAhead.decide(point(listOf(0.1, 0.1), precipNow = 0.1, code = 51, temp = 29.5), null, now)
        assertEquals(WeatherKind.RAINING, h.kind)
        assertEquals("🌦 Drizzle · 30°", RainAhead.text(h))
    }

    @Test
    fun rainComingHereGivesMinutesRoundedToFive() {
        val h = RainAhead.decide(point(listOf(0.0, 0.0, 0.5, 1.0)), null, now + 60_000)
        assertEquals(WeatherKind.RAIN_SOON, h.kind)
        assertEquals(30, h.minutes)                       // slot 2 starts 29 min from "now"
        assertEquals("🌧 Rain in 30 min", RainAhead.text(h))
    }

    @Test
    fun drizzleBelowThresholdIsDry() {
        val h = RainAhead.decide(point(listOf(0.1, 0.1, 0.1)), null, now)
        assertEquals(WeatherKind.DRY, h.kind)
        assertEquals("☁ 31°", RainAhead.text(h))
    }

    @Test
    fun rainUpTheRoadWhenDryHere() {
        val h = RainAhead.decide(point(listOf(0.0)), point(listOf(0.0, 0.0, 0.8)), now)
        assertEquals(WeatherKind.RAIN_AHEAD, h.kind)
        assertEquals("🌧 Rain ahead", RainAhead.text(h))
    }

    @Test
    fun thunderstormGetsItsOwnIcon() {
        val h = RainAhead.decide(point(listOf(2.0), precipNow = 2.0, code = 95), null, now)
        assertTrue(RainAhead.text(h).startsWith("⛈"))
    }

    @Test
    fun parsesSingleAndMultiPointResponses() {
        val one = """{"current":{"time":1792480000,"temperature_2m":29.4,"precipitation":0.0,"weather_code":2},
            "minutely_15":{"time":[1792480000,1792480900],"precipitation":[0.0,0.6]}}"""
        val p = OpenMeteo.parse(one).single()
        assertEquals(29.4, p.tempC!!, 1e-9)
        assertEquals(2, p.code)
        assertEquals(listOf(1_792_480_000_000L to 0.0, 1_792_480_900_000L to 0.6), p.slots)
        assertEquals(2, OpenMeteo.parse("[$one,$one]").size)
    }

    @Test
    fun urlRoundsCoordinatesForPrivacy() {
        val url = OpenMeteo.url(listOf(6.927079 to 79.861244, 7.0 to 80.0))
        assertTrue(url.contains("latitude=6.93,7.00"))
        assertTrue(url.contains("longitude=79.86,80.00"))
        assertTrue(url.startsWith("https://api.open-meteo.com/"))
    }

    @Test
    fun aheadPointIsTenKmAlongTheBearing() {
        val (lat, lon) = RainAhead.aheadOf(6.9, 79.86, 0.0)        // due north
        assertEquals(6.9 + 10 / 111.19, lat, 0.001)
        assertEquals(79.86, lon, 1e-6)
    }
}
