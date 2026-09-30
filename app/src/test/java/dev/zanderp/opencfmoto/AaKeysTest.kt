package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class AaKeysTest {
    @Test
    fun controlsSendsToMapPreviewFirst() {
        assertEquals(AaKeys.Target.MAP, AaKeys.target(mapFirst = true, hasMap = true, hasAa = true))
        assertEquals(AaKeys.Target.AA, AaKeys.target(mapFirst = true, hasMap = false, hasAa = true))
    }

    @Test
    fun dashViewIgnoresMapPreview() {
        assertEquals(AaKeys.Target.AA, AaKeys.target(mapFirst = false, hasMap = true, hasAa = true))
        assertEquals(AaKeys.Target.NONE, AaKeys.target(mapFirst = false, hasMap = true, hasAa = false))
    }

    @Test
    fun noSessionSendsNothingWithTheScreensOwnMessage() {
        assertEquals(AaKeys.Target.NONE, AaKeys.target(mapFirst = true, hasMap = false, hasAa = false))
        assertEquals("Start Android Auto or Map first", AaKeys.noSinkText(mapFirst = true))
        assertEquals("Connect to the bike first", AaKeys.noSinkText(mapFirst = false))
    }
}
