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

    /** Left x of the panel, inside the rider's left/right screen margins ([mL], [mR]). */
    fun panelX(canvasW: Int, sidePx: Int, left: Boolean, mL: Int = 0, mR: Int = 0): Int =
        if (left) mL else canvasW - mR - sidePx

    fun isInPanel(cx: Int, canvasW: Int, sidePx: Int, left: Boolean, mL: Int = 0, mR: Int = 0): Boolean {
        if (sidePx <= 0) return false
        val x = panelX(canvasW, sidePx, left, mL, mR)
        return cx >= x && cx < x + sidePx
    }

    /**
     * Touch x to map into Android Auto, or null: DOWN on the panel dropped; MOVE/UP clamped to the adjacent
     * Android Auto column (never into a margin, where the drag would be dropped and leave a pointer stuck).
     */
    fun touchX(cx: Int, canvasW: Int, sidePx: Int, left: Boolean, isDown: Boolean, mL: Int = 0, mR: Int = 0): Int? = when {
        !isInPanel(cx, canvasW, sidePx, left, mL, mR) -> cx
        isDown -> null
        left -> panelX(canvasW, sidePx, true, mL, mR) + sidePx
        else -> panelX(canvasW, sidePx, false, mL, mR) - 1
    }

    /**
     * The panel's GL rect inside the screen margins, vertically matching Android Auto's area (the compositor
     * passes the same top/bottom margin offsets to GL for the AA viewport).
     */
    fun panelRectGl(canvasW: Int, canvasH: Int, sidePx: Int, left: Boolean, mL: Int, mR: Int, mT: Int, mB: Int): StripRect =
        StripRect(panelX(canvasW, sidePx, left, mL, mR), mT, sidePx, (canvasH - mT - mB).coerceAtLeast(1))

    /** Only carve the panel when Android Auto was actually laid out for it at Connect (else AA overflows). */
    fun pipelinePanelPx(canvasW: Int, percent: Int, appliedAtConnect: Boolean): Int =
        if (appliedAtConnect) panelPx(canvasW, percent) else 0

    /** Text size for a panel: the smaller of a height fraction and a width fraction (tall narrow panels). */
    fun textPx(w: Int, h: Int, heightFrac: Float, widthFrac: Float): Float = minOf(h * heightFrac, w * widthFrac)
}
