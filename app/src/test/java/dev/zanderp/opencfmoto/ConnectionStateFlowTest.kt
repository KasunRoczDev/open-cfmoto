package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateFlowTest {
    @Test
    fun flowFollowsSet() {
        ConnectionState.set(Phase.JOINING_WIFI, "CFMOTO4288")
        assertEquals(Phase.JOINING_WIFI to "CFMOTO4288", ConnectionState.flow.value)
        ConnectionState.set(Phase.STREAMING)          // keeps detail
        assertEquals(Phase.STREAMING to "CFMOTO4288", ConnectionState.flow.value)
        ConnectionState.set(Phase.IDLE, "")
    }
}
