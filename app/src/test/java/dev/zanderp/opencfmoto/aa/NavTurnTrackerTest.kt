package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.NextTurn
import dev.zanderp.opencfmoto.Directions
import dev.zanderp.opencfmoto.Lane
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationDestinationDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationLane
import dev.zanderp.opencfmoto.TurnKind
import dev.zanderp.opencfmoto.TurnSide
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.MsgType
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationClusterStatus
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationCurrentPosition
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationManeuver
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationRoad
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationState
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStep
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStepDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDetail
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDistanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTurnTrackerTest {
    private fun detail(e: NextTurnDetail.NextEvent, s: NextTurnDetail.Side, road: String = "Main St", exit: Int? = null) =
        NextTurnDetail.newBuilder().setRoad(road).setNextTurn(e).setSide(s)
            .apply { if (exit != null) setTurnNumber(exit) }.build().toByteArray()

    private fun distance(m: Int) = NextTurnDistanceEvent.newBuilder().setDistanceMeters(m).build().toByteArray()

    private fun status(s: NavigationClusterStatus.NavigationStatusEnum) =
        NavigationClusterStatus.newBuilder().setStatus(s).build().toByteArray()

    @Test
    fun legacyDetailThenDistance() {
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.RIGHT)))
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, road = "Main St"), t.current)
        assertTrue(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(347)))
        assertEquals(347, t.current!!.distanceM)
        assertFalse(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(347)))
    }

    @Test
    fun distanceBeforeDetailIsKept() {
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(900)))   // first result is always reported
        assertNull(t.current)
        assertFalse(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(800)))
        assertNull(t.current)
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.SLIGHT_TURN, NextTurnDetail.Side.LEFT))
        assertEquals(NextTurn(TurnKind.SLIGHT, TurnSide.LEFT, distanceM = 800, road = "Main St"), t.current)
    }

    @Test
    fun roundaboutExitOnlyForRoundabout() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT, NextTurnDetail.Side.RIGHT, exit = 3))
        assertEquals(3, t.current!!.exitNumber)
        assertEquals(TurnKind.ROUNDABOUT, t.current!!.kind)
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT, exit = 2))
        assertNull(t.current!!.exitNumber)
    }

    @Test
    fun statusInactiveClearsAndReroutingKeeps() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        assertFalse(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.REROUTING)))
        assertTrue(t.current != null)
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.INACTIVE)))
        assertNull(t.current)
    }

    @Test
    fun stopClears() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, ByteArray(0)))
        assertNull(t.current)
    }

    @Test
    fun newerStateAndPosition() {
        val state = NavigationState.newBuilder().addSteps(
            NavigationStep.newBuilder()
                .setManeuver(NavigationManeuver.newBuilder().setType(NavigationManeuver.NavigationType.TURN_SHARP_RIGHT))
                .setRoad(NavigationRoad.newBuilder().setName("High St")),
        ).build().toByteArray()
        val pos = NavigationCurrentPosition.newBuilder().setStepDistance(
            NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(120)),
        ).build().toByteArray()
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))
        assertEquals(NextTurn(TurnKind.SHARP, TurnSide.RIGHT, distanceM = 120, road = "High St"), t.current)
    }

    @Test
    fun honoursOffsetAndLength() {
        val payload = distance(500)
        val framed = ByteArray(4) + payload + ByteArray(3)
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.STRAIGHT, NextTurnDetail.Side.UNSPECIFIED))
        t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, framed, 4, payload.size)
        assertEquals(500, t.current!!.distanceM)
        assertEquals(TurnSide.NONE, t.current!!.side)
    }

    @Test(expected = Exception::class)
    fun malformedPayloadThrows() {
        NavTurnTracker().onMessage(MsgType.NEXTTURNDETAILS_VALUE, byteArrayOf(0x0A, 0x7F))
    }

    @Test
    fun freshTrackerReportsFirstClear() {
        // A new AA session must be able to clear a turn left on the strip by the previous session.
        assertTrue(NavTurnTracker().onMessage(MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, ByteArray(0)))
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.INACTIVE)))
        assertFalse(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.INACTIVE)))
    }

    @Test
    fun distanceOnlyChangeIsNotSignificant() {
        val a = NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 350, road = "Main St")
        assertFalse(NavTurnTracker.significantChange(a, a.copy(distanceM = 340)))
        assertTrue(NavTurnTracker.significantChange(a, a.copy(side = TurnSide.LEFT)))
        assertTrue(NavTurnTracker.significantChange(a, a.copy(road = "High St")))
        assertTrue(NavTurnTracker.significantChange(a, a.copy(kind = TurnKind.ROUNDABOUT, exitNumber = 2)))
        assertTrue(NavTurnTracker.significantChange(a, null))
        assertTrue(NavTurnTracker.significantChange(null, a))
        assertFalse(NavTurnTracker.significantChange(null, null))
    }

    @Test
    fun unknownTypeIgnored() {
        assertFalse(NavTurnTracker().onMessage(1234, ByteArray(0)))
    }

    @Test
    fun navigationTypeMappingCoversEveryValue() {
        for (t in NavigationManeuver.NavigationType.values()) NavTurnTracker.fromNavigationType(t)  // must not throw
        val m = NavTurnTracker.Companion
        assertEquals(TurnKind.SLIGHT to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.KEEP_LEFT))
        assertEquals(TurnKind.NORMAL to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT))
        assertEquals(TurnKind.U_TURN to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.U_TURN_LEFT))
        assertEquals(TurnKind.RAMP_ON to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT))
        assertEquals(TurnKind.RAMP_OFF to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT))
        assertEquals(TurnKind.MERGE to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED))
        assertEquals(TurnKind.ROUNDABOUT to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW))
        assertEquals(TurnKind.DESTINATION to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.DESTINATION_RIGHT))
        assertEquals(TurnKind.STRAIGHT to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.NAME_CHANGE))
        assertEquals(TurnKind.UNKNOWN to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.FERRY_BOAT))
    }

    @Test
    fun nextEventMappingCoversEveryValue() {
        for (e in NextTurnDetail.NextEvent.values()) for (s in NextTurnDetail.Side.values()) NavTurnTracker.fromNextEvent(e, s)
        val m = NavTurnTracker.Companion
        assertEquals(TurnKind.SHARP to TurnSide.LEFT, m.fromNextEvent(NextTurnDetail.NextEvent.SHARP_TURN, NextTurnDetail.Side.LEFT))
        assertEquals(TurnKind.RAMP_OFF to TurnSide.RIGHT, m.fromNextEvent(NextTurnDetail.NextEvent.OFFRAMP, NextTurnDetail.Side.RIGHT))
        assertEquals(TurnKind.DESTINATION to TurnSide.NONE, m.fromNextEvent(NextTurnDetail.NextEvent.DESTINATION, NextTurnDetail.Side.UNSPECIFIED))
        assertEquals(TurnKind.UNKNOWN to TurnSide.NONE, m.fromNextEvent(NextTurnDetail.NextEvent.FERRY_TRAIN, NextTurnDetail.Side.UNSPECIFIED))
    }

    @Test
    fun typeNames() {
        assertEquals("NEXTTURNDETAILS", NavTurnTracker.typeName(MsgType.NEXTTURNDETAILS_VALUE))
        assertEquals("UNKNOWN(1234)", NavTurnTracker.typeName(1234))
    }

    private fun step(t: NavigationManeuver.NavigationType, road: String? = null, lanes: List<NavigationLane> = emptyList()) =
        NavigationStep.newBuilder().setManeuver(NavigationManeuver.newBuilder().setType(t))
            .apply { if (road != null) setRoad(NavigationRoad.newBuilder().setName(road)) }
            .addAllLanes(lanes).build()

    private fun lane(highlighted: Boolean, vararg shapes: NavigationLane.LaneDirection.Shape) =
        NavigationLane.newBuilder().apply {
            for (s in shapes) addLaneDirections(NavigationLane.LaneDirection.newBuilder().setShape(s).setIsHighlighted(highlighted))
        }.build()

    @Test
    fun newerMessagesGiveThenLanesEtaAndRemaining() {
        val state = NavigationState.newBuilder()
            .addSteps(step(NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT, "Galle Rd", listOf(
                lane(false, NavigationLane.LaneDirection.Shape.NORMAL_LEFT),
                lane(true, NavigationLane.LaneDirection.Shape.STRAIGHT, NavigationLane.LaneDirection.Shape.SLIGHT_RIGHT),
            )))
            .addSteps(step(NavigationManeuver.NavigationType.TURN_NORMAL_LEFT, "Duplication Rd"))
            .build().toByteArray()
        val pos = NavigationCurrentPosition.newBuilder()
            .setStepDistance(NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(350)))
            .addDestinationDistances(
                NavigationDestinationDistance.newBuilder()
                    .setDistance(NavigationDistance.newBuilder().setMeters(12400))
                    .setEstimatedTimeAtArrival("18:42")
                    .setTimeToArrivalSeconds(1320),
            ).build().toByteArray()
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))
        val d = t.directions
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 350, road = "Galle Rd"), d.turn)
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.LEFT, road = "Duplication Rd"), d.then)
        assertEquals(
            listOf(
                Lane(listOf(TurnKind.NORMAL to TurnSide.LEFT), highlighted = false),
                Lane(listOf(TurnKind.STRAIGHT to TurnSide.NONE, TurnKind.SLIGHT to TurnSide.RIGHT), highlighted = true),
            ),
            d.lanes,
        )
        assertEquals("18:42", d.etaText)
        assertEquals(1320L, d.etaSeconds)
        assertEquals(12400, d.remainingM)
    }

    @Test
    fun legacyMessagesGiveTurnOnlyDirections() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(200))
        assertEquals(Directions(NextTurn(TurnKind.NORMAL, TurnSide.LEFT, distanceM = 200, road = "Main St")), t.directions)
    }

    @Test
    fun directionsChangeAloneIsReported() {
        val state = NavigationState.newBuilder().addSteps(step(NavigationManeuver.NavigationType.STRAIGHT)).build().toByteArray()
        val t = NavTurnTracker()
        t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state)
        val pos = NavigationCurrentPosition.newBuilder().addDestinationDistances(
            NavigationDestinationDistance.newBuilder().setTimeToArrivalSeconds(600),
        ).build().toByteArray()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))   // only ETA changed
        assertEquals(600L, t.directions.etaSeconds)
    }

    @Test
    fun stopClearsDirections() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        t.onMessage(MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, ByteArray(0))
        assertEquals(Directions.NONE, t.directions)
    }

    @Test
    fun laneShapesMapToArrows() {
        val m = NavTurnTracker.Companion
        for (s in NavigationLane.LaneDirection.Shape.values()) m.fromLaneShape(s)   // must not throw
        assertEquals(TurnKind.SHARP to TurnSide.RIGHT, m.fromLaneShape(NavigationLane.LaneDirection.Shape.SHARP_RIGHT))
        assertEquals(TurnKind.U_TURN to TurnSide.LEFT, m.fromLaneShape(NavigationLane.LaneDirection.Shape.U_TURN_LEFT))
        assertEquals(TurnKind.STRAIGHT to TurnSide.NONE, m.fromLaneShape(NavigationLane.LaneDirection.Shape.UNKNOWN))
    }

    @Test
    fun handlesOnlyNavTypes() {
        assertTrue(NavTurnTracker.handles(MsgType.NEXTTURNDETAILS_VALUE))
        assertTrue(NavTurnTracker.handles(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE))
        assertFalse(NavTurnTracker.handles(1234))
    }

    @Test
    fun laneArrowToUseComesFirst() {
        // Shared straight+right lane where only RIGHT is the way to go: draw the right arrow.
        val l = NavigationLane.newBuilder()
            .addLaneDirections(NavigationLane.LaneDirection.newBuilder().setShape(NavigationLane.LaneDirection.Shape.STRAIGHT).setIsHighlighted(false))
            .addLaneDirections(NavigationLane.LaneDirection.newBuilder().setShape(NavigationLane.LaneDirection.Shape.NORMAL_RIGHT).setIsHighlighted(true))
            .build()
        val state = NavigationState.newBuilder()
            .addSteps(step(NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT, lanes = listOf(l))).build().toByteArray()
        val t = NavTurnTracker()
        t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state)
        assertEquals(Lane(listOf(TurnKind.NORMAL to TurnSide.RIGHT, TurnKind.STRAIGHT to TurnSide.NONE), true), t.directions.lanes.single())
    }
}
