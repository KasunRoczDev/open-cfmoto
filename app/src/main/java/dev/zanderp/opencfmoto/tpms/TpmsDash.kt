// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import java.util.Locale

/** How a tyre tag on the dash is coloured. */
enum class TyreLevel { OK, ALARM, STALE, NONE }

/** One wheel's tag on the dash: "27.6" over "26°C" (or "LOW", or "12 min ago"). */
data class TyreTag(val value: String, val sub: String, val level: TyreLevel)

/** The side panel's tyre block. Data-class equality = nothing visible changed. */
data class TyrePanel(
    /** "CFMoto 150SC · psi". */
    val title: String,
    val front: TyreTag,
    val rear: TyreTag,
) {
    val alarm get() = front.level == TyreLevel.ALARM || rear.level == TyreLevel.ALARM
}

/** Turns the live TPMS state into the dash side panel's tyre block. Pure — unit-tested. */
object TpmsDash {
    /** Null when the rider turned it off or no wheel is paired / waiting for its sensor. */
    fun panel(st: TpmsMonitor.State, model: String?, nowMs: Long): TyrePanel? {
        if (!st.settings.onDash) return null
        if (Wheel.entries.none { st.wheel(it).paired || st.wheel(it).pendingId != null }) return null
        val lim = st.limits
        val name = st.spec?.modelName ?: model?.trim()?.takeIf { it.isNotEmpty() } ?: "Tyres"
        return TyrePanel(
            title = "$name · ${unitText(lim.unit)}",
            front = tag(st.wheel(Wheel.FRONT), Wheel.FRONT, lim, nowMs),
            rear = tag(st.wheel(Wheel.REAR), Wheel.REAR, lim, nowMs),
        )
    }

    fun tag(ws: WheelState, w: Wheel, s: TpmsSettings, nowMs: Long): TyreTag {
        val r = ws.reading
        if (!ws.paired || r == null) return TyreTag("—", if (ws.paired || ws.pendingId != null) "waiting" else "not paired", TyreLevel.NONE)
        val value = s.unit.format(r.pressureKpa, Locale.US)
        val alarms = TpmsRules.alarms(r, w, s)
        return when {
            alarms.isNotEmpty() -> TyreTag(value, alarms.joinToString(" ") { short(it) }, TyreLevel.ALARM)
            !TpmsRules.isLive(ws.atMs, nowMs) -> TyreTag(value, ago(nowMs - ws.atMs), TyreLevel.STALE)
            else -> TyreTag(value, s.formatTemp(r.tempC), TyreLevel.OK)
        }
    }

    fun short(a: TpmsAlarm) = when (a) {
        TpmsAlarm.LOW -> "LOW"
        TpmsAlarm.HIGH -> "HIGH"
        TpmsAlarm.HOT -> "HOT"
        TpmsAlarm.BATTERY -> "BATT"
    }

    fun unitText(u: PressureUnit) = when (u) {
        PressureUnit.PSI -> "psi"
        PressureUnit.BAR -> "bar"
        PressureUnit.KPA -> "kPa"
    }

    /** Whole minutes / hours / days, so the panel only redraws when the text changes. */
    fun ago(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return when {
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }
}
