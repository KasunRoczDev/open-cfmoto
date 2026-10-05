// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.content.Context
import dev.zanderp.opencfmoto.BikeScope
import dev.zanderp.opencfmoto.LogBus
import java.io.File

/** Persists one [MaintenanceState] per bike as a JSON file under `filesDir/maintenance/` — same
 *  one-file-per-entity pattern as `TripStore`, keyed by the bike id `BikeScope` already derives. */
object MaintenanceStore {
    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "maintenance").apply { if (!exists()) mkdirs() }

    private fun file(ctx: Context, bikeRaw: String): File =
        File(dir(ctx), "${BikeScope.idFor(bikeRaw)}.json")

    /** null = this bike has no baseline set yet — the screen should ask for one. */
    fun load(ctx: Context, bikeRaw: String): MaintenanceState? {
        val f = file(ctx, bikeRaw)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val state = MaintenanceCodec.decode(text)
        if (state == null) LogBus.log("[MAINT] saved state unreadable for ${BikeScope.idFor(bikeRaw)} — treating as not set up")
        return state
    }

    fun save(ctx: Context, bikeRaw: String, state: MaintenanceState) {
        runCatching { file(ctx, bikeRaw).writeText(MaintenanceCodec.encode(state)) }
    }

    /** Sets (or re-sets) the baseline without touching any item's service history. */
    fun setBaseline(ctx: Context, bikeRaw: String, km: Double, atMs: Long) {
        val current = load(ctx, bikeRaw) ?: MaintenanceState(km, atMs)
        save(ctx, bikeRaw, current.copy(baselineKm = km, baselineSetAtMs = atMs))
    }

    /** Records [itemId] serviced at [km]/[atMs] and clears its notified flag for the next cycle. */
    fun markServiced(ctx: Context, bikeRaw: String, itemId: String, km: Double, atMs: Long) {
        val current = load(ctx, bikeRaw) ?: return
        save(ctx, bikeRaw, current.withServiced(itemId, km, atMs))
    }

    fun setNotified(ctx: Context, bikeRaw: String, itemId: String, notified: Boolean) {
        val current = load(ctx, bikeRaw) ?: return
        save(ctx, bikeRaw, current.withNotified(itemId, notified))
    }
}
