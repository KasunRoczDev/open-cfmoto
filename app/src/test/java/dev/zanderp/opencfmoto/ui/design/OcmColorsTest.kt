package dev.zanderp.opencfmoto.ui.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class OcmColorsTest {
    @Test
    fun lightTokensMatchSpec() {
        assertEquals(Color(0xFFF4F7FA), LightOcmColors.bg)
        assertEquals(Color(0xFF0A7F8C), LightOcmColors.accent)
        assertEquals(Color(0xFFFFFFFF), LightOcmColors.onAccent)
        assertEquals(Color(0xFF14191F), LightOcmColors.text)
        assertEquals(Color(0xFFD93A3A), LightOcmColors.error)
    }

    @Test
    fun darkTokensMatchSpec() {
        assertEquals(Color(0xFF0B1016), DarkOcmColors.bg)
        assertEquals(Color(0xFF171F29), DarkOcmColors.surface)
        assertEquals(Color(0xFF24303D), DarkOcmColors.surface2)
        assertEquals(Color(0xFF27C1D3), DarkOcmColors.accent)
        assertEquals(Color(0xFF06262B), DarkOcmColors.onAccent)
        assertEquals(Color(0xFFF4F7FA), DarkOcmColors.text)
        assertEquals(Color(0xFFAAB8C6), DarkOcmColors.text2)
        assertEquals(Color(0xFF34D399), DarkOcmColors.live)
    }
}
