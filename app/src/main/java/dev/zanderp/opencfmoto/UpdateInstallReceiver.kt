// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * Result of an [ApkUpdater.install] session. Android may still want the rider to confirm (first
 * self-update, or older Android): in the app we open that screen right away; from the background we
 * post a notification that opens it. On success the process is replaced, so there's nothing to do.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                LogBus.log("[UPDATE] Android asks the rider to confirm")
                if (interactive) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (_: Exception) {}
                }
                UpdateNotices.readyToInstall(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> LogBus.log("[UPDATE] installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                LogBus.log("[UPDATE] install failed: $msg")
                if (interactive && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_INTERACTIVE = "interactive"
    }
}

/** The two update notifications: "ready, tap to install" and "available, tap to update". */
object UpdateNotices {
    private const val CHANNEL_ID = "mymoto_updates"
    private const val NOTIFICATION_ID = 0x5550

    /** Android needs a tap: the notification opens its install confirmation. */
    fun readyToInstall(ctx: Context, confirm: Intent) {
        val pending = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(ctx, ctx.getString(R.string.update_notif_ready_title), ctx.getString(R.string.update_notif_ready_text), pending)
    }

    /** We can't install yet (no "Install unknown apps" access): open the app's update check. */
    fun available(ctx: Context, version: String) {
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
        }
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, ctx.getString(R.string.update_notif_available_title, version), ctx.getString(R.string.update_notif_available_text), pending)
    }

    private fun post(ctx: Context, title: String, text: String, tap: PendingIntent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.update_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Notifications denied: the next manual "Check for update" still finds it.
        }
    }
}
