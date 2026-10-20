package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcoBandTest {
    private fun kmh(v: Float) = v / 3.6f
    private val sc = EcoBands.forModel("150SC")

    @Test
    fun the150scHasItsOwnBandAnySpelling() {
        for (m in listOf("150SC", "150 SC", "150-sc")) assertEquals(EcoBand(40, 60, 5_000, 6_500), EcoBands.forModel(m))
    }

    @Test
    fun unknownModelFallsBackToTheGenericBand() {
        assertEquals(EcoBands.DEFAULT, EcoBands.forModel("800NK"))
        assertEquals(EcoBands.DEFAULT, EcoBands.forModel(null))
    }

    @Test
    fun hintPlacesSpeedAgainstTheBand() {
        assertEquals(EcoHint.BELOW, sc.hint(kmh(30f)))
        assertEquals(EcoHint.IN_BAND, sc.hint(kmh(40f)))
        assertEquals(EcoHint.IN_BAND, sc.hint(kmh(60f)))
        assertEquals(EcoHint.ABOVE, sc.hint(kmh(65f)))
    }

    @Test
    fun noHintWhenStoppedOrWithoutSpeed() {
        assertNull(sc.hint(null))
        assertNull(sc.hint(Float.NaN))
        assertNull(sc.hint(0f))
    }

    @Test
    fun hysteresisStopsFlickerAtTheEdges() {
        // Inside the band, a 61 km/h jitter stays in; from above, 59 km/h is not yet back in.
        assertEquals(EcoHint.IN_BAND, sc.hint(kmh(61.5f), EcoHint.IN_BAND))
        assertEquals(EcoHint.ABOVE, sc.hint(kmh(62.5f), EcoHint.IN_BAND))
        assertEquals(EcoHint.ABOVE, sc.hint(kmh(59f), EcoHint.ABOVE))
        assertEquals(EcoHint.IN_BAND, sc.hint(kmh(57.5f), EcoHint.ABOVE))
        assertEquals(EcoHint.BELOW, sc.hint(kmh(41f), EcoHint.BELOW))
        assertEquals(EcoHint.IN_BAND, sc.hint(kmh(38.5f), EcoHint.IN_BAND))
    }

    @Test
    fun labels() {
        assertEquals("40–60 km/h", sc.speedLabel(MapUnits.METRIC))
        assertEquals("24–37 mph", sc.speedLabel(MapUnits.IMPERIAL))
        assertEquals("~5–6.5k rpm", sc.rpmLabel())
        assertNull(EcoBands.DEFAULT.rpmLabel())
        assertEquals("60 km/h", sc.maxLabel(MapUnits.METRIC))
        assertEquals("37 mph", sc.maxLabel(MapUnits.IMPERIAL))
    }

    @Test
    fun scorerUsesTheModelBand() {
        // A steady 35 km/h is eco on the generic 30–70 band but not on the 150SC's 40–60.
        fun ride(s: EcoScorer): Double {
            for (i in 0..60) s.onFix(kmh(35f), i * 1000L)
            return s.ecoMeters
        }
        assert(ride(EcoScorer()) > 0.0)
        assertEquals(0.0, ride(EcoScorer(sc)), 0.0)
    }
}
