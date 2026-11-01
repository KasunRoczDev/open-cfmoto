package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsUrlPassword() {
        val out = LogRedactor.redact("qr ssid=CFMOTO-x&pwd=supersecret&modelid=1")
        assertTrue(out.contains("pwd=«redacted»"))
        assertFalse(out.contains("supersecret"))
        assertTrue(out.contains("ssid=CFMOTO-x"))
    }

    @Test
    fun masksSerialTail() {
        val out = LogRedactor.redact("""{"HUID":"CRCP230501740"}""")
        assertTrue(out.contains("CRCP"))
        assertFalse(out.contains("CRCP230501740"))
    }

    @Test
    fun truncatesCoordinatePairs() {
        assertEquals("[LAP] armed at 52.37,4.89", LogRedactor.redact("[LAP] armed at 52.370216,4.895168"))
        assertEquals("pin -33.86, 151.20 ok", LogRedactor.redact("pin -33.868820, 151.209296 ok"))
    }

    @Test
    fun truncatesNamedCoordinates() {
        assertEquals("fix lat=52.37 lon=4.89", LogRedactor.redact("fix lat=52.3702 lon=4.8951"))
        assertEquals("latitude: -1.28", LogRedactor.redact("latitude: -1.286389"))
    }

    @Test
    fun leavesOrdinaryNumbersAlone() {
        val line = "[VIDEO] 800x384 @ 2.5 Mbps fps=29.97 took 12.5,8.0 ms v2.1.0"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun updateCheckerVersionCompare() {
        assertTrue(UpdateChecker.isNewer("v1.0.16", "1.0.15"))
        assertFalse(UpdateChecker.isNewer("1.0.15", "1.0.16"))
        assertTrue(UpdateChecker.isNewer("0.1.2.1", "0.1.2"))
    }
}
