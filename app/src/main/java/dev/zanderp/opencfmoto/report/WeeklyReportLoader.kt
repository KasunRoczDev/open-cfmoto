// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.report

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.EcoBands
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TripStore
import dev.zanderp.opencfmoto.fuel.FuelEstimate
import dev.zanderp.opencfmoto.fuel.FuelSpecs
import dev.zanderp.opencfmoto.fuel.FuelStore
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Reads rides + fuel for the selected bike and builds a [WeekReport] — blocking, call off the main thread. */
object WeeklyReportLoader {
    fun load(ctx: Context, weekStartMs: Long): WeekReport {
        val app = ctx.applicationContext
        val bike = BikeMemory.selected(app)
        val zone = TimeZone.getDefault()
        val trips = TripStore.list(app)
        val fills = bike?.let { FuelStore.list(app, it.raw) }.orEmpty()
        val fuel = FuelEstimate.status(fills, trips, bike?.raw, FuelSpecs.forModel(bike?.model), System.currentTimeMillis(), zone)
        return WeeklyReport.build(trips, bike?.raw, fills, fuel, EcoBands.forModel(bike?.model), weekStartMs, zone)
    }
}

/** Monday morning: "Last week: 82 km · 5 rides · eco 64%" — skipped when there was no riding. */
class WeeklyReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { post() }.onFailure { LogBus.log("[REPORT] weekly notification failed: $it") }
        return Result.success()
    }

    private fun post() {
        val ctx = applicationContext
        val zone = TimeZone.getDefault()
        val lastWeek = WeeklyReport.shiftWeeks(WeeklyReport.weekStart(System.currentTimeMillis(), zone), -1, zone)
        val r = WeeklyReportLoader.load(ctx, lastWeek)
        if (r.rides.isEmpty()) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.report_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_REPORT, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append(ctx.getString(R.string.report_notif_text, r.km.toInt(), r.rides.size))
            r.ecoPercent?.let { append(ctx.getString(R.string.report_notif_eco, it)) }
        }
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.report_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "opencfmoto_weekly_report"
        private const val WORK_NAME = "weekly_report"
        private const val NOTIFICATION_ID = 0x5752

        /** Weekly, first run next Monday ~09:00. KEEP: re-calling on every app start never resets it. */
        fun schedule(ctx: Context) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                timeInMillis = WeeklyReport.weekStart(now.timeInMillis, TimeZone.getDefault())
                add(Calendar.DAY_OF_MONTH, 7)
                set(Calendar.HOUR_OF_DAY, 9)
            }
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
