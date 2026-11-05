package dev.zanderp.opencfmoto.connect

import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.WifiRejoinLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Field bug (S25 Ultra, bike off): Stop logged "[state] Stopped" but the bike Wi-Fi join loop kept
 * going — "re-requesting bike Wi-Fi (attempt 4) …" and the phase flipped back to RECONNECTING.
 * After Stop, nothing queued before it may re-request Wi-Fi or restart the connect flow.
 */
class StopCancelsRetriesTest {

    /** Handler stand-in: queued blocks run only when the test says so. */
    private class FakeTimers {
        val queued = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelAllCalls = 0
        fun post(ms: Long, block: () -> Unit) { queued += ms to block }
        fun cancelAll() { cancelAllCalls++; queued.clear() }
        fun runAll() { while (queued.isNotEmpty()) queued.removeAt(0).second.invoke() }
    }

    private lateinit var timers: FakeTimers
    private lateinit var loop: WifiRejoinLoop
    private val fired = mutableListOf<Int>()

    @Before
    fun setUp() {
        timers = FakeTimers()
        loop = WifiRejoinLoop(timers::post, timers::cancelAll)
        fired.clear()
    }

    private fun scheduleRejoin(firstDelivered: Boolean = false) =
        loop.schedule(firstDelivered) { attempt -> fired += attempt }

    @Test
    fun retriesRunWhileActive() {
        loop.start()
        scheduleRejoin(); timers.runAll()
        scheduleRejoin(); timers.runAll()
        assertEquals(listOf(1, 2), fired)
    }

    @Test
    fun stopCancelsQueuedRetry() {
        loop.start()
        scheduleRejoin(); timers.runAll()       // attempt 1
        scheduleRejoin()                         // attempt 2 queued (join timed out, bike off)
        loop.stop()                              // rider taps Stop
        assertTrue("queued retry must be cancelled", timers.queued.isEmpty())
        timers.runAll()
        assertEquals(listOf(1), fired)
    }

    @Test
    fun retryAlreadyInFlightDoesNothingAfterStop() {
        // A retry the handler has already dequeued (or a cancel that raced) must still be inert.
        val leaky = mutableListOf<() -> Unit>()
        val loop = WifiRejoinLoop(post = { _, b -> leaky += b }, cancelAll = { /* misses it */ })
        loop.start()
        loop.schedule(firstDelivered = false) { fired += it }
        loop.stop()
        leaky.forEach { it() }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun joinTimeoutAfterStopSchedulesNothing() {
        // The 90 s requestNetwork timeout (onUnavailable) lands after Stop → no attempt N+1.
        loop.start()
        scheduleRejoin(); timers.runAll()
        loop.stop()
        scheduleRejoin()
        assertTrue(timers.queued.isEmpty())
        assertEquals(1, loop.attempts)
        assertFalse(loop.enabled)
    }

    @Test
    fun retryFromOldSessionCannotFireInNewOne() {
        val leaky = mutableListOf<() -> Unit>()
        val loop = WifiRejoinLoop(post = { _, b -> leaky += b }, cancelAll = { })
        loop.start()
        loop.schedule(firstDelivered = false) { fired += 100 + it }
        loop.stop()
        loop.start()                             // rider taps Connect again
        leaky.toList().forEach { it() }
        assertTrue("stale retry from the stopped session fired", fired.isEmpty())
    }

    @Test
    fun connectAfterStopStartsFreshCount() {
        loop.start()
        repeat(3) { scheduleRejoin(); timers.runAll() }
        loop.stop()
        loop.start()
        scheduleRejoin(); timers.runAll()
        assertEquals(listOf(1, 2, 3, 1), fired)
    }

    @Test
    fun backoffUnchanged() {
        assertEquals(4_000L, WifiRejoinLoop.delayFor(firstDelivered = false, attempt = 1))
        assertEquals(4_000L, WifiRejoinLoop.delayFor(firstDelivered = false, attempt = 5))
        assertEquals(300L, WifiRejoinLoop.delayFor(firstDelivered = true, attempt = 1))
        assertEquals(2_500L, WifiRejoinLoop.delayFor(firstDelivered = true, attempt = 2))
        assertEquals(15_000L, WifiRejoinLoop.delayFor(firstDelivered = true, attempt = 20))
    }

    // ------------------------------------------------------------ controller timers

    private fun controller(e: FakeConnectionEngine) =
        ConnectionController(e).also { it.attach(FakeUiPort()) }

    @Test
    fun stopVoidsQueuedConnectTimers() {
        val e = FakeConnectionEngine()
        val c = controller(e)
        c.onConnectPressed()                     // queues 900 / 4500 / 18000 self-mode + give-up timers
        assertTrue(e.delayed.isNotEmpty())
        e.phase = Phase.RECONNECTING             // what the surviving Wi-Fi loop used to set
        c.stop()
        e.calls.clear()
        e.runDelayed()
        assertFalse("self-mode re-triggered after Stop", e.calls.contains("triggerSelfMode"))
        assertFalse("phase changed after Stop: ${e.calls}", e.calls.any { it.startsWith("setPhase") })
    }

    @Test
    fun stopBeforeAutoConnectFiresKeepsItStopped() {
        val e = FakeConnectionEngine()
        val c = controller(e)
        c.onHostCreated(startGpx = false, resume = false, btTrigger = false)   // queues auto-connect (1200)
        c.stop()
        e.calls.clear()
        e.runDelayed()
        assertFalse(e.calls.contains("startAaHandoff"))
        assertFalse(e.calls.any { it.startsWith("joinWifi") })
    }

    @Test
    fun connectAfterStopStillRuns() {
        val e = FakeConnectionEngine()
        val c = controller(e)
        c.stop()
        e.phase = Phase.STOPPED
        c.onConnectPressed()
        e.calls.clear()
        e.runDelayed()
        assertTrue(e.calls.contains("triggerSelfMode"))
    }
}
