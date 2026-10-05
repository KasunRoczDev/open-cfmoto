// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

/**
 * One line of the owner's manual maintenance chart.
 *
 * [kmInterval]/[monthInterval] are the periodic-chart recurrence (null = that axis doesn't apply;
 * an item is due on whichever axis is set and reached first). [firstDueKm] overrides the very first
 * due point (before the item has ever been serviced) for items that also appear in the break-in
 * chart at 1000 km — e.g. engine oil recurs every 3000 km/3 months, but is first due at 1000 km.
 * [oneTime] marks a break-in-only item (no periodic-chart recurrence): once serviced, it's done.
 */
data class MaintenanceItem(
    val id: String,
    val category: String,
    val name: String,
    val action: String,
    val kmInterval: Int?,
    val monthInterval: Int?,
    val oneTime: Boolean = false,
    val firstDueKm: Int? = null,
)

/** Static per-model service schedules, transcribed from each bike's owner's manual. */
object MaintenanceSchedule {
    private val SCHEDULES: Map<String, List<MaintenanceItem>> = mapOf(
        "150sc" to CFMOTO_150SC,
    )

    /** Items for [model] (the free-typed Garage "model" text, e.g. "150 SC"); empty when unrecognized or null. */
    fun forModel(model: String?): List<MaintenanceItem> {
        val key = normalize(model) ?: return emptyList()
        return SCHEDULES[key] ?: emptyList()
    }

    /** Lowercase, letters/digits only, so "150 SC", "150-SC" and "150sc" all hit the same entry. */
    private fun normalize(model: String?): String? =
        model?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
}

/** CFMoto 150SC — owner's manual Break-In (pp. 106–108) + Periodic (pp. 108–114) maintenance charts. */
private val CFMOTO_150SC = listOf(
    // Engine
    MaintenanceItem("engine_oil", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000),
    MaintenanceItem("engine_oil_filter", "Engine", "Oil filter", "Replace", kmInterval = 3000, monthInterval = 3),
    MaintenanceItem("engine_coarse_oil_filter", "Engine", "Coarse oil filter", "Clean", kmInterval = 1000, monthInterval = null, oneTime = true),
    MaintenanceItem("engine_main_reducer_gearbox_oil", "Engine", "Main reducer gearbox oil", "Inspect and repair or replace if necessary", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000),
    MaintenanceItem("engine_idle", "Engine", "Idle", "Inspect and adjust if necessary", kmInterval = 5000, monthInterval = null, firstDueKm = 1000),
    MaintenanceItem("engine_coolant", "Engine", "Coolant", "Replace", kmInterval = 35000, monthInterval = 24, firstDueKm = 1000),
    MaintenanceItem("engine_throttle_system", "Engine", "Throttle system", "Inspect and adjust if necessary", kmInterval = 5000, monthInterval = null, firstDueKm = 1000),
    MaintenanceItem("engine_throttle_body", "Engine", "Throttle body", "Clean", kmInterval = 5000, monthInterval = null),
    MaintenanceItem("engine_air_filter", "Engine", "Air filter elements", "Clean", kmInterval = 5000, monthInterval = 12),
    MaintenanceItem("engine_spark_plug", "Engine", "Spark plug", "Replace", kmInterval = 10000, monthInterval = null),
    MaintenanceItem("engine_valve_clearance", "Engine", "Valve clearance", "Inspect and adjust if necessary", kmInterval = 40000, monthInterval = null),
    // Electrical
    MaintenanceItem("electrical_functions", "Electrical", "Functions of electrical parts", "Inspect and repair or replace if necessary", kmInterval = 10000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("electrical_battery", "Electrical", "Battery", "Inspect and recharge if necessary", kmInterval = 5000, monthInterval = 6, firstDueKm = 1000),
    MaintenanceItem("electrical_fuses", "Electrical", "Fuses or circuit breakers", "Inspect and replace if necessary", kmInterval = 5000, monthInterval = 6, firstDueKm = 1000),
    MaintenanceItem("electrical_wires", "Electrical", "Wires", "Inspect for damage and bending", kmInterval = 10000, monthInterval = 12),
    // Wheels
    MaintenanceItem("wheels_tire_condition", "Wheels", "Tire condition", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = 12),
    MaintenanceItem("wheels_tire_pressure", "Wheels", "Tire pressure", "Inspect and replenish if necessary", kmInterval = 5000, monthInterval = 12),
    MaintenanceItem("wheels_bearings", "Wheels", "Wheel bearings", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = null),
    // Brake
    MaintenanceItem("brake_systems", "Brake", "Front and rear braking systems", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = 12),
    MaintenanceItem("brake_discs", "Brake", "Brake discs", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("brake_pads", "Brake", "Brake pads", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("brake_fluid_level", "Brake", "Brake fluid level", "Inspect and replenish if necessary", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("brake_hoses", "Brake", "Brake hoses", "Inspect for damage and sealing", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("brake_lever", "Brake", "Front and rear brake lever", "Inspect free play", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("brake_fluid", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 24),
    // Suspension
    MaintenanceItem("suspension_system", "Suspension", "Suspension system", "Inspect and repair or replace if necessary", kmInterval = 5000, monthInterval = null),
    MaintenanceItem("suspension_shocks", "Suspension", "Front and rear shock absorbers", "Inspect for oil leakage", kmInterval = 10000, monthInterval = 12),
    // Frame
    MaintenanceItem("frame", "Frame", "Frame", "Inspect and repair or replace if necessary", kmInterval = 30000, monthInterval = null),
    // Steering
    MaintenanceItem("steering_bearings", "Steering", "Steering bearings", "Inspect and repair or replace if necessary", kmInterval = 10000, monthInterval = 12),
    // Cooling
    MaintenanceItem("cooling_level", "Cooling", "Coolant level", "Inspect and replenish if necessary", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("cooling_fan", "Cooling", "Radiator fan function", "Inspect and repair or replace if necessary", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("cooling_hoses", "Cooling", "Cooling hoses", "Inspect and replace if necessary", kmInterval = 10000, monthInterval = 12),
    // Transmission
    MaintenanceItem("transmission_belt", "Transmission", "Driving belt", "Inspect and adjust if necessary", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("transmission_weight_rollers", "Transmission", "Weight roller set", "Inspect and replace if necessary", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("transmission_drive_pulley", "Transmission", "Drive pulley", "Inspect and replace if necessary", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("transmission_driven_pulley", "Transmission", "Driven pulley", "Inspect and replace if necessary", kmInterval = 10000, monthInterval = 12),
    // Other
    MaintenanceItem("other_fault_memory", "Other", "Fault control memory", "Read with PDA", kmInterval = 10000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("other_movable_parts", "Other", "Movable parts", "Lubricate and inspect flexibility", kmInterval = 10000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("other_bolts_nuts", "Other", "Bolts and nuts", "Inspect firmness", kmInterval = 10000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("other_cables", "Other", "Cables", "Inspect for damage, bending and setting", kmInterval = 5000, monthInterval = 12, firstDueKm = 1000),
    MaintenanceItem("other_pipes_hoses", "Other", "Pipes, ducts, hoses and sleeves", "Inspect for cracks, sealing and correct setting", kmInterval = 10000, monthInterval = 12),
    MaintenanceItem("other_windshield", "Other", "Windshield", "Inspect for shaking; replace bracket if clearance exceeds 5 mm", kmInterval = 25000, monthInterval = null),
)
