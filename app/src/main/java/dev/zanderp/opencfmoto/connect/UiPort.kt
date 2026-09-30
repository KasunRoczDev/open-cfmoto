// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent

/** What [ConnectionController] needs from the visible screen. Attached in onCreate, detached in onDestroy. */
interface UiPort {
    val activity: Activity
    fun launchQrScan()
    fun launchMirrorConsent(intent: Intent)
    fun moveTaskToBack()
}
