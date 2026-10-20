// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.fuel

import android.content.Context
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.BikeScope
import dev.zanderp.opencfmoto.TripStore
import java.io.File
import java.util.TimeZone

/** Persists each bike's fill-ups as one JSON file under `filesDir/fuel/` — same pattern as `MaintenanceStore`. */
object FuelStore {
    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "fuel").apply { if (!exists()) mkdirs() }

    private fun file(ctx: Context, bikeRaw: String): File =
        File(dir(ctx), "${BikeScope.idFor(bikeRaw)}.json")

    /** Oldest first. */
    fun list(ctx: Context, bikeRaw: String): List<FuelEntry> {
        val f = file(ctx, bikeRaw)
        if (!f.exists()) return emptyList()
        return FuelCodec.decode(runCatching { f.readText() }.getOrNull() ?: return emptyList()).sortedBy { it.atMs }
    }

    fun add(ctx: Context, bikeRaw: String, entry: FuelEntry) = write(ctx, bikeRaw, list(ctx, bikeRaw) + entry)

    fun delete(ctx: Context, bikeRaw: String, id: String) = write(ctx, bikeRaw, list(ctx, bikeRaw).filter { it.id != id })

    private fun write(ctx: Context, bikeRaw: String, entries: List<FuelEntry>) {
        runCatching { file(ctx, bikeRaw).writeText(FuelCodec.encode(entries.sortedBy { it.atMs })) }
        dev.zanderp.opencfmoto.widget.RideWidget.requestUpdate(ctx)
    }

    /** The selected bike's fuel status (reads trips from disk — call off the main thread). */
    fun statusForSelected(ctx: Context, nowMs: Long): FuelStatus {
        val bike = BikeMemory.selected(ctx) ?: return FuelStatus.EMPTY
        return FuelEstimate.status(
            list(ctx, bike.raw), TripStore.list(ctx), bike.raw, FuelSpecs.forModel(bike.model), nowMs, TimeZone.getDefault(),
        )
    }
}
