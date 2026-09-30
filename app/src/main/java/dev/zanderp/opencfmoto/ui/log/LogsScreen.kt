// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType
import kotlinx.coroutines.delay

private const val TAIL_CHARS = 64 * 1024

@Composable
fun LogsScreen(onShare: () -> Unit, onClear: () -> Unit) {
    val c = Ocm.colors
    var text by remember { mutableStateOf(LogBus.tail(TAIL_CHARS)) }
    val scroll = rememberScrollState()
    // Throttled: re-read the tail at most every 300 ms, and keep updating while lines stream in
    // (a debounce would freeze the view during a connect burst).
    LaunchedEffect(Unit) {
        LogBus.revision.collect {   // StateFlow is conflated: a slow collector sees only the latest
            text = LogBus.tail(TAIL_CHARS)
            delay(300)
        }
    }
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().background(c.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_me_logs), style = OcmType.screenTitle, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.ui_logs_share)) }
            OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.ui_logs_clear)) }
        }
        SelectionContainer(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp),
        ) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.text, modifier = Modifier.verticalScroll(scroll))
        }
    }
}
