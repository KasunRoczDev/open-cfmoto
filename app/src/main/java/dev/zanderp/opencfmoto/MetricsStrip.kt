// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Run a strip step so that no failure can escape into the bike/AA path (spec: strip failures never
 * break video). Logs `[STRIP] <what> failed: …` and returns false on any exception.
 */
internal inline fun stripGuarded(log: (String) -> Unit, what: String, block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: Exception) {
        log("[STRIP] $what failed: $e — band stays black, Android Auto unaffected")
        false
    }

/**
 * Metrics strip coordinator for one Android Auto session: GPS ([MetricsSource]) and AA turns
 * ([setTurn]) update [RiderMetrics]; a single `metrics-strip` thread formats them and renders a
 * bitmap only when a displayed value changes (plus a 1 s tick so stale values fall back to `--`).
 * [start] is idempotent — it always stops a previous session first.
 */
object MetricsStrip {
    private const val TICK_MS = 1_000L

    private var executor: ScheduledExecutorService? = null
    private var source: MetricsSource? = null
    @Volatile private var sink: ((Bitmap) -> Unit)? = null
    @Volatile private var log: (String) -> Unit = {}
    @Volatile private var units = MapUnits.METRIC
    @Volatile private var stripW = 0
    @Volatile private var stripH = 0
    @Volatile private var cells: Set<StripCell> = StripCells.ALL
    @Volatile private var metrics = RiderMetrics()
    private var lastValues: StripValues? = null   // metrics-strip thread only
    private var failLogged = false                // metrics-strip thread only
    @Volatile private var mode = StripMode.METRICS
    @Volatile private var onVisible: (Boolean) -> Unit = {}
    @Volatile private var directions: Directions = Directions.NONE
    @Volatile private var navAtElapsedMs = 0L
    @Volatile private var navAtWallMs = 0L
    @Volatile private var is24h = true
    private var lastView: DirectionsView? = null     // metrics-strip thread only
    private var lastVisible: Boolean? = null         // metrics-strip thread only
    @Volatile private var appCtx: Context? = null
    @Volatile private var forest = EcoForest()
    private var eco = EcoScorer()                    // main-looper (GPS) thread only
    @Volatile private var ecoPercent: Int? = null
    @Volatile private var ecoBand = EcoBands.DEFAULT
    @Volatile private var ecoHint: EcoHint? = null
    /** Range when this session started (loaded off-thread); the live value subtracts [sessionKm]. */
    @Volatile private var fuelRangeAtStartKm: Double? = null
    @Volatile private var sessionKm = 0.0
    // Weather line (SIDE mode): fetched off-thread, at most every WEATHER_EVERY_MS or WEATHER_MOVE_M.
    @Volatile private var weatherOn = false
    @Volatile private var weather: dev.zanderp.opencfmoto.weather.WeatherHint? = null
    @Volatile private var weatherAtMs = 0L
    @Volatile private var weatherFetching = false
    private var lastWeatherTryMs = 0L                 // main-looper (GPS) thread only
    private var weatherLat = Double.NaN               // main-looper (GPS) thread only
    private var weatherLon = Double.NaN
    private const val WEATHER_EVERY_MS = 10 * 60_000L
    private const val WEATHER_RETRY_MS = 2 * 60_000L
    private const val WEATHER_MOVE_M = 5_000f
    private const val WEATHER_STALE_MS = 30 * 60_000L
    @Volatile private var noGps = false
    private var lastEcoSaveMs = 0L                   // main-looper (GPS) thread only
    private var lastSide: SidePanelView? = null      // metrics-strip thread only

    @Synchronized
    fun start(
        ctx: Context,
        canvasW: Int,
        stripPx: Int,
        sink: (Bitmap) -> Unit,
        log: (String) -> Unit,
        cells: Set<StripCell> = StripCells.ALL,
        mode: StripMode = StripMode.METRICS,
        onVisible: (Boolean) -> Unit = {},
    ) {
        stop()
        val app = ctx.applicationContext
        this.sink = sink
        this.log = log
        units = MapPrefs.units(app)
        stripW = canvasW
        stripH = stripPx
        this.cells = cells
        this.mode = mode
        this.onVisible = onVisible
        appCtx = app
        if (mode == StripMode.SIDE) {
            forest = EcoStore.load(app)
            ecoBand = EcoBands.forModel(BikeMemory.selected(app)?.model)
            eco = EcoScorer(ecoBand)
            ecoPercent = null
            ecoHint = null
            fuelRangeAtStartKm = null
            sessionKm = 0.0
            weatherOn = AppSettings.weatherOnDash(app)
            weather = null
            weatherAtMs = 0L
            lastWeatherTryMs = 0L
            weatherLat = Double.NaN
            weatherLon = Double.NaN
            lastEcoSaveMs = SystemClock.elapsedRealtime()
        }
        lastSide = null
        is24h = android.text.format.DateFormat.is24HourFormat(app)
        directions = Directions.NONE
        lastView = null
        lastVisible = null
        lastValues = null
        failLogged = false
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "metrics-strip").apply { isDaemon = true }
        }
        executor = ex
        if (mode == StripMode.SIDE) {
            // Reads every saved ride from disk — keep it off the caller's thread.
            ex.execute {
                fuelRangeAtStartKm = try {
                    dev.zanderp.opencfmoto.fuel.FuelStore.statusForSelected(app, System.currentTimeMillis()).rangeKm
                } catch (e: Exception) {
                    log("[STRIP] fuel range unavailable: $e")
                    null
                }
                fuelRangeAtStartKm?.let { log("[FUEL] range at start ~${it.toInt()} km") }
            }
        }
        ex.scheduleWithFixedDelay({ refresh() }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
        if (mode == StripMode.METRICS || mode == StripMode.SIDE) {
            val src = MetricsSource(app) { loc -> onFix(loc) }
            source = src
            noGps = !src.start()
            if (noGps) log("[STRIP] no GPS (permission or GPS off) — showing --")
        }
        log("[STRIP] started ${canvasW}x$stripPx units=$units mode=$mode" +
            if (mode == StripMode.METRICS) " cells=${StripCells.serialize(cells)}" else " overlay")
    }

    @Synchronized
    fun stop() {
        if (mode == StripMode.SIDE) appCtx?.let { EcoStore.save(it, forest) }
        source?.stop()
        source = null
        executor?.shutdownNow()
        executor = null
        sink = null
        metrics = RiderMetrics()
        directions = Directions.NONE
        onVisible = {}
    }

    /** Phase 2: next turn from Android Auto (null = no active route). Any thread. */
    fun setTurn(turn: NextTurn?) = update { it.copy(turn = turn) }

    /** Directions from Android Auto; called for every nav message (keeps the route fresh). Any thread. */
    fun setDirections(d: Directions) {
        directions = d
        navAtElapsedMs = SystemClock.elapsedRealtime()
        navAtWallMs = System.currentTimeMillis()
        val ex = synchronized(this) { executor }
        try { ex?.execute { refresh() } } catch (_: RejectedExecutionException) {}
    }

    /** AA session ended: no route any more (both modes). */
    fun clearNavigation() {
        setTurn(null)
        setDirections(Directions.NONE)
    }

    private fun onFix(loc: Location) {
        val alt = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        update {
            it.withFix(
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                altitudeM = alt,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                atMs = SystemClock.elapsedRealtime(),
            )
        }
        if (mode == StripMode.SIDE) {
            val now = SystemClock.elapsedRealtime()
            // No speed in this fix → skip it (NaN), never score it as a stop.
            val step = eco.onFix(if (loc.hasSpeed()) loc.speed else Float.NaN, now)
            ecoPercent = eco.ecoPercent
            ecoHint = ecoBand.hint(if (loc.hasSpeed()) loc.speed else null, ecoHint)
            sessionKm = eco.movingMeters / 1000.0
            maybeFetchWeather(loc)
            val month = EcoForest.monthKey(System.currentTimeMillis(), java.util.TimeZone.getDefault())
            val (next, done) = forest.add(step.ecoMeters, step.movingMeters, month)
            forest = next
            if (done > 0) log("[ECO] tree complete (#${next.trees})")
            if (now - lastEcoSaveMs >= 30_000L) {
                lastEcoSaveMs = now
                appCtx?.let { EcoStore.save(it, next) }
                log("[ECO] saved eco=${String.format(java.util.Locale.US, "%.1f", next.currentMeters / 1000)} km")
            }
        }
    }

    private fun update(f: (RiderMetrics) -> RiderMetrics) {
        val ex: ScheduledExecutorService?
        synchronized(this) {
            metrics = f(metrics)
            ex = executor
        }
        try { ex?.execute { refresh() } } catch (_: RejectedExecutionException) {}
    }

    private fun refresh() {
        try {
            val out = sink ?: return
            if (mode == StripMode.SIDE) { refreshSide(out); return }
            if (mode == StripMode.DIRECTIONS) {
                refreshDirections(out)
                return
            }
            val v = MetricsFormat.values(metrics, units, SystemClock.elapsedRealtime())
            if (v == lastValues) return
            val bmp = MetricsStripRenderer.render(v, stripW, stripH, cells)
            lastValues = v
            out(bmp)
        } catch (e: Exception) {
            if (!failLogged) {
                failLogged = true
                log("[STRIP] render failed: $e — band stays black, Android Auto unaffected")
            }
        }
    }

    private fun refreshDirections(out: (Bitmap) -> Unit) {
        val d = directions
        val now = SystemClock.elapsedRealtime()
        val active = DirectionsFormat.active(d, navAtElapsedMs, now)
        if (!active) {
            if (lastVisible != false) {
                log("[STRIP] band hidden (" + (if (d.turn != null) "route stale" else "no route") + ")")
                lastVisible = false
                lastView = null
                onVisible(false)
            }
            return
        }
        val v = DirectionsFormat.view(d, units, stripW, stripH, navAtWallMs, java.util.TimeZone.getDefault(), is24h) ?: return
        if (v != lastView) {
            lastView = v
            out(DirectionsRenderer.render(v, stripW, stripH))
        }
        // Show only after this route's first bitmap is queued (same handler → uploaded before the reveal),
        // so the previous route's band never flashes up.
        if (lastVisible != true) {
            log("[STRIP] band shown")
            lastVisible = true
            onVisible(true)
        }
    }

    /** Rain now / soon / up the road, from Open-Meteo. Main-looper (GPS) thread; the request runs on its own thread. */
    private fun maybeFetchWeather(loc: Location) {
        if (!weatherOn || weatherFetching) return
        val now = SystemClock.elapsedRealtime()
        val moved = if (weatherLat.isNaN()) Float.MAX_VALUE
            else FloatArray(1).also { Location.distanceBetween(weatherLat, weatherLon, loc.latitude, loc.longitude, it) }[0]
        if (lastWeatherTryMs != 0L && now - lastWeatherTryMs < WEATHER_EVERY_MS && moved < WEATHER_MOVE_M) return
        lastWeatherTryMs = now
        weatherLat = loc.latitude
        weatherLon = loc.longitude
        val here = loc.latitude to loc.longitude
        // Only look up the road when actually riding somewhere — a parked bike's bearing is noise.
        val ahead = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) {
            dev.zanderp.opencfmoto.weather.RainAhead.aheadOf(loc.latitude, loc.longitude, loc.bearing.toDouble())
        } else null
        weatherFetching = true
        kotlin.concurrent.thread(name = "strip-weather", isDaemon = true) {
            try {
                val url = dev.zanderp.opencfmoto.weather.OpenMeteo.url(listOfNotNull(here, ahead))
                val pts = dev.zanderp.opencfmoto.weather.OpenMeteo.parse(AppHttp.getText(url))
                val hint = dev.zanderp.opencfmoto.weather.RainAhead.decide(pts.first(), pts.getOrNull(1), System.currentTimeMillis())
                weather = hint
                weatherAtMs = SystemClock.elapsedRealtime()
                log("[WEATHER] " + dev.zanderp.opencfmoto.weather.RainAhead.text(hint).ifEmpty { "no data" })
                val ex = synchronized(this) { executor }
                try { ex?.execute { refresh() } } catch (_: RejectedExecutionException) {}
            } catch (e: Exception) {
                log("[WEATHER] fetch failed: $e")
                lastWeatherTryMs = SystemClock.elapsedRealtime() - WEATHER_EVERY_MS + WEATHER_RETRY_MS   // retry sooner
            } finally {
                weatherFetching = false
            }
        }
    }

    /** "⛽ ~185 km" / "⛽ ~115 mi" — whole units, so the panel redraws only when the number changes. */
    internal fun fuelText(rangeKm: Double, units: MapUnits): String =
        if (units == MapUnits.IMPERIAL) "⛽ ~${(rangeKm / 1.609344).toInt()} mi" else "⛽ ~${rangeKm.toInt()} km"

    internal fun fuelLevel(rangeKm: Double): FuelLevel = when {
        rangeKm < dev.zanderp.opencfmoto.fuel.FuelEstimate.CRITICAL_RANGE_KM -> FuelLevel.CRITICAL
        rangeKm < dev.zanderp.opencfmoto.fuel.FuelEstimate.LOW_RANGE_KM -> FuelLevel.LOW
        else -> FuelLevel.OK
    }

    /** The last weather, unless it's too old to trust. */
    private fun freshWeather(): dev.zanderp.opencfmoto.weather.WeatherHint? =
        weather?.takeIf { SystemClock.elapsedRealtime() - weatherAtMs < WEATHER_STALE_MS }

    private fun refreshSide(out: (Bitmap) -> Unit) {
        val d = directions
        val active = DirectionsFormat.active(d, navAtElapsedMs, SystemClock.elapsedRealtime())
        // Large nominal width: the side panel stacks the parts vertically, so nothing is dropped for width.
        val dir = if (active) DirectionsFormat.view(d, units, 100_000, 40, navAtWallMs, java.util.TimeZone.getDefault(), is24h) else null
        val f = forest
        val v = SidePanelView(
            directions = dir,
            stage = f.stage,
            progress = (f.progress * 100).toInt() / 100f,
            ecoKm = (f.currentMeters / 100).toInt() / 10.0,
            ecoPercent = ecoPercent,
            treeNumber = f.trees + 1,
            noGps = noGps,
            ecoBand = ecoBand.speedLabel(units),
            ecoMax = ecoBand.maxLabel(units),
            ecoRpm = ecoBand.rpmLabel(),
            ecoHint = ecoHint,
            fuelText = fuelRangeAtStartKm?.let { start -> fuelText((start - sessionKm).coerceAtLeast(0.0), units) },
            fuelLevel = fuelRangeAtStartKm?.let { start -> fuelLevel((start - sessionKm).coerceAtLeast(0.0)) } ?: FuelLevel.OK,
            weatherText = freshWeather()?.let { dev.zanderp.opencfmoto.weather.RainAhead.text(it) }?.ifEmpty { null },
            weatherRain = freshWeather()?.kind?.let { it != dev.zanderp.opencfmoto.weather.WeatherKind.DRY } ?: false,
        )
        if (v == lastSide) return
        lastSide = v
        out(SidePanelRenderer.render(v, stripW, stripH))
    }
}
