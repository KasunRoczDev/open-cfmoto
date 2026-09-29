// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * Setup ▸ Navigate on Connect ▸ Commute: when the rider taps Connect (or auto-connect runs), the
 * first time Android Auto reaches the dash we start Maps to a saved place picked by time of day —
 * morning place before [SWITCH_HOUR], evening place after.
 *
 * One shot per Connect ([arm] → [consume]): automatic reconnects (fuel stop, signal drop,
 * park/resume) never re-navigate, so a route the rider set themselves is never replaced.
 */
object CommuteNav {
    const val SWITCH_HOUR = 12
    private const val LAUNCH_DELAY_MS = 3_000L

    @Volatile private var armed = false
    @Volatile private var appCtx: Context? = null

    /** Saved-place slot to use at [hour] (0..23). */
    fun pickSlot(hour: Int, morningSlot: Int, eveningSlot: Int): Int =
        if (hour < SWITCH_HOUR) morningSlot else eveningSlot

    fun whenLabel(hour: Int): String =
        if (hour < SWITCH_HOUR) "before $SWITCH_HOUR:00" else "after $SWITCH_HOUR:00"

    fun arm() { armed = true }

    fun disarm() { armed = false }

    /** True exactly once after [arm]. */
    @Synchronized
    fun consume(): Boolean {
        if (!armed) return false
        armed = false
        return true
    }

    /** Called for a rider-initiated Connect: arms only when Commute is on. */
    fun armForConnect(ctx: Context) {
        if (!AppSettings.commuteNav(ctx)) {
            disarm()
            return
        }
        appCtx = ctx.applicationContext
        arm()
        LogBus.log("[NAV] commute armed — navigates on first stream")
    }

    /** From [ConnectionState] on the transition into STREAMING. */
    fun onStreaming() {
        if (!consume()) return
        val ctx = appCtx ?: return
        Handler(Looper.getMainLooper()).postDelayed({ launch(ctx) }, LAUNCH_DELAY_MS)
    }

    private fun launch(ctx: Context) {
        try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val slot = pickSlot(hour, AppSettings.commuteMorningSlot(ctx), AppSettings.commuteEveningSlot(ctx))
            if (!SavedPlaces.isSet(ctx, slot)) {
                LogBus.log("[NAV] commute: saved place ${slot + 1} is not set — nothing started")
                return
            }
            val name = SavedPlaces.name(ctx, slot).ifBlank { SavedPlaces.query(ctx, slot) }
            LogBus.log("[NAV] commute → $name (${whenLabel(hour)})")
            NavLauncher.navigate(ctx, SavedPlaces.query(ctx, slot), LogBus::log)
        } catch (e: Exception) {
            LogBus.log("[NAV] commute failed: $e")
        }
    }
}
