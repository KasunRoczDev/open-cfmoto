// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.ActionTile
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RideScreen(
    s: RideUiState,
    hero: HeroData,
    onPrimary: () -> Unit,
    onDashView: () -> Unit,
    onControls: () -> Unit,
    onParked: () -> Unit,
    onMirror: () -> Unit,
    onScan: () -> Unit,
    onRides: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_ride_title), style = OcmType.screenTitle, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(stringResource(s.phase.labelRes), s.statusKind)
            Spacer(Modifier.width(10.dp))
            Text(
                s.bikeName ?: stringResource(R.string.main_no_bike_paired),
                style = OcmType.body, color = c.text2, maxLines = 1,
            )
        }
        Hero(heroText(hero.model, s.bikeName), hero.photoPath)
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
        if (s.detail.isNotBlank()) Text(s.detail, style = OcmType.body, color = c.text2)
        hero.summary?.let { RideCard(it, onRides) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_dash_view), Icons.Rounded.CastConnected, onDashView, s.dashViewEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_controls), Icons.Rounded.Gamepad, onControls, s.controlsEnabled, Modifier.weight(1f))
            ActionTile(
                stringResource(if (hero.parked != null) R.string.ui_tile_parked else R.string.ui_tile_park),
                Icons.Rounded.LocalParking, onParked, true, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_mirror), Icons.Rounded.ScreenShare, onMirror, s.mirrorEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_scan), Icons.Rounded.QrCodeScanner, onScan, s.scanEnabled, Modifier.weight(1f))
        }
    }
}

/** Big faded model name behind the rider's photo (or a neutral bike drawing). */
@Composable
private fun Hero(text: String, photoPath: String?) {
    val c = Ocm.colors
    val photo by produceState<ImageBitmap?>(null, photoPath) {
        value = photoPath?.let { p -> withContext(Dispatchers.IO) { decodeHeroPhoto(p)?.asImageBitmap() } }
    }
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        BasicText(
            text,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            style = OcmType.heroModel.copy(color = c.text.copy(alpha = 0.15f), textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 72.sp),
        )
        val p = photo
        if (p != null) {
            Image(
                p, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.BottomCenter).size(width = 280.dp, height = 160.dp).clip(RoundedCornerShape(24.dp)),
            )
        } else {
            Icon(
                Icons.Rounded.TwoWheeler, contentDescription = null, tint = c.text2,
                modifier = Modifier.align(Alignment.BottomCenter).size(150.dp),
            )
        }
    }
}

@Composable
private fun RideCard(sum: RideSummary, onClick: () -> Unit) {
    val c = Ocm.colors
    val day = when (sum.last.day) {
        RideDay.TODAY -> stringResource(R.string.ui_day_today)
        RideDay.YESTERDAY -> stringResource(R.string.ui_day_yesterday)
        RideDay.EARLIER -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
            .format(Instant.ofEpochMilli(sum.last.endMs).atZone(ZoneId.systemDefault()))
    }
    val km = String.format(Locale.getDefault(), "%.1f", sum.last.km)
    OcmCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_last_ride).uppercase(), style = OcmType.label, color = c.text2)
                Text(
                    "$day · ${stringResource(R.string.ui_km, km)} · ${RideSummary.durationText(sum.last.movingMs)}",
                    style = OcmType.body, color = c.text,
                )
            }
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.ui_this_month).uppercase(), style = OcmType.label, color = c.text2)
                Text(stringResource(R.string.ui_km, sum.monthKm.toString()), style = OcmType.stat, color = c.accent)
            }
        }
    }
}
