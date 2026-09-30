// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.ButtonMappingScreen
import dev.zanderp.opencfmoto.ui.ride.MappingDialog
import dev.zanderp.opencfmoto.ui.ride.MappingRow
import dev.zanderp.opencfmoto.ui.ride.MappingUi

/**
 * Remap what each handlebar gesture does, and keep the destinations a button can navigate to. Rows
 * are built from [ButtonGesture]'s entries, and everything is read live by [MediaButtonBridge] on
 * each press — no reconnect needed to try a change.
 */
class ButtonMappingActivity : AppCompatActivity() {

    private var ui by mutableStateOf(MappingUi("", "", emptyList(), "", false, "", false, false, false))
    private var dialog by mutableStateOf<MappingDialog?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Rotation recreates this screen: bring back a Saved places dialog with what was typed (spec §9).
        MappingDialog.Places.restore(savedInstanceState?.getStringArrayList(KEY_PLACES_DRAFT))?.let { dialog = it }
        setContent {
            OcmTheme {
                ButtonMappingScreen(
                    ui = ui,
                    dialog = dialog,
                    onBack = { finish() },
                    onBtSettings = { BluetoothHelper.openBluetoothSettings(this) },
                    onTeachHandlebar = { dialog = MappingDialog.TeachHandlebar },
                    onRow = ::pickAction,
                    onPlaces = {
                        dialog = MappingDialog.Places(
                            (0 until SavedPlaces.COUNT).map { SavedPlaces.name(this, it) },
                            (0 until SavedPlaces.COUNT).map { SavedPlaces.query(this, it) },
                        )
                    },
                    onRemote = ::setRemotePad,
                    onTeachRemote = ::teachRemote,
                    onPreset = { dialog = MappingDialog.PresetPick },
                    onClearPreset = { dialog = MappingDialog.ClearPreset },
                    onOverlay = ::openOverlaySettings,
                    onReset = { dialog = MappingDialog.Reset },
                    onDismiss = ::dismissDialog,
                    onPickAction = ::applyAction,
                    onSavePlaces = ::savePlaces,
                    onEditPlaces = { dialog = it },
                    onVolumePresence = ::setVolumePresence,
                    onRemoteGesture = ::waitForRemoteKey,
                    onPresetChosen = { dialog = MappingDialog.PresetConfirm(it) },
                    onPresetApply = ::applyPreset,
                    onClearConfirmed = ::clearPreset,
                    onResetConfirmed = ::resetAll,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (dialog as? MappingDialog.Places)?.let { outState.putStringArrayList(KEY_PLACES_DRAFT, it.save()) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (RemotePad.teachListener != null) RemotePad.teachListener = null
        super.onDestroy()
    }

    /** Closing any dialog also stops a pending remote-key capture (Back / outside tap / Cancel). */
    private fun dismissDialog() {
        if (dialog is MappingDialog.TeachRemoteWait) RemotePad.teachListener = null
        dialog = null
    }

    // ─────────────────────────── state ───────────────────────────

    private fun refresh() {
        val rows = ButtonGesture.entries.map { MappingRow(it, ButtonMap.get(this, it).displayLabel(this)) }
        val navMapped = ButtonGesture.entries.any { ButtonMap.get(this, it).isNav }
        val active = ButtonClusterPreset.active(this)
        val placeNames = (0 until SavedPlaces.COUNT).filter { SavedPlaces.isSet(this, it) }.map { slot ->
            SavedPlaces.name(this, slot).ifBlank { getString(R.string.ui_mapping_place_n, slot + 1) }
        }
        ui = MappingUi(
            btStatus = BluetoothHelper.status(this).shortLine(),
            presence = "Handlebar sources: ${ButtonPresencePrefs.summarize(this)}",
            rows = rows,
            placesSummary = if (placeNames.isEmpty()) getString(R.string.ui_mapping_places_none) else placeNames.joinToString(" · "),
            remoteOn = RemotePad.enabled(this),
            presetText = if (active != null) "Active: ${active.title}" else "No preset — shipped defaults / your custom map",
            presetActive = active != null,
            // The overlay grant only matters once a button can actually launch Maps.
            overlayNeeded = navMapped && !NavLauncher.canLaunchFromBackground(this),
            resetEnabled = !ButtonMap.isAllDefault(this),
        )
    }

    // ─────────────────────────── gestures ───────────────────────────

    private fun pickAction(gesture: ButtonGesture) {
        val actions = ButtonAction.entries
        dialog = MappingDialog.PickAction(
            gesture, actions.map { it.displayLabel(this) }, actions.indexOf(ButtonMap.get(this, gesture)),
        )
    }

    private fun applyAction(gesture: ButtonGesture, which: Int) {
        val action = ButtonAction.entries[which]
        ButtonMap.set(this, gesture, action)
        LogBus.log("→ button mapping: ${gesture.label} = ${action.label}")
        if (action.isNav && !SavedPlaces.isSet(this, action.navSlot)) {
            Toast.makeText(this, "Set that place's address in Saved places", Toast.LENGTH_SHORT).show()
        }
        dialog = null
        refresh()
    }

    private fun savePlaces(names: List<String>, queries: List<String>) {
        for (slot in 0 until SavedPlaces.COUNT) {
            SavedPlaces.set(this, slot, names.getOrNull(slot) ?: "", queries.getOrNull(slot) ?: "")
        }
        dialog = null
        refresh()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(NavLauncher.overlayPermissionIntent(this))
        } catch (e: Exception) {
            LogBus.log("couldn't open the overlay permission screen ($e)")
            Toast.makeText(this, "Open Settings → Apps → OpenCfMoto → Display over other apps", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────── remote pad ───────────────────────────

    private fun setRemotePad(on: Boolean) {
        RemotePad.setEnabled(this, on)
        LogBus.log("→ remote pad ${if (on) "on" else "off"}")
        refresh()
        Toast.makeText(
            this,
            if (on) "Remote pad on — HID keys map to handlebar gestures" else "Remote pad off",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun teachRemote() {
        if (!RemotePad.enabled(this)) {
            Toast.makeText(this, "Turn remote pad on first", Toast.LENGTH_SHORT).show()
            return
        }
        dialog = MappingDialog.TeachRemotePick
    }

    private fun waitForRemoteKey(gesture: ButtonGesture) {
        dialog = MappingDialog.TeachRemoteWait(gesture)
        RemotePad.teachListener = listen@{ ev ->
            if (ev.device?.isVirtual == true) return@listen false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                RemotePad.teach(this, ev.keyCode, gesture)
                RemotePad.teachListener = null
                dialog = null
                Toast.makeText(
                    this,
                    getString(R.string.buttons_teach_remote_ok, KeyEvent.keyCodeToString(ev.keyCode), gesture.label),
                    Toast.LENGTH_SHORT,
                ).show()
                LogBus.log("→ remote pad teach: ${KeyEvent.keyCodeToString(ev.keyCode)} → ${gesture.label}")
            }
            true
        }
    }

    // ─────────────────────────── handlebar teach ───────────────────────────

    /** 1 = volume rocker present, 0 = absent, -1 = reset presence (same effects as the old dialog). */
    private fun setVolumePresence(choice: Int) {
        when (choice) {
            1 -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.PRESENT)
                LogBus.log("→ teach handlebar: volume rocker PRESENT")
                Toast.makeText(this, "▲/▼ marked present — volume pin stays on", Toast.LENGTH_SHORT).show()
            }
            0 -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.ABSENT)
                LogBus.log("→ teach handlebar: volume rocker ABSENT")
                Toast.makeText(this, "▲/▼ absent — phone volume no longer pinned", Toast.LENGTH_SHORT).show()
            }
            else -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.UNKNOWN)
                ButtonPresencePrefs.setTrackKeys(this, ButtonPresence.UNKNOWN)
                LogBus.log("→ teach handlebar: presence reset to UNKNOWN")
                Toast.makeText(this, "Presence reset — will auto-probe again", Toast.LENGTH_SHORT).show()
            }
        }
        MediaButtonBridge.instance?.refreshVolumePresencePolicy()
        dialog = null
        refresh()
    }

    // ─────────────────────────── presets / reset ───────────────────────────

    private fun applyPreset(preset: ButtonClusterPreset) {
        preset.apply(this)
        LogBus.log("→ button mapping preset: ${preset.title}")
        dialog = null
        refresh()
        // Cluster only picks the bar map + turns handlebar→AA on. Touch stays as-is
        // (800MT etc. keep touch + bars together — never force Disable touchscreen).
        Toast.makeText(this, "Applied: ${preset.title} (handlebar → AA on)", Toast.LENGTH_SHORT).show()
    }

    private fun clearPreset() {
        ButtonClusterPreset.clear(this)
        LogBus.log("→ button mapping preset cleared")
        dialog = null
        refresh()
        Toast.makeText(this, "Preset cleared", Toast.LENGTH_SHORT).show()
    }

    private fun resetAll() {
        ButtonMap.resetAll(this)
        LogBus.log("→ button mapping reset to defaults")
        dialog = null
        refresh()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_PLACES_DRAFT = "places_draft"
    }
}

/** True for the navigate-to-a-saved-place actions. */
private val ButtonAction.isNav: Boolean
    get() = this == ButtonAction.NAV_1 || this == ButtonAction.NAV_2 || this == ButtonAction.NAV_3

/** Which [SavedPlaces] slot a nav action points at. */
private val ButtonAction.navSlot: Int
    get() = when (this) {
        ButtonAction.NAV_1 -> 0
        ButtonAction.NAV_2 -> 1
        else -> 2
    }
