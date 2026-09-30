// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.ButtonClusterPreset
import dev.zanderp.opencfmoto.ButtonGesture
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SegmentedChoice
import dev.zanderp.opencfmoto.ui.design.SettingRow

data class MappingRow(val gesture: ButtonGesture, val action: String)

data class MappingUi(
    val btStatus: String,
    val presence: String,
    val rows: List<MappingRow>,
    val placesSummary: String,
    val remoteOn: Boolean,
    val presetText: String,
    val presetActive: Boolean,
    val overlayNeeded: Boolean,
    val resetEnabled: Boolean,
)

/** At most one dialog at a time; the Activity owns it so actions stay next to the prefs they write. */
sealed interface MappingDialog {
    data class PickAction(val gesture: ButtonGesture, val labels: List<String>, val current: Int) : MappingDialog
    data class Places(val names: List<String>, val queries: List<String>) : MappingDialog
    data object TeachHandlebar : MappingDialog
    data object TeachRemotePick : MappingDialog
    data class TeachRemoteWait(val gesture: ButtonGesture) : MappingDialog
    data object PresetPick : MappingDialog
    data class PresetConfirm(val preset: ButtonClusterPreset) : MappingDialog
    data object ClearPreset : MappingDialog
    data object Reset : MappingDialog
}

@Composable
fun ButtonMappingScreen(
    ui: MappingUi,
    dialog: MappingDialog?,
    onBack: () -> Unit,
    onBtSettings: () -> Unit,
    onTeachHandlebar: () -> Unit,
    onRow: (ButtonGesture) -> Unit,
    onPlaces: () -> Unit,
    onRemote: (Boolean) -> Unit,
    onTeachRemote: () -> Unit,
    onPreset: () -> Unit,
    onClearPreset: () -> Unit,
    onOverlay: () -> Unit,
    onReset: () -> Unit,
    // dialog results
    onDismiss: () -> Unit,
    onPickAction: (ButtonGesture, Int) -> Unit,
    onSavePlaces: (List<String>, List<String>) -> Unit,
    onVolumePresence: (Int) -> Unit,          // 1 present, 0 absent, -1 reset
    onRemoteGesture: (ButtonGesture) -> Unit,
    onPresetChosen: (ButtonClusterPreset) -> Unit,
    onPresetApply: (ButtonClusterPreset) -> Unit,
    onClearConfirmed: () -> Unit,
    onResetConfirmed: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        OcmTopBar(stringResource(R.string.buttons_button_mapping), onBack)
        // 1. Bluetooth
        OcmCard {
            Text(ui.btStatus, style = OcmType.title, color = c.text, modifier = Modifier.fillMaxWidth().clickable(onClick = onBtSettings).heightIn(min = 48.dp))
            Text(ui.presence, style = OcmType.body, color = c.text2)
            ActionButton(stringResource(R.string.buttons_teach_handlebar), onTeachHandlebar)
        }
        // 2. What each press does
        OcmCard {
            Text(stringResource(R.string.buttons_gestures), style = OcmType.title, color = c.text)
            for (r in ui.rows) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onRow(r.gesture) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.gesture.label, style = OcmType.title, color = c.text)
                        Text(r.gesture.hint, style = OcmType.body, color = c.text2)
                    }
                    Spacer(Modifier.width(10.dp))
                    // Capped so a long action ("Navigate to saved place 1 (not set yet)") never squeezes the gesture name.
                    Text(r.action, style = OcmType.body, color = c.accent, maxLines = 3, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 150.dp))
                }
            }
        }
        // 3. Saved places
        OcmCard {
            SettingRow(stringResource(R.string.buttons_saved_places), ui.placesSummary, onClick = onPlaces)
        }
        // 4. Helmet / remote pad
        OcmCard {
            Text(stringResource(R.string.buttons_remote_pad), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.buttons_remote_pad_desc), style = OcmType.body, color = c.text2)
            SegmentedChoice(
                listOf(stringResource(R.string.gpx_on), stringResource(R.string.res_off)),
                if (ui.remoteOn) 0 else 1,
                { onRemote(it == 0) },
            )
            ActionButton(stringResource(R.string.buttons_teach_remote), onTeachRemote)
        }
        // 5. Dash preset
        OcmCard {
            Text(stringResource(R.string.buttons_your_left_switch_cluster), style = OcmType.title, color = c.text)
            Text(ui.presetText, style = OcmType.body, color = c.text2)
            Row {
                ActionButton(stringResource(R.string.buttons_apply_preset), onPreset, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClearPreset, enabled = ui.presetActive, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.buttons_clear_preset))
                }
            }
        }
        // 6. Overlay permission
        if (ui.overlayNeeded) {
            OcmCard {
                Text(stringResource(R.string.buttons_allow_navigate_from_handlebars), style = OcmType.title, color = c.text)
                Text(stringResource(R.string.buttons_starting_navigation_from_a_handlebar_button_whil), style = OcmType.body, color = c.text2)
                ActionButton(stringResource(R.string.buttons_enable_display_over_other_apps), onOverlay)
            }
        }
        // 7. Reset
        OutlinedButton(onClick = onReset, enabled = ui.resetEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.buttons_reset_to_defaults))
        }
        Spacer(Modifier.navigationBarsPadding())
    }

    when (dialog) {
        null -> Unit
        is MappingDialog.PickAction -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.gesture.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    dialog.labels.forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(i == dialog.current) { onPickAction(dialog.gesture, i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(i == dialog.current, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MappingDialog.Places -> {
            val names = rememberSaveable(dialog, saver = TextListSaver) { dialog.names.toMutableStateList() }
            val queries = rememberSaveable(dialog, saver = TextListSaver) { dialog.queries.toMutableStateList() }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.buttons_saved_places)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.buttons_map_a_gesture_to_navigate_to_saved_place_and_a_p), style = OcmType.body)
                        for (i in names.indices) {
                            Text(stringResource(R.string.ui_mapping_place_n, i + 1), style = OcmType.label, modifier = Modifier.padding(top = 12.dp))
                            OutlinedTextField(names[i], { names[i] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.row_saved_place_name_e_g_home)) })
                            OutlinedTextField(queries[i], { queries[i] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.row_saved_place_address_or_place)) })
                        }
                    }
                },
                confirmButton = { TextButton({ onSavePlaces(names.toList(), queries.toList()) }) { Text(stringResource(R.string.ui_save)) } },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        MappingDialog.TeachHandlebar -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.buttons_teach_title)) },
            text = { Text(stringResource(R.string.buttons_teach_message)) },
            confirmButton = { TextButton({ onVolumePresence(1) }) { Text(stringResource(R.string.buttons_teach_volume_present)) } },
            dismissButton = {
                Row {
                    TextButton({ onVolumePresence(-1) }) { Text(stringResource(R.string.buttons_teach_reset)) }
                    TextButton({ onVolumePresence(0) }) { Text(stringResource(R.string.buttons_teach_volume_absent)) }
                }
            },
        )
        MappingDialog.TeachRemotePick -> ListDialog(
            stringResource(R.string.buttons_teach_remote_pick), ButtonGesture.entries.map { it.label }, onDismiss,
        ) { onRemoteGesture(ButtonGesture.entries[it]) }
        is MappingDialog.TeachRemoteWait -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.buttons_teach_remote_wait)) },
            text = { Text(dialog.gesture.label) },
            confirmButton = {},
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        MappingDialog.PresetPick -> ListDialog(
            "Left switch cluster", ButtonClusterPreset.entries.map { it.title }, onDismiss,
        ) { onPresetChosen(ButtonClusterPreset.entries[it]) }
        is MappingDialog.PresetConfirm -> ConfirmDialog(
            dialog.preset.title,
            dialog.preset.summary + "\n\nReplace the current mapping for this bike? Saved places are kept.",
            "Apply", onDismiss,
        ) { onPresetApply(dialog.preset) }
        MappingDialog.ClearPreset -> ConfirmDialog(
            "Clear cluster preset?",
            "Removes the active cluster tag and restores shipped gesture defaults. " +
                "Saved places stay. Some bikes work fine with no preset — just tweak rows below if needed.",
            "Clear", onDismiss, onClearConfirmed,
        )
        MappingDialog.Reset -> ConfirmDialog(
            "Reset to defaults?",
            "Every gesture goes back to the shipped defaults: ◀/▶ = knob, " +
                "★ = Select, ★ hold = Home, ◀◀/▶▶ = D-pad ←→, ★★ = Back.\n\n" +
                "Does not change which cluster preset is active. Use Clear preset to drop the tag.",
            "Reset", onDismiss, onResetConfirmed,
        )
    }
}

/** Keeps typed place names/addresses across recomposition and rotation. */
private val TextListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val c = Ocm.colors
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
    ) { Text(text) }
}

@Composable
private fun ListDialog(title: String, items: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, label ->
                    Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(i) }.padding(vertical = 16.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
