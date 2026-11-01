// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Setup → "Restore working projection": one action back to plain, shipped-default Android Auto on the
 * dash, without reinstalling. Per-bike settings are written for the selected bike (so a value shared
 * from another bike can't leak back in); global experimental switches go off.
 *
 * Kept on purpose: the Wi‑Fi transport (some dashes only join over P2P), button mapping, map theme,
 * saved bikes and all ride data. Applies next connect.
 */
object ProjectionReset {
    fun restore(ctx: Context) {
        VideoPrefs.set(ctx, VideoQuality.BALANCED)
        VideoPrefs.setFit(ctx, ScreenFit.FILL)
        VideoPrefs.setPower(ctx, PowerMode.BALANCED)
        VideoPrefs.setResolution(ctx, ResolutionMode.AUTO)
        VideoPrefs.setMirrorOrientation(ctx, MirrorOrientation.MATCH_DASH)
        VideoPrefs.setDpiOverride(ctx, null)
        val (w, h) = VideoPrefs.aspectTarget(ctx)
        VideoPrefs.setMatchAspect(ctx, MatchAspectMode.AUTO, w, h)
        ProfilePrefs.set(ctx, ProfileOverride.AUTO)
        ScreenMargins.reset(ctx)
        MetricsStripPrefs.setHeight(ctx, StripHeight.OFF)
        MetricsStripPrefs.setTurnInfo(ctx, false)
        AppSettings.setForceNonTouch(ctx, false)
        AppSettings.setForceTouch(ctx, false)
        AppSettings.setLockAaWhileMoving(ctx, false)
        LogBus.log("→ projection settings restored to defaults (transport, buttons and ride data kept)")
    }
}
