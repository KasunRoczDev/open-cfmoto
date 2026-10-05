// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.content.Context
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.TripStore

/** What the Me tab's Maintenance row shows. [hasSchedule] false = unknown/no model; [hasBaseline]
 *  false (with [hasSchedule] true) = model recognized but the rider hasn't set an odometer yet. */
data class MaintenanceSummary(val dueCount: Int, val hasSchedule: Boolean, val hasBaseline: Boolean) {
    companion object {
        val EMPTY = MaintenanceSummary(0, hasSchedule = false, hasBaseline = false)

        /** Reads prefs/trips from disk — call off the main thread. */
        fun load(ctx: Context, nowMs: Long): MaintenanceSummary {
            val bike = BikeMemory.selected(ctx) ?: return EMPTY
            val items = MaintenanceSchedule.forModel(bike.model)
            if (items.isEmpty()) return MaintenanceSummary(0, hasSchedule = false, hasBaseline = false)
            val state = MaintenanceStore.load(ctx, bike.raw)
                ?: return MaintenanceSummary(0, hasSchedule = true, hasBaseline = false)
            val estimate = MaintenanceEstimate.estimateKm(state, TripStore.list(ctx), bike.raw)
            val due = MaintenanceEstimate.visibleItems(items, state.items).count {
                MaintenanceEstimate.status(it, state.items[it.id], estimate, nowMs, state.trackingStartedAtMs) != MaintenanceStatus.OK
            }
            return MaintenanceSummary(due, hasSchedule = true, hasBaseline = true)
        }
    }
}
