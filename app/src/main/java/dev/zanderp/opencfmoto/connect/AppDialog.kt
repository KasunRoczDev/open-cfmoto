// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import dev.zanderp.opencfmoto.UpdateChecker

/** One pending dialog, rendered by the Compose DialogHost. Held as state so it survives recreation. */
sealed interface AppDialog {
    data class Rival(val installed: Boolean) : AppDialog
    data object VpnKillSwitch : AppDialog
    data class NearbyBt(val needNearby: Boolean) : AppDialog
    data object CrashRecovery : AppDialog
    data class Update(val release: UpdateChecker.Release) : AppDialog
    data object MirrorReady : AppDialog
    data class ReportProblem(val defaultModel: String) : AppDialog
}

data class BikeInfo(val name: String?, val hasSaved: Boolean)
