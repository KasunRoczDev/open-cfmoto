// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Shows one saved ride's route on an OpenStreetMap (osmdroid — no API key). Draws the GPS track as a
 * polyline with start/end markers and frames it. Tiles are fetched over the internet and cached by
 * osmdroid; a route with no points just centres on a default location.
 */
class TripMapActivity : AppCompatActivity() {

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid needs a non-default user agent or OSM tile servers reject the requests.
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setContentView(R.layout.activity_trip_map)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trip_map_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)

        val id = intent.getStringExtra(EXTRA_ID)
        val trip = id?.let { TripStore.get(this, it) }
        if (trip == null) {
            Toast.makeText(this, "Trip not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.map_title).text = trip.dateText()
        findViewById<TextView>(R.id.map_stats).text =
            "${trip.distanceText()} · ${trip.durationText()} · avg ${trip.avgKmh} km/h"

        val imperial = MapPrefs.units(this) == MapUnits.IMPERIAL
        // A ride has at most a few thousand points; analyse off the main thread anyway.
        Thread {
            val b = RideAnalysis.analyze(trip, imperial)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderRoute(trip, b, imperial)
                renderStats(trip, b, imperial)
            }
        }.start()
    }

    private fun renderRoute(trip: Trip, b: RideBehaviour, imperial: Boolean) {
        val pts = trip.points
        val geo = pts.map { GeoPoint(it.lat, it.lon) }
        if (geo.isEmpty()) {
            map.controller.setZoom(4.0)
            return
        }

        // Route coloured by speed: one polyline per run of segments in the same band.
        var runStart = 0
        for (i in 1..b.bands.size) {
            if (i == b.bands.size || b.bands[i] != b.bands[runStart]) {
                val band = b.bands[runStart]
                map.overlays.add(Polyline(map).apply {
                    setPoints(geo.subList(runStart, i + 1))
                    outlinePaint.color = RideBehaviourUi.bandColor(band)
                    outlinePaint.strokeWidth = 10f
                })
                runStart = i
            }
        }

        addMarker(geo.first(), getString(R.string.map_start_label), Color.GREEN)
        if (geo.size > 1) addMarker(geo.last(), getString(R.string.map_end_label), Color.RED)

        fun at(i: Int) = RideBehaviourUi.clock(pts[i].time)
        b.slowestIndex?.let { i ->
            behaviourMarker(geo[i], "▼", RideBehaviourUi.SLOW,
                "${getString(R.string.behaviour_slowest)} · ${RideBehaviourUi.speed(pts[i].speedMs, imperial)} · ${at(i)}")
        }
        for (s in b.stops) {
            behaviourMarker(geo[s.index], "P", RideBehaviourUi.STOP,
                "${getString(R.string.behaviour_stop)} · ${RideBehaviourUi.duration(s.durationMs)} · ${at(s.index)}")
        }
        for (e in b.hardBrakes) {
            behaviourMarker(geo[e.index], "!", RideBehaviourUi.BRAKE,
                "${getString(R.string.behaviour_hard_brake)} · ${RideBehaviourUi.speedValue(e.fromMs, imperial)} → " +
                    "${RideBehaviourUi.speed(e.toMs, imperial)} · ${at(e.index)}")
        }
        for (e in b.hardAccels) {
            behaviourMarker(geo[e.index], "»", RideBehaviourUi.ACCEL,
                "${getString(R.string.behaviour_hard_accel)} · ${RideBehaviourUi.speedValue(e.fromMs, imperial)} → " +
                    "${RideBehaviourUi.speed(e.toMs, imperial)} · ${at(e.index)}")
        }
        // Top speed last: osmdroid draws later overlays on top and gives them the tap (a brake often
        // starts right at the top-speed point).
        b.maxIndex?.let { i ->
            behaviourMarker(geo[i], "▲", RideBehaviourUi.TOP,
                "${getString(R.string.behaviour_top_speed)} · ${RideBehaviourUi.speed(pts[i].speedMs, imperial)} · ${at(i)}")
        }
        map.invalidate()

        // Frame the whole route once the map has been laid out.
        val bbox = BoundingBox.fromGeoPoints(geo)
        map.post {
            try {
                map.zoomToBoundingBox(bbox.increaseByScale(1.4f), false, 48)
            } catch (_: Exception) {
                map.controller.setZoom(15.0)
                map.controller.setCenter(geo.first())
            }
        }
    }

    private fun behaviourMarker(point: GeoPoint, symbol: String, color: Int, label: String) {
        map.overlays.add(Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = RideBehaviourUi.markerIcon(this@TripMapActivity, symbol, color)
            title = label
        })
    }

    /** Legend + 3×2 stat cells + distance / time / score rows under the map. */
    private fun renderStats(trip: Trip, b: RideBehaviour, imperial: Boolean) {
        val box = findViewById<LinearLayout>(R.id.map_behaviour)
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = getString(R.string.behaviour_legend)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
        })
        val none = getString(R.string.behaviour_none)
        val cells = listOf(
            RideBehaviourUi.speedValue(b.maxSpeedMs, imperial) to getString(R.string.behaviour_max),
            RideBehaviourUi.speedValue(b.avgMs, imperial) to getString(R.string.behaviour_avg),
            RideBehaviourUi.speedValue(b.movingAvgMs, imperial) to getString(R.string.behaviour_moving_avg),
            (b.slowestMovingMs?.let { RideBehaviourUi.speedValue(it, imperial) } ?: none) to getString(R.string.behaviour_slowest_short),
            b.stops.size.toString() to getString(R.string.behaviour_stops_short, RideBehaviourUi.duration(b.stopTimeMs)),
            "${b.hardBrakes.size} / ${b.hardAccels.size}" to getString(R.string.behaviour_brakes_accels),
        )
        for (row in cells.chunked(3)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((value, label) in row) line.addView(statCell(value, label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(line)
        }
        val totalMs = trip.end - trip.start
        box.addView(statRow(getString(R.string.behaviour_distance), trip.distanceText()))
        box.addView(statRow(getString(R.string.behaviour_riding_time),
            getString(R.string.behaviour_riding_time_value, RideBehaviourUi.duration(trip.movingTimeMs), RideBehaviourUi.duration(totalMs))))
        box.addView(statRow(getString(R.string.behaviour_score),
            b.score?.let { getString(R.string.behaviour_score_value, it) } ?: none))
    }

    private fun statCell(value: String, label: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(TextView(context).apply {
            text = value
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
        })
    }

    private fun statRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = value
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addMarker(point: GeoPoint, label: String, tint: Int) {
        val marker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label
            icon?.setTint(tint)
        }
        map.overlays.add(marker)
    }

    override fun onResume() {
        super.onResume()
        if (this::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (this::map.isInitialized) map.onPause()
    }

    companion object {
        private const val EXTRA_ID = "trip_id"

        fun start(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
