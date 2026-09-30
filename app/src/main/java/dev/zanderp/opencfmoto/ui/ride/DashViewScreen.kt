// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.content.res.Configuration
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType

data class DashViewUi(
    val title: String,
    val topBar: Boolean,
    val pad: Boolean,
    val hint: Boolean,
    val gpxPreview: Boolean,
)

/** A View owned by the Activity, moved into whichever AndroidView hosts it now. */
private fun <T : View> T.detached(): T = apply { (parent as? ViewGroup)?.removeView(this) }

@Composable
fun DashViewScreen(
    ui: DashViewUi,
    surface: SurfaceView,
    gpxHost: FrameLayout,
    onClose: () -> Unit,
    onMapHub: () -> Unit,
    onTheme: () -> Unit,
    onTogglePad: () -> Unit,
    onFullscreen: () -> Unit,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
) {
    val c = Ocm.colors
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (ui.topBar) {
            Row(
                Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().displayCutoutPadding().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.Rounded.Close, onClose)
                Text(ui.title, style = OcmType.title, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                BarIcon(Icons.Rounded.Map, onMapHub)
                BarIcon(Icons.Rounded.Contrast, onTheme)
                BarIcon(Icons.Rounded.Gamepad, onTogglePad)
                BarIcon(Icons.Rounded.Fullscreen, onFullscreen)
            }
        }
        val video: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                AndroidView(
                    factory = { surface.detached() }, modifier = Modifier.fillMaxSize(),
                    update = { it.visibility = if (ui.gpxPreview) View.GONE else View.VISIBLE },
                )
                AndroidView(
                    factory = { gpxHost.detached() }, modifier = Modifier.fillMaxSize(),
                    update = { it.visibility = if (ui.gpxPreview) View.VISIBLE else View.GONE },
                )
                if (ui.hint) {
                    Text(
                        stringResource(R.string.hud_start_android_auto_and_connect_to_your_bike_to_s),
                        style = OcmType.body, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                video(Modifier.weight(1f).fillMaxHeight())
                if (ui.pad) {
                    AaPad(
                        onKey, onScroll, onVoice,
                        Modifier.width(300.dp).fillMaxHeight().background(c.bg).navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        } else {
            video(Modifier.weight(1f).fillMaxWidth())
            if (ui.pad) {
                AaPad(onKey, onScroll, onVoice, Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(12.dp))
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = null, tint = Ocm.colors.accent)
    }
}
