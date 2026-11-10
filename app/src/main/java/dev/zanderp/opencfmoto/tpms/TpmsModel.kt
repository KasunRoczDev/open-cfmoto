// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import java.util.Locale

enum class Wheel(val key: String) { FRONT("front"), REAR("rear") }

enum class PressureUnit(val key: String) {
    PSI("psi"), BAR("bar"), KPA("kpa");

    fun fromKpa(kpa: Double): Double = when (this) {
        PSI -> kpa * TpmsProtocol.PSI_PER_KPA
        BAR -> kpa / 100.0
        KPA -> kpa
    }

    fun toKpa(v: Double): Double = when (this) {
        PSI -> v / TpmsProtocol.PSI_PER_KPA
        BAR -> v * 100.0
        KPA -> v
    }

    /** Number only, in this unit: psi 1 decimal, bar 2 decimals, kPa whole. */
    fun format(kpa: Double, locale: Locale = Locale.getDefault()): String = when (this) {
        PSI -> String.format(locale, "%.1f", fromKpa(kpa))
        BAR -> String.format(locale, "%.2f", fromKpa(kpa))
        KPA -> String.format(locale, "%.0f", fromKpa(kpa))
    }

    companion object {
        fun of(key: String?): PressureUnit = entries.firstOrNull { it.key == key } ?: PSI
    }
}

enum class TpmsAlarm { LOW, HIGH, HOT, BATTERY }

/** Rider thresholds. Defaults are the official app's: 170–320 kPa, 70 °C, 2.6 V. */
data class TpmsSettings(
    val unit: PressureUnit = PressureUnit.PSI,
    val fahrenheit: Boolean = false,
    val frontLowKpa: Double = 170.0,
    val frontHighKpa: Double = 320.0,
    val rearLowKpa: Double = 170.0,
    val rearHighKpa: Double = 320.0,
    val highTempC: Int = 70,
    val lowBatteryV: Double = 2.6,
    val alerts: Boolean = true,
    val background: Boolean = false,
    /** The rider set their own limits; otherwise they follow the bike's [TyreSpec] when we know it. */
    val customLimits: Boolean = false,
    /** Riding two-up or carrying luggage — the spec's loaded pressures apply. */
    val loaded: Boolean = false,
    /** Show the bike and both tyres on the Android Auto side panel. */
    val onDash: Boolean = true,
) {
    fun lowKpa(w: Wheel) = if (w == Wheel.FRONT) frontLowKpa else rearLowKpa
    fun highKpa(w: Wheel) = if (w == Wheel.FRONT) frontHighKpa else rearHighKpa

    /**
     * The limits actually in force: the rider's own, or ones derived from the bike's recommended
     * pressures ([TyreSpec.LOW_FRACTION] under, [TyreSpec.HIGH_FRACTION] over).
     */
    fun effective(spec: TyreSpec?): TpmsSettings {
        if (customLimits || spec == null) return this
        val f = spec.recommendedKpa(Wheel.FRONT, loaded)
        val r = spec.recommendedKpa(Wheel.REAR, loaded)
        return copy(
            frontLowKpa = f * (1 - TyreSpec.LOW_FRACTION), frontHighKpa = f * (1 + TyreSpec.HIGH_FRACTION),
            rearLowKpa = r * (1 - TyreSpec.LOW_FRACTION), rearHighKpa = r * (1 + TyreSpec.HIGH_FRACTION),
        )
    }

    fun formatTemp(c: Int): String = if (fahrenheit) "${Math.round(c * 9 / 5.0 + 32)}°F" else "$c°C"
}

/** The maker's tyre figures for a model (cold pressures). */
data class TyreSpec(
    val modelName: String,
    val frontSize: String,
    val rearSize: String,
    val frontKpa: Double,
    val rearKpa: Double,
    val frontLoadedKpa: Double,
    val rearLoadedKpa: Double,
    val minTread: String,
) {
    fun recommendedKpa(w: Wheel, loaded: Boolean): Double = when (w) {
        Wheel.FRONT -> if (loaded) frontLoadedKpa else frontKpa
        Wheel.REAR -> if (loaded) rearLoadedKpa else rearKpa
    }

    val hasLoadedFigures get() = frontLoadedKpa != frontKpa || rearLoadedKpa != rearKpa

    companion object {
        /** Low alarm this far under the recommended cold pressure. */
        const val LOW_FRACTION = 0.10
        /** High alarm this far over it — tyres run 10–20 % above cold pressure once warm, so leave headroom. */
        const val HIGH_FRACTION = 0.30
    }
}

object TyreSpecs {
    /** CFMoto 150SC owner's manual: 190 kPa front and rear, 200 kPa rear two-up or loaded. */
    private val CFMOTO_150SC = TyreSpec(
        modelName = "CFMoto 150SC",
        frontSize = "110/70-14 M/C", rearSize = "130/70-13 M/C",
        frontKpa = 190.0, rearKpa = 190.0, frontLoadedKpa = 190.0, rearLoadedKpa = 200.0,
        minTread = "0.8–1.0 mm",
    )

    private val BY_MODEL = mapOf("150sc" to CFMOTO_150SC)

    /** Any spelling: "150 SC", "150-sc", "150SC" — same matching as the fuel specs. */
    fun forModel(model: String?): TyreSpec? = BY_MODEL[model?.lowercase()?.filter { it.isLetterOrDigit() }]
}

/** A wheel's sensor pairing and its last reading (survives restarts — sensors sleep when parked). */
data class WheelState(
    val address: String? = null,
    /** 6-hex ID waiting for its sensor to wake up and advertise (ID-mode pairing). */
    val pendingId: String? = null,
    val reading: TpmsProtocol.Reading? = null,
    val atMs: Long = 0,
    val rssi: Int? = null,
) {
    val paired get() = address != null
}

object TpmsRules {
    /** A reading older than this is shown as "last seen" rather than live: sensors sleep a few minutes after stopping. */
    const val STALE_MS = 10 * 60_000L

    fun alarms(r: TpmsProtocol.Reading, w: Wheel, s: TpmsSettings): Set<TpmsAlarm> {
        val out = mutableSetOf<TpmsAlarm>()
        if (r.pressureKpa < s.lowKpa(w)) out += TpmsAlarm.LOW
        if (r.pressureKpa > s.highKpa(w)) out += TpmsAlarm.HIGH
        if (r.tempC > s.highTempC) out += TpmsAlarm.HOT
        if (r.batteryV > 1.0 && r.batteryV < s.lowBatteryV) out += TpmsAlarm.BATTERY
        return out
    }

    /**
     * Which wheel (if any) an advertisement from an unpaired [address] should be paired to:
     * a wheel waiting for this sensor's printed ID, or the wheel in learn mode when the sensor reports
     * pressure (the official app's rule — an inflated tyre, so a loose sensor on the bench is skipped).
     * A sensor already paired to the other wheel is never stolen.
     */
    fun pairTarget(
        address: String,
        r: TpmsProtocol.Reading,
        wheels: Map<Wheel, WheelState>,
        learning: Wheel?,
    ): Wheel? {
        if (wheels.values.any { it.address.equals(address, ignoreCase = true) }) return null
        if (r.pairId != null) {
            wheels.entries.firstOrNull { (_, s) -> s.pendingId != null && s.pendingId == r.pairId }?.let { return it.key }
        }
        if (learning != null && r.pressureKpa > 0) return learning
        return null
    }

    fun isLive(atMs: Long, nowMs: Long) = atMs > 0 && nowMs - atMs < STALE_MS
}
