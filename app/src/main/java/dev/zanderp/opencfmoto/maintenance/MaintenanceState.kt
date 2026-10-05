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

/** One bike's maintenance tracking: the odometer baseline and every serviced item's history. */
data class MaintenanceState(
    val baselineKm: Double,
    val baselineSetAtMs: Long,
    val items: Map<String, ItemState> = emptyMap(),
)
