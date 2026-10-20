// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.fuel

import org.json.JSONArray
import org.json.JSONObject

/** One fill-up. [cost] is the total paid (any currency, optional); [fullTank] = filled to the brim. */
data class FuelEntry(
    val id: String,
    val atMs: Long,
    val litres: Double,
    val cost: Double?,
    val fullTank: Boolean,
)

/** What a model holds and burns on paper — the fallback until the rider's own fill-ups measure it. */
data class FuelSpec(val tankL: Double, val specL100: Double)

object FuelSpecs {
    /** CFMoto 150SC: 8.5 L tank; CFMoto quotes 2.5–2.7 L/100 km. */
    private val CFMOTO_150SC = FuelSpec(tankL = 8.5, specL100 = 2.6)

    private val BY_MODEL = mapOf("150sc" to CFMOTO_150SC)

    /** The model's spec, or null when we don't know it. Any spelling: "150 SC", "150-sc", "150SC". */
    fun forModel(model: String?): FuelSpec? = BY_MODEL[model?.lowercase()?.filter { it.isLetterOrDigit() }]
}

/** Pure JSON <-> fill-ups (no Android dependency, so it's unit-testable directly). */
object FuelCodec {
    fun encode(entries: List<FuelEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("id", e.id).put("atMs", e.atMs).put("litres", e.litres).put("full", e.fullTank)
            e.cost?.let { o.put("cost", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Empty on a corrupt file; unreadable rows are skipped rather than losing the whole log. */
    fun decode(json: String): List<FuelEntry> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                FuelEntry(
                    id = o.getString("id"),
                    atMs = o.getLong("atMs"),
                    litres = o.getDouble("litres"),
                    cost = if (o.has("cost")) o.getDouble("cost") else null,
                    fullTank = o.optBoolean("full", true),
                )
            }.getOrNull()
        }
    }
}
