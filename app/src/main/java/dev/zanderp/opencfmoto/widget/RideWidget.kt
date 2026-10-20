// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.TripStore
import dev.zanderp.opencfmoto.fuel.FuelEstimate
import dev.zanderp.opencfmoto.fuel.FuelSpecs
import dev.zanderp.opencfmoto.fuel.FuelStore
import dev.zanderp.opencfmoto.maintenance.MaintenanceEstimate
import dev.zanderp.opencfmoto.maintenance.MaintenanceSchedule
import dev.zanderp.opencfmoto.maintenance.MaintenanceStore
import dev.zanderp.opencfmoto.ui.ride.RideDay
import dev.zanderp.opencfmoto.ui.ride.RideSummary
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Home-screen widget: fuel range · last ride · next service at a glance. Refreshed hourly by the
 * system and right away via [requestUpdate] when a ride, fill-up or service is saved. Reading the
 * rides takes a moment, so updates run on a background thread under [goAsync].
 */
class RideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread(name = "ride-widget") {
            try {
                val views = render(context.applicationContext)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (e: Exception) {
                LogBus.log("[WIDGET] update failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Ask placed widgets to redraw (no-op when none are on the home screen). Any thread. */
        fun requestUpdate(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, RideWidget::class.java))
                if (ids.isEmpty()) return
                app.sendBroadcast(
                    Intent(app, RideWidget::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            } catch (_: Exception) {
            }
        }

        private fun render(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_ride)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            val bike = BikeMemory.selected(ctx)
            v.setTextViewText(R.id.widget_bike, bike?.let { SavedBike.normalizeModel(it.model) ?: it.name } ?: ctx.getString(R.string.app_name))
            val now = System.currentTimeMillis()
            val trips = TripStore.list(ctx)

            val fills = bike?.let { FuelStore.list(ctx, it.raw) }.orEmpty()
            val range = FuelEstimate.status(fills, trips, bike?.raw, FuelSpecs.forModel(bike?.model), now, TimeZone.getDefault()).rangeKm
            v.setTextViewText(R.id.widget_range, range?.let { ctx.getString(R.string.fuel_range_value, it.toInt()) } ?: "—")

            val last = RideSummary.of(trips, now, ZoneId.systemDefault())?.last
            v.setTextViewText(
                R.id.widget_last_ride,
                last?.let {
                    val day = when (it.day) {
                        RideDay.TODAY -> ctx.getString(R.string.ui_day_today)
                        RideDay.YESTERDAY -> ctx.getString(R.string.ui_day_yesterday)
                        RideDay.EARLIER -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(it.endMs))
                    }
                    "$day\n" + ctx.getString(R.string.ui_km, String.format(Locale.getDefault(), "%.1f", it.km))
                } ?: "—",
            )

            v.setTextViewText(R.id.widget_service, serviceText(ctx, bike, trips, now))
            return v
        }

        private fun serviceText(ctx: Context, bike: SavedBike?, trips: List<dev.zanderp.opencfmoto.Trip>, now: Long): String {
            bike ?: return "—"
            val items = MaintenanceSchedule.forModel(bike.model)
            val state = MaintenanceStore.load(ctx, bike.raw) ?: return ctx.getString(R.string.widget_service_setup)
            val estimate = MaintenanceEstimate.estimateKm(state, trips, bike.raw)
            val next = NextService.of(items, state, estimate, now) ?: return "—"
            return when {
                next.overdue > 0 -> ctx.resources.getQuantityString(R.plurals.widget_service_overdue, next.overdue, next.overdue)
                next.inKm != null -> ctx.getString(R.string.widget_service_km, next.name, next.inKm)
                next.inDays != null -> ctx.getString(R.string.widget_service_days, next.name, next.inDays)
                else -> next.name ?: "—"
            }
        }
    }
}
