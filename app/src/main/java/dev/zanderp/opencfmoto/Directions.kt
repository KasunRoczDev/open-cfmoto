// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One road lane: the arrows painted on it and whether Maps says to use it. */
data class Lane(val arrows: List<Pair<TurnKind, TurnSide>>, val highlighted: Boolean)

/**
 * Everything the Directions band can show, as Android Auto last sent it. Parts Maps doesn't send stay
 * null/empty and are simply not drawn. [etaSeconds] is "seconds to arrival" at the time of the message.
 */
data class Directions(
    val turn: NextTurn?,
    val then: NextTurn? = null,
    val lanes: List<Lane> = emptyList(),
    val etaText: String? = null,
    val etaSeconds: Long? = null,
    val remainingM: Int? = null,
) {
    companion object {
        val NONE = Directions(null)
    }
}

/** Which optional parts fit the band this frame. */
data class DirectionsPlan(val road: Boolean, val then: Boolean, val lanes: Boolean, val eta: Boolean)

/** Display strings for one band frame. Data-class equality = "nothing visible changed". */
data class DirectionsView(
    val turn: NextTurn,
    val distance: String,
    val road: String?,
    val then: NextTurn?,
    val lanes: List<Lane>,
    val eta: String?,
    val remaining: String?,
)

object DirectionsFormat {
    const val STALE_MS = 60_000L
    const val THEN_MAX_M = 1000

    // Cell widths as multiples of the band height (used to decide what fits; the renderer uses the same).
    internal const val TURN_W = 2.6f
    internal const val ROAD_EXTRA_W = 0.8f
    internal const val THEN_W = 1.4f
    internal const val LANE_W = 0.5f
    internal const val LANES_PAD_W = 0.3f
    internal const val ETA_W = 2.4f

    /** A route is shown while there's a next turn and Maps spoke within [STALE_MS]. */
    fun active(d: Directions?, lastMsgElapsedMs: Long, nowElapsedMs: Long): Boolean =
        d?.turn != null && nowElapsedMs - lastMsgElapsedMs <= STALE_MS

    /** Maps' own arrival text when sent, else message time + seconds in the phone's clock format. */
    fun etaText(mapsText: String?, etaSeconds: Long?, receivedAtWallMs: Long, zone: TimeZone, is24h: Boolean): String? {
        if (!mapsText.isNullOrBlank()) return "ETA $mapsText"
        val s = etaSeconds ?: return null
        val fmt = SimpleDateFormat(if (is24h) "H:mm" else "h:mm a", Locale.US).apply { timeZone = zone }
        return "ETA " + fmt.format(Date(receivedAtWallMs + s * 1000L))
    }

    /** Fit the optional parts into [bandW]; drop order lanes → then → road → ETA. */
    fun plan(bandW: Int, bandH: Int, hasRoad: Boolean, hasThen: Boolean, laneCount: Int, hasEta: Boolean): DirectionsPlan {
        val h = bandH.toFloat()
        var road = hasRoad
        var then = hasThen
        var lanes = laneCount > 0
        var eta = hasEta
        fun width(): Float = h * (TURN_W + (if (road) ROAD_EXTRA_W else 0f) + (if (then) THEN_W else 0f) +
            (if (lanes) laneCount * LANE_W + LANES_PAD_W else 0f) + (if (eta) ETA_W else 0f))
        if (width() > bandW) lanes = false
        if (width() > bandW) then = false
        if (width() > bandW) road = false
        if (width() > bandW) eta = false
        return DirectionsPlan(road, then, lanes, eta)
    }

    fun view(
        d: Directions,
        units: MapUnits,
        bandW: Int,
        bandH: Int,
        receivedAtWallMs: Long,
        zone: TimeZone,
        is24h: Boolean,
    ): DirectionsView? {
        val turn = d.turn ?: return null
        val imperial = units == MapUnits.IMPERIAL
        val thenCandidate = d.then?.takeIf { (turn.distanceM ?: Int.MAX_VALUE) <= THEN_MAX_M }
        val eta = etaText(d.etaText, d.etaSeconds, receivedAtWallMs, zone, is24h)
        val p = plan(bandW, bandH, !turn.road.isNullOrBlank(), thenCandidate != null, d.lanes.size, eta != null)
        return DirectionsView(
            turn = turn,
            distance = turn.distanceM?.let { MetricsFormat.turnDistance(it, imperial) } ?: MetricsFormat.NONE,
            road = turn.road.takeIf { p.road },
            then = thenCandidate.takeIf { p.then },
            lanes = if (p.lanes) d.lanes else emptyList(),
            eta = eta.takeIf { p.eta },
            remaining = d.remainingM?.takeIf { p.eta }?.let { MetricsFormat.turnDistance(it, imperial) },
        )
    }
}
