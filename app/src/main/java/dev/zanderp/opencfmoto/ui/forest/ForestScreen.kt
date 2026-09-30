// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.forest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.EcoStore
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TreeDrawing
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import java.util.Locale
import kotlin.math.ceil

private const val COLUMNS = 6

@Composable
fun ForestScreen() {
    val ctx = LocalContext.current
    val f = remember { EcoStore.load(ctx) }
    val c = Ocm.colors
    val rows = maxOf(1, ceil((f.trees + 1) / COLUMNS.toDouble()).toInt())
    OcmScreen {
        Text(stringResource(R.string.forest_title), style = OcmType.screenTitle, color = c.text)
        Canvas(
            Modifier.fillMaxWidth().height((rows * 64 + 16).dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF16242A), Color(0xFF10301F)))),
        ) {
            val cell = size.width / COLUMNS
            val rowH = (size.height - 16.dp.toPx()) / rows
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                for (i in 0..f.trees) {
                    val col = i % COLUMNS
                    val row = i / COLUMNS
                    val cx = cell * col + cell / 2
                    val base = 8.dp.toPx() + rowH * (row + 1)
                    val grown = i < f.trees
                    val scale = if (grown) 0.8f + ((i * 37) % 7) / 20f else 0.9f   // varied, stable per tree
                    TreeDrawing.draw(nc, if (grown) 5 else f.stage, cx, base, minOf(cell, rowH) * scale, shade = i)
                }
            }
        }
        OcmCard {
            StatRow(stringResource(R.string.forest_trees), f.trees.toString())
            StatRow(
                stringResource(R.string.forest_growing),
                stringResource(R.string.forest_growing_value, TreeDrawing.stageName(f.stage), f.currentMeters / 1000),
            )
            StatRow(stringResource(R.string.forest_all_time), String.format(Locale.US, "%.0f km", f.allTimeMeters / 1000))
            StatRow(
                stringResource(R.string.forest_month),
                f.monthEcoPercentFor(dev.zanderp.opencfmoto.EcoForest.monthKey(System.currentTimeMillis(), java.util.TimeZone.getDefault()))
                    ?.let { "$it%" } ?: "—",
            )
        }
        OcmCard { Text(stringResource(R.string.forest_note), style = OcmType.body, color = c.text2) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val c = Ocm.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OcmType.body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = OcmType.title, color = c.text)
    }
}
