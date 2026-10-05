// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

/** The daily check's single notification: how many items just went overdue, and the first few names. */
data class MaintenanceNotice(val count: Int, val names: List<String>) {
    companion object {
        const val MAX_NAMES = 5

        fun forItems(items: List<MaintenanceItem>): MaintenanceNotice? =
            if (items.isEmpty()) null else MaintenanceNotice(items.size, items.take(MAX_NAMES).map { it.name })
    }
}
