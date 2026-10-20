// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.weather

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One place's weather: now, plus rain per 15-minute slot ([slots] = epoch ms to mm). */
data class WeatherPoint(
    val tempC: Double?,
    val precipMm: Double,
    val code: Int?,
    val slots: List<Pair<Long, Double>>,
)

enum class WeatherKind { RAINING, RAIN_SOON, RAIN_AHEAD, DRY }

/** What the dash says. [minutes] only for [WeatherKind.RAIN_SOON]; [stormy] = thunderstorm codes. */
data class WeatherHint(val kind: WeatherKind, val minutes: Int?, val tempC: Int?, val stormy: Boolean = false, val drizzle: Boolean = false)

/** Open-Meteo request/response (free, no key). Pure — unit-tested. */
object OpenMeteo {
    /**
     * Current conditions + 15-minute precipitation for the next 2 h at each point. Coordinates are
     * rounded to 2 decimals (~1 km) — enough for rain, and it doesn't send the rider's exact spot.
     */
    fun url(points: List<Pair<Double, Double>>): String {
        fun r(v: Double) = String.format(Locale.US, "%.2f", v)
        return "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + points.joinToString(",") { r(it.first) } +
            "&longitude=" + points.joinToString(",") { r(it.second) } +
            "&current=temperature_2m,precipitation,weather_code" +
            "&minutely_15=precipitation&forecast_minutely_15=8&timeformat=unixtime&timezone=GMT"
    }

    /** One [WeatherPoint] per requested point (Open-Meteo returns an object for one point, an array for more). */
    fun parse(json: String): List<WeatherPoint> {
        val t = json.trim()
        val objs = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(t))
        return objs.map { o ->
            val cur = o.optJSONObject("current")
            val m = o.optJSONObject("minutely_15")
            val times = m?.optJSONArray("time")
            val rain = m?.optJSONArray("precipitation")
            val slots = if (times != null && rain != null) {
                (0 until minOf(times.length(), rain.length())).map { times.getLong(it) * 1000 to rain.optDouble(it, 0.0) }
            } else emptyList()
            WeatherPoint(
                tempC = cur?.optDouble("temperature_2m")?.takeIf { !it.isNaN() },
                precipMm = cur?.optDouble("precipitation", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                code = cur?.takeIf { it.has("weather_code") }?.optInt("weather_code"),
                slots = slots,
            )
        }
    }
}

/** Decides the dash's weather line from the rider's spot and a point up the road. Pure — unit-tested. */
object RainAhead {
    /** ≥ this per 15 min counts as rain (~0.8 mm/h — enough to wet the road). */
    const val RAIN_MM_15 = 0.2
    const val LOOK_AHEAD_KM = 10.0
    private const val SOON_MS = 120 * 60_000L
    private const val AHEAD_MS = 60 * 60_000L
    private val RAIN_CODES = (51..67) + (80..82) + (95..99)
    private val STORM_CODES = 95..99
    private val DRIZZLE_CODES = 51..57

    fun decide(here: WeatherPoint, ahead: WeatherPoint?, nowMs: Long): WeatherHint {
        val temp = here.tempC?.roundToInt()
        val stormy = here.code in STORM_CODES || ahead?.code in STORM_CODES
        if (here.precipMm >= 0.1 || here.code in RAIN_CODES) {
            // Drizzle codes with little water: say so, rather than crying "Raining".
            val drizzle = here.code in DRIZZLE_CODES && here.precipMm < RAIN_MM_15
            return WeatherHint(WeatherKind.RAINING, null, temp, stormy, drizzle)
        }
        firstRain(here, nowMs, SOON_MS)?.let { at ->
            val min = (((at - nowMs) / 60_000.0) / 5).roundToInt() * 5
            return WeatherHint(WeatherKind.RAIN_SOON, min.coerceAtLeast(5), temp, stormy)
        }
        if (ahead != null && (ahead.precipMm >= 0.1 || ahead.code in RAIN_CODES || firstRain(ahead, nowMs, AHEAD_MS) != null)) {
            return WeatherHint(WeatherKind.RAIN_AHEAD, null, temp, stormy)
        }
        return WeatherHint(WeatherKind.DRY, null, temp)
    }

    /** Start of the first rainy 15-minute slot within [withinMs] (a slot already under way counts). */
    private fun firstRain(p: WeatherPoint, nowMs: Long, withinMs: Long): Long? =
        p.slots.firstOrNull { (t, mm) -> t + 15 * 60_000L > nowMs && t <= nowMs + withinMs && mm >= RAIN_MM_15 }
            ?.first?.coerceAtLeast(nowMs)

    /** The point [km] up the road along [bearingDeg] (great-circle). */
    fun aheadOf(lat: Double, lon: Double, bearingDeg: Double, km: Double = LOOK_AHEAD_KM): Pair<Double, Double> {
        val r = 6371.0
        val d = km / r
        val b = Math.toRadians(bearingDeg)
        val la1 = Math.toRadians(lat)
        val lo1 = Math.toRadians(lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lo1 + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return Math.toDegrees(la2) to Math.toDegrees(lo2)
    }

    /** The panel line: "🌧 Raining · 27°", "🌦 Drizzle · 29°", "🌧 Rain in 35 min", "🌧 Rain ahead", "☁ 31°". */
    fun text(h: WeatherHint): String {
        val icon = if (h.stormy) "⛈" else "🌧"
        val temp = h.tempC?.let { " · $it°" } ?: ""
        return when (h.kind) {
            WeatherKind.RAINING -> if (h.drizzle) "🌦 Drizzle$temp" else "$icon Raining$temp"
            WeatherKind.RAIN_SOON -> "$icon Rain in ${h.minutes} min"
            WeatherKind.RAIN_AHEAD -> "$icon Rain ahead"
            WeatherKind.DRY -> h.tempC?.let { "☁ $it°" } ?: ""
        }
    }
}
