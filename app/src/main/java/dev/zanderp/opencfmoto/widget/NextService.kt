// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.widget

import dev.zanderp.opencfmoto.maintenance.MaintenanceEstimate
import dev.zanderp.opencfmoto.maintenance.MaintenanceItem
import dev.zanderp.opencfmoto.maintenance.MaintenanceState
import dev.zanderp.opencfmoto.maintenance.MaintenanceStatus

/**
 * What the widget's service slot says: how many items are overdue, else the item closest to due
 * (by km, or days for calendar-only items). Pure — unit-tested.
 */
data class NextService(val overdue: Int, val name: String?, val inKm: Int?, val inDays: Int?) {
    companion object {
        fun of(items: List<MaintenanceItem>, state: MaintenanceState, estimateKm: Double, nowMs: Long): NextService? {
            val visible = MaintenanceEstimate.visibleItems(items, state.items)
            if (visible.isEmpty()) return null
            val overdue = visible.count {
                MaintenanceEstimate.status(it, state.items[it.id], estimateKm, nowMs, state.trackingStartedAtMs) == MaintenanceStatus.OVERDUE
            }
            if (overdue > 0) return NextService(overdue, null, null, null)
            // Closest to due relative to its due-soon window, like the Maintenance screen's detail text.
            val next = visible.minByOrNull { item ->
                val st = state.items[item.id]
                listOfNotNull(
                    MaintenanceEstimate.remainingKm(item, st, estimateKm)?.div(MaintenanceEstimate.DUE_SOON_KM),
                    MaintenanceEstimate.remainingDays(item, st, state.trackingStartedAtMs, nowMs)?.toDouble()
                        ?.div(MaintenanceEstimate.DUE_SOON_DAYS),
                ).minOrNull() ?: Double.MAX_VALUE
            } ?: return null
            val st = state.items[next.id]
            val km = MaintenanceEstimate.remainingKm(next, st, estimateKm)
            val days = MaintenanceEstimate.remainingDays(next, st, state.trackingStartedAtMs, nowMs)
            val byKm = km != null && (days == null || km / MaintenanceEstimate.DUE_SOON_KM <= days.toDouble() / MaintenanceEstimate.DUE_SOON_DAYS)
            return NextService(0, next.name, if (byKm) km!!.toInt() else null, if (byKm) null else days?.toInt())
        }
    }
}
