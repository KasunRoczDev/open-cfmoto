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

/**
 * One service done to one item — the history entry behind [ItemState]. [photos] are file names in
 * `filesDir/maintenance_photos/` (receipts, the job card…).
 */
data class ServiceRecord(
    val id: String,
    val itemId: String,
    val km: Double,
    val atMs: Long,
    val photos: List<String> = emptyList(),
)

/** One bike's maintenance tracking: the odometer baseline and every serviced item's history.
 *  [notifiedUnserviced] holds ids of never-serviced items that already fired their overdue
 *  notification (serviced items carry that flag on their own [ItemState]).
 *  [baselineSetAtMs] moves on every re-sync (it only decides which rides count toward the estimate);
 *  [trackingStartedAtMs] is the first baseline's time and anchors never-serviced calendar due points,
 *  so re-syncing the odometer never pushes those back. */
data class MaintenanceState(
    val baselineKm: Double,
    val baselineSetAtMs: Long,
    val items: Map<String, ItemState> = emptyMap(),
    val notifiedUnserviced: Set<String> = emptySet(),
    val trackingStartedAtMs: Long = baselineSetAtMs,
    /** Every service recorded since history was added, oldest first. */
    val history: List<ServiceRecord> = emptyList(),
) {
    /** [itemId]'s services, newest first. */
    fun historyFor(itemId: String): List<ServiceRecord> = history.filter { it.itemId == itemId }.sortedByDescending { it.atMs }

    /** Adds receipt photos to an existing record (no-op for an unknown [recordId]). */
    fun withRecordPhotos(recordId: String, add: List<String>): MaintenanceState =
        copy(history = history.map { if (it.id == recordId) it.copy(photos = it.photos + add) else it })

    fun withoutRecordPhoto(recordId: String, photo: String): MaintenanceState =
        copy(history = history.map { if (it.id == recordId) it.copy(photos = it.photos - photo) else it })

    /** Re-syncs the odometer; service history and the tracking start are untouched. */
    fun withBaseline(km: Double, atMs: Long): MaintenanceState = copy(baselineKm = km, baselineSetAtMs = atMs)

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

    /** Records a service (with its history entry), which also starts a fresh notification cycle for the item. */
    fun withServiced(itemId: String, km: Double, atMs: Long, photos: List<String> = emptyList()): MaintenanceState =
        copy(
            items = items + (itemId to ItemState(km, atMs)),
            notifiedUnserviced = notifiedUnserviced - itemId,
            history = history + ServiceRecord(recordId(itemId, atMs), itemId, km, atMs, photos),
        )

    companion object {
        fun recordId(itemId: String, atMs: Long) = "$itemId-$atMs"
    }
}
