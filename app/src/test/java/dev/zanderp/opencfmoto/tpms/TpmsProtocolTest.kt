package dev.zanderp.opencfmoto.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TpmsProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Flags, "TPMS" name, 11-byte manufacturer data — the layout the HR TPMS sensors advertise. */
    private fun tpmsPacket(volt: Int, temp: Int, pHi: Int, pLo: Int, id: IntArray = intArrayOf(0x12, 0xAB, 0x9F)) =
        bytes(
            0x02, 0x01, 0x06,
            0x05, 0x08, 0x54, 0x50, 0x4D, 0x53,
            0x0C, 0xFF, 0x00, 0x01, volt, temp, pHi, pLo, id[0], id[1], id[2], 0x00, 0x00,
        )

    private fun kyPacket(volt: Int, temp: Int, pressure: Int): ByteArray {
        val r = bytes(0x03, 0x03, 0xA5, 0x27, 0x03, 0x08, 0x4B, 0x59, 0x08, 0xFF, 0x00, volt, temp, pressure shr 8, pressure and 0xFF, 0, 0)
        val crc = TpmsProtocol.crc16(r, 0, 15)
        r[15] = TpmsProtocol.CRC_HI[(crc shr 8) and 0xFF]
        r[16] = TpmsProtocol.CRC_LO[crc and 0xFF]
        return r
    }

    @Test
    fun crcIsCrc16Cms() {
        assertEquals(0xAEE7, TpmsProtocol.crc16("123456789".toByteArray(), 0, 9))
    }

    @Test
    fun tablesAreFullSize() {
        assertEquals(256, TpmsProtocol.CRC_HI.size)
        assertEquals(256, TpmsProtocol.CRC_LO.size)
    }

    @Test
    fun decodesTpmsFamilyLikeTheOfficialApp() {
        // 2.9 V, 27 °C, 301 raw → 201 kPa gauge ≈ 29.2 psi (the rear tyre on the rider's phone).
        val r = TpmsProtocol.decode(tpmsPacket(volt = 170, temp = 77, pHi = 0x01, pLo = 0x2D))!!
        assertEquals(TpmsProtocol.Family.TPMS, r.family)
        assertEquals(2.9, r.batteryV, 0.001)
        assertEquals(27, r.tempC)
        assertEquals(201.0, r.pressureKpa, 0.001)
        assertEquals("12AB9F", r.pairId)
        assertEquals("29.2", PressureUnit.PSI.format(r.pressureKpa, Locale.US))
    }

    @Test
    fun tpmsBelowAtmosphereReadsFlat() {
        val r = TpmsProtocol.decode(tpmsPacket(volt = 150, temp = 40, pHi = 0, pLo = 99))!!
        assertEquals(0.0, r.pressureKpa, 0.0)
        assertEquals(-10, r.tempC)
    }

    @Test
    fun tpmsByAndroidNameOnly() {
        // Some stacks split the name out; the advertiser's resolved name is enough with manufacturer data present.
        val noName = bytes(0x02, 0x01, 0x06, 0x0C, 0xFF, 0, 1, 170, 77, 1, 0x2D, 1, 2, 3, 0, 0)
        assertNull(TpmsProtocol.decode(noName))
        assertEquals("010203", TpmsProtocol.decode(noName, deviceName = "TPMS")!!.pairId)
    }

    @Test
    fun decodesKyFamily() {
        // 3.0 V, 25 °C, 44.6 psi absolute → 30.0 psi gauge.
        val r = TpmsProtocol.decode(kyPacket(volt = 30, temp = 25, pressure = 446))!!
        assertEquals(TpmsProtocol.Family.KY, r.family)
        assertEquals(3.0, r.batteryV, 0.001)
        assertEquals(25, r.tempC)
        assertEquals("30.0", PressureUnit.PSI.format(r.pressureKpa, Locale.US))
        assertNull(r.pairId)
    }

    @Test
    fun kyNegativeTempAndFlat() {
        val r = TpmsProtocol.decode(kyPacket(volt = 30, temp = -5, pressure = 140))!!
        assertEquals(-5, r.tempC)
        assertEquals(0.0, r.pressureKpa, 0.0)
    }

    @Test
    fun kyWithBadCrcIsDropped() {
        val p = kyPacket(volt = 30, temp = 25, pressure = 446)
        p[13] = (p[13] + 1).toByte()
        assertNull(TpmsProtocol.decode(p))
    }

    @Test
    fun ignoresOtherAdverts() {
        assertNull(TpmsProtocol.decode(bytes(0x02, 0x01, 0x06, 0x05, 0x09, 0x41, 0x42, 0x43, 0x44, 0x03, 0xFF, 1, 2)))
        assertNull(TpmsProtocol.decode(ByteArray(0)))
        assertNull(TpmsProtocol.decode(null))
    }

    @Test
    fun normalizesPairIds() {
        assertEquals("12AB9F", TpmsProtocol.normalizePairId(" 12-ab-9f "))
        assertEquals("AB9F01", TpmsProtocol.normalizePairId("ID:0012AB9F01"))
        assertEquals("", TpmsProtocol.normalizePairId(null))
    }

    @Test
    fun unitsRoundTrip() {
        assertEquals("2.01", PressureUnit.BAR.format(201.0, Locale.US))
        assertEquals("201", PressureUnit.KPA.format(201.0, Locale.US))
        assertEquals(36.0, PressureUnit.PSI.fromKpa(PressureUnit.PSI.toKpa(36.0)), 1e-9)
    }

    @Test
    fun alarmsFollowThresholds() {
        val s = TpmsSettings()
        fun r(kpa: Double, t: Int = 30, v: Double = 3.0) = TpmsProtocol.Reading(TpmsProtocol.Family.TPMS, kpa, t, v, "000001")
        assertTrue(TpmsRules.alarms(r(220.0), Wheel.FRONT, s).isEmpty())
        assertEquals(setOf(TpmsAlarm.LOW), TpmsRules.alarms(r(150.0), Wheel.FRONT, s))
        assertEquals(setOf(TpmsAlarm.HIGH), TpmsRules.alarms(r(330.0), Wheel.REAR, s))
        assertEquals(setOf(TpmsAlarm.HOT, TpmsAlarm.BATTERY), TpmsRules.alarms(r(220.0, t = 75, v = 2.5), Wheel.REAR, s))
        assertEquals(setOf(TpmsAlarm.LOW), TpmsRules.alarms(r(220.0), Wheel.REAR, s.copy(rearLowKpa = 230.0)))
    }

    @Test
    fun pairingRules() {
        val reading = TpmsProtocol.Reading(TpmsProtocol.Family.TPMS, 200.0, 25, 3.0, "12AB9F")
        val flat = reading.copy(pressureKpa = 0.0)
        val none = mapOf(Wheel.FRONT to WheelState(), Wheel.REAR to WheelState())
        assertNull(TpmsRules.pairTarget("AA", reading, none, learning = null))
        assertEquals(Wheel.REAR, TpmsRules.pairTarget("AA", reading, none, learning = Wheel.REAR))
        assertNull(TpmsRules.pairTarget("AA", flat, none, learning = Wheel.REAR))   // learn needs an inflated tyre

        val waiting = mapOf(Wheel.FRONT to WheelState(pendingId = "12AB9F"), Wheel.REAR to WheelState())
        assertEquals(Wheel.FRONT, TpmsRules.pairTarget("AA", flat, waiting, learning = null))   // ID match needs no pressure
        assertEquals(Wheel.FRONT, TpmsRules.pairTarget("AA", reading, waiting, learning = Wheel.REAR))

        val taken = mapOf(Wheel.FRONT to WheelState(address = "aa"), Wheel.REAR to WheelState())
        assertNull(TpmsRules.pairTarget("AA", reading, taken, learning = Wheel.REAR))
    }

    @Test
    fun cfmoto150scSpec() {
        val spec = TyreSpecs.forModel("150 SC")!!
        assertEquals(spec, TyreSpecs.forModel("150sc"))
        assertNull(TyreSpecs.forModel("800MT"))
        assertEquals("27.6", PressureUnit.PSI.format(spec.recommendedKpa(Wheel.FRONT, loaded = false), Locale.US))
        assertEquals("27.6", PressureUnit.PSI.format(spec.recommendedKpa(Wheel.REAR, loaded = false), Locale.US))
        assertEquals("29.0", PressureUnit.PSI.format(spec.recommendedKpa(Wheel.REAR, loaded = true), Locale.US))
        assertEquals("27.6", PressureUnit.PSI.format(spec.recommendedKpa(Wheel.FRONT, loaded = true), Locale.US))
    }

    @Test
    fun limitsFollowTheSpecUntilCustomised() {
        val spec = TyreSpecs.forModel("150SC")!!
        val lim = TpmsSettings().effective(spec)
        assertEquals(171.0, lim.frontLowKpa, 0.01)    // 10 % under 190
        assertEquals(247.0, lim.frontHighKpa, 0.01)   // 30 % over 190
        assertEquals(180.0, TpmsSettings(loaded = true).effective(spec).rearLowKpa, 0.01)
        val own = TpmsSettings(customLimits = true, frontLowKpa = 150.0)
        assertEquals(150.0, own.effective(spec).frontLowKpa, 0.0)
        assertEquals(170.0, TpmsSettings().effective(null).frontLowKpa, 0.0)
        // The rider's 29.2 psi rear (201 kPa) is fine; 160 kPa is low.
        val r = TpmsProtocol.Reading(TpmsProtocol.Family.TPMS, 201.0, 27, 2.9, "12AB9F")
        assertTrue(TpmsRules.alarms(r, Wheel.REAR, lim).isEmpty())
        assertEquals(setOf(TpmsAlarm.LOW), TpmsRules.alarms(r.copy(pressureKpa = 160.0), Wheel.REAR, lim))
    }

    @Test
    fun decodesTheRidersRealRearSensor() {
        // Captured from the rider's rear sensor (B9:41:FA:00:40:B3) on 2026-11-10.
        val hex = "050854504D530CFF0300AF4C012E0040B3B6FA030327A8" + "00".repeat(31)
        val raw = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val r = TpmsProtocol.decode(raw)!!
        assertEquals("0040B3", r.pairId)
        assertEquals(202.0, r.pressureKpa, 0.0)
        assertEquals(26, r.tempC)
        assertEquals(3.0, r.batteryV, 0.001)
        assertEquals("29.3", PressureUnit.PSI.format(r.pressureKpa, Locale.US))
    }
}
