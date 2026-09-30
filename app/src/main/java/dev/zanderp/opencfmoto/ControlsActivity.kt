// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.ControlsScreen
import dev.zanderp.opencfmoto.ui.ride.ControlsUi
import dev.zanderp.opencfmoto.ui.ride.PlaceChip

/** Drive Android Auto from the phone: navigate-to, map theme, pad, volume, handlebar mode. */
class ControlsActivity : AppCompatActivity() {

    private var ui by mutableStateOf(
        ControlsUi(emptyList(), MapTheme.AUTO, false, 0, 15, false, "", 0, false),
    )
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ui.padFullscreen) setPadFullscreen(false) else finish()
            }
        })

        // Touchscreen dashes (800MT, 1000 MT-X) run Android Auto's touch UI, which has no focus
        // cursor for a rotary knob to move — so handlebar-button navigation can't work there and only
        // costs the rider their music. Warn, and let them just tap the dash. The live profile is
        // authoritative once connected; otherwise fall back to what the last connect remembered.
        AppSettings.applyToHolder(this)
        val live = BikeProfileHolder.active
        val touchDash: Boolean? = if (live !== BikeProfiles.legacy) {
            DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
            BikeProfileHolder.advertisesScreenTouch
        } else {
            DashMemory.lastDashTouch(this)
        }
        ui = ui.copy(touchDash = touchDash == true)
        // Rotation recreates this screen: stay in pad full screen (spec §9).
        if (savedInstanceState?.getBoolean(KEY_PAD_FULLSCREEN) == true) setPadFullscreen(true, announce = false)

        setContent {
            OcmTheme {
                ControlsScreen(
                    ui = ui,
                    onBack = { finish() },
                    onNavigate = { dest ->
                        if (dest.isBlank()) Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
                        else NavLauncher.navigate(this, dest.trim(), LogBus::log)
                    },
                    onPlace = { slot -> NavLauncher.navigate(this, SavedPlaces.query(this, slot), LogBus::log) },
                    onTheme = ::setMapTheme,
                    onPadFullscreen = { setPadFullscreen(it) },
                    onKey = { AaKeys.key(this, it, mapFirst = true) },
                    onScroll = { AaKeys.scroll(this, it, mapFirst = true) },
                    onVoice = {
                        AaKeys.ensureMic(this, REQ_MIC)
                        AaKeys.key(this, AaInput.KEY_ASSISTANT, mapFirst = true)
                    },
                    onVolume = { v ->
                        ui = ui.copy(volume = v)
                        MediaButtonBridge.setVolume(this, v)
                    },
                    onControlAa = { checked ->
                        ButtonMode.set(this, checked)
                        MediaButtonBridge.instance?.setCaptureActive(checked)
                        LogBus.log("→ handlebar buttons ${if (checked) "control Android Auto" else "control media"}")
                        if (checked) AaKeys.ensureMic(this, REQ_MIC)
                        ui = ui.copy(controlAa = checked)
                    },
                    onButtonMapping = { startActivity(Intent(this, ButtonMappingActivity::class.java)) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PAD_FULLSCREEN, ui.padFullscreen)
    }

    override fun onResume() {
        super.onResume()
        val (now, max) = MediaButtonBridge.volumeLevels(this)
        ui = ui.copy(
            places = (0 until SavedPlaces.COUNT).filter { SavedPlaces.isSet(this, it) }.map { slot ->
                PlaceChip(slot, SavedPlaces.name(this, slot).ifBlank { SavedPlaces.query(this, slot) })
            },
            theme = NightPrefs.theme(this),
            volume = now,
            volumeMax = max,
            controlAa = ButtonMode.isControlAa(this),
            btStatus = BluetoothHelper.status(this).shortLine(),
            mapped = ButtonMap.mappedCount(this),
        )
    }

    /**
     * Driving mode: pad fills the screen, system bars hide, and [FLAG_KEEP_SCREEN_ON] stops the
     * phone from dimming/locking while the pad is up. Back exits fullscreen first.
     */
    private fun setPadFullscreen(on: Boolean, announce: Boolean = true) {
        ui = ui.copy(padFullscreen = on)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            if (announce) Toast.makeText(this, "Pad fullscreen — screen stays on. Press Back to exit.", Toast.LENGTH_SHORT).show()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        ui = ui.copy(theme = theme)
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_MIC = 71
        private const val KEY_PAD_FULLSCREEN = "pad_fullscreen"
    }
}
