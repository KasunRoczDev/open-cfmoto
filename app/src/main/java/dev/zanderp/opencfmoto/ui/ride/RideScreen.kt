// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.ActionTile
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.StatusChip

@Composable
fun RideScreen(
    s: RideUiState,
    onPrimary: () -> Unit,
    onDashView: () -> Unit,
    onControls: () -> Unit,
    onMirror: () -> Unit,
    onScan: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_ride_title), style = OcmType.screenTitle, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(stringResource(s.phase.labelRes), s.statusKind)
            Spacer(Modifier.width(10.dp))
            Text(
                s.bikeName?.let { stringResource(R.string.main_paired, it) } ?: stringResource(R.string.main_no_bike_paired),
                style = OcmType.body, color = c.text2, maxLines = 1,
            )
        }
        if (s.detail.isNotBlank()) Text(s.detail, style = OcmType.body, color = c.text2)
        PrimaryButton(
            text = when (s.primary) {
                PrimaryAction.STOP -> stringResource(R.string.main_stop)
                PrimaryAction.CONNECT -> stringResource(R.string.main_connect_to, s.bikeName ?: "")
                PrimaryAction.SCAN -> stringResource(R.string.ui_scan_your_bike)
            },
            icon = if (s.primary == PrimaryAction.STOP) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
            onClick = onPrimary,
            danger = s.primary == PrimaryAction.STOP,
        )
        SectionLabel(stringResource(R.string.ui_quick_actions))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_dash_view), Icons.Rounded.CastConnected, onDashView, s.dashViewEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_controls), Icons.Rounded.Gamepad, onControls, s.controlsEnabled, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_mirror), Icons.Rounded.ScreenShare, onMirror, s.mirrorEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_scan), Icons.Rounded.QrCodeScanner, onScan, s.scanEnabled, Modifier.weight(1f))
        }
    }
}
