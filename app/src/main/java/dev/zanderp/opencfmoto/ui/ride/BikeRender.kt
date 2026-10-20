// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.annotation.DrawableRes
import dev.zanderp.opencfmoto.R

/** Bundled side-view renders (transparent background), keyed like the maintenance schedules. */
private val RENDERS = mapOf(
    "150sc" to R.drawable.bike_150sc,
)

/** Lowercase, letters/digits only, so "150 SC", "150-SC" and "150sc" all hit the same entry. */
private fun normalize(model: String?): String? =
    model?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

/** The stock render for the rider's model, or null when we don't ship one. */
@DrawableRes
fun stockBikeRender(model: String?): Int? = normalize(model)?.let { RENDERS[it] }

/**
 * Splits a model into the two stacked lines of the hero's faded backdrop — the leading number and the
 * rest ("150 SC" → "150" / "SC", "800NK" → "800" / "NK"). Null when there's no number-then-letters shape.
 */
fun heroLines(text: String): Pair<String, String>? {
    val compact = text.filter { !it.isWhitespace() && it != '-' }
    val digits = compact.takeWhile { it.isDigit() }
    val rest = compact.drop(digits.length)
    return if (digits.isNotEmpty() && rest.isNotEmpty()) digits to rest.uppercase() else null
}
