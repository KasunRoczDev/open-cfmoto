// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON <-> [MaintenanceState] (no Android dependency, so it's unit-testable directly). */
object MaintenanceCodec {
    fun encode(state: MaintenanceState): String {
        val items = JSONObject()
        for ((id, s) in state.items) {
            items.put(
                id,
                JSONObject()
                    .put("lastServicedKm", s.lastServicedKm)
                    .put("lastServicedAtMs", s.lastServicedAtMs)
                    .put("notified", s.notified),
            )
        }
        return JSONObject()
            .put("baselineKm", state.baselineKm)
            .put("baselineSetAtMs", state.baselineSetAtMs)
            .put("trackingStartedAtMs", state.trackingStartedAtMs)
            .put("items", items)
            .put("notifiedUnserviced", JSONArray(state.notifiedUnserviced.sorted()))
            .toString()
    }

    /** null on any parse failure (corrupt/missing file) — callers treat that as "not set up yet". */
    fun decode(json: String): MaintenanceState? = runCatching {
        val o = JSONObject(json)
        val itemsObj = o.optJSONObject("items") ?: JSONObject()
        val items = buildMap {
            for (id in itemsObj.keys()) {
                val s = itemsObj.getJSONObject(id)
                put(id, ItemState(s.getDouble("lastServicedKm"), s.getLong("lastServicedAtMs"), s.optBoolean("notified", false)))
            }
        }
        val notifiedArr = o.optJSONArray("notifiedUnserviced") ?: JSONArray()
        val notified = (0 until notifiedArr.length()).map { notifiedArr.getString(it) }.toSet()
        val baselineSetAtMs = o.getLong("baselineSetAtMs")
        MaintenanceState(
            o.getDouble("baselineKm"), baselineSetAtMs, items, notified,
            trackingStartedAtMs = o.optLong("trackingStartedAtMs", baselineSetAtMs),
        )
    }.getOrNull()
}
