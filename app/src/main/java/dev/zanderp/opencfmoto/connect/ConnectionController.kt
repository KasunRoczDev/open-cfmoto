// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.content.Intent
import android.provider.Settings
import dev.zanderp.opencfmoto.AboutActivity
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.RivalClient
import dev.zanderp.opencfmoto.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connect flow formerly inside MainActivity: Connect/Stop, scan, auto-connect, parked resume,
 * Bluetooth trigger, mirror, map hand-off, rival/VPN prompts. Lives for the whole process so the
 * flow survives MainActivity being recreated (Google AA foregrounding does that mid-connect).
 */
class ConnectionController(private val engine: ConnectionEngine) {

    private var port: UiPort? = null
    private val _dialog = MutableStateFlow<AppDialog?>(null)
    val dialog: StateFlow<AppDialog?> = _dialog.asStateFlow()
    private val _bike = MutableStateFlow(BikeInfo(null, false))
    val bike: StateFlow<BikeInfo> = _bike.asStateFlow()

    private var pendingAaStart = false
    private var rivalPromptShown = false
    private var rivalSettingsSentAt: Long? = null
    private var vpnPromptShown = false
    @Volatile private var autoConnectStarted = false
    @Volatile private var autoConnectSkipLogged = false

    private fun log(msg: String) = engine.log(msg)

    // ---------------------------------------------------------------- screen lifecycle

    fun attach(port: UiPort) { this.port = port; refreshBike() }

    fun detach(port: UiPort) { if (this.port === port) this.port = null }

    /** The attached screen's activity, if any (used by the engine for screen-bound Android APIs). */
    fun currentActivity(): android.app.Activity? = port?.activity

    fun refreshBike() { _bike.value = BikeInfo(engine.bikeName(), engine.hasSavedBike()) }

    fun dismissDialog() { _dialog.value = null }

    /** MainActivity.onCreate (after setContent + attach). */
    fun onHostCreated(startGpx: Boolean, resume: Boolean, btTrigger: Boolean, gpxToBike: Boolean = false) {
        engine.applySettingsToHolder()
        if (engine.crashPending()) _dialog.value = AppDialog.CrashRecovery
        // Soft dep check after auto-connect has had time to start — never race Connect.
        engine.postDelayed(2500) { port?.activity?.let { engine.dependencyLaunchCheck(it) } }
        // Opening the home screen must not resume a killed NAV_TO (looks like "Arrived at X").
        val projecting = engine.phase == Phase.STREAMING || engine.phase == Phase.MIRRORING
        if (!startGpx && !projecting) engine.abandonStaleNavigation("app open")
        if (startGpx) engine.postDelayed(0) { beginGpxProjection(gpxToBike) }
        engine.ensureProber()
        engine.requestNotificationPermission(port?.activity)
        log("Ready. Tap Connect to project Android Auto to your dash.")
        try {
            if (!engine.setupSeen()) engine.startSetup(port?.activity) else maybeAutoConnect()
            maybeResumeFromParked(resume)
            maybeStartFromBtTrigger(btTrigger)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            engine.persistSession()
        }
    }

    /** MainActivity.onResume. */
    fun onHostResumed(resumeExtra: Boolean) {
        refreshBike()
        engine.clearWifiOffNoticeIfEnabled()
        // Retry auto-connect on resume: after finishing first-run setup, or once the bike's Wi-Fi
        // comes into range shortly after launch. Guarded so it only ever starts one attempt.
        if (engine.setupSeen()) maybeAutoConnect()
        maybeRetryAfterRivalStop()
        maybeCheckUpdate()
        maybeResumeFromParked(resumeExtra)
    }

    /** MainActivity.onNewIntent. */
    fun onNewIntent(resume: Boolean, btTrigger: Boolean, startGpx: Boolean, gpxToBike: Boolean) {
        maybeResumeFromParked(resume)
        maybeStartFromBtTrigger(btTrigger)
        if (startGpx) beginGpxProjection(gpxToBike)
    }

    /** MainActivity.onDestroy (after detach). */
    fun onHostDestroyed() {
        // The AA→bike chain must OUTLIVE the activity while the AA service runs (recreation
        // mid-hand-off left a black dash). Only tear down for the mirror path / genuine exit.
        if (!engine.aaServiceRunning()) engine.hostDestroyedTeardown()
    }

    // ---------------------------------------------------------------- connect

    /** Connect while idle; Stop while busy / projecting. */
    fun onConnectPressed() {
        val phase = engine.phase
        if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
            stop()
            return
        }
        val act = port?.activity ?: return
        if (engine.dependencyBlocks(act, forScan = false)) {
            log("→ Connect blocked — missing dependencies (see dialog)")
            return
        }
        val saved = engine.savedQr()
        if (saved != null) {
            log("→ Connect: reusing saved bike '${engine.bikeName()}' (no scan needed)")
            engine.clearProjection()
            engine.clearGpxSession()
            engine.ensureLocationPermission(act)
            startAaFlow(saved)
        } else {
            log("→ Connect: no saved bike — scan the dash QR.")
            startAaScan()
        }
    }

    fun stop() = engine.stopEverything()

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    fun startAaScan() {
        val act = port?.activity ?: return
        if (engine.dependencyBlocks(act, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        pendingAaStart = true
        engine.clearProjection()
        engine.ensureLocationPermission(act)
        try {
            port?.launchQrScan()
        } catch (e: Exception) {
            log("scan launch failed ($e)")
            pendingAaStart = false
        }
    }

    /** QR scan result; [raw] null = cancelled. */
    fun onQrScanned(raw: String?) {
        if (raw == null) {
            log("QR scan cancelled")
            return
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            val hint = QrData.parseFailureHint(raw)
            log("QR parse FAILED — missing ssid/pwd?" + (hint?.let { " ($it)" } ?: ""))
            engine.toast(hint ?: engine.string(R.string.main_invalid_qr), long = true)
            return
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}, hotspot=${qr.supportsPhoneHotspot}) " +
                "modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        engine.saveBike(raw, qr)
        refreshBike()
        if (pendingAaStart) {
            pendingAaStart = false
            startAaFlow(qr)
        } else {
            // Mirror path (screen projection already armed): connect straight away.
            engine.applyProfile(qr)
            engine.applyMirrorOrientation(port?.activity)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: qr.ssid)
            engine.joinWifi(qr, gateOnAaSteady = false, activity = port?.activity)
            // Same as startMirrorLink: single-app capture needs the shared app visible.
            port?.moveTaskToBack()
            engine.toast(engine.string(R.string.main_mirror_leave_shared_app), long = true)
        }
    }

    /**
     * Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect and a
     * fresh scan. @param armCommute false for a parked-resume: only a real Connect may start commute nav.
     */
    fun startAaFlow(qr: QrData, armCommute: Boolean = true) {
        try {
            val act = port?.activity
            if (!engine.ensureWifiOrPrompt(act)) return
            // AA Connect needs Nearby + Bluetooth so HUR (START_WIRELESS_PROJECTION) can run when
            // WirelessStartupActivity is a no-op (common on AA 16.4+/17.4+). Mirror/Map unchanged.
            if (!ensureNearbyBtForAaConnect()) return
            engine.armCommute(armCommute)
            // Map / Mirror / stale PXC must die first — half-switches leave framesSent=0.
            engine.tearDownForModeSwitch(clearMap = true, clearMirror = true)
            engine.applyProfile(qr)
            val bikeName = engine.bikeName() ?: qr.ssid
            engine.setPhase(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
            // Parallel startup: AA reaching steady video and the bike Wi-Fi join overlap; BikeLink gates
            // the bike probe until BOTH complete (see RealConnectionEngine.startAaHandoff).
            engine.startAaHandoff()
            // Trigger Google AA from the FOREGROUND activity (BAL-safe) after the :5288 server binds.
            // Uses whichever activity is attached *when it fires* — AA can recreate MainActivity.
            engine.postDelayed(900) {
                try {
                    engine.triggerSelfMode(port?.activity)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if a session
            // is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            engine.postDelayed(4_500) {
                if (aaAlreadyLiveOrTerminal()) {
                    if (engine.aaSessionActive()) log("[AA] skip re-trigger — AA already connected/decoding")
                    return@postDelayed
                }
                try {
                    log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    engine.triggerSelfMode(port?.activity)
                } catch (e: Exception) {
                    log("AA self-mode re-trigger failed: $e")
                }
            }
            // Give up the silent black/QR reconnect loop — surface a clear error after several tries.
            engine.postDelayed(18_000) {
                val p = engine.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (engine.aaSessionActive()) return@postDelayed
                log("[AA] Android Auto never attached — stopping silent retry")
                val detail = if (engine.aaSessionSeen()) {
                    engine.string(R.string.conn_detail_aa_dropped)
                } else {
                    engine.string(R.string.conn_detail_aa_not_started)
                }
                engine.setPhase(Phase.ERROR, detail)
            }
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            engine.joinWifi(qr, gateOnAaSteady = true, activity = act)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            engine.persistSession()
            engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_connect_failed))
        }
    }

    private fun aaAlreadyLiveOrTerminal(): Boolean {
        val p = engine.phase
        return engine.aaSessionActive() ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
            p == Phase.MIRRORING
    }

    /** Block AA Connect until Nearby (API 31+) is granted and Bluetooth is on. */
    private fun ensureNearbyBtForAaConnect(): Boolean {
        if (engine.canAttemptHur()) return true
        val needNearby = engine.needsNearbyPermission()
        if (needNearby) {
            engine.requestNearbyPermission(port?.activity)
            log("→ Nearby/Bluetooth required for AA Connect — waiting for grant + BT on")
        } else {
            log("→ Bluetooth is off — turn it on, then tap Connect again")
        }
        engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_need_bluetooth))
        _dialog.value = AppDialog.NearbyBt(needNearby)
        return false
    }

    /** BAL-safe resume after the service parked AA (notification tap or plain open while parked). */
    private fun maybeResumeFromParked(explicit: Boolean) {
        if (!explicit && !engine.aaParked() && engine.phase != Phase.WAITING_FOR_BIKE) return
        val saved = engine.savedQr() ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && engine.ssidInRange(saved.ssid) == false) return
        log("→ Resuming projection to '${engine.bikeName()}' from the foreground")
        autoConnectStarted = true
        engine.notifyForegroundResuming()
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved, armCommute = false)
    }

    /** Helmet remote / watch ACL → same one-tap Connect as a saved-QR tap. */
    private fun maybeStartFromBtTrigger(trigger: Boolean) {
        if (!trigger) return
        if (engine.aaServiceRunning()) return
        val p = engine.phase
        if (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING) return
        val saved = engine.savedQr() ?: return
        log("→ Bluetooth trigger: starting Connect for '${engine.bikeName()}'")
        autoConnectStarted = true
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved)
    }

    /** Auto-connect on launch — at most once per process; only a real start latches it. */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!engine.autoConnectEnabled()) return
        if (engine.aaServiceRunning()) return
        val p = engine.phase
        if (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING) return
        val saved = engine.savedQr()
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!engine.aaInstalled()) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!engine.wifiEnabled()) {
            // Don't latch — retry once the rider turns Wi‑Fi back on.
            engine.ensureWifiOrPrompt(port?.activity)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }
        val inRange = engine.ssidInRange(saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${engine.bikeName()}' not in range — will retry when its Wi-Fi appears")
            return
        }
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${engine.bikeName()}' ($why). Disable in Setup ▸ Startup.")
        engine.clearProjection()   // bike uses the AA pipeline, not mirror
        engine.ensureLocationPermission(port?.activity)
        engine.postDelayed(1200) {
            try {
                if (port == null) {
                    // Recreated before we fired — let the next instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (!engine.aaServiceRunning() && !engine.phase.busy) startAaFlow(saved)
            } catch (e: Exception) {
                log("auto-connect failed (app stays open): $e")
                engine.persistSession()
                engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_auto_connect_failed))
            }
        }
    }

    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    private fun reconnectSavedBike() {
        val saved = engine.savedQr() ?: return
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved)
    }

    /** Back from the rival's App-info (Force stop) within the window → one retry. */
    private fun maybeRetryAfterRivalStop() {
        val sentAt = rivalSettingsSentAt ?: return
        rivalSettingsSentAt = null
        if (!RivalClient.shouldRetryOnResume(sentAt, engine.elapsedRealtime())) return
        log("→ back from CFMoto app settings — retrying")
        engine.postDelayed(1000) { reconnectSavedBike() }
    }

    // ---------------------------------------------------------------- phase → prompts

    /** Every ConnectionState transition (wired in RealConnectionEngine.install). */
    fun onPhaseChanged(phase: Phase, detail: String) {
        if (phase == Phase.ERROR && detail.isNotBlank() && !isHusConnectNoise(detail)) {
            try { engine.reportError(detail) } catch (_: Exception) {}
        }
        // The bike's link ports are held by the official CFMoto app — offer to close it. Once per error.
        if (phase == Phase.ERROR && detail.contains("CFMoto app", ignoreCase = true)) {
            if (!rivalPromptShown) {
                rivalPromptShown = true
                _dialog.value = AppDialog.Rival(engine.rivalInstalled())
            }
        } else {
            rivalPromptShown = false
            // The error went away before the rider saw it — don't show a stale prompt on return.
            if (_dialog.value is AppDialog.Rival) _dialog.value = null
        }
        // Only a confirmed kill-switch with a live internet VPN.
        if (phase == Phase.ERROR && detail.contains("kill-switch", ignoreCase = true) && engine.vpnActive()) {
            if (!vpnPromptShown) {
                vpnPromptShown = true
                _dialog.value = AppDialog.VpnKillSwitch
            }
        } else {
            vpnPromptShown = false
            if (_dialog.value === AppDialog.VpnKillSwitch) _dialog.value = null
        }
    }

    /** AA 17.4 HUS banner — riders see it often; don't flood telemetry. */
    private fun isHusConnectNoise(detail: String): Boolean {
        if (detail == engine.string(R.string.conn_detail_aa_not_started)) return true
        val d = detail.lowercase()
        return d.contains("head unit server") || d.contains("start head unit")
    }

    // ---------------------------------------------------------------- dialog actions

    fun onRivalCloseAndRetry() {
        _dialog.value = null
        val killed = engine.closeRival()
        log(if (killed) "→ asked Android to close the official CFMoto app; retrying…"
            else "→ couldn't auto-close the official CFMoto app — open its settings to Force stop.")
        engine.postDelayed(1500) { reconnectSavedBike() }
    }

    fun onRivalAppSettings() {
        _dialog.value = null
        if (engine.openRivalAppInfo()) {
            // Coming back after Force stop reconnects on its own (see onHostResumed).
            rivalSettingsSentAt = engine.elapsedRealtime()
        } else {
            engine.toast(engine.string(R.string.main_settings_failed))
        }
    }

    fun onRivalDismiss() {
        _dialog.value = null
        rivalSettingsSentAt = null
    }

    fun onVpnSettings() {
        _dialog.value = null
        if (!engine.openSettingsAction("android.net.vpn.SETTINGS") &&
            !engine.openSettingsAction(Settings.ACTION_SETTINGS)
        ) {
            engine.toast("Open Settings ▸ Network ▸ VPN", long = true)
        }
    }

    fun onNearbySettings(needNearby: Boolean) {
        _dialog.value = null
        val ok = if (needNearby) {
            engine.openSettingsAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, forThisApp = true)
        } else {
            engine.openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (!ok) engine.openSettingsAction(Settings.ACTION_SETTINGS)
    }

    fun onCrashShareLogs() { _dialog.value = null; engine.shareLog(port?.activity) }
    fun onCrashKeepLogs() { _dialog.value = null }
    fun onCrashDismiss() { _dialog.value = null; engine.clearCrash() }

    fun onUpdateDownload(release: UpdateChecker.Release) {
        _dialog.value = null
        if (!engine.openUrl(release.downloadUrl)) engine.toast(engine.string(R.string.main_download_failed))
    }

    fun onUpdateSkip(release: UpdateChecker.Release) { _dialog.value = null; engine.skipUpdate(release.version) }

    fun onMirrorContinue() { _dialog.value = null; startMirrorLink() }

    fun onReportShare(problem: String, model: String, year: String) {
        _dialog.value = null
        engine.shareProblemReport(port?.activity, problem, model, year)
    }

    // ---------------------------------------------------------------- Me tab helpers

    fun shareLog() = engine.shareLog(port?.activity)

    fun reportProblem() { _dialog.value = AppDialog.ReportProblem(engine.bikeName().orEmpty()) }

    fun checkUpdateManual() {
        engine.toast(engine.string(R.string.main_checking_update))
        engine.checkUpdate(manual = true) { release ->
            if (release == null) engine.toast(engine.string(R.string.main_up_to_date))
            else _dialog.value = AppDialog.Update(release)
        }
    }

    private fun maybeCheckUpdate() {
        // Never replace a pending rider prompt (rival / VPN / Bluetooth / crash) with an update offer.
        engine.checkUpdate(manual = false) { release ->
            if (release != null && _dialog.value == null) _dialog.value = AppDialog.Update(release)
        }
    }

    // ---------------------------------------------------------------- mirror / map / search

    /** Mirror: arm screen capture and link as mirror (ignores duplicate taps while frames flow). */
    fun onMirrorPressed() {
        val mirroring = engine.phase == Phase.MIRRORING || engine.phase == Phase.RECONNECTING
        val ms = engine.msSinceLastFrame()
        if (mirroring && ms < 15_000L) {
            log("→ Mirror: already mirroring (last frame ${ms}ms ago) — ignore duplicate tap")
            engine.toast("Already mirroring — wait for the dash, or tap Stop first")
            return
        }
        log("→ Mirror: cast phone screen to dash")
        pendingAaStart = false
        engine.applyMirrorOrientation(port?.activity)
        engine.ensureLocationPermission(port?.activity)
        val intent = engine.createMirrorConsentIntent() ?: return
        port?.launchMirrorConsent(intent)
    }

    /** Screen-capture consent result. */
    fun onMirrorConsent(ok: Boolean, resultCode: Int, data: Intent?) {
        if (!ok || data == null) {
            log("screen-capture consent declined")
            engine.clearMirrorOrientation()
            return
        }
        engine.armMirrorProjection(resultCode, data) { _dialog.value = AppDialog.MirrorReady }
    }

    /** After screen-capture consent: connect using saved bike or scan. */
    private fun startMirrorLink() {
        val saved = engine.savedQr()
        if (saved != null) {
            log("→ Mirror: reusing saved bike '${engine.bikeName()}'")
            // Drop AA / Map / old PXC; keep the MediaProjection token we just armed.
            engine.tearDownForModeSwitch(clearMap = true, clearMirror = false)
            engine.applyProfile(saved)
            engine.applyMirrorOrientation(port?.activity)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: saved.ssid)
            engine.joinWifi(saved, gateOnAaSteady = false, activity = port?.activity)
            // Single-app MediaProjection only emits while the shared app is visible.
            port?.moveTaskToBack()
            engine.toast(engine.string(R.string.main_mirror_leave_shared_app), long = true)
        } else {
            log("→ Mirror: scan the dash QR…")
            port?.launchQrScan()
        }
    }

    /** Map tab "Open map": always the Hub. */
    fun onMapPressed() {
        if (engine.phase != Phase.STREAMING && engine.phase != Phase.MIRRORING) {
            engine.abandonStaleNavigation("map hub")
        }
        log("→ Map: open Hub")
        engine.openMapHub(port?.activity)
    }

    /** Map / GPX session ready — hard switch the dash to our map, or phone preview. */
    fun beginGpxProjection(gpxToBike: Boolean) {
        if (!engine.gpxActive()) {
            log("→ Map: nothing prepared — open Map / GPX first")
            engine.openMapHub(port?.activity)
            return
        }
        pendingAaStart = false
        engine.ensureLocationPermission(port?.activity)
        val p = engine.phase
        val live = p == Phase.STREAMING || p == Phase.MIRRORING || p == Phase.RECONNECTING ||
            p == Phase.PXC_CONNECTING || p == Phase.STARTING_AA || p == Phase.AA_VIDEO_LIVE ||
            p == Phase.JOINING_WIFI
        val wantBike = gpxToBike || live
        val saved = engine.savedQr()
        if (wantBike && saved != null) {
            log("→ Map: ${engine.gpxDescription()} → '${engine.bikeName()}' (hard switch)")
            engine.tearDownForModeSwitch(clearMap = false, clearMirror = true)
            engine.applyProfile(saved)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: saved.ssid)
            engine.joinWifi(saved, gateOnAaSteady = false, activity = port?.activity)
            return
        }
        log("→ Map: phone Dash preview (${engine.gpxDescription()})")
        engine.openGpxPreview(port?.activity)
    }

    /**
     * Search box: our map on the bike → dash search; AA live → Google Maps; else Map hub on phone.
     * @return true when the field should be cleared.
     */
    fun navigateToTyped(text: String): Boolean {
        val dest = text.trim()
        if (dest.isEmpty()) {
            engine.toast(engine.string(R.string.main_type_place))
            return false
        }
        val aaLive = engine.aaPipelineLive()
        val ourMapOnBike = engine.gpxActive() && engine.dashRemoteAvailable()
        if (ourMapOnBike && engine.dashRemoteSubmit(dest)) {
            engine.toast(engine.string(R.string.main_searching_dash, dest))
            return true
        }
        if (aaLive && !engine.gpxActive()) {
            engine.sendDestinationToAndroidAuto(dest)
            return true
        }
        if (!aaLive && engine.dashRemoteSubmit(dest)) {
            engine.toast(engine.string(R.string.main_searching_dash, dest))
            return true
        }
        engine.openMapSearch(port?.activity, dest)
        return false
    }

    /** Live suggestions on the dash while typing (only while OUR map is on the bike). */
    fun onSearchTextChanged(text: String) {
        val q = text.trim()
        if (q.length >= 2 && engine.gpxActive() && engine.dashRemoteAvailable()) engine.dashRemoteSubmit(q)
    }
}
