// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TripStore
import java.util.concurrent.TimeUnit

/** Once a day: for the active bike, post one notification covering every item that just became overdue. */
class MaintenanceCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { check() }.onFailure { LogBus.log("[MAINT] daily check failed: $it") }
        return Result.success()
    }

    private fun check() {
        val ctx = applicationContext
        val bike = BikeMemory.selected(ctx) ?: return
        val items = MaintenanceSchedule.forModel(bike.model)
        if (items.isEmpty()) return
        val state = MaintenanceStore.load(ctx, bike.raw) ?: return
        val now = System.currentTimeMillis()
        val estimate = MaintenanceEstimate.estimateKm(state, TripStore.list(ctx), bike.raw)
        val due = MaintenanceEstimate.itemsToNotify(items, state, estimate, now)
        val notice = MaintenanceNotice.forItems(due) ?: return
        notify(ctx, notice)
        for (item in due) MaintenanceStore.setNotified(ctx, bike.raw, item.id, true)
    }

    private fun notify(ctx: Context, notice: MaintenanceNotice) {
        ensureChannel(ctx)
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_MAINTENANCE, true)
        }
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (notice.count == 1) {
            ctx.getString(R.string.maintenance_notif_text, notice.names.first())
        } else {
            ctx.resources.getQuantityString(R.plurals.maintenance_notif_text_many, notice.count, notice.count)
        }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.maintenance_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (notice.count > 1) {
            val inbox = Notification.InboxStyle().setBigContentTitle(text)
            notice.names.forEach { inbox.addLine(it) }
            val more = notice.count - notice.names.size
            if (more > 0) inbox.setSummaryText(ctx.getString(R.string.maintenance_notif_more, more))
            builder.setStyle(inbox)
        }
        // One fixed id: a later run replaces the earlier summary instead of stacking another.
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.maintenance_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "opencfmoto_maintenance"
        private const val WORK_NAME = "maintenance_check"
        private const val NOTIFICATION_ID = 0x4D41

        /** Idempotent: KEEP means re-calling this on every app start never resets the schedule. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceCheckWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
