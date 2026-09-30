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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
                // Top + sides: in landscape a side nav bar / cutout must not cover the ⛶ / ✕ buttons.
                Modifier.fillMaxWidth().background(c.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .heightIn(min = 56.dp),
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
        // One Layout for both orientations: the video is always its first child, so a rotation (the
        // Activity keeps configChanges) only re-lays it out — the SurfaceView / GPX map view are never
        // detached, and surfaceChanged → updatePreviewSize handles the new size as before.
        val padWidth = 300.dp
        Layout(
            content = {
                Box(Modifier.fillMaxSize()) {
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
                if (ui.pad) {
                    AaPad(
                        onKey, onScroll, onVoice,
                        Modifier.background(c.bg).navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(if (landscape) 10.dp else 12.dp),
                    )
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { measurables, cs ->
            val w = cs.maxWidth
            val h = cs.maxHeight
            val pad = measurables.getOrNull(1)?.let {
                if (landscape) it.measure(Constraints.fixed(padWidth.roundToPx().coerceAtMost(w), h))
                else it.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h))
            }
            val r = dashRects(w, h, landscape, if (pad == null) 0 else if (landscape) pad.width else pad.height)
            val video = measurables[0].measure(Constraints.fixed(r.videoW, r.videoH))
            layout(w, h) {
                video.place(0, 0)
                pad?.place(r.padX, r.padY)
            }
        }
    }
}

/** Video size and pad position (px) inside the area below the top bar. */
internal data class DashRects(val videoW: Int, val videoH: Int, val padX: Int, val padY: Int)

/** Portrait: pad (height [padMain]) under the video. Landscape: pad (width [padMain]) on the right. */
internal fun dashRects(w: Int, h: Int, landscape: Boolean, padMain: Int): DashRects =
    if (landscape) {
        val vw = (w - padMain).coerceAtLeast(0)
        DashRects(vw, h, vw, 0)
    } else {
        val vh = (h - padMain).coerceAtLeast(0)
        DashRects(w, vh, 0, vh)
    }

@Composable
private fun BarIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = null, tint = Ocm.colors.accent)
    }
}
