// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * [BikeWifi]'s "re-request the bike's AP" loop: backoff timing plus a hard off-switch for Stop.
 *
 * Pure Kotlin (timers are injected) so unit tests can prove that once [stop] runs, no queued or
 * in-flight retry re-requests Wi-Fi — the bug where Stop logged "Stopped" but the join loop kept
 * flipping the phase back to RECONNECTING ("re-requesting bike Wi-Fi (attempt 4) …").
 */
internal class WifiRejoinLoop(
    private val post: (delayMs: Long, block: () -> Unit) -> Unit,
    private val cancelAll: () -> Unit,
) {
    /** Number of re-requests scheduled since the last [start] / [resetAttempts]. */
    var attempts = 0
        private set

    /** False after [stop]: [schedule] is a no-op and already-queued retries do nothing. */
    @Volatile var enabled = false
        private set

    /** Bumped on every [start]/[stop] so a retry queued by an older session can't fire in a newer one. */
    private var generation = 0

    /** New join session: drop anything queued, reset the backoff, allow retries. */
    fun start() {
        cancelAll()
        generation++
        attempts = 0
        enabled = true
    }

    /** Rider tapped Stop (or Wi-Fi was released/parked): cancel queued retries and refuse new ones. */
    fun stop() {
        enabled = false
        generation++
        cancelAll()
    }

    /** The bike's AP came back — next loss starts the backoff from the fast first step again. */
    fun resetAttempts() { attempts = 0 }

    /**
     * Queue one re-request. [firstDelivered] = the session has had the bike's network at least once
     * (silent re-joins); before that, never use the near-instant retry — it cancels the system device
     * picker mid-tap ("app canceled the request to choose a device").
     */
    fun schedule(firstDelivered: Boolean, onFire: (attempt: Int) -> Unit) {
        if (!enabled) return
        attempts++
        val attempt = attempts
        val gen = generation
        post(delayFor(firstDelivered, attempt)) {
            if (!enabled || gen != generation) return@post
            onFire(attempt)
        }
    }

    companion object {
        const val REJOIN_FAST_MS = 300L
        const val REJOIN_BASE_MS = 2500L
        const val REJOIN_MAX_MS = 15000L
        /** After a failed *first* join (picker canceled / timed out), wait before re-prompting. */
        const val FIRST_JOIN_RETRY_MS = 4_000L

        fun delayFor(firstDelivered: Boolean, attempt: Int): Long = when {
            !firstDelivered -> FIRST_JOIN_RETRY_MS
            attempt <= 1 -> REJOIN_FAST_MS
            else -> minOf(REJOIN_BASE_MS * (attempt - 1), REJOIN_MAX_MS)
        }
    }
}
