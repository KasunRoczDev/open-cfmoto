package dev.zanderp.opencfmoto

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DashNightModeTest {
    @Test
    fun dayPhoneBecomesNightOnTheDash() {
        val day = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertEquals(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES, VideoPipeline.forceNightUiMode(day))
    }

    @Test
    fun nightStaysNightAndTypeBitsSurvive() {
        val carNight = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES
        assertEquals(carNight, VideoPipeline.forceNightUiMode(carNight))
        assertEquals(Configuration.UI_MODE_NIGHT_YES, VideoPipeline.forceNightUiMode(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }
}
