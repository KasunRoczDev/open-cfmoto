// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class StatusKind { IDLE, BUSY, LIVE, ERROR }

private val TileShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(20.dp)

/** Screen background: soft vertical gradient + scrolling column with the standard gutter. */
@Composable
fun OcmScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Ocm.colors
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgTop, 0.45f to c.bg, 1f to c.bg)),
    ) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Ocm.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) c.error else c.accent,
            contentColor = if (danger) c.onError else c.onAccent,
        ),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(text, style = OcmType.title, maxLines = 1)
    }
}

@Composable
fun StatusChip(text: String, kind: StatusKind) {
    val c = Ocm.colors
    val color = when (kind) {
        StatusKind.IDLE -> c.idle
        StatusKind.BUSY -> c.busy
        StatusKind.LIVE -> c.live
        StatusKind.ERROR -> c.error
    }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = OcmType.label.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = color)
    }
}

@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = Ocm.colors
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(TileShape)
            .background(c.surface)
            .border(1.dp, c.outline, TileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.heightIn(min = 6.dp))
        Text(label, style = OcmType.body, color = c.text, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun OcmCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ocm.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.outline, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = OcmType.label, color = Ocm.colors.text2, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Ocm.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = OcmType.title, color = c.text)
            if (subtitle != null) Text(subtitle, style = OcmType.body, color = c.text2)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text2)
    }
}
