// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.aa.proto.Sensors.SensorBatch.DrivingStatusData.Status

/**
 * What to tell Android Auto's DRIVING_STATUS sensor, from phone GPS speed. A real head unit reports
 * restrictions while the vehicle moves and AA then locks the keyboard, settings and long lists; we used
 * to report UNRESTRICTED for the whole session.
 *
 * Hysteresis so a rolling stop or GPS jitter doesn't flap the dash: moving at ≥ [MOVING_KMH], parked
 * again only after [STOP_HOLD_MS] below [STOPPED_KMH]. A fix without speed changes nothing (a tunnel
 * keeps the last state). Pure — unit-tested.
 */
class DrivingStatusPolicy {
    var moving = false
        private set
    private var slowSinceMs: Long? = null

    /** Feed one fix ([speedMps] null when it has no speed). Returns true when [moving] changed. */
    fun onFix(speedMps: Float?, nowMs: Long): Boolean {
        if (speedMps == null || speedMps.isNaN()) return false
        val kmh = speedMps * 3.6f
        if (!moving) {
            if (kmh < MOVING_KMH) return false
            moving = true
            slowSinceMs = null
            return true
        }
        if (kmh >= STOPPED_KMH) {
            slowSinceMs = null
            return false
        }
        val since = slowSinceMs ?: nowMs.also { slowSinceMs = it }
        if (nowMs - since < STOP_HOLD_MS) return false
        moving = false
        slowSinceMs = null
        return true
    }

    val status: Int get() = if (moving) STATUS_MOVING else STATUS_PARKED

    companion object {
        const val MOVING_KMH = 10f
        const val STOPPED_KMH = 4f
        const val STOP_HOLD_MS = 5_000L

        const val STATUS_PARKED = Status.UNRESTRICTED_VALUE
        /** What a moving car reports: no keyboard, no settings, short messages. Video and voice stay on. */
        const val STATUS_MOVING = Status.NO_KEYBOARD_INPUT_VALUE or Status.NO_CONFIG_VALUE or Status.LIMIT_MESSAGE_LEN_VALUE
    }
}
