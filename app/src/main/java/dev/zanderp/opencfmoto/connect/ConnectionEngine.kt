// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.UpdateChecker

/**
 * Everything the connect flow does to Android / the existing engine (Wi-Fi, AA service, prober…).
 * [RealConnectionEngine] holds the bodies moved from the old MainActivity; tests use a fake.
 */
interface ConnectionEngine {
    // --- reads ---
    val phase: Phase
    fun savedQr(): QrData?
    fun bikeName(): String?
    fun hasSavedBike(): Boolean
    fun aaServiceRunning(): Boolean
    fun aaParked(): Boolean
    fun aaSessionActive(): Boolean
    fun aaSessionSeen(): Boolean
    fun autoConnectEnabled(): Boolean
    fun aaInstalled(): Boolean
    fun wifiEnabled(): Boolean
    fun ssidInRange(ssid: String): Boolean?
    fun canAttemptHur(): Boolean
    fun needsNearbyPermission(): Boolean
    fun vpnActive(): Boolean
    fun setupSeen(): Boolean
    fun elapsedRealtime(): Long
    fun gpxActive(): Boolean
    fun gpxDescription(): String
    fun aaPipelineLive(): Boolean
    fun dashRemoteAvailable(): Boolean
    fun msSinceLastFrame(): Long
    fun crashPending(): Boolean
    fun string(resId: Int, vararg args: Any): String

    // --- plumbing ---
    fun setPhase(phase: Phase, detail: String? = null)
    fun log(msg: String)
    fun toast(text: String, long: Boolean = false)
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun reportError(detail: String)
    fun persistSession()

    // --- startup ---
    fun applySettingsToHolder()
    fun ensureProber()
    fun requestNotificationPermission(activity: Activity?)
    fun dependencyLaunchCheck(activity: Activity)
    fun clearWifiOffNoticeIfEnabled()

    // --- connect flow ---
    fun ensureWifiOrPrompt(activity: Activity?): Boolean
    fun dependencyBlocks(activity: Activity, forScan: Boolean): Boolean
    fun ensureLocationPermission(activity: Activity?)
    fun requestNearbyPermission(activity: Activity?)
    fun armCommute(arm: Boolean)
    fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean)
    fun applyProfile(qr: QrData)
    fun startAaHandoff()
    fun triggerSelfMode(activity: Activity?)
    fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?)
    fun stopEverything()
    fun clearProjection()
    fun clearGpxSession()
    fun abandonStaleNavigation(reason: String)
    fun notifyForegroundResuming()
    fun saveBike(raw: String, qr: QrData)

    // --- mirror / map ---
    fun applyMirrorOrientation(activity: Activity?)
    fun clearMirrorOrientation()
    fun createMirrorConsentIntent(): Intent?
    fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit)
    fun openMapHub(activity: Activity?)
    fun openGpxPreview(activity: Activity?)
    fun openMapSearch(activity: Activity?, query: String)
    fun dashRemoteSubmit(query: String): Boolean
    fun sendDestinationToAndroidAuto(query: String)

    // --- dialogs' actions ---
    fun closeRival(): Boolean
    fun openRivalAppInfo(): Boolean
    fun rivalInstalled(): Boolean
    fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit)
    fun skipUpdate(version: String)
    fun openUrl(url: String): Boolean
    fun openSettingsAction(action: String, forThisApp: Boolean = false): Boolean
    fun shareLog(activity: Activity?)
    fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String)
    fun clearCrash()
    fun startSetup(activity: Activity?)
    fun hostDestroyedTeardown()
}
