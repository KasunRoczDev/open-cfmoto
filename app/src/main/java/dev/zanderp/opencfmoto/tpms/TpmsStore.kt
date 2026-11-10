// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import android.content.Context
import android.content.SharedPreferences

/** Sensor pairing, thresholds and last readings in the `tpms` prefs file (so Full backup carries them). */
object TpmsStore {
    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("tpms", Context.MODE_PRIVATE)

    fun settings(ctx: Context): TpmsSettings {
        val p = prefs(ctx)
        val d = TpmsSettings()
        return TpmsSettings(
            unit = PressureUnit.of(p.getString("unit", d.unit.key)),
            fahrenheit = p.getBoolean("fahrenheit", d.fahrenheit),
            frontLowKpa = p.getFloat("front_low", d.frontLowKpa.toFloat()).toDouble(),
            frontHighKpa = p.getFloat("front_high", d.frontHighKpa.toFloat()).toDouble(),
            rearLowKpa = p.getFloat("rear_low", d.rearLowKpa.toFloat()).toDouble(),
            rearHighKpa = p.getFloat("rear_high", d.rearHighKpa.toFloat()).toDouble(),
            highTempC = p.getInt("high_temp", d.highTempC),
            lowBatteryV = p.getFloat("low_batt", d.lowBatteryV.toFloat()).toDouble(),
            alerts = p.getBoolean("alerts", d.alerts),
            background = p.getBoolean("background", d.background),
            customLimits = p.getBoolean("custom_limits", d.customLimits),
            loaded = p.getBoolean("loaded", d.loaded),
            onDash = p.getBoolean("on_dash", d.onDash),
        )
    }

    fun saveSettings(ctx: Context, s: TpmsSettings) {
        prefs(ctx).edit()
            .putString("unit", s.unit.key)
            .putBoolean("fahrenheit", s.fahrenheit)
            .putFloat("front_low", s.frontLowKpa.toFloat())
            .putFloat("front_high", s.frontHighKpa.toFloat())
            .putFloat("rear_low", s.rearLowKpa.toFloat())
            .putFloat("rear_high", s.rearHighKpa.toFloat())
            .putInt("high_temp", s.highTempC)
            .putFloat("low_batt", s.lowBatteryV.toFloat())
            .putBoolean("alerts", s.alerts)
            .putBoolean("background", s.background)
            .putBoolean("custom_limits", s.customLimits)
            .putBoolean("loaded", s.loaded)
            .putBoolean("on_dash", s.onDash)
            .apply()
    }

    fun wheel(ctx: Context, w: Wheel): WheelState {
        val p = prefs(ctx)
        val k = w.key
        val at = p.getLong("${k}_at", 0)
        val reading = if (at > 0) {
            TpmsProtocol.Reading(
                family = runCatching { TpmsProtocol.Family.valueOf(p.getString("${k}_family", "TPMS")!!) }.getOrDefault(TpmsProtocol.Family.TPMS),
                pressureKpa = p.getFloat("${k}_kpa", 0f).toDouble(),
                tempC = p.getInt("${k}_temp", 0),
                batteryV = p.getFloat("${k}_volt", 0f).toDouble(),
                pairId = p.getString("${k}_id", null),
            )
        } else null
        return WheelState(
            address = p.getString("${k}_addr", null),
            pendingId = p.getString("${k}_pending", null),
            reading = reading,
            atMs = at,
        )
    }

    fun saveWheel(ctx: Context, w: Wheel, s: WheelState) {
        val k = w.key
        val e = prefs(ctx).edit()
            .putString("${k}_addr", s.address)
            .putString("${k}_pending", s.pendingId)
        val r = s.reading
        if (r != null && s.atMs > 0) {
            e.putString("${k}_family", r.family.name)
                .putFloat("${k}_kpa", r.pressureKpa.toFloat())
                .putInt("${k}_temp", r.tempC)
                .putFloat("${k}_volt", r.batteryV.toFloat())
                .putString("${k}_id", r.pairId)
                .putLong("${k}_at", s.atMs)
        } else {
            e.remove("${k}_family").remove("${k}_kpa").remove("${k}_temp").remove("${k}_volt").remove("${k}_id").remove("${k}_at")
        }
        e.apply()
    }
}
