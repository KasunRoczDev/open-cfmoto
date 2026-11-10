// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes

/**
 * A bike picture for the dash side panel plus where its wheels are, as fractions of the image
 * (x of width, y and radius of height), so each tyre's pressure can sit on the right wheel.
 * The bundled renders face right: front wheel on the right.
 */
data class DashBikeArt(
    @DrawableRes val res: Int?,
    val aspect: Float,
    val rearX: Float, val rearY: Float, val rearR: Float,
    val frontX: Float, val frontY: Float, val frontR: Float,
) {
    companion object {
        /** CFMoto 150SC studio render (1084×715). */
        private val CFMOTO_150SC = DashBikeArt(
            R.drawable.bike_150sc, 1084f / 715f,
            rearX = 0.166f, rearY = 0.826f, rearR = 0.174f,
            frontX = 0.867f, frontY = 0.815f, frontR = 0.172f,
        )

        /** No render for this model: the panel draws a simple outline with two wheels. */
        val GENERIC = DashBikeArt(null, 1.6f, 0.20f, 0.72f, 0.24f, 0.80f, 0.72f, 0.24f)

        private val BY_MODEL = mapOf("150sc" to CFMOTO_150SC)

        fun forModel(model: String?): DashBikeArt =
            BY_MODEL[model?.lowercase()?.filter { it.isLetterOrDigit() }] ?: GENERIC

        /** Decodes the render (once per dash session — off the UI thread). Null for [GENERIC] or on failure. */
        fun load(ctx: Context, art: DashBikeArt): Bitmap? =
            art.res?.let { runCatching { BitmapFactory.decodeResource(ctx.resources, it) }.getOrNull() }
    }
}
