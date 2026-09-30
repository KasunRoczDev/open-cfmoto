package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBusTest {
    @Test
    fun revisionIncrementsPerLineAndOnClear() {
        val r0 = LogBus.revision.value
        LogBus.log("one")
        LogBus.log("two")
        assertEquals(r0 + 2, LogBus.revision.value)
        LogBus.clear()
        assertEquals(r0 + 3, LogBus.revision.value)
    }

    @Test
    fun tailReturnsOnlyTheEnd() {
        LogBus.clear()
        repeat(200) { LogBus.log("line-$it-" + "x".repeat(40)) }
        val t = LogBus.tail(500)
        assertTrue(t.length <= 500)
        assertTrue(t.trimEnd().endsWith("line-199-" + "x".repeat(40)))
    }
}
