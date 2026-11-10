// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tpms

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.DashBikeArt
import dev.zanderp.opencfmoto.EcoBands
import dev.zanderp.opencfmoto.EcoForest
import dev.zanderp.opencfmoto.EcoStore
import dev.zanderp.opencfmoto.MapPrefs
import dev.zanderp.opencfmoto.SidePanelRenderer
import dev.zanderp.opencfmoto.SidePanelView
import dev.zanderp.opencfmoto.tpms.TpmsDash
import dev.zanderp.opencfmoto.tpms.TpmsMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A typical side panel: 30 % of a 1280 px wide dash, full dash height. */
private const val PREVIEW_W = 384
private const val PREVIEW_H = 480

/**
 * The Android Auto side panel exactly as the dash draws it (same renderer), with the live tyre block —
 * so the rider can check it without the bike. Directions are left out (no route here).
 */
@Composable
fun DashPreview(st: TpmsMonitor.State, nowMs: Long) {
    val ctx = LocalContext.current
    var assets by remember { mutableStateOf<Triple<Bitmap?, DashBikeArt, Pair<EcoForest, String?>>?>(null) }
    LaunchedEffect(Unit) {
        assets = withContext(Dispatchers.IO) {
            val app = ctx.applicationContext
            val model = BikeMemory.selected(app)?.model
            val art = DashBikeArt.forModel(model)
            Triple(DashBikeArt.load(app, art), art, EcoStore.load(app) to model)
        }
    }
    val a = assets ?: return
    val (bike, art, eco) = a
    val (forest, model) = eco
    val img = remember(st, nowMs / 60_000, a) {
        val units = MapPrefs.units(ctx)
        val band = EcoBands.forModel(model)
        val v = SidePanelView(
            directions = null,
            stage = forest.stage,
            progress = (forest.progress * 100).toInt() / 100f,
            ecoKm = (forest.currentMeters / 100).toInt() / 10.0,
            ecoPercent = null,
            treeNumber = forest.trees + 1,
            noGps = false,
            ecoBand = band.speedLabel(units),
            ecoMax = band.maxLabel(units),
            ecoRpm = band.rpmLabel(),
            ecoHint = null,
            tyres = TpmsDash.panel(st, model, nowMs),
        )
        SidePanelRenderer.render(v, PREVIEW_W, PREVIEW_H, bike, art).asImageBitmap()
    }
    Image(
        img, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)),
    )
}
