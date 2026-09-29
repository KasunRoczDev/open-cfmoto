package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RivalRetryTest {
    private val sent = 1_000_000L

    @Test
    fun retriesWhenBackSoonAfterAppSettings() {
        assertTrue(RivalClient.shouldRetryOnResume(sent, sent + 20_000))
    }

    @Test
    fun retriesUpToFiveMinutes() {
        assertTrue(RivalClient.shouldRetryOnResume(sent, sent + RivalClient.RETRY_WINDOW_MS))
    }

    @Test
    fun noRetryAfterWindow() {
        assertFalse(RivalClient.shouldRetryOnResume(sent, sent + RivalClient.RETRY_WINDOW_MS + 1))
    }

    @Test
    fun noRetryWhenNeverSent() {
        assertFalse(RivalClient.shouldRetryOnResume(null, sent))
    }

    @Test
    fun noRetryOnClockGoingBackwards() {
        assertFalse(RivalClient.shouldRetryOnResume(sent, sent - 1))
    }
}
