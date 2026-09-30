// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.zanderp.opencfmoto.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Android side of the connect flow: bodies moved verbatim from the old MainActivity. */
class RealConnectionEngine(private val app: Application) : ConnectionEngine {
    private val ctx: Context get() = app
    private val main = Handler(Looper.getMainLooper())

    /** The attached screen, if any (set in [install]). */
    private var screen: () -> Activity? = { null }

    /** Orientation lock belongs to the screen; nothing to restore when none is attached. */
    private fun clearMirrorLock() { screen()?.let { MirrorOrientationLock.clear(it) } }

    /** Wire the controller to ConnectionState (formerly MainActivity's listener → renderStatus). */
    fun install(controller: ConnectionController) {
        screen = { controller.currentActivity() }
        ConnectionState.listener = { phase, detail -> main.post { controller.onPhaseChanged(phase, detail) } }
    }

    private fun prober(): EasyConnProber =
        BikeLink.prober ?: EasyConnProber(ctx, LogBus::log).also { BikeLink.prober = it }

    private fun noScreen(what: String) = LogBus.log("[ui] $what skipped — no screen")

    /** Chooser from the attached screen when there is one, else as a new task from the app context. */
    private fun startChooser(activity: Activity?, chooser: Intent) {
        if (activity != null) activity.startActivity(chooser)
        else ctx.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------ reads
    override val phase: Phase get() = ConnectionState.phase
    override fun savedQr() = BikeMemory.lastQr(ctx)
    override fun bikeName() = BikeMemory.lastBikeName(ctx)
    override fun hasSavedBike() = BikeMemory.hasSaved(ctx)
    override fun aaServiceRunning() = AndroidAutoService.isRunning
    override fun aaParked() = AndroidAutoService.isParked
    override fun aaSessionActive() = AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding
    override fun aaSessionSeen() = AaVideoBridge.aaSessionSeen
    override fun autoConnectEnabled() = AppSettings.autoConnect(ctx)
    override fun aaInstalled() = SetupHelper.isAndroidAutoInstalled(ctx)
    override fun wifiEnabled() = WifiGate.isWifiEnabled(ctx)
    override fun ssidInRange(ssid: String) = BikeWifi.isSsidInRange(ctx, ssid)
    override fun canAttemptHur() = dev.zanderp.opencfmoto.aa.AaSelfMode.canAttemptHur(ctx)
    override fun needsNearbyPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    override fun vpnActive() = BikeWifi.isVpnActive(ctx)
    override fun setupSeen() = SetupActivity.hasSeen(ctx)
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    override fun gpxActive() = GpxSession.active
    override fun gpxDescription() = "${GpxSession.mode} '${GpxSession.trackName}'"
    override fun aaPipelineLive() = AaVideoBridge.pipeline != null
    override fun dashRemoteAvailable() = DashRemote.isAvailable
    override fun msSinceLastFrame() = BikeLink.prober?.msSinceLastFrame() ?: Long.MAX_VALUE
    override fun crashPending() = CrashGuard.pendingCrashText(ctx) != null
    override fun string(resId: Int, vararg args: Any) = ctx.getString(resId, *args)

    // ------------------------------------------------------------ plumbing
    override fun setPhase(phase: Phase, detail: String?) = ConnectionState.set(phase, detail)
    override fun log(msg: String) = LogBus.log(msg)
    override fun toast(text: String, long: Boolean) {
        main.post { Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
    override fun reportError(detail: String) = AnonymousTelemetry.reportError(ctx, detail)
    override fun persistSession() = CrashGuard.persistSession(ctx)

    // ------------------------------------------------------------ startup
    override fun applySettingsToHolder() = AppSettings.applyToHolder(ctx)
    override fun ensureProber() { prober() }
    override fun requestNotificationPermission(activity: Activity?) {
        // Android 13+: the mediaProjection FGS notification needs it (MainActivity.onCreate, lines 520-529).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val a = activity ?: return noScreen("notification permission")
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }
    override fun dependencyLaunchCheck(activity: Activity) { DependencyPrompt.showOnLaunchIfNeeded(activity) }
    override fun clearWifiOffNoticeIfEnabled() { if (WifiGate.isWifiEnabled(ctx)) WifiGate.cancelNotification(ctx) }

    // ------------------------------------------------------------ connect flow
    override fun ensureWifiOrPrompt(activity: Activity?): Boolean =
        if (activity != null) WifiGate.ensureEnabledOrPrompt(activity) else WifiGate.ensureEnabledOrNotify(ctx)

    override fun dependencyBlocks(activity: Activity, forScan: Boolean) = DependencyPrompt.showForConnect(activity, forScan)

    override fun ensureLocationPermission(activity: Activity?) {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier (MainActivity 1372-1381).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val a = activity ?: return noScreen("location permission")
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun requestNearbyPermission(activity: Activity?) {
        val a = activity ?: return noScreen("Nearby permission")
        ActivityCompat.requestPermissions(
            a, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQ_BT_FOR_AA,
        )
    }

    override fun armCommute(arm: Boolean) { if (arm) CommuteNav.armForConnect(ctx) else CommuteNav.disarm() }

    override fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) {
        // MainActivity lines 158-183, verbatim with the substitution rules. The `::prober.isInitialized`
        // branch is dropped: the only prober is now BikeLink.prober.
        LogBus.log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(ctx) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        if (clearMirror) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(ctx) } catch (_: Exception) {}
            clearMirrorLock()
        }
        if (clearMap) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        BikeLink.beginHandoff(ctx)
    }

    override fun applyProfile(qr: QrData) {
        AppSettings.applyToHolder(ctx)
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, ctx)
        DashMemory.setLastDashTouch(ctx, BikeProfileHolder.advertisesScreenTouch)
        val userOverride = VideoPrefs.resolutionOverride(ctx, BikeProfileHolder.active)
        // In AUTO mode, if a previous session revealed this dash is a different orientation than the
        // profile assumes, flip AA to match. Learned from the dash's REQ_CONFIG_CAPTURE (see DashMemory).
        val autoGeo = if (userOverride == null) DashMemory.specFor(ctx, qr.ssid, BikeProfileHolder.active) else null
        BikeProfileHolder.aaVideoOverride = userOverride ?: autoGeo
        val spec = BikeProfileHolder.aaVideo
        BikeProfileHolder.stripSession = MetricsStripPrefs.snapshot(ctx)
        BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(ctx, spec, qr.ssid)
        val aspectMode = VideoPrefs.matchAspectMode(ctx)
        val aspectNote = BikeProfileHolder.aaContentMargins.let { m ->
            when {
                m.any -> " [match-aspect ${aspectMode.name}: margins ${m.marginW}x${m.marginH}]"
                aspectMode == MatchAspectMode.OFF -> " [match-aspect OFF]"
                VideoPrefs.detectedPanelSize(ctx) == null -> " [match-aspect: waiting for panel size]"
                else -> " [match-aspect ${aspectMode.name}: margins 0]"
            }
        }
        val note = when {
            userOverride != null -> " (override: ${VideoPrefs.resolution(ctx).label})"
            autoGeo != null -> " (auto-orientation from last connect)"
            else -> ""
        }
        val touchNote = when {
            BikeProfileHolder.forceNonTouch -> " [Disable touchscreen ON — focus/knob AA]"
            BikeProfileHolder.forceTouch -> " [Force touchscreen ON — touch AA]"
            else -> ""
        }
        val ov = BikeProfileHolder.profileOverride
        val ovNote = if (ov != ProfileOverride.AUTO) " [profile override: ${ov.shortLabel}]" else ""
        LogBus.log("→ bike profile (QR ssid=${qr.ssid} modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi$note$touchNote$ovNote$aspectNote")
    }

    override fun startAaHandoff() {
        // MainActivity lines 207-215.
        BikeLink.beginHandoff(ctx)
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            ConnectionState.set(Phase.AA_VIDEO_LIVE)
            LogBus.log("→ Android Auto video is live")
            AndroidAutoService.upgradeForegroundForMicrophone()
            BikeLink.markAaVideoSteady()
        }
        AndroidAutoService.start(ctx)
    }

    override fun triggerSelfMode(activity: Activity?) {
        val a = activity ?: return noScreen("AA self-mode trigger")
        dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(a, log = LogBus::log)
    }

    override fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        if (!ensureWifiOrPrompt(activity)) return
        ConnectionState.set(Phase.JOINING_WIFI)
        val transport = AppSettings.transport(ctx)
        // Phone-hosts-hotspot (Zontes action=128 / no SoftAP pwd): dash joins the phone.
        if (qr.supportsPhoneHotspot && qr.pwd.isEmpty()) {
            joinPhoneHotspot(qr, gateOnAaSteady, activity)
            return
        }
        // AUTO: P2P when the QR is P2P-only (incl. non-DIRECT SSIDs — join by MAC), or DIRECT-*.
        // SoftAP fallback remains in joinWifiP2p.onFailed for SoftAP-capable units.
        // Never force P2P when the QR is SoftAP-only (action bit3 clear) — Setup→P2P on those
        // bikes only burns 25s then falls back (Benelli TRK / bj* SSIDs in the field).
        val useP2p = when (transport) {
            WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
            WifiTransport.AP -> false
            WifiTransport.AUTO ->
                (qr.supportsP2p && !qr.supportsAp) ||
                    (qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                        (qr.supportsP2p || !qr.supportsAp))
        }
        // Per-bike memory refines AUTO only (an explicit P2P/AP setting is the rider's call): once a
        // transport has produced a live link for THIS bike, use it and skip the dead path. Some dashes
        // advertise DIRECT-* + SoftAP, so AUTO tries P2P first and burns the whole timeout on every
        // connect even when P2P never forms a group on this phone, while SoftAP connects in seconds.
        val remembered = if (transport == WifiTransport.AUTO) BikeMemory.winningTransport(ctx, qr.ssid) else null
        val effUseP2p = when {
            remembered == "AP" && qr.supportsAp -> false
            remembered == "P2P" && qr.supportsP2p -> true
            else -> useP2p
        }
        if (remembered != null && effUseP2p != useP2p) {
            LogBus.log("→ transport memory: '$remembered' connected before for '${qr.ssid}' — using it, skipping the dead path")
        }
        if (transport == WifiTransport.P2P && !useP2p) {
            LogBus.log(
                "→ Setup Wi‑Fi transport is P2P but QR is SoftAP-only " +
                    "(action=${qr.action} ssid=${qr.ssid}) — joining SoftAP instead",
            )
        }
        if (effUseP2p) {
            val isDirect = qr.ssid.startsWith("DIRECT-", ignoreCase = true)
            // P2P-only QRs with a SoftAP-style SSID but no MAC burn ~40s on MAC ERROR
            // (Voge-5G / ZT5G / etc.). Prefer SoftAP immediately when we have a password.
            if (!isDirect && qr.mac.isNullOrBlank() && qr.pwd.isNotEmpty()) {
                LogBus.log(
                    "→ QR says P2P-only; SSID '${qr.ssid}' has no MAC — " +
                        "joining SoftAP first (skip Wi‑Fi Direct by MAC)",
                )
            } else {
                if (!isDirect) {
                    LogBus.log(
                        "→ QR says P2P-only; SSID '${qr.ssid}' is not DIRECT-* — " +
                            "trying Wi‑Fi Direct by MAC (then SoftAP fallback)",
                    )
                }
                joinWifiP2p(qr, gateOnAaSteady, activity)
                return
            }
        }
        BikeWifi.reuseOrJoin(
            context = ctx,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = { network ->
                WifiGate.cancelNotification(ctx)
                // SoftAP got Wi-Fi up for this bike → remember it so AUTO skips P2P next time.
                BikeMemory.setWinningTransport(ctx, qr.ssid, "AP")
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // Map / Mirror: no AA gating — rebuild PXC immediately on the bound network.
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        prober().start(network ?: BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    private fun joinPhoneHotspot(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        LogBus.log(
            "→ phone-hotspot mode (action=${qr.action} mac=${qr.mac}) — " +
                "assist dialog (app cannot create AP; set dash SSID/pwd in system hotspot)",
        )
        ConnectionState.set(Phase.JOINING_WIFI, ctx.getString(R.string.main_phone_hotspot_status))
        PhoneHotspotAssist.showSetupDialog(
            activity = activity ?: run {
                noScreen("phone-hotspot setup")
                ConnectionState.set(Phase.ERROR, ctx.getString(R.string.main_phone_hotspot_cancelled))
                return
            },
            qr = qr,
            onContinue = { startPhoneHotspotScan(gateOnAaSteady, activity) },
            onCancel = {
                ConnectionState.set(Phase.ERROR, ctx.getString(R.string.main_phone_hotspot_cancelled))
            },
        )
    }

    private fun startPhoneHotspotScan(gateOnAaSteady: Boolean, activity: Activity?) {
        val creds = PhoneHotspotAssist.loadCreds(ctx)
        if (creds.ssid.isNotEmpty()) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.main_phone_hotspot_waiting_named, creds.ssid),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(ctx, R.string.main_phone_hotspot_hint, Toast.LENGTH_LONG).show()
        }
        ConnectionState.set(Phase.JOINING_WIFI, ctx.getString(R.string.main_phone_hotspot_status))
        Thread({
            try {
                var subnets = emptyList<PhoneHotspotScan.Subnet>()
                val pollRounds = 30 // ~45s at 1.5s
                for (i in 0 until pollRounds) {
                    if (ConnectionState.phase == Phase.STOPPED) return@Thread
                    subnets = PhoneHotspotScan.tetheringSubnets()
                    if (subnets.isNotEmpty()) break
                    if (i == 0 || i % 5 == 0) {
                        LogBus.log(
                            "[HOTSPOT] waiting for tether interface… " +
                                "(${i + 1}/$pollRounds) set Android hotspot" +
                                creds.ssid.takeIf { it.isNotEmpty() }?.let { " SSID='$it'" }.orEmpty(),
                        )
                    }
                    try {
                        Thread.sleep(1_500)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (subnets.isEmpty()) {
                    LogBus.log(
                        "[HOTSPOT] no tether interface after wait — open hotspot settings " +
                            "and set the dash SSID/password, then Connect again",
                    )
                    main.post {
                        ConnectionState.set(Phase.ERROR, ctx.getString(R.string.main_phone_hotspot_no_iface))
                        val a = activity ?: run { noScreen("hotspot dialog"); return@post }
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                            .setTitle(R.string.main_phone_hotspot_dialog_title)
                            .setMessage(R.string.main_phone_hotspot_no_iface)
                            .setPositiveButton(R.string.main_phone_hotspot_open_settings) { _, _ ->
                                PhoneHotspotAssist.openHotspotSettings(a)
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    }
                    return@Thread
                }
                val subnet = subnets.first()
                LogBus.log(
                    "[HOTSPOT] using ${subnet.interfaceName} " +
                        "${subnet.localAddress.hostAddress}/${subnet.prefixLength}",
                )
                // Prefer NSD first (works when multicast reaches the tether iface).
                val nsd = EasyConnDiscovery.discoverNsd(ctx, LogBus::log)
                val peer = nsd?.host
                    ?: PhoneHotspotScan.findEasyConnPeer(subnet, LogBus::log)
                if (peer == null) {
                    main.post {
                        ConnectionState.set(Phase.ERROR, ctx.getString(R.string.main_phone_hotspot_no_peer))
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.main_phone_hotspot_no_peer),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@Thread
                }
                val bindIp = subnet.localAddress
                main.post {
                    WifiGate.cancelNotification(ctx)
                    if (gateOnAaSteady) {
                        LogBus.log(
                            "→ hotspot peer ${peer.hostAddress} (waiting for AA); " +
                                "bind=${bindIp.hostAddress}",
                        )
                        BikeLink.markP2pReady(bindIp, peer)
                    } else {
                        ConnectionState.set(Phase.PXC_CONNECTING)
                        LogBus.log("→ hotspot peer ${peer.hostAddress}; starting EasyConn PXC …")
                        try {
                            prober().start(
                                network = null,
                                gatewayOverride = peer,
                                bindIpOverride = bindIp,
                            )
                        } catch (e: Exception) {
                            LogBus.log("prober start failed: $e")
                        }
                    }
                }
            } catch (e: Exception) {
                LogBus.log("[HOTSPOT] failed: $e")
                main.post {
                    ConnectionState.set(Phase.ERROR, "hotspot scan failed")
                }
            }
        }, "phone-hotspot-scan").apply { isDaemon = true }.start()
    }

    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        if (!ensureWifiOrPrompt(activity)) return
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        // How long to wait for a P2P group before failing over to SoftAP:
        //  - "P2P" remembered  → proven P2P device on this phone; give it the full window.
        //  - SoftAP advertised → a working fallback exists, so bail (12s) when P2P is hopeless
        //    (some phones ERROR on every join attempt in <1s but the group never forms).
        //  - otherwise (P2P-only) → full window; there is nothing to fall back to.
        val known = BikeMemory.winningTransport(ctx, qr.ssid)
        val p2pTimeout = when {
            known == "P2P" -> BikeWifiP2p.CONNECT_TIMEOUT_MS
            qr.supportsAp && qr.pwd.isNotEmpty() -> 12_000L
            else -> BikeWifiP2p.CONNECT_TIMEOUT_MS
        }
        BikeWifiP2p.connect(
            context = ctx,
            qr = qr,
            timeoutMs = p2pTimeout,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(ctx)
                // P2P actually formed a group for this bike → remember it (some DIRECT-* bikes need P2P).
                BikeMemory.setWinningTransport(ctx, qr.ssid, "P2P")
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        prober().start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                if (qr.pwd.isEmpty() || qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                    LogBus.log("P2P join failed: $reason — no SoftAP credentials to fall back to")
                    ConnectionState.set(Phase.ERROR, "Wi‑Fi Direct failed")
                    return@connect
                }
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(ctx)) return@connect
                BikeWifi.join(
                    context = ctx,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(ctx)
                        // SoftAP fallback connected → remember AP so AUTO goes straight here next time.
                        BikeMemory.setWinningTransport(ctx, qr.ssid, "AP")
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                prober().start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    override fun stopEverything() {
        // MainActivity lines 1481-1495 (bleWakeUp lines dropped — dead code).
        LogBus.log("→ stopping everything (Android Auto + bike)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(ctx) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(ctx) } catch (e: Exception) { LogBus.log("projection stop: $e") }
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(ctx, LogBus::log) } catch (e: Exception) { LogBus.log("wifi leave: $e") }
        ConnectionState.set(Phase.STOPPED, "")
    }

    override fun clearProjection() { ProjectionHolder.projection = null }
    override fun clearGpxSession() { GpxSession.clear() }
    override fun abandonStaleNavigation(reason: String) { GpxSession.abandonStaleNavigation(reason) }
    override fun notifyForegroundResuming() = AndroidAutoService.notifyForegroundResuming()
    override fun saveBike(raw: String, qr: QrData) = BikeMemory.save(ctx, raw, qr)

    // ------------------------------------------------------------ mirror / map
    override fun applyMirrorOrientation(activity: Activity?) {
        val a = activity ?: return noScreen("mirror orientation")
        MirrorOrientationLock.apply(a)
    }
    override fun clearMirrorOrientation() = clearMirrorLock()

    override fun createMirrorConsentIntent(): Intent? = try {
        // MainActivity lines 1536-1546.
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            toast("Single-app mirror needs Android 14+. Whole screen will be used.", long = true)
            mpm.createScreenCaptureIntent()
        }
    } catch (e: Exception) {
        LogBus.log("mirror start failed ($e)")
        null
    }

    override fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit) {
        // MainActivity lines 365-409: FGS of type mediaProjection must be RUNNING before
        // getMediaProjection() on API 34+ → poll its foreground flag every 100 ms (max 5 s).
        ProjectionService.start(ctx)
        val maxTries = 50
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(resultCode, data)
                        GpxSession.clear()
                        LogBus.log("screen-capture armed (FGS up after ${tries * 100}ms) — pick Entire screen or a single app (Android 14+)")
                        onReady()
                    } catch (e: Exception) {
                        LogBus.log("getMediaProjection failed: $e")
                        ProjectionService.stop(ctx)
                        clearMirrorLock()
                    }
                } else if (tries++ < maxTries) {
                    main.postDelayed(this, 100)
                } else {
                    LogBus.log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(ctx)
                    clearMirrorLock()
                }
            }
        }
        main.post(poll)
    }

    // Always triggered from the screen; the start helpers need an Activity context (no NEW_TASK flag).
    override fun openMapHub(activity: Activity?) { activity?.let { GpxActivity.start(it) } ?: noScreen("open map") }
    override fun openGpxPreview(activity: Activity?) { activity?.let { HudViewActivity.startGpxPreview(it) } ?: noScreen("GPX preview") }
    override fun openMapSearch(activity: Activity?, query: String) { activity?.let { GpxActivity.startSearch(it, query) } ?: noScreen("map search") }
    override fun dashRemoteSubmit(query: String) = DashRemote.submit(query)

    override fun sendDestinationToAndroidAuto(query: String) {
        Toast.makeText(ctx, ctx.getString(R.string.main_sent_aa, query), Toast.LENGTH_SHORT).show()
        LogBus.log("[search] AA: looking up \"$query\"…")
        val near = lastKnownLatLon()
        NominatimSearch.searchAsync(
            query = query,
            nearLat = near?.first,
            nearLon = near?.second,
            onResult = { places ->
                main.post {
                    val best = places.firstOrNull()
                    if (best != null) {
                        // Prefer the place name (city) so Maps starts guidance to that place;
                        // bare lat/lon with a "(label)" suffix was opening Maps with an empty search.
                        val place = best.name.ifBlank { query }
                        LogBus.log("[search] AA: resolved → $place (${best.lat},${best.lon})")
                        if (!NavLauncher.navigate(ctx, place, LogBus::log)) {
                            NavLauncher.navigateLatLon(ctx, best.lat, best.lon, place, LogBus::log)
                        }
                    } else {
                        LogBus.log("[search] AA: no geocode hit — free-text navigate \"$query\"")
                        NavLauncher.navigate(ctx, query, LogBus::log)
                    }
                }
            },
            onError = { err ->
                main.post {
                    LogBus.log("[search] AA: geocode failed ($err) — free-text navigate \"$query\"")
                    NavLauncher.navigate(ctx, query, LogBus::log)
                }
            },
        )
    }

    private fun lastKnownLatLon(): Pair<Double, Double>? {
        return try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) loc.latitude to loc.longitude else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ dialog actions
    override fun closeRival() = RivalClient.closeBestEffort(ctx)
    override fun openRivalAppInfo() = RivalClient.openAppInfo(ctx)
    override fun rivalInstalled() = RivalClient.isInstalled(ctx)

    override fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit) {
        Thread {
            val release = try { UpdateChecker.check(ctx, manual = manual) } catch (_: Exception) { null }
            main.post { onResult(release) }
        }.start()
    }

    override fun skipUpdate(version: String) = UpdateChecker.skip(ctx, version)

    override fun openUrl(url: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    override fun openSettingsAction(action: String, forThisApp: Boolean): Boolean = try {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forThisApp) i.data = Uri.fromParts("package", ctx.packageName, null)
        ctx.startActivity(i); true
    } catch (_: Exception) { false }

    override fun shareLog(activity: Activity?) {
        try {
            CrashGuard.persistSession(ctx)
            val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file))

            val crash = CrashGuard.crashFile(ctx)
            if (crash.exists() && crash.length() > 0L) {
                uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", crash))
                LogBus.log("attaching crash report: ${crash.name} (${crash.length()} bytes)")
            }

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(ctx.getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", d))
                LogBus.log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooser(activity, Intent.createChooser(send, "Share log"))
            LogBus.log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            LogBus.log("share failed: $e")
        }
    }

    override fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String) {
        try {
            val diagnostics = buildString {
                appendLine(
                    "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                        "git=${BuildConfig.GIT_HASH}",
                )
                appendLine("profile=${BikeProfileHolder.active.name}")
                appendLine("override=${BikeProfileHolder.profileOverride}")
                appendLine("transport=${AppSettings.transport(ctx).label}")
                appendLine("margins=${ScreenMargins.summary()}")
                appendLine("forceNonTouch=${BikeProfileHolder.forceNonTouch}")
                appendLine("forceTouch=${BikeProfileHolder.forceTouch}")
                appendLine("ssid=${BikeMemory.lastQr(ctx)?.ssid ?: "—"}")
                appendLine("phase=${ConnectionState.phase} ${ConnectionState.detail}")
            }
            val text = ProblemReport.file(
                problem = problem.ifBlank { "(not specified)" },
                model = model,
                year = year,
                diagnostics = diagnostics,
                log = LogBus.snapshot(),
            )
            val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "opencfmoto-report.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ProblemReport.subject(model, BuildConfig.VERSION_NAME))
                putExtra(Intent.EXTRA_TEXT, ProblemReport.body(problem, model, year, diagnostics))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooser(activity, Intent.createChooser(send, ctx.getString(R.string.main_share_problem_report)))
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.main_share_report_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    override fun clearCrash() = CrashGuard.clearCrash(ctx)
    override fun startSetup(activity: Activity?) { activity?.let { SetupActivity.start(it) } ?: noScreen("setup") }

    override fun hostDestroyedTeardown() {
        // MainActivity lines 826-838 (bleWakeUp lines dropped — dead code).
        AaVideoBridge.onSteadyVideo = null
        BikeLink.prober?.stop()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(ctx)
        BikeWifi.leave(ctx, LogBus::log)
        BikeWifiP2p.stop(LogBus::log)
    }

    private companion object {
        const val REQ_BT_FOR_AA = 4
    }
}
