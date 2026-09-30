// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Saved rides grouped by calendar day. Prev/Next flips the day like a day calendar;
 * each trip card can open the map, export GPX, or long-press delete.
 */
class TripsListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var behaviourHost: LinearLayout
    private var monthKey: MonthKey? = null
    private lateinit var empty: TextView
    private lateinit var dayLabel: TextView
    private lateinit var daySummary: TextView
    private lateinit var todayBtn: MaterialButton

    /** Local midnight of the day currently shown. */
    private var dayStartMs: Long = 0L
    private var allTrips: List<Trip> = emptyList()
    private var didInitialDayPick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trips)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trips_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        container = findViewById(R.id.trips_container)
        behaviourHost = findViewById(R.id.trips_behaviour_host)
        empty = findViewById(R.id.trips_empty)
        dayLabel = findViewById(R.id.trips_day_label)
        daySummary = findViewById(R.id.trips_day_summary)
        todayBtn = findViewById(R.id.trips_today)

        findViewById<MaterialButton>(R.id.trips_day_prev).setOnClickListener {
            shiftDay(-1)
        }
        findViewById<MaterialButton>(R.id.trips_day_next).setOnClickListener {
            shiftDay(1)
        }
        todayBtn.setOnClickListener {
            dayStartMs = startOfDay(System.currentTimeMillis())
            renderDay()
        }

        dayStartMs = startOfDay(System.currentTimeMillis())
    }

    override fun onResume() {
        super.onResume()
        allTrips = TripStore.list(this)
        // First open: if today has no rides, land on the most recent ride day.
        if (!didInitialDayPick) {
            didInitialDayPick = true
            val today = startOfDay(System.currentTimeMillis())
            if (allTrips.none { startOfDay(it.start) == today }) {
                allTrips.firstOrNull()?.let { dayStartMs = startOfDay(it.start) }
            }
        }
        renderDay()
        renderBehaviour()
    }

    /** "Riding behaviour" month card above the day's rides (analysis off the main thread). */
    private fun renderBehaviour() {
        val zone = java.util.TimeZone.getDefault()
        val trips = allTrips
        val months = MonthlyBehaviour.months(trips, zone)
        if (months.isEmpty()) {
            behaviourHost.removeAllViews()
            return
        }
        val key = monthKey?.takeIf { it in months } ?: months.first()
        monthKey = key
        val imperial = MapPrefs.units(this) == MapUnits.IMPERIAL
        Thread {
            val s = MonthlyBehaviour.summarize(trips, key, zone)
            runOnUiThread {
                if (isFinishing || isDestroyed || monthKey != key) return@runOnUiThread
                behaviourHost.removeAllViews()
                if (s != null) behaviourHost.addView(behaviourCard(s, months, imperial))
            }
        }.start()
    }

    private fun behaviourCard(s: MonthSummary, months: List<MonthKey>, imperial: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(ContextCompat.getColor(context, R.color.surface))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.outline))
            }
        }
        val idx = months.indexOf(s.key)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.behaviour_title)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Plain tappable arrows (48 dp) — no style dependency on the old View screen.
        fun arrow(label: String, desc: Int, target: Int?) = TextView(this).apply {
            text = label
            contentDescription = getString(desc)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            alpha = if (target != null) 1f else 0.3f
            isEnabled = target != null
            setOnClickListener { target?.let { monthKey = months[it]; renderBehaviour() } }
        }
        header.addView(arrow("‹", R.string.behaviour_prev_month, (idx + 1).takeIf { it < months.size }))
        header.addView(TextView(this).apply {
            val cal = Calendar.getInstance().apply { clear(); set(s.key.year, s.key.month - 1, 1) }
            text = java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
        })
        header.addView(arrow("›", R.string.behaviour_next_month, (idx - 1).takeIf { it >= 0 }))
        card.addView(header)

        card.addView(RadarView(this).apply {
            set(s.radar, listOf(
                getString(R.string.behaviour_axis_distance), getString(R.string.behaviour_axis_time),
                getString(R.string.behaviour_axis_top), getString(R.string.behaviour_axis_braking),
                getString(R.string.behaviour_axis_accel),
            ), s.score?.toString() ?: getString(R.string.behaviour_none))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)))

        val rows = listOf(
            getString(R.string.behaviour_rides) to s.rides.toString(),
            getString(R.string.behaviour_distance) to String.format(Locale.US, "%.1f km", s.distanceKm),
            getString(R.string.behaviour_riding_time) to RideBehaviourUi.duration(s.ridingTimeMs),
            getString(R.string.behaviour_top_speed_month) to RideBehaviourUi.speed(s.topSpeedMs, imperial),
            getString(R.string.behaviour_avg_moving) to RideBehaviourUi.speed(s.avgMovingMs, imperial),
            getString(R.string.behaviour_hard_brakes) to s.hardBrakes.toString(),
            getString(R.string.behaviour_hard_accels) to s.hardAccels.toString(),
            getString(R.string.behaviour_stops) to s.stops.toString(),
        )
        for ((label, value) in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply {
                text = value
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(line)
        }
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
    }

    private fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
        cal.add(Calendar.DAY_OF_YEAR, delta)
        dayStartMs = startOfDay(cal.timeInMillis)
        renderDay()
    }

    private fun renderDay() {
        container.removeAllViews()
        val dayEnd = dayStartMs + 24L * 60L * 60L * 1000L
        val dayTrips = allTrips.filter { it.start in dayStartMs until dayEnd }
            .sortedByDescending { it.start }

        dayLabel.text = DAY_FMT.format(Date(dayStartMs))
        val today = startOfDay(System.currentTimeMillis())
        todayBtn.visibility = if (dayStartMs == today) View.GONE else View.VISIBLE

        if (dayTrips.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (allTrips.isEmpty()) {
                getString(R.string.trips_no_trips_yet)
            } else {
                getString(R.string.trips_no_rides_browse_days)
            }
            daySummary.text = getString(R.string.trips_no_rides_summary)
        } else {
            empty.visibility = View.GONE
            val km = dayTrips.sumOf { it.distanceKm }
            val n = dayTrips.size
            daySummary.text = resources.getQuantityString(
                R.plurals.trips_day_summary,
                n,
                n,
                km,
            )
            for (trip in dayTrips) container.addView(buildCard(trip))
        }
    }

    private fun buildCard(trip: Trip): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@TripsListActivity, R.color.surface))
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { TripMapActivity.start(this@TripsListActivity, trip.id) }
            setOnLongClickListener { confirmDelete(trip); true }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (trip.points.size >= 2) {
            val thumbH = dp(112)
            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbH,
                ).apply { bottomMargin = dp(10) }
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = getString(R.string.trips_route_preview)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                setBackgroundColor(ContextCompat.getColor(this@TripsListActivity, R.color.surface_high))
            }
            preview.post {
                val w = preview.width.coerceAtLeast(dp(240))
                preview.setImageBitmap(
                    TripRouteThumb.render(
                        trip = trip,
                        widthPx = w,
                        heightPx = thumbH,
                        strokeColor = ContextCompat.getColor(this, R.color.brand_orange),
                        bgColor = ContextCompat.getColor(this, R.color.surface_high),
                        startColor = ContextCompat.getColor(this, R.color.status_live),
                        endColor = ContextCompat.getColor(this, R.color.status_error),
                    ),
                )
            }
            col.addView(preview)
        }

        col.addView(TextView(this).apply {
            text = trip.timeRangeText()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        stats.addView(stat(getString(R.string.trip_distance), trip.distanceText()))
        stats.addView(stat(getString(R.string.trips_stat_time), trip.durationText()))
        stats.addView(stat(getString(R.string.trips_stat_avg), "${trip.avgKmh} km/h"))
        stats.addView(stat(getString(R.string.trips_stat_max), "${trip.maxKmh} km/h"))
        col.addView(stats)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            gravity = Gravity.END
        }
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.trips_export_gpx)
                textSize = 13f
                setOnClickListener { exportGpx(trip) }
            },
        )
        col.addView(actions)

        card.addView(col)
        return card
    }

    private fun exportGpx(trip: Trip) {
        try {
            if (trip.points.isEmpty()) {
                Toast.makeText(this, getString(R.string.trips_no_gps_points), Toast.LENGTH_SHORT).show()
                return
            }
            val dir = File(cacheDir, "trip-export").apply { mkdirs() }
            val file = File(dir, TripGpx.suggestedFileName(trip))
            file.writeText(TripGpx.toXml(trip), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.trips_export_gpx)))
            LogBus.log("→ Trip GPX exported: ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.trips_export_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun stat(label: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_secondary))
            textSize = 10f
            gravity = Gravity.START
        })
        box.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return box
    }

    private fun confirmDelete(trip: Trip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trips_delete_trip_title)
            .setMessage("${trip.dateText()} · ${trip.distanceText()}")
            .setPositiveButton(R.string.trips_delete) { _, _ ->
                TripStore.delete(this, trip.id)
                allTrips = TripStore.list(this)
                renderDay()
            }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val DAY_FMT = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripsListActivity::class.java))
        }

        fun startOfDay(epochMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
