package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.UpdateChecker

/** Records every engine call; delayed blocks are queued and run by [runDelayed]. */
class FakeConnectionEngine : ConnectionEngine {
    val calls = mutableListOf<String>()
    val delayed = mutableListOf<Pair<Long, () -> Unit>>()
    val activitiesSeenBySelfMode = mutableListOf<Activity?>()

    override var phase: Phase = Phase.IDLE
    var saved: QrData? = QrData.parse(SAMPLE_QR)
    var name: String? = "CFMOTO4288"
    var aaRunning = false
    var parked = false
    var sessionActive = false
    var sessionSeen = false
    var autoConnect = true
    var installed = true
    var wifiOn = true
    var inRange: Boolean? = true
    var hur = true
    var needNearby = false
    var vpn = false
    var setupDone = true
    var now = 1_000_000L
    var gpx = false
    var aaPipeline = false
    var dashRemote = false
    var lastFrameMs = Long.MAX_VALUE
    var crash = false
    var depsBlock = false
    var rivalInstalledFlag = true
    var openRivalOk = true
    var mirrorIntent: Intent? = Intent()
    var updateResult: UpdateChecker.Release? = null

    override fun savedQr() = saved
    override fun bikeName() = name
    override fun hasSavedBike() = saved != null
    override fun aaServiceRunning() = aaRunning
    override fun aaParked() = parked
    override fun aaSessionActive() = sessionActive
    override fun aaSessionSeen() = sessionSeen
    override fun autoConnectEnabled() = autoConnect
    override fun aaInstalled() = installed
    override fun wifiEnabled() = wifiOn
    override fun ssidInRange(ssid: String) = inRange
    override fun canAttemptHur() = hur
    override fun needsNearbyPermission() = needNearby
    override fun vpnActive() = vpn
    override fun setupSeen() = setupDone
    override fun elapsedRealtime() = now
    override fun gpxActive() = gpx
    override fun gpxDescription() = "FREE ''"
    override fun aaPipelineLive() = aaPipeline
    override fun dashRemoteAvailable() = dashRemote
    override fun msSinceLastFrame() = lastFrameMs
    override fun crashPending() = crash
    override fun string(resId: Int, vararg args: Any) = "str$resId" + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")

    override fun setPhase(phase: Phase, detail: String?) { this.phase = phase; calls += "setPhase:$phase" }
    override fun log(msg: String) { calls += "log:$msg" }
    override fun toast(text: String, long: Boolean) { calls += "toast:$text" }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { delayed += delayMs to block; calls += "postDelayed:$delayMs" }
    override fun reportError(detail: String) { calls += "reportError" }
    override fun persistSession() { calls += "persistSession" }
    override fun applySettingsToHolder() { calls += "applySettingsToHolder" }
    override fun ensureProber() { calls += "ensureProber" }
    override fun requestNotificationPermission(activity: Activity?) { calls += "requestNotificationPermission" }
    override fun dependencyLaunchCheck(activity: Activity) { calls += "dependencyLaunchCheck" }
    override fun clearWifiOffNoticeIfEnabled() { calls += "clearWifiOffNotice" }
    override fun ensureWifiOrPrompt(activity: Activity?): Boolean { calls += "ensureWifi"; return wifiOn }
    override fun dependencyBlocks(activity: Activity, forScan: Boolean): Boolean { calls += "deps:$forScan"; return depsBlock }
    override fun ensureLocationPermission(activity: Activity?) { calls += "ensureLocation" }
    override fun requestNearbyPermission(activity: Activity?) { calls += "requestNearby" }
    override fun armCommute(arm: Boolean) { calls += "armCommute:$arm" }
    override fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) { calls += "tearDown:$clearMap,$clearMirror" }
    override fun applyProfile(qr: QrData) { calls += "applyProfile" }
    override fun startAaHandoff() { calls += "startAaHandoff" }
    override fun triggerSelfMode(activity: Activity?) { activitiesSeenBySelfMode += activity; calls += "triggerSelfMode" }
    override fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) { calls += "joinWifi:$gateOnAaSteady" }
    override fun stopEverything() { calls += "stopEverything" }
    override fun clearProjection() { calls += "clearProjection" }
    override fun clearGpxSession() { calls += "clearGpxSession" }
    override fun abandonStaleNavigation(reason: String) { calls += "abandonNav:$reason" }
    override fun notifyForegroundResuming() { calls += "notifyForegroundResuming" }
    override fun saveBike(raw: String, qr: QrData) { calls += "saveBike" }
    override fun applyMirrorOrientation(activity: Activity?) { calls += "mirrorOrientation" }
    override fun clearMirrorOrientation() { calls += "clearMirrorOrientation" }
    override fun createMirrorConsentIntent(): Intent? { calls += "createMirrorIntent"; return mirrorIntent }
    override fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit) { calls += "armMirror"; onReady() }
    override fun closeRival(): Boolean { calls += "closeRival"; return true }
    override fun openRivalAppInfo(): Boolean { calls += "openRivalAppInfo"; return openRivalOk }
    override fun rivalInstalled() = rivalInstalledFlag
    override fun dashRemoteSubmit(query: String): Boolean { calls += "dashSubmit:$query"; return dashRemote }
    override fun sendDestinationToAndroidAuto(query: String) { calls += "sendToAa:$query" }
    override fun openMapSearch(activity: Activity?, query: String) { calls += "openMapSearch:$query" }
    override fun openMapHub(activity: Activity?) { calls += "openMapHub" }
    override fun openGpxPreview(activity: Activity?) { calls += "openGpxPreview" }
    override fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit) { calls += "checkUpdate:$manual"; onResult(updateResult) }
    override fun skipUpdate(version: String) { calls += "skipUpdate" }
    override fun openUrl(url: String): Boolean { calls += "openUrl"; return true }
    override fun openSettingsAction(action: String, forThisApp: Boolean): Boolean { calls += "openSettings:$action"; return true }
    override fun shareLog(activity: Activity?) { calls += "shareLog" }
    override fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String) { calls += "shareReport" }
    override fun clearCrash() { calls += "clearCrash" }
    override fun startSetup(activity: Activity?) { calls += "startSetup" }
    override fun hostDestroyedTeardown() { calls += "hostTeardown" }

    /** Run queued delayed blocks (in order); blocks may queue more. */
    fun runDelayed() {
        while (delayed.isNotEmpty()) delayed.removeAt(0).second.invoke()
    }

    companion object {
        const val SAMPLE_QR = "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37426&sn=Vasr&action=1" +
            "&ssid=CFMOTO4288&pwd=e278450c&uid=abc"
    }
}
