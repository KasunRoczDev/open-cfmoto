// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmSpace
import dev.zanderp.opencfmoto.ui.design.OcmType
import kotlinx.coroutines.delay

/**
 * Destination entry over the Ride screen — replaces the old Map tab's search box. Opened by the Ride
 * header's search button, or by the bike's "type on phone" request ([focusTick] bumps → refocus).
 * Results go to the dash / Android Auto via [onSearch] (true → clear the field and close).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSearchSheet(
    focusTick: Int,
    onSearch: (String) -> Boolean,
    onSearchTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ocm.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusTick) {
        delay(150)   // let the sheet attach before asking for focus
        focus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(text) { delay(350); onSearchTextChanged(text) }   // live dash suggestions (debounced)
    val submit = {
        if (onSearch(text)) {
            text = ""
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .padding(start = OcmSpace.m, end = OcmSpace.m, bottom = OcmSpace.l),
            verticalArrangement = Arrangement.spacedBy(OcmSpace.s),
        ) {
            Text(
                stringResource(R.string.ui_search_label), style = OcmType.title, color = c.text,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.ui_search_sheet_desc), style = OcmType.body, color = c.text2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OcmSpace.s)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.ui_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                Button(
                    onClick = submit,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.ui_search_go))
                }
            }
            Text(stringResource(R.string.ui_search_sheet_safety), style = OcmType.caption, color = c.text2)
        }
    }
}
