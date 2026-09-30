// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType

data class QrUi(val hint: String?, val cameraDenied: Boolean)

@Composable
fun QrScanScreen(
    ui: QrUi,
    preview: PreviewView,
    onClose: () -> Unit,
    onZoom: (Float) -> Unit,
    onPhoto: () -> Unit,
    onManual: () -> Unit,
) {
    val c = Ocm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        OcmTopBar(stringResource(R.string.ui_qr_title), onClose)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ui.hint ?: stringResource(R.string.qr_point_the_camera_at_the_bike_s_motoplay_qr_if_it), style = OcmType.body, color = c.text2)
            // Square camera area: as wide as the screen, but never taller than the space left (landscape).
            Box(
                Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .align(Alignment.CenterHorizontally).clip(RoundedCornerShape(24.dp)).background(Color.Black),
            ) {
                if (ui.cameraDenied) {
                    Text(
                        stringResource(R.string.ui_qr_denied), style = OcmType.body, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    AndroidView(factory = { preview.apply { (parent as? ViewGroup)?.removeView(this) } }, modifier = Modifier.fillMaxSize())
                    // Viewfinder frame.
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).aspectRatio(1f).border(3.dp, c.accent, RoundedCornerShape(20.dp)))
                }
            }
            if (!ui.cameraDenied) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, ratio) in listOf(R.string.qr_1x to 1f, R.string.qr_2x to 2f, R.string.qr_3x to 3f)) {
                        OutlinedButton(onClick = { onZoom(ratio) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null)
                Text(stringResource(R.string.qr_from_photo), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, tint = c.accent)
                Text(stringResource(R.string.garage_enter_wi_fi_manually), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
