// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import kotlin.math.roundToInt

/**
 * Geometry of the side panel (a column beside Android Auto on the bike output). Pure — unit-tested.
 * Android Auto is laid out in the rest of the width; the panel acts like an extra left/right margin.
 */
object SidePanelLayout {
    const val MIN_PX = 120
    const val MIN_AA_PX = 200
    const val MAX_FRACTION = 0.45f

    /** Narrow / Medium / Wide reuse the strip size choice. */
    fun percentFor(h: StripHeight): Int = when (h) {
        StripHeight.OFF -> 0
        StripHeight.SMALL -> 25
        StripHeight.MEDIUM -> 30
        StripHeight.LARGE -> 35
    }

    /** Panel width in canvas pixels, or 0 when the canvas is too narrow for a useful panel + AA. */
    fun panelPx(canvasW: Int, percent: Int): Int {
        if (percent <= 0 || canvasW <= 0) return 0
        val px = (canvasW * percent / 100f).roundToInt().coerceAtMost((canvasW * MAX_FRACTION).toInt())
        return if (px < MIN_PX || canvasW - px < MIN_AA_PX) 0 else px
    }

    fun aaWidth(panelW: Int, sidePx: Int): Int = (panelW - sidePx).coerceAtLeast(16)

    fun panelX(canvasW: Int, sidePx: Int, left: Boolean): Int = if (left) 0 else canvasW - sidePx

    fun isInPanel(cx: Int, canvasW: Int, sidePx: Int, left: Boolean): Boolean =
        sidePx > 0 && if (left) cx < sidePx else cx >= canvasW - sidePx

    /** Touch x to map into Android Auto, or null: DOWN on the panel dropped; MOVE/UP clamped to its edge. */
    fun touchX(cx: Int, canvasW: Int, sidePx: Int, left: Boolean, isDown: Boolean): Int? = when {
        !isInPanel(cx, canvasW, sidePx, left) -> cx
        isDown -> null
        left -> sidePx
        else -> canvasW - sidePx - 1
    }
}
