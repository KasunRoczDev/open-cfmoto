// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R

/** Heads-up alerts when a tyre goes out of range — one per wheel, re-alerting only on a new problem. */
object TpmsNotifier {
    private const val CHANNEL_ID = "tpms_alarm"
    const val EXTRA_OPEN_TPMS = "open_tpms"

    private fun id(w: Wheel) = if (w == Wheel.FRONT) 5101 else 5102

    fun onAlarms(ctx: Context, w: Wheel, alarms: Set<TpmsAlarm>, previous: Set<TpmsAlarm>, r: TpmsProtocol.Reading, s: TpmsSettings) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (alarms.isEmpty()) {
            nm.cancel(id(w))
            return
        }
        val fresh = (alarms - previous).isNotEmpty()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.tpms_notif_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val wheel = ctx.getString(if (w == Wheel.FRONT) R.string.tpms_front else R.string.tpms_rear)
        val text = alarms.joinToString(" · ") { label(ctx, it) } + " — " +
            s.unit.format(r.pressureKpa) + " " + unitLabel(ctx, s.unit) + ", " + s.formatTemp(r.tempC)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(ctx.getString(R.string.tpms_notif_title, wheel))
            .setContentText(text)
            .setContentIntent(openIntent(ctx))
            .setOnlyAlertOnce(!fresh)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        try {
            nm.notify(id(w), n)
        } catch (_: SecurityException) {
            // Notifications denied — the screen and Ride card still show the alarm.
        }
    }

    fun clear(ctx: Context, w: Wheel) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id(w))
    }

    fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 51,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TPMS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun label(ctx: Context, a: TpmsAlarm): String = ctx.getString(
        when (a) {
            TpmsAlarm.LOW -> R.string.tpms_alarm_low
            TpmsAlarm.HIGH -> R.string.tpms_alarm_high
            TpmsAlarm.HOT -> R.string.tpms_alarm_hot
            TpmsAlarm.BATTERY -> R.string.tpms_alarm_battery
        },
    )

    fun unitLabel(ctx: Context, u: PressureUnit): String = ctx.getString(
        when (u) {
            PressureUnit.PSI -> R.string.tpms_unit_psi
            PressureUnit.BAR -> R.string.tpms_unit_bar
            PressureUnit.KPA -> R.string.tpms_unit_kpa
        },
    )
}
