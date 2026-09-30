// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType

/**
 * On-screen Android Auto pad: knob (⟲/⟳), d-pad + OK, then Back · Home · Voice. [fill] stretches the
 * rows to the available height (Controls full screen); otherwise keys are 64 dp tall.
 */
@Composable
fun AaPad(
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_prev)) { onScroll(-1) }
            PadKey(stringResource(R.string.controls_str)) { onKey(AaInput.KEY_UP) }
            PadKey(stringResource(R.string.controls_next)) { onScroll(+1) }
        }
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_str_2)) { onKey(AaInput.KEY_LEFT) }
            PadKey("OK", accent = true) { onKey(AaInput.KEY_ENTER) }
            PadKey(stringResource(R.string.controls_str_3)) { onKey(AaInput.KEY_RIGHT) }
        }
        PadRow(fill) {
            Spacer(Modifier.weight(1f))
            PadKey(stringResource(R.string.controls_str_4)) { onKey(AaInput.KEY_DOWN) }
            Spacer(Modifier.weight(1f))
        }
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_back)) { onKey(AaInput.KEY_BACK) }
            PadKey(stringResource(R.string.controls_home)) { onKey(AaInput.KEY_HOME) }
            PadKey(stringResource(R.string.controls_voice)) { onVoice() }
        }
    }
}

@Composable
private fun ColumnScope.PadRow(fill: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        (if (fill) Modifier.weight(1f) else Modifier.height(64.dp)).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.PadKey(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Ocm.colors
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
            .background(if (accent) c.accent else c.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcmType.title, color = if (accent) c.onAccent else c.text, maxLines = 1)
    }
}
