// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.Directions
import dev.zanderp.opencfmoto.Lane
import dev.zanderp.opencfmoto.NextTurn
import dev.zanderp.opencfmoto.TurnKind
import dev.zanderp.opencfmoto.TurnSide
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.MsgType
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationClusterStatus
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationCurrentPosition
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationLane
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationManeuver
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationState
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDetail
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDistanceEvent

/**
 * Folds Android Auto instrument-cluster navigation messages (both the legacy NEXTTURN* family and
 * the newer NAVIGATION_STATE/CURRENT_POSITION family) into one [NextTurn]. Pure: no logging, no
 * Android — [AapControlNav] logs. Malformed payloads throw (the caller catches and logs).
 */
class NavTurnTracker {
    var current: NextTurn? = null
        private set

    /** Full directions for the Directions band (turn + then/lanes/ETA when the newer messages carry them). */
    var directions: Directions = Directions.NONE
        private set

    private var then: NextTurn? = null
    private var lanes: List<Lane> = emptyList()
    private var etaText: String? = null
    private var etaSeconds: Long? = null
    private var remainingM: Int? = null

    private var kind: TurnKind? = null
    private var side = TurnSide.NONE
    private var exit: Int? = null
    private var road: String? = null
    private var distanceM: Int? = null

    // A new tracker (new AA session) reports its first result even when it is "no turn", so a turn
    // left on the strip by the previous session gets cleared.
    private var reported = false

    /** @return true when [current] changed (always true for the first handled message). */
    fun onMessage(type: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        when (type) {
            MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> clear()
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                val s = NavigationClusterStatus.newBuilder().mergeFrom(data, offset, length).buildPartial()
                when (s.status) {
                    NavigationClusterStatus.NavigationStatusEnum.ACTIVE,
                    NavigationClusterStatus.NavigationStatusEnum.REROUTING -> Unit
                    else -> clear()
                }
            }
            MsgType.NEXTTURNDETAILS_VALUE -> {
                val d = NextTurnDetail.newBuilder().mergeFrom(data, offset, length).buildPartial()
                val (k, sd) = fromNextEvent(d.nextTurn, d.side)
                kind = k
                side = sd
                exit = if (k == TurnKind.ROUNDABOUT && d.hasTurnNumber() && d.turnNumber > 0) d.turnNumber else null
                road = d.road.takeIf { d.hasRoad() && it.isNotBlank() }
            }
            MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                val e = NextTurnDistanceEvent.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (e.hasDistanceMeters()) distanceM = e.distanceMeters
            }
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                val st = NavigationState.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (st.stepsCount > 0) {
                    val step = st.getSteps(0)
                    val (k, sd) = fromNavigationType(step.maneuver.type)
                    kind = k
                    side = sd
                    val man = step.maneuver
                    exit = if (k == TurnKind.ROUNDABOUT && man.hasRoundaboutExitNumber() && man.roundaboutExitNumber > 0) {
                        man.roundaboutExitNumber
                    } else null
                    road = step.road.name.takeIf { step.hasRoad() && it.isNotBlank() }
                    lanes = step.lanesList.map { l ->
                        Lane(l.laneDirectionsList.map { fromLaneShape(it.shape) }, l.laneDirectionsList.any { it.isHighlighted })
                    }
                    then = if (st.stepsCount > 1) {
                        val s2 = st.getSteps(1)
                        val (k2, sd2) = fromNavigationType(s2.maneuver.type)
                        NextTurn(k2, sd2, road = s2.road.name.takeIf { s2.hasRoad() && it.isNotBlank() })
                    } else null
                }
            }
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                val p = NavigationCurrentPosition.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (p.hasStepDistance() && p.stepDistance.hasDistance()) distanceM = p.stepDistance.distance.meters
                if (p.destinationDistancesCount > 0) {
                    val dd = p.getDestinationDistances(0)
                    etaText = dd.estimatedTimeAtArrival.takeIf { dd.hasEstimatedTimeAtArrival() && it.isNotBlank() }
                    etaSeconds = if (dd.hasTimeToArrivalSeconds()) dd.timeToArrivalSeconds else null
                    remainingM = if (dd.hasDistance()) dd.distance.meters else null
                }
            }
            MsgType.INSTRUMENT_CLUSTER_START_VALUE -> Unit
            else -> return false
        }
        val next = kind?.let { NextTurn(it, side, exit, distanceM, road) }
        val dir = if (next == null) Directions.NONE else Directions(next, then, lanes, etaText, etaSeconds, remainingM)
        if (next == current && dir == directions && reported) return false
        current = next
        directions = dir
        reported = true
        return true
    }

    private fun clear() {
        kind = null
        side = TurnSide.NONE
        exit = null
        road = null
        distanceM = null
        then = null
        lanes = emptyList()
        etaText = null
        etaSeconds = null
        remainingM = null
    }

    companion object {
        private val NAV_TYPES = setOf(
            MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
            MsgType.INSTRUMENT_CLUSTER_START_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE,
            MsgType.NEXTTURNDETAILS_VALUE,
            MsgType.NEXTTURNDISTANCEANDTIME_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE,
        )

        /** True for the message types [onMessage] understands (used to keep the route "fresh"). */
        fun handles(type: Int): Boolean = type in NAV_TYPES

        internal fun fromLaneShape(s: NavigationLane.LaneDirection.Shape): Pair<TurnKind, TurnSide> = when (s) {
            NavigationLane.LaneDirection.Shape.SLIGHT_LEFT -> TurnKind.SLIGHT to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.SLIGHT_RIGHT -> TurnKind.SLIGHT to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.NORMAL_LEFT -> TurnKind.NORMAL to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.NORMAL_RIGHT -> TurnKind.NORMAL to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.SHARP_LEFT -> TurnKind.SHARP to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.SHARP_RIGHT -> TurnKind.SHARP to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.U_TURN_LEFT -> TurnKind.U_TURN to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.U_TURN_RIGHT -> TurnKind.U_TURN to TurnSide.RIGHT
            else -> TurnKind.STRAIGHT to TurnSide.NONE
        }
        /** True when something a rider would notice changed (not just the ~1 Hz distance tick) — log gate. */
        fun significantChange(prev: NextTurn?, next: NextTurn?): Boolean =
            prev?.copy(distanceM = null) != next?.copy(distanceM = null)

        fun typeName(type: Int): String = MsgType.forNumber(type)?.name ?: "UNKNOWN($type)"

        internal fun fromNextEvent(e: NextTurnDetail.NextEvent, s: NextTurnDetail.Side): Pair<TurnKind, TurnSide> {
            val kind = when (e) {
                NextTurnDetail.NextEvent.DEPART -> TurnKind.DEPART
                NextTurnDetail.NextEvent.NAME_CHANGE, NextTurnDetail.NextEvent.STRAIGHT -> TurnKind.STRAIGHT
                NextTurnDetail.NextEvent.SLIGHT_TURN -> TurnKind.SLIGHT
                NextTurnDetail.NextEvent.TURN -> TurnKind.NORMAL
                NextTurnDetail.NextEvent.SHARP_TURN -> TurnKind.SHARP
                NextTurnDetail.NextEvent.U_TURN -> TurnKind.U_TURN
                NextTurnDetail.NextEvent.ON_RAMP -> TurnKind.RAMP_ON
                NextTurnDetail.NextEvent.OFFRAMP -> TurnKind.RAMP_OFF
                NextTurnDetail.NextEvent.FORK -> TurnKind.FORK
                NextTurnDetail.NextEvent.MERGE -> TurnKind.MERGE
                NextTurnDetail.NextEvent.ROUNDABOUT_ENTER,
                NextTurnDetail.NextEvent.ROUNDABOUT_EXIT,
                NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> TurnKind.ROUNDABOUT
                NextTurnDetail.NextEvent.DESTINATION -> TurnKind.DESTINATION
                else -> TurnKind.UNKNOWN
            }
            val side = when (s) {
                NextTurnDetail.Side.LEFT -> TurnSide.LEFT
                NextTurnDetail.Side.RIGHT -> TurnSide.RIGHT
                else -> TurnSide.NONE
            }
            return kind to (if (kind == TurnKind.UNKNOWN) TurnSide.NONE else side)
        }

        internal fun fromNavigationType(t: NavigationManeuver.NavigationType): Pair<TurnKind, TurnSide> {
            val n = t.name
            val side = when {
                n.endsWith("LEFT") -> TurnSide.LEFT
                n.endsWith("RIGHT") -> TurnSide.RIGHT
                else -> TurnSide.NONE
            }
            val kind = when {
                n.startsWith("ON_RAMP") -> TurnKind.RAMP_ON
                n.startsWith("OFF_RAMP") -> TurnKind.RAMP_OFF
                n.startsWith("ROUNDABOUT") -> TurnKind.ROUNDABOUT
                n.startsWith("DESTINATION") -> TurnKind.DESTINATION
                n.startsWith("U_TURN") -> TurnKind.U_TURN
                n.startsWith("FORK") -> TurnKind.FORK
                n.startsWith("MERGE") -> TurnKind.MERGE
                n.startsWith("KEEP") || n.startsWith("TURN_SLIGHT") -> TurnKind.SLIGHT
                n.startsWith("TURN_SHARP") -> TurnKind.SHARP
                n.startsWith("TURN_NORMAL") -> TurnKind.NORMAL
                n == "DEPART" -> TurnKind.DEPART
                n == "STRAIGHT" || n == "NAME_CHANGE" -> TurnKind.STRAIGHT
                else -> TurnKind.UNKNOWN
            }
            return kind to (if (kind == TurnKind.ROUNDABOUT || kind == TurnKind.UNKNOWN) TurnSide.NONE else side)
        }
    }
}
