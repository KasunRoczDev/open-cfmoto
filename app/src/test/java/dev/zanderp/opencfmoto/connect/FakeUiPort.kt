package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent

/** Screen stand-in: android.jar stubs return defaults (isReturnDefaultValues), so Activity() is safe. */
class FakeUiPort(override val activity: Activity = Activity()) : UiPort {
    val calls = mutableListOf<String>()
    override fun launchQrScan() { calls += "launchQrScan" }
    override fun launchMirrorConsent(intent: Intent) { calls += "launchMirrorConsent" }
    override fun moveTaskToBack() { calls += "moveTaskToBack" }
}
