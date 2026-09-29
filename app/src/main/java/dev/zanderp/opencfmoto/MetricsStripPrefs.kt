// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/** Rider choice for the metrics strip under Android Auto. [px] is the requested band in dash pixels. */
enum class StripHeight(val px: Int, @StringRes val labelRes: Int) {
    OFF(0, R.string.pref_strip_off),
    SMALL(48, R.string.pref_strip_small),
    MEDIUM(64, R.string.pref_strip_medium),
    LARGE(80, R.string.pref_strip_large),
}

/** Per-bike metrics-strip settings (scoped via [BikeScope]). Read at Connect; changes apply next connect. */
object MetricsStripPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_HEIGHT = "metrics_strip_height"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun height(ctx: Context): StripHeight {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_HEIGHT, StripHeight.OFF.name)
        return runCatching { StripHeight.valueOf(name!!) }.getOrDefault(StripHeight.OFF)
    }

    fun setHeight(ctx: Context, h: StripHeight) {
        BikeScope.putString(prefs(ctx), ctx, KEY_HEIGHT, h.name)
    }
}
