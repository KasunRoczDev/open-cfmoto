// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.design.StatusKind

enum class PrimaryAction { CONNECT, SCAN, STOP }

/** Everything the Ride tab shows, derived purely from the connection phase (spec §2.3). */
data class RideUiState(
    val phase: Phase,
    val statusKind: StatusKind,
    val primary: PrimaryAction,
    val bikeName: String?,
    val detail: String,
    val scanEnabled: Boolean,
    val mirrorEnabled: Boolean,
    val dashViewEnabled: Boolean,
    val controlsEnabled: Boolean,
)

fun rideUiState(phase: Phase, detail: String, bikeName: String?, hasSavedBike: Boolean): RideUiState {
    val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
    val busy = phase.busy
    return RideUiState(
        phase = phase,
        statusKind = when {
            live -> StatusKind.LIVE
            phase == Phase.ERROR -> StatusKind.ERROR
            busy -> StatusKind.BUSY
            else -> StatusKind.IDLE
        },
        primary = when {
            busy || live -> PrimaryAction.STOP
            hasSavedBike -> PrimaryAction.CONNECT
            else -> PrimaryAction.SCAN
        },
        bikeName = bikeName,
        detail = detail,
        scanEnabled = !busy,
        mirrorEnabled = !busy || live,
        dashViewEnabled = live,
        controlsEnabled = true,
    )
}
