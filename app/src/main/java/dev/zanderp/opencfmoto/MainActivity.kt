// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import dev.zanderp.opencfmoto.connect.AppGraph
import dev.zanderp.opencfmoto.connect.UiPort
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.shell.AppShell

/**
 * Compose host. The connect flow lives in [dev.zanderp.opencfmoto.connect.ConnectionController]
 * (AppGraph.connection); this activity forwards lifecycle + intents and provides the [UiPort].
 */
class MainActivity : AppCompatActivity() {

    private val controller get() = AppGraph.connection
    private val searchFocusTick = mutableIntStateOf(0)
    private val openMaintenanceTick = mutableIntStateOf(0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        controller.onQrScanned(if (result.resultCode == RESULT_OK) raw else null)
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onMirrorConsent(result.resultCode == RESULT_OK, result.resultCode, result.data)
    }

    private val port = object : UiPort {
        override val activity: Activity get() = this@MainActivity
        override fun launchQrScan() { scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java)) }
        override fun launchMirrorConsent(intent: Intent) { projectionLauncher.launch(intent) }
        override fun moveTaskToBack() { this@MainActivity.moveTaskToBack(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OcmTheme { AppShell(controller, searchFocusTick.intValue, openMaintenanceTick.intValue) } }
        controller.attach(port)
        installTypeOnPhoneHandler()
        takeOpenMaintenance(intent)
        val flags = takeIntentFlags(intent)
        controller.onHostCreated(
            startGpx = flags.startGpx, resume = flags.resume, btTrigger = flags.btTrigger, gpxToBike = flags.gpxToBike,
        )
    }

    override fun onPause() {
        CrashGuard.persistSession(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeOpenMaintenance(intent)
        val f = takeIntentFlags(intent)
        controller.onNewIntent(resume = f.resume, btTrigger = f.btTrigger, startGpx = f.startGpx, gpxToBike = f.gpxToBike)
    }

    override fun onResume() {
        super.onResume()
        controller.attach(port)
        controller.onHostResumed(resumeExtra = takeIntentFlags(intent).resume)
    }

    override fun onDestroy() {
        DashRemote.setTypeOnPhoneHandler(null)
        controller.detach(port)
        controller.onHostDestroyed()
        super.onDestroy()
    }

    private data class IntentFlags(val resume: Boolean, val btTrigger: Boolean, val startGpx: Boolean, val gpxToBike: Boolean)

    /** Read the one-shot extras and remove them so a later onResume doesn't re-fire them. */
    private fun takeIntentFlags(i: Intent?): IntentFlags {
        if (i == null) return IntentFlags(false, false, false, false)
        val f = IntentFlags(
            resume = i.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false),
            btTrigger = i.getBooleanExtra(EXTRA_BT_TRIGGER, false),
            startGpx = i.getBooleanExtra(EXTRA_START_GPX, false),
            gpxToBike = i.getBooleanExtra(EXTRA_GPX_TO_BIKE, false),
        )
        i.removeExtra(AndroidAutoService.EXTRA_RESUME)
        i.removeExtra(EXTRA_BT_TRIGGER)
        i.removeExtra(EXTRA_START_GPX)
        i.removeExtra(EXTRA_GPX_TO_BIKE)
        return f
    }

    /** Maintenance notification tap → open Me ▸ Maintenance (one-shot, like the other extras). */
    private fun takeOpenMaintenance(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_MAINTENANCE, false) != true) return
        i.removeExtra(EXTRA_OPEN_MAINTENANCE)
        openMaintenanceTick.intValue += 1
    }

    /** Bike Search tap → bring the app forward, open the Map tab and focus the search field. */
    private fun installTypeOnPhoneHandler() {
        DashRemote.setTypeOnPhoneHandler {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                } catch (_: Exception) {}
                searchFocusTick.intValue += 1
                try {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(40)
                } catch (_: Exception) {}
                Toast.makeText(this, "Bike search open — type here. Results appear on the dash.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_START_GPX = "start_gpx"
        const val EXTRA_BT_TRIGGER = "bt_trigger"
        const val EXTRA_OPEN_MAINTENANCE = "open_maintenance"
        /** When set with [EXTRA_START_GPX], join the bike even if not already live. */
        const val EXTRA_GPX_TO_BIKE = "gpx_to_bike"
    }
}
