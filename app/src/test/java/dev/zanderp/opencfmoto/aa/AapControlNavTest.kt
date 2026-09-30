package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.Directions
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.MsgType
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationCurrentPosition
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationManeuver
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationState
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStep
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStepDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class AapControlNavTest {
    private fun msg(type: Int, bytes: ByteArray) = AapMessage(Channel.ID_NAV, 0, type, 0, bytes.size, bytes)

    @Test
    fun oneBadMessageDoesNotFreezeThatTypeForever() {
        val fed = mutableListOf<Directions>()
        val nav = AapControlNav(feed = { fed += it })
        val state = NavigationState.newBuilder().addSteps(
            NavigationStep.newBuilder().setManeuver(NavigationManeuver.newBuilder().setType(NavigationManeuver.NavigationType.STRAIGHT)),
        ).build().toByteArray()
        nav.execute(msg(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state))
        nav.execute(msg(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, byteArrayOf(0x0A, 0x7F)))   // malformed
        val good = NavigationCurrentPosition.newBuilder().setStepDistance(
            NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(420)),
        ).build().toByteArray()
        nav.execute(msg(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, good))
        assertEquals(420, fed.last().turn?.distanceM)
    }

    @Test
    fun malformedMessageIsNotFed() {
        val fed = mutableListOf<Directions>()
        val nav = AapControlNav(feed = { fed += it })
        nav.execute(msg(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, byteArrayOf(0x0A, 0x7F)))
        assertEquals(0, fed.size)
    }
}
