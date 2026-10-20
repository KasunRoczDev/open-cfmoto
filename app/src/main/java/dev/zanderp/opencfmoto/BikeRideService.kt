// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Logs a ride for as long as the bike's own Bluetooth (calls / music) is connected — no projection
 * needed, the phone can stay in a pocket. A location-typed foreground service keeps GPS flowing
 * into the shared recorder via [TripAutoLog.setBikeBtLive]; a disconnect ends the ride after
 * [BikeBtRide.DISCONNECT_GRACE_MS] unless the bike comes back first.
 *
 * Background GPS needs "Allow all the time" location: started from a Bluetooth broadcast the
 * service is exempt from the background-start ban, but not from the while-in-use location rule.
 */
class BikeRideService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val endRide = Runnable {
        LogBus.log("[trip] bike Bluetooth still gone after ${BikeBtRide.DISCONNECT_GRACE_MS / 60_000} min — ending ride")
        finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECTED -> {
                handler.removeCallbacks(endRide)
                if (!promote()) {
                    finish()
                    return START_NOT_STICKY
                }
                running = true
                TripAutoLog.setBikeBtLive(this, true)
            }
            ACTION_DISCONNECTED -> {
                handler.removeCallbacks(endRide)
                handler.postDelayed(endRide, BikeBtRide.DISCONNECT_GRACE_MS)
                LogBus.log("[trip] bike Bluetooth disconnected — ride ends in ${BikeBtRide.DISCONNECT_GRACE_MS / 60_000} min unless it reconnects")
            }
            else -> finish()   // ACTION_STOP from the notification, or an unexpected restart
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(endRide)
        if (TripAutoLog.bikeBtLive) TripAutoLog.setBikeBtLive(this, false)
        running = false
        super.onDestroy()
    }

    private fun finish() {
        handler.removeCallbacks(endRide)
        if (TripAutoLog.bikeBtLive) TripAutoLog.setBikeBtLive(this, false)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Foreground with the location type; false when Android refuses it (no "all the time" location). */
    private fun promote(): Boolean {
        val n = notification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            if (!hasBackgroundLocation(this)) {
                LogBus.log("[trip] bike Bluetooth ride: location isn't \"Allow all the time\" — GPS may pause while the app is closed")
            }
            true
        } catch (e: Exception) {
            LogBus.log("[trip] bike Bluetooth ride can't start: $e — set location to \"Allow all the time\"")
            false
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_bt_ride_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BikeRideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bike = AppSettings.btRideName(this) ?: getString(R.string.notif_bt_ride_bike)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_bt_ride_title))
            .setContentText(getString(R.string.notif_bt_ride_text, bike))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.main_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bike_bt_ride"
        private const val NOTIF_ID = 4711
        private const val ACTION_CONNECTED = "dev.zanderp.opencfmoto.BT_RIDE_CONNECTED"
        private const val ACTION_DISCONNECTED = "dev.zanderp.opencfmoto.BT_RIDE_DISCONNECTED"
        private const val ACTION_STOP = "dev.zanderp.opencfmoto.BT_RIDE_STOP"

        /** True while the service holds a ride open (main thread writes). */
        @Volatile var running = false
            private set

        fun hasBackgroundLocation(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun onBikeConnected(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BikeRideService::class.java).setAction(ACTION_CONNECTED))
            } catch (e: Exception) {
                LogBus.log("[trip] bike Bluetooth ride start refused: $e")
            }
        }

        /** Only reaches a running service — starting one just to stop it would break the FGS contract. */
        fun onBikeDisconnected(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, BikeRideService::class.java).setAction(ACTION_DISCONNECTED))
            } catch (e: Exception) {
                LogBus.log("[trip] bike Bluetooth ride disconnect not delivered: $e")
            }
        }
    }
}

/** Starts / ends a [BikeRideService] ride when the chosen bike's Bluetooth connects / disconnects. */
class BikeBtRideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (!AppSettings.btRideLog(context) || !AppSettings.logTrips(context)) return
        val mac = try { bluetoothDeviceFrom(intent)?.address } catch (_: SecurityException) { null } ?: return
        if (!BikeBtRide.isBike(mac, AppSettings.btRideMac(context))) return
        if (connected) {
            LogBus.log("[trip] bike Bluetooth connected — logging this ride")
            BikeRideService.onBikeConnected(context)
        } else {
            BikeRideService.onBikeDisconnected(context)
        }
    }
}
