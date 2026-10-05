// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

enum class MaintenanceStatus { OK, DUE_SOON, OVERDUE }

/** Present in [MaintenanceState.items] only once an item has been serviced at least once. */
data class ItemState(
    val lastServicedKm: Double,
    val lastServicedAtMs: Long,
    val notified: Boolean = false,
)

/** One bike's maintenance tracking: the odometer baseline and every serviced item's history.
 *  [notifiedUnserviced] holds ids of never-serviced items that already fired their overdue
 *  notification (serviced items carry that flag on their own [ItemState]). */
data class MaintenanceState(
    val baselineKm: Double,
    val baselineSetAtMs: Long,
    val items: Map<String, ItemState> = emptyMap(),
    val notifiedUnserviced: Set<String> = emptySet(),
) {
    fun isNotified(itemId: String): Boolean =
        items[itemId]?.notified ?: (itemId in notifiedUnserviced)

    fun withNotified(itemId: String, notified: Boolean = true): MaintenanceState {
        val existing = items[itemId]
        return if (existing != null) {
            copy(items = items + (itemId to existing.copy(notified = notified)))
        } else {
            copy(notifiedUnserviced = if (notified) notifiedUnserviced + itemId else notifiedUnserviced - itemId)
        }
    }

    /** Records a service, which also starts a fresh notification cycle for the item. */
    fun withServiced(itemId: String, km: Double, atMs: Long): MaintenanceState =
        copy(items = items + (itemId to ItemState(km, atMs)), notifiedUnserviced = notifiedUnserviced - itemId)
}
