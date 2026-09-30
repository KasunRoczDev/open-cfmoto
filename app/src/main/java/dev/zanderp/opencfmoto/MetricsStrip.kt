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
        ex.scheduleWithFixedDelay({ refresh() }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
        if (mode == StripMode.METRICS) {
            val src = MetricsSource(app) { loc -> onFix(loc) }
            source = src
            if (!src.start()) log("[STRIP] no GPS (permission or GPS off) — showing --")
        }
        log("[STRIP] started ${canvasW}x$stripPx units=$units mode=$mode" +
            if (mode == StripMode.METRICS) " cells=${StripCells.serialize(cells)}" else " overlay")
    }

    @Synchronized
    fun stop() {
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
        if (active != lastVisible) {
            if (active) log("[STRIP] band shown")
            else log("[STRIP] band hidden (" + (if (d.turn != null) "route stale" else "no route") + ")")
            lastVisible = active
            if (!active) lastView = null
            onVisible(active)
        }
        if (!active) return
        val v = DirectionsFormat.view(d, units, stripW, stripH, navAtWallMs, java.util.TimeZone.getDefault(), is24h) ?: return
        if (v == lastView) return
        lastView = v
        out(DirectionsRenderer.render(v, stripW, stripH))
    }
}
