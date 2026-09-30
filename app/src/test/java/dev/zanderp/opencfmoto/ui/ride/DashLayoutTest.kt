package dev.zanderp.opencfmoto.ui.ride

import org.junit.Assert.assertEquals
import org.junit.Test

class DashLayoutTest {
    @Test
    fun portraitStacksPadUnderVideo() {
        assertEquals(DashRects(1080, 1300, 0, 1300), dashRects(1080, 2000, landscape = false, padMain = 700))
    }

    @Test
    fun landscapePutsPadOnTheRight() {
        assertEquals(DashRects(1220, 900, 1220, 0), dashRects(2000, 900, landscape = true, padMain = 780))
    }

    @Test
    fun noPadGivesVideoTheWholeArea() {
        assertEquals(DashRects(2000, 900, 2000, 0), dashRects(2000, 900, landscape = true, padMain = 0))
    }

    @Test
    fun padLargerThanAreaNeverGoesNegative() {
        assertEquals(DashRects(1080, 0, 0, 0), dashRects(1080, 500, landscape = false, padMain = 700))
    }
}
