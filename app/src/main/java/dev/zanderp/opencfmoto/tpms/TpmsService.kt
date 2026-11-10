// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R

/**
 * "Watch in the background": keeps a low-power, sensor-filtered BLE scan alive with the app closed
 * so low-pressure alerts still fire while riding with the phone pocketed. Its ongoing notification
 * shows both tyres. Runs only while the rider has it switched on and at least one wheel is paired.
 */
class TpmsService : Service() {
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val s = TpmsMonitor.state.value.settings
            TpmsMonitor.updateSettings(this, s.copy(background = false))
            stop()
            return START_NOT_STICKY
        }
        TpmsMonitor.init(this)
        val s = TpmsMonitor.state.value
        if (!s.settings.background || !s.anyPaired) {
            stop()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            LogBus.log("[tpms] background watch refused: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        TpmsMonitor.acquire(this, TAG, foreground = false)
        return START_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        TpmsMonitor.release(TAG)
        super.onDestroy()
    }

    private fun stop() {
        if (running === this) running = null
        TpmsMonitor.release(TAG)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refresh() {
        val text = summary(this)
        if (text == lastText) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build())
        } catch (_: SecurityException) {
        }
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tpms_service_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 52, Intent(this, TpmsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = summary(this)
        lastText = text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.tpms_service_title))
            .setContentText(text)
            .setContentIntent(TpmsNotifier.openIntent(this))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.main_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "service"
        private const val CHANNEL_ID = "tpms_watch"
        private const val NOTIF_ID = 5100
        private const val ACTION_STOP = "dev.zanderp.opencfmoto.TPMS_STOP"

        @Volatile private var running: TpmsService? = null

        /** Starts or stops the service to match the settings. Call from the foreground (screen / activity). */
        fun sync(ctx: Context) {
            TpmsMonitor.init(ctx)
            val s = TpmsMonitor.state.value
            val want = s.settings.background && s.anyPaired
            val c = ctx.applicationContext
            if (want && running == null) {
                try {
                    c.startForegroundService(Intent(c, TpmsService::class.java))
                } catch (e: Exception) {
                    LogBus.log("[tpms] can't start background watch: $e")
                }
            } else if (!want && running != null) {
                c.stopService(Intent(c, TpmsService::class.java))
            }
        }

        internal fun onReading(@Suppress("UNUSED_PARAMETER") ctx: Context) {
            running?.refresh()
        }

        /** "F 29.2 psi · R 31.0 psi" — a dash for a wheel without a reading yet. */
        fun summary(ctx: Context): String {
            val s = TpmsMonitor.state.value
            val unit = TpmsNotifier.unitLabel(ctx, s.settings.unit)
            return Wheel.entries.filter { s.wheel(it).paired }.joinToString(" · ") { w ->
                val r = s.wheel(w).reading
                val letter = ctx.getString(if (w == Wheel.FRONT) R.string.tpms_front_short else R.string.tpms_rear_short)
                "$letter " + (r?.let { s.settings.unit.format(it.pressureKpa) + " " + unit } ?: "–")
            }
        }
    }
}
