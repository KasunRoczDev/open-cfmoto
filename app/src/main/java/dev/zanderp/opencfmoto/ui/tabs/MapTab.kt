// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import kotlinx.coroutines.delay

/**
 * @param searchFocusTick increments when the bike asks to "type on phone" → focus the field.
 * @param onSearch returns true when the field should be cleared.
 */
@Composable
fun MapTab(
    searchFocusTick: Int,
    openMapEnabled: Boolean,
    onOpenMap: () -> Unit,
    onSearch: (String) -> Boolean,
    onSearchTextChanged: (String) -> Unit,
) {
    val c = Ocm.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchFocusTick) { if (searchFocusTick > 0) focus.requestFocus() }
    LaunchedEffect(text) { delay(350); onSearchTextChanged(text) }   // live dash suggestions (debounced)
    OcmScreen {
        Text(stringResource(R.string.ui_tab_map), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            Text(stringResource(R.string.ui_map_title), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_map_desc), style = OcmType.body, color = c.text2)
            PrimaryButton(stringResource(R.string.ui_map_open), Icons.Rounded.Map, onOpenMap, enabled = openMapEnabled)
        }
        SectionLabel(stringResource(R.string.ui_search_label))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.ui_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (onSearch(text)) text = "" }),
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { if (onSearch(text)) text = "" }, modifier = Modifier.heightIn(min = 56.dp)) {
                androidx.compose.material3.Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.ui_search_go))
            }
        }
    }
}
