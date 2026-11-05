// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Joins the bike's hotspot using WifiNetworkSpecifier (no system Wi-Fi config required).
 *
 * The system shows a dialog asking the user to accept the network. After acceptance, the
 * resulting Network object is process-bound so our TCP sockets and mDNS lookups go through
 * the bike's interface (which has no internet — that's fine). Once the SSID has been approved,
 * Android re-satisfies the request without prompting again, so re-joins are silent.
 *
 * ## Auto-rejoin on loss (bike power-cycle)
 * A [WifiNetworkSpecifier] request is *terminal* on loss: when the bike is switched off its hotspot
 * disappears and the framework won't re-satisfy the old request on its own. So if the network drops
 * while a session is still active, we re-issue the request (with backoff) until the bike's AP comes
 * back. The FIRST acquisition drives the normal start-up; every subsequent one is a re-acquire that
 * restarts the bike link on the fresh network via [BikeLink.onWifiReacquired] (fresh IP + rebound
 * server sockets + fresh probe). Without this, stopping and restarting the bike left the app probing
 * a dead interface and needed several manual Stop→Connect cycles to recover.
 */
object BikeWifi {
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var request: NetworkRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    var currentNetwork: Network? = null
        private set

    @Volatile private var active = false
    @Volatile private var firstDelivered = false
    private var ssid: String = ""
    private var onAvailableCb: ((Network) -> Unit)? = null
    private var onLostCb: (() -> Unit)? = null
    private var logCb: ((String) -> Unit)? = null

    // Watch for the bike's AP until the rider taps Stop (leave()): a stopped bike may be off for a
    // while, and re-issuing the (approved, silent) request is cheap. Backoff grows then caps so we
    // don't hammer the framework while parked.
    //
    // The FIRST re-request after a drop is near-instant: at home the phone races to re-join a saved
    // network the moment the bike's AP blips, and if we don't re-grab the bike AP fast the dash's
    // EasyConn times out ("device is not on the network") and needs a manual restart. Grabbing it back
    // immediately keeps us on the bike's network long enough for the dash to reconnect on its own.
    // Timings live in [WifiRejoinLoop]; every retry runs on [handler], and Stop cancels the loop.
    private val rejoin = WifiRejoinLoop(
        post = { ms, block -> handler.postDelayed(block, ms) },
        cancelAll = { handler.removeCallbacksAndMessages(null) },
    )
    /**
     * Must be passed to [ConnectivityManager.requestNetwork]. Without a timeout the request can
     * wait forever after the bike AP dies — none of onAvailable/onLost/onUnavailable fire — so
     * ignition-cycle reconnect stalls until the rider toggles something. open-cflink hit 7+ min
     * hangs without this.
     *
     * First acquisition needs a long window: Android shows a "choose a device" dialog and if we
     * time out while it's open the system says "The app canceled the request to choose a device."
     * Silent re-joins (already approved) can stay short.
     */
    private const val FIRST_JOIN_TIMEOUT_MS = 90_000
    private const val REJOIN_TIMEOUT_MS = 12_000

    fun join(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
    ) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Tear down any previous session's callback (and queued retries) first.
        rejoin.start()
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        this.appContext = context.applicationContext
        this.cm = cm
        this.ssid = ssid
        this.onAvailableCb = onAvailable
        this.onLostCb = onLost
        this.logCb = log
        this.active = true
        this.firstDelivered = false

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        log("requesting Wi-Fi join: $ssid …")
        registerCallback()
    }

    /**
     * Mode switches (AA↔Map↔Mirror) must keep bike Wi‑Fi and only rebuild PXC.
     * If we're already process-bound to [ssid], fire [onAvailable] immediately — do not
     * unregister/re-request (that added multi-second stalls and "already running" races).
     */
    fun reuseOrJoin(
        context: Context,
        ssid: String,
        psk: String,
        onAvailable: (Network) -> Unit,
        onLost: () -> Unit,
        log: (String) -> Unit,
    ) {
        val net = currentNetwork
        if (active && net != null && this.ssid.equals(ssid, ignoreCase = true)) {
            this.onAvailableCb = onAvailable
            this.onLostCb = onLost
            this.logCb = log
            // A parked network (keep-Wi-Fi Stop) is being reused: watch for drops again.
            if (!rejoin.enabled) rejoin.start()
            log("Wi-Fi already joined: $ssid — skipping re-join (mode switch)")
            onAvailable(net)
            return
        }
        join(context, ssid, psk, onAvailable, onLost, log)
    }

    private fun registerCallback() {
        val cm = cm ?: return
        val req = request ?: return
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                // Do not bindProcessToNetwork here. Binding the process to the bike AP removes
                // the 127.0.0.1 route (AaReceiver.connectLoopback). That dropped AA ~500ms after
                // a successful handshake on DIRECT-go-CFMOTO / 800NK before video went steady.
                // [BikeLink] / [EasyConnProber] call [rebindProcessToBike] when PXC actually starts.
                AppHttp.ensureCellularUplink()
                rejoin.resetAttempts()
                logLinkOnce(network)
                if (!firstDelivered) {
                    firstDelivered = true
                    logCb?.invoke("Wi-Fi joined: $ssid (network=$network, bind deferred until AA video)")
                    onAvailableCb?.invoke(network)
                } else {
                    logCb?.invoke("Wi-Fi re-acquired: $ssid — restarting bike link on fresh network")
                    BikeLink.onWifiReacquired(network)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Band/RSSI logged once per join from onAvailable path via logLinkOnce.
            }

            override fun onLost(network: Network) {
                logCb?.invoke("Wi-Fi lost: $network")
                currentNetwork = null
                linkLogged = false
                // Drop the process bind immediately. Leaving it pinned to a dead Network causes
                // ENONET on loopback binds (AA :5288) and probes until the rider taps Stop.
                unbindProcess()
                onLostCb?.invoke()
                if (active) scheduleRejoin()
            }

            override fun onUnavailable() {
                // Stopped while the request was pending: don't log "will retry" or flag an error.
                if (!active || !rejoin.enabled) {
                    if (currentNetwork == null) unbindProcess()
                    return
                }
                val timeoutSec = (if (firstDelivered) REJOIN_TIMEOUT_MS else FIRST_JOIN_TIMEOUT_MS) / 1000
                logCb?.invoke(
                    "Wi-Fi join unavailable after ${timeoutSec}s " +
                        "(bike off, out of range, declined, or system picker timed out) — will retry",
                )
                // First join often means the system Wi‑Fi / Nearby picker was ignored — surface it.
                if (!firstDelivered) {
                    logCb?.invoke(
                        "→ TAP the phone's Wi‑Fi / device picker NOW (SSID of the bike), " +
                            "or join that network in Settings → Wi‑Fi, then Connect again",
                    )
                    try {
                        val detail = appContext?.getString(R.string.conn_detail_wifi_picker)
                            ?: "approve the Wi‑Fi / device picker, then Connect again"
                        ConnectionState.set(Phase.ERROR, detail)
                    } catch (_: Exception) {
                    }
                }
                // Join timed out with no live Network — make sure we aren't still pinned to a
                // previous dead bike AP (resume / auto-connect path).
                if (currentNetwork == null) unbindProcess()
                if (active) scheduleRejoin()
            }
        }
        callback = cb
        // Timeout is mandatory — see FIRST_JOIN_TIMEOUT_MS / REJOIN_TIMEOUT_MS.
        val timeoutMs = if (firstDelivered) REJOIN_TIMEOUT_MS else FIRST_JOIN_TIMEOUT_MS
        if (!firstDelivered) {
            logCb?.invoke(
                "→ approve the phone's Wi‑Fi / device picker if it appears " +
                    "(do not leave it open past ${FIRST_JOIN_TIMEOUT_MS / 1000}s)",
            )
        }
        cm.requestNetwork(req, cb, timeoutMs)
    }

    @Volatile private var linkLogged = false

    private fun logLinkOnce(network: Network) {
        if (linkLogged) return
        val cm = this.cm ?: return
        try {
            val caps = cm.getNetworkCapabilities(network) ?: return
            val info = caps.transportInfo as? android.net.wifi.WifiInfo ?: return
            linkLogged = true
            val mhz = info.frequency
            val band = if (mhz in 2400..2500) "2.4GHz — SHARED WITH BLUETOOTH" else "${mhz / 1000}GHz"
            logCb?.invoke(
                "[wifi] link: ${mhz}MHz ($band), rssi=${info.rssi}dBm, " +
                    "tx=${info.txLinkSpeedMbps}Mbps, rx=${info.rxLinkSpeedMbps}Mbps",
            )
        } catch (_: Exception) {
        }
    }

    private fun scheduleRejoin() {
        if (!active) return
        // Before the first successful join the loop never uses the near-instant retry (it would
        // cancel the system device picker mid-tap); after firstDelivered the first one is near-instant.
        rejoin.schedule(firstDelivered) { attempt ->
            if (!active) return@schedule
            logCb?.invoke("re-requesting bike Wi-Fi (attempt $attempt) …")
            // Don't stomp the WAITING_FOR_BIKE state the service sets once AA is parked.
            if (ConnectionState.phase != Phase.WAITING_FOR_BIKE) {
                ConnectionState.set(Phase.RECONNECTING, "waiting for bike Wi-Fi")
            }
            registerCallback()
        }
    }

    /**
     * Best-effort "is the bike's hotspot nearby?" check for auto-connect. Deliberately biased toward
     * `null` ("unknown → try anyway"): a false "not in range" is worse than a wasted connect attempt.
     *  - `true`  : the SSID is in a fresh Wi-Fi scan (or we're already on it) → connect.
     *  - `false` : we have FRESH scan results and the SSID is not among them → the bike really is away.
     *  - `null`  : we can't be sure (Wi-Fi off, no location permission, empty/stale scan cache) →
     *              the caller should attempt anyway.
     *
     * Reads cached scan results and only trusts "absent" when at least one result is recent (Android
     * throttles active scans, so a stale cache must not be read as "bike gone"). Also fires a
     * best-effort [WifiManager.startScan] to refresh the cache for the next check.
     */
    fun isSsidInRange(context: Context, ssid: String): Boolean? {
        if (ssid.isBlank()) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wm.isWifiEnabled) return null
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return null

        // Already associated with the bike's AP counts as in range.
        val connected = wm.connectionInfo?.ssid?.trim('"')
        if (connected != null && connected.equals(ssid, ignoreCase = true)) return true

        val results = try { wm.scanResults } catch (_: SecurityException) { null } ?: return null
        // Refresh for next time (throttled/deprecated — failures are fine).
        try { wm.startScan() } catch (_: Exception) {}
        if (results.isEmpty()) return null

        // ScanResult.timestamp is microseconds since boot when the AP was last seen.
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val fresh = results.filter { it.timestamp > 0 && nowUs - it.timestamp < FRESH_SCAN_US }
        if (fresh.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        if (results.any { it.SSID?.equals(ssid, ignoreCase = true) == true }) return true
        // Not found: only a confident "no" if we actually have fresh evidence; else unknown.
        return if (fresh.isNotEmpty()) false else null
    }

    private const val FRESH_SCAN_US = 30_000_000L  // 30s

    /**
     * End the session Wi-Fi: full teardown, or [park] (stay associated) when the rider
     * enabled Setup → keep bike Wi-Fi after disconnect.
     */
    fun releaseSession(context: Context, log: (String) -> Unit) {
        if (AppSettings.keepWifiAfterDisconnect(context)) {
            park(context, log)
            BikeWifiP2p.park(log)
        } else {
            leave(context, log)
            BikeWifiP2p.stop(log)
        }
    }

    /**
     * Keep the SoftAP association (callback stays registered) but unbind the process so
     * cellular / maps work. Some dashes forget the clock the moment the group drops.
     *
     * Parking is still a Stop: the re-join loop is cancelled so a later drop or a pending join
     * timeout can't flip the phase back to RECONNECTING. With no live bike network there is nothing
     * to keep (bike off / never joined), so that case releases the request entirely — otherwise its
     * 90 s timeout fires after Stop and restarts the retry loop.
     */
    fun park(context: Context, log: (String) -> Unit) {
        rejoin.stop()
        if (currentNetwork == null) {
            log("Wi-Fi keep-after-disconnect: no live bike network to keep — releasing the join request")
            leave(context, log)
            return
        }
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        unbindProcess(cm)
        log("Wi-Fi kept after disconnect (dash clock) — process unbound, SoftAP still associated")
    }

    fun leave(context: Context, log: (String) -> Unit) {
        active = false
        rejoin.stop()
        val cm = this.cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        firstDelivered = false
        linkLogged = false
        unbindProcess(cm)
        currentNetwork = null
        log("Wi-Fi released")
    }

    /**
     * Clear process→Network binding so loopback / cellular sockets work again.
     * Safe to call when already unbound. Used after bike Wi‑Fi loss and before AA :5288 bind.
     */
    fun unbindProcess(cmIn: ConnectivityManager? = null, context: Context? = null) {
        val cm = cmIn
            ?: this.cm
            ?: context?.applicationContext
                ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    /**
     * If we aren't on a live bike [Network], ensure the process isn't stuck on a stale bind
     * (ENONET on ServerSocket / localhost). No-op when [currentNetwork] is set.
     */
    fun unbindIfNoBikeNetwork(context: Context) {
        if (currentNetwork != null) return
        unbindProcess(context = context)
    }

    /**
     * Re-assert process→bike-network binding. VPNs (PCAPdroid, commercial clients, Always-on VPN)
     * often steal the default route after we join; without this, outbound probes can leave via the
     * tunnel even though [currentNetwork] is still the bike AP.
     */
    fun rebindProcessToBike(context: Context): Boolean {
        val n = currentNetwork ?: return false
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.bindProcessToNetwork(n)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True if a **live internet-capable** VPN network is present. Idle/leftover `TRANSPORT_VPN`
     * interfaces (OEM Secure Wi‑Fi stubs, parked clients) used to trip false "VPN blocking" errors
     * whenever a bike probe timed out for any reason — including while Maps was starting.
     *
     * Kill-switches still surface via [isVpnBindBlocked] / [testBikeSocketBind] (EPERM).
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Short description of VPN networks for Logs (empty when none). */
    fun vpnNetworksSummary(context: Context): String {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val inet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val vali = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                "vpn(internet=$inet,validated=$vali)"
            }.joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Always-on VPN with "Block connections without VPN" returns EPERM from
     * [Network.bindSocket] / [Network.getSocketFactory] — the app cannot pin traffic to bike Wi-Fi.
     *
     * Requires a **live internet VPN** ([isVpnActive]): bare EPERM / "Operation not permitted"
     * also shows up when the bike [Network] is stale after Map↔AA toggles or a Wi‑Fi blip, and that
     * must not pop the kill-switch dialog.
     */
    fun isVpnBindBlocked(context: Context, error: Throwable?): Boolean {
        if (error == null || !isVpnActive(context)) return false
        var t: Throwable? = error
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("EPERM", ignoreCase = true) ||
                m.contains("Operation not permitted", ignoreCase = true)
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Quick check: can we create a socket on the bike [Network]? Returns null if OK, else the error.
     * Call after join when a VPN may be in lockdown mode.
     */
    fun testBikeSocketBind(context: Context): Exception? {
        val n = currentNetwork ?: return null
        rebindProcessToBike(context)
        return try {
            n.socketFactory.createSocket().close()
            null
        } catch (e1: Exception) {
            if (isVpnBindBlocked(context, e1)) return e1
            try {
                val s = java.net.Socket()
                n.bindSocket(s)
                s.close()
                null
            } catch (e2: Exception) {
                e2
            }
        }
    }
}
