package dev.zanderp.opencfmoto.connect

import dev.zanderp.opencfmoto.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionControllerTest {
    private lateinit var e: FakeConnectionEngine
    private lateinit var port: FakeUiPort
    private lateinit var c: ConnectionController

    @Before
    fun setUp() {
        e = FakeConnectionEngine()
        port = FakeUiPort()
        c = ConnectionController(e)
        c.attach(port)
    }

    private fun idx(call: String) = e.calls.indexOfFirst { it == call || it.startsWith(call) }

    @Test
    fun connectWithSavedBikeRunsFlowInOrder() {
        c.onConnectPressed()
        val order = listOf("deps:false", "clearProjection", "clearGpxSession", "ensureLocation", "ensureWifi",
            "armCommute:true", "tearDown:true,true", "applyProfile", "setPhase:STARTING_AA", "startAaHandoff",
            "postDelayed:900", "postDelayed:4500", "postDelayed:18000", "joinWifi:true")
        val positions = order.map { idx(it) }
        assertTrue("missing: ${order.filterIndexed { i, _ -> positions[i] < 0 }}", positions.none { it < 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun connectWhileBusyOrLiveStops() {
        for (p in listOf(Phase.JOINING_WIFI, Phase.STREAMING, Phase.MIRRORING)) {
            e.calls.clear(); e.phase = p
            c.onConnectPressed()
            assertTrue(e.calls.contains("stopEverything"))
            assertFalse(e.calls.contains("startAaHandoff"))
        }
    }

    @Test
    fun connectWithoutSavedBikeScans() {
        e.saved = null
        c.onConnectPressed()
        assertTrue(port.calls.contains("launchQrScan"))
    }

    @Test
    fun wifiOffDoesNotStartAa() {
        e.wifiOn = false
        c.onConnectPressed()
        assertFalse(e.calls.contains("startAaHandoff"))
        assertFalse(e.calls.any { it.startsWith("joinWifi") })
    }

    @Test
    fun nearbyMissingShowsDialogAndStops() {
        e.hur = false; e.needNearby = true
        c.onConnectPressed()
        assertTrue(e.calls.contains("requestNearby"))
        assertEquals(Phase.ERROR, e.phase)
        assertEquals(AppDialog.NearbyBt(needNearby = true), c.dialog.value)
        assertFalse(e.calls.contains("startAaHandoff"))
    }

    @Test
    fun parkedResumeDoesNotArmCommute() {
        c.onHostResumed(resumeExtra = true)
        assertTrue(e.calls.contains("armCommute:false"))
        assertFalse(e.calls.contains("armCommute:true"))
    }

    @Test
    fun autoConnectFiresOnce() {
        c.onHostCreated(startGpx = false, resume = false, btTrigger = false)
        e.runDelayed()
        c.onHostResumed(resumeExtra = false)
        e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun autoConnectRetriesWhenBikeAppears() {
        e.inRange = false
        c.onHostCreated(false, false, false); e.runDelayed()
        assertEquals(0, e.calls.count { it == "startAaHandoff" })
        e.inRange = true
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun autoConnectResetsLatchWhenNoScreenAtFireTime() {
        c.onHostCreated(false, false, false)
        c.detach(port)                       // activity recreated before the 1 200 ms start fired
        e.runDelayed()
        assertEquals(0, e.calls.count { it == "startAaHandoff" })
        c.attach(port)
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun selfModeTriggerUsesCurrentActivityOrSkips() {
        c.onConnectPressed()
        val newPort = FakeUiPort()
        c.detach(port); c.attach(newPort)    // Google AA recreated MainActivity
        e.runDelayed()
        assertTrue(e.activitiesSeenBySelfMode.isNotEmpty())
        assertTrue(e.activitiesSeenBySelfMode.all { it === newPort.activity })
    }

    @Test
    fun rivalErrorShowsDialogOnce() {
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
        c.dismissDialog()
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertNull(c.dialog.value)            // same error: not again
        c.onPhaseChanged(Phase.IDLE, "")
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
    }

    @Test
    fun rivalAppSettingsThenResumeRetriesOnce() {
        e.autoConnect = false                // isolate the rival retry from auto-connect
        c.onRivalAppSettings()
        e.now += 20_000
        c.onHostResumed(false); e.runDelayed()
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
        assertEquals(1, e.calls.count { it == "log:→ back from CFMoto app settings — retrying" })
    }

    @Test
    fun vpnDialogOnlyWithActiveVpn() {
        c.onPhaseChanged(Phase.ERROR, "VPN kill-switch blocked")
        assertNull(c.dialog.value)
        e.vpn = true
        c.onPhaseChanged(Phase.IDLE, "")
        c.onPhaseChanged(Phase.ERROR, "VPN kill-switch blocked")
        assertSame(AppDialog.VpnKillSwitch, c.dialog.value)
    }

    @Test
    fun dialogPersistsAcrossDetachAttach() {
        c.detach(port)
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        c.attach(FakeUiPort())
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
    }

    @Test
    fun navigateToTypedRoutes() {
        assertFalse(c.navigateToTyped("  "))
        e.aaPipeline = true
        assertTrue(c.navigateToTyped("Colombo"))
        assertTrue(e.calls.contains("sendToAa:Colombo"))
        e.aaPipeline = false
        assertFalse(c.navigateToTyped("Kandy"))
        assertTrue(e.calls.contains("openMapSearch:Kandy"))
    }

    @Test
    fun mirrorConsentShowsReadyDialog() {
        c.onMirrorPressed()
        assertTrue(port.calls.contains("launchMirrorConsent"))
        c.onMirrorConsent(ok = true, resultCode = -1, data = android.content.Intent())
        assertSame(AppDialog.MirrorReady, c.dialog.value)
    }

    @Test
    fun crashPendingShowsRecoveryDialogOnCreate() {
        e.crash = true
        c.onHostCreated(false, false, false)
        assertSame(AppDialog.CrashRecovery, c.dialog.value)
    }
}
