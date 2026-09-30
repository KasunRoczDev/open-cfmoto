// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.MapTheme
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SegmentedChoice
import dev.zanderp.opencfmoto.ui.design.SettingRow
import kotlin.math.roundToInt

data class PlaceChip(val slot: Int, val name: String)

data class ControlsUi(
    val places: List<PlaceChip>,
    val theme: MapTheme,
    val touchDash: Boolean,
    val volume: Int,
    val volumeMax: Int,
    val controlAa: Boolean,
    val btStatus: String,
    val mapped: Int,
    val padFullscreen: Boolean,
)

private val Themes = listOf(MapTheme.AUTO, MapTheme.DAY, MapTheme.NIGHT)

@Composable
fun ControlsScreen(
    ui: ControlsUi,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onPlace: (Int) -> Unit,
    onTheme: (MapTheme) -> Unit,
    onPadFullscreen: (Boolean) -> Unit,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
    onVolume: (Int) -> Unit,
    onControlAa: (Boolean) -> Unit,
    onButtonMapping: () -> Unit,
) {
    val c = Ocm.colors
    if (ui.padFullscreen) {
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_on_screen_pad), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { onPadFullscreen(false) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.ui_controls_exit), style = OcmType.title, color = c.accent)
                }
            }
            AaPad(onKey, onScroll, onVoice, Modifier.weight(1f).fillMaxWidth(), fill = true)
        }
        return
    }
    OcmScreen {
        OcmTopBar(stringResource(R.string.controls_controls), onBack)
        // 1. Navigate to…
        OcmCard {
            Text(stringResource(R.string.controls_navigate_to), style = OcmType.title, color = c.text)
            var dest by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    dest, { dest = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.controls_destination)) },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onNavigate(dest) }, modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text(stringResource(R.string.controls_navigate)) }
            }
            if (ui.places.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in ui.places) PlacePill(p) { onPlace(p.slot) }
                }
            }
        }
        // 2. Map theme
        OcmCard {
            Text(stringResource(R.string.controls_map_theme), style = OcmType.title, color = c.text)
            SegmentedChoice(
                listOf(stringResource(R.string.controls_auto), stringResource(R.string.controls_day), stringResource(R.string.controls_night)),
                Themes.indexOf(ui.theme).coerceAtLeast(0),
                { onTheme(Themes[it]) },
            )
        }
        // 3. On-screen pad
        OcmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_on_screen_pad), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { onPadFullscreen(true) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = null, tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.controls_fullscreen), color = c.accent)
                }
            }
            if (ui.touchDash) {
                Text(stringResource(R.string.controls_your_dash_is_a_touchscreen_just_tap_the_screen_t), style = OcmType.body, color = c.busy)
            }
            AaPad(onKey, onScroll, onVoice, Modifier.fillMaxWidth())
        }
        // 4. Volume
        OcmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_volume), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                Text(ui.volume.toString(), style = OcmType.title, color = c.accent)
            }
            Slider(
                value = ui.volume.toFloat(),
                onValueChange = { onVolume(it.roundToInt()) },
                valueRange = 0f..ui.volumeMax.coerceAtLeast(1).toFloat(),
                steps = (ui.volumeMax - 1).coerceAtLeast(0),
            )
        }
        // 5. Handlebar buttons
        OcmCard {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.controls_handlebar_buttons_drive_android_auto),
                    style = OcmType.title, color = c.text, modifier = Modifier.weight(1f),
                )
                Switch(
                    ui.controlAa, onControlAa,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                )
            }
            SettingRow(
                title = stringResource(R.string.controls_customize_buttons),
                subtitle = stringResource(R.string.ui_controls_mapped, ui.btStatus, ui.mapped),
                onClick = onButtonMapping,
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun PlacePill(p: PlaceChip, onClick: () -> Unit) {
    val c = Ocm.colors
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(c.surface2).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (p.slot) { 0 -> Icons.Rounded.Home; 1 -> Icons.Rounded.Work; else -> Icons.Rounded.Star },
            contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(p.name, style = OcmType.title, color = c.text, maxLines = 1)
    }
}
