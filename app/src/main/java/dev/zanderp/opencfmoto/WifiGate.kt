// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Phone Wi‑Fi must be on before we can join the bike AP / P2P group. When it's off, join silently
 * times out — so we block early and ask the rider to turn Wi‑Fi on (dialog when the UI is up;
 * notification only as a fallback with no Activity).
 */
object WifiGate {
    private const val CHANNEL_ID = "opencfmoto_wifi"
    private const val NOTIF_ID = 4

    /** Avoid stacking multiple Wi‑Fi dialogs / re-notifying while it stays off. */
    @Volatile private var promptShowing = false
    @Volatile private var notifiedWhileOff = false
    /** When we last raised the Wi‑Fi panel: launch + auto-connect both ask, the rider sees it once. */
    @Volatile private var panelShownAt = 0L

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return true // can't tell — don't block
        @Suppress("DEPRECATION")
        return wm.isWifiEnabled
    }

    /**
     * Foreground path: raise Android's Wi‑Fi panel so the rider turns it on with one tap. (Apps can't
     * switch Wi‑Fi on themselves since Android 10.) Prefer this from activities.
     * @return true when phone Wi‑Fi is on and the caller may proceed.
     */
    fun ensureEnabledOrPrompt(activity: Activity): Boolean {
        if (isWifiEnabled(activity)) {
            clearOffState(activity)
            return true
        }
        LogBus.log("[WIFI] phone Wi‑Fi is OFF — showing the Wi‑Fi panel")
        ConnectionState.set(Phase.ERROR, "phone Wi‑Fi is off")
        showPanel(activity)
        return false
    }

    /**
     * App opened with a bike saved and Wi‑Fi off: raise the Wi‑Fi panel straight away, without marking
     * the connection as failed (nothing was attempted yet).
     */
    fun promptOnLaunch(activity: Activity) {
        if (isWifiEnabled(activity)) return
        LogBus.log("[WIFI] app opened with phone Wi‑Fi OFF — showing the Wi‑Fi panel")
        showPanel(activity)
    }

    /** Android's Wi‑Fi panel (a sheet over the app); the old dialog if the phone has no panel. */
    private fun showPanel(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            ensureEnabledOrNotify(activity)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - panelShownAt < PANEL_DEBOUNCE_MS) return
        panelShownAt = now
        try {
            activity.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            android.widget.Toast.makeText(activity, R.string.wifi_turn_on_hint, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            LogBus.log("[WIFI] Wi‑Fi panel unavailable ($e) — showing the dialog")
            showDialog(activity)
        }
    }

    /**
     * No Activity available (background callback): post a notification that opens Wi‑Fi settings.
     */
    fun ensureEnabledOrNotify(context: Context): Boolean {
        if (isWifiEnabled(context)) {
            clearOffState(context)
            return true
        }
        LogBus.log("[WIFI] phone Wi‑Fi is OFF — posting Wi‑Fi settings notification")
        ConnectionState.set(Phase.ERROR, "phone Wi‑Fi is off")
        if (!notifiedWhileOff) {
            notifiedWhileOff = true
            postNotification(context)
        }
        return false
    }

    fun cancelNotification(context: Context) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    fun openWifiSettings(context: Context) {
        try {
            val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(flags))
        } catch (_: Exception) {
            try {
                val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags))
            } catch (_: Exception) {
            }
        }
    }

    private fun clearOffState(context: Context) {
        promptShowing = false
        panelShownAt = 0L
        notifiedWhileOff = false
        cancelNotification(context)
    }

    private fun showDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            ensureEnabledOrNotify(activity)
            return
        }
        if (promptShowing) return
        promptShowing = true
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Turn on Wi‑Fi")
                .setMessage(
                    "My moto joins the bike over Wi‑Fi, but your phone’s Wi‑Fi is off.\n\n" +
                        "Turn it on, then tap Connect again."
                )
                .setPositiveButton("Wi‑Fi settings") { _, _ ->
                    promptShowing = false
                    openWifiSettings(activity)
                }
                .setNegativeButton("Dismiss") { _, _ ->
                    promptShowing = false
                }
                .setOnCancelListener { promptShowing = false }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            promptShowing = false
            LogBus.log("[WIFI] dialog failed: $e — falling back to notification")
            ensureEnabledOrNotify(activity)
        }
    }

    private fun postNotification(context: Context) {
        val app = context.applicationContext
        try {
            ensureChannel(app)
            val openWifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                app,
                0,
                openWifi,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Turn on Wi‑Fi")
                .setContentText("My moto can’t reach the bike — tap to open Wi‑Fi settings.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            LogBus.log("[WIFI] notification failed: $e")
        }
    }

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wi‑Fi required",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when phone Wi‑Fi is off during Connect"
            }
        )
    }

    private const val PANEL_DEBOUNCE_MS = 15_000L
}
