// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The OpenCfMoto mark (gauge ring + needle + wireless arcs), drawn in theme colours. Same geometry as
 * drawable/ic_ocm_mark.xml and docs/brand/opencfmoto-mark.svg, on a 64-unit box. Decorative: callers put
 * the app or screen name next to it.
 */
@Composable
fun OcmMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val c = Ocm.colors
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 64f
        val center = Offset(24.5f * u, 39.5f * u)
        fun arc(r: Float, start: Float, sweep: Float, width: Float, color: androidx.compose.ui.graphics.Color) =
            drawArc(
                color, start, sweep, useCenter = false,
                topLeft = Offset(center.x - r * u, center.y - r * u), size = Size(2 * r * u, 2 * r * u),
                style = Stroke(width = width * u, cap = StrokeCap.Round),
            )
        arc(17f, 135f, 270f, 6f, c.text)
        arc(26f, -78f, 66f, 4.5f, c.accent)
        arc(33.5f, -78f, 66f, 4.5f, c.accent)
        drawLine(c.accent, center, Offset(center.x + 7.778f * u, center.y - 7.778f * u), strokeWidth = 5f * u, cap = StrokeCap.Round)
        drawCircle(c.accent, radius = 4.5f * u, center = center)
    }
}
