// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import dev.zanderp.opencfmoto.Trip
import java.util.Calendar
import java.util.TimeZone

/** Estimates where a bike stands against its [MaintenanceSchedule] from a baseline + ride history. */
object MaintenanceEstimate {
    const val DUE_SOON_KM = 300.0
    const val DUE_SOON_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    /** Baseline + every trip for [bikeRaw] (or untagged/legacy trips, which count for any bike) that
     *  ended after the baseline was set. */
    fun estimateKm(state: MaintenanceState, trips: List<Trip>, bikeRaw: String?): Double {
        val rideMeters = trips
            .filter { (it.bikeRaw == null || it.bikeRaw == bikeRaw) && it.end > state.baselineSetAtMs }
            .sumOf { it.distanceMeters }
        return state.baselineKm + rideMeters / 1000.0
    }

    /** Next km due-point, or null when the item has no km interval. */
    fun dueKm(item: MaintenanceItem, itemState: ItemState?): Double? {
        val interval = item.kmInterval ?: return null
        val last = itemState?.lastServicedKm
        return if (last != null) last + interval else (item.firstDueKm ?: interval).toDouble()
    }

    /** Next calendar due-point, or null when the item has no month interval. */
    fun dueAtMs(item: MaintenanceItem, itemState: ItemState?, baselineSetAtMs: Long): Long? {
        val interval = item.monthInterval ?: return null
        val from = itemState?.lastServicedAtMs ?: baselineSetAtMs
        return addMonthsUtc(from, interval)
    }

    /** Km left until due (negative = overdue by that much), or null when the item has no km interval. */
    fun remainingKm(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double): Double? =
        dueKm(item, itemState)?.let { it - estimateKm }

    /** Days left until due (negative = overdue), or null when the item has no month interval. */
    fun remainingDays(item: MaintenanceItem, itemState: ItemState?, baselineSetAtMs: Long, nowMs: Long): Long? =
        dueAtMs(item, itemState, baselineSetAtMs)?.let { (it - nowMs) / DAY_MS }

    fun status(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double, nowMs: Long, baselineSetAtMs: Long): MaintenanceStatus {
        val dueKm = dueKm(item, itemState)
        val dueAt = dueAtMs(item, itemState, baselineSetAtMs)
        val overdue = (dueKm != null && estimateKm >= dueKm) || (dueAt != null && nowMs >= dueAt)
        if (overdue) return MaintenanceStatus.OVERDUE
        val soonByKm = dueKm != null && estimateKm >= dueKm - DUE_SOON_KM
        val soonByDate = dueAt != null && nowMs >= dueAt - DUE_SOON_DAYS * DAY_MS
        return if (soonByKm || soonByDate) MaintenanceStatus.DUE_SOON else MaintenanceStatus.OK
    }

    /** [items] minus one-time items that have already been serviced once (they never recur). */
    fun visibleItems(items: List<MaintenanceItem>, states: Map<String, ItemState>): List<MaintenanceItem> =
        items.filterNot { it.oneTime && states.containsKey(it.id) }

    /** Visible items that are newly overdue and haven't already fired a notification. */
    fun itemsToNotify(items: List<MaintenanceItem>, state: MaintenanceState, estimateKm: Double, nowMs: Long): List<MaintenanceItem> =
        visibleItems(items, state.items).filter { item ->
            val itemState = state.items[item.id]
            itemState?.notified != true &&
                status(item, itemState, estimateKm, nowMs, state.baselineSetAtMs) == MaintenanceStatus.OVERDUE
        }

    private fun addMonthsUtc(fromMs: Long, months: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = fromMs
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }
}
