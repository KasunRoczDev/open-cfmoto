package dev.zanderp.opencfmoto.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsDashTest {
    private val now = 1_794_300_000_000L
    private val spec = TyreSpecs.forModel("150 SC")
    private fun reading(kpa: Double, temp: Int = 26) = TpmsProtocol.Reading(TpmsProtocol.Family.TPMS, kpa, temp, 3.0, "0040B3")

    private fun state(front: WheelState = WheelState(), rear: WheelState = WheelState(), s: TpmsSettings = TpmsSettings()) =
        TpmsMonitor.State(settings = s, wheels = mapOf(Wheel.FRONT to front, Wheel.REAR to rear), spec = spec)

    @Test
    fun hiddenWhenNothingPairedOrTurnedOff() {
        assertNull(TpmsDash.panel(state(), "150 SC", now))
        val rear = WheelState(address = "B9", reading = reading(202.0), atMs = now)
        assertNull(TpmsDash.panel(state(rear = rear, s = TpmsSettings(onDash = false)), "150 SC", now))
    }

    @Test
    fun liveRearAndWaitingFront() {
        val p = TpmsDash.panel(
            state(front = WheelState(pendingId = "004600"), rear = WheelState(address = "B9", reading = reading(202.0), atMs = now - 60_000)),
            "150 SC", now,
        )!!
        assertEquals("CFMoto 150SC · psi", p.title)
        assertEquals(TyreTag("29.3", "26°C", TyreLevel.OK), p.rear)
        assertEquals(TyreTag("—", "waiting", TyreLevel.NONE), p.front)
        assertFalse(p.alarm)
    }

    @Test
    fun alarmAndStaleTags() {
        val low = TpmsDash.tag(WheelState(address = "A", reading = reading(150.0), atMs = now), Wheel.FRONT, TpmsSettings().effective(spec), now)
        assertEquals(TyreLevel.ALARM, low.level)
        assertEquals("LOW", low.sub)
        val asleep = TpmsDash.tag(WheelState(address = "A", reading = reading(200.0), atMs = now - 35 * 60_000), Wheel.REAR, TpmsSettings(), now)
        assertEquals(TyreTag("29.0", "35 min ago", TyreLevel.STALE), asleep)
        assertTrue(TpmsDash.panel(state(front = WheelState(address = "A", reading = reading(150.0), atMs = now)), "150 SC", now)!!.alarm)
    }

    @Test
    fun unknownModelUsesItsNameAndUnit() {
        val st = TpmsMonitor.State(
            settings = TpmsSettings(unit = PressureUnit.BAR),
            wheels = mapOf(Wheel.FRONT to WheelState(address = "A", reading = reading(220.0), atMs = now), Wheel.REAR to WheelState()),
        )
        val p = TpmsDash.panel(st, "800MT", now)!!
        assertEquals("800MT · bar", p.title)
        assertEquals("2.20", p.front.value)
        assertEquals("not paired", p.rear.sub)
    }
}
