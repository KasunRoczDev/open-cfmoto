# Maintenance Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Me ▸ Maintenance estimates the 150SC's odometer from a one-time baseline plus tracked ride distance, shows every service item from the owner's manual with an OK/Due soon/Overdue status, lets the rider mark an item serviced, and pushes one notification per item the first time it goes overdue.

**Architecture:** A pure `maintenance` package (`MaintenanceItem`/`MaintenanceSchedule` static data, `MaintenanceState`/`ItemState` tracking shape, `MaintenanceEstimate` due-math — all unit-tested, no Android dependency) sits under a thin Android layer (`MaintenanceCodec` JSON encode/decode, also pure and tested; `MaintenanceStore` file IO per bike, following the existing `TripStore`/`EcoStore` pattern). A new Compose screen (`ui/maintenance/MaintenanceScreen.kt`) reads/writes through that layer. A daily `WorkManager` worker reuses the same pure `MaintenanceEstimate.itemsToNotify` to decide what to notify.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.03.00, Material3), `androidx.work:work-runtime-ktx` (new dependency), JUnit 4, `org.json`.

**Spec:** `docs/superpowers/specs/2026-10-02-maintenance-tracker-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto`; pure/Android maintenance logic under `dev.zanderp.opencfmoto.maintenance`; the Compose screen under `dev.zanderp.opencfmoto.ui.maintenance`. New `.kt` files start with the 3-line SPDX/AGPL header (copy from `TripStore.kt`).
- Tap targets ≥ **56 dp** (match `SettingRow`/`PrimaryButton` conventions already in `ui/design/Components.kt`).
- Colours only from `Ocm.colors`; type only from `OcmType`; reuse `StatusChip`/`StatusKind` for the OK/Due soon/Overdue pills (`StatusKind.LIVE` = OK, `BUSY` = Due soon, `ERROR` = Overdue).
- New strings go in `res/values/strings.xml` only (other locales lag by convention — see `forest_*` strings, never translated). Do **not** delete or renumber existing strings.
- "Due soon" window = **300 km** or **14 days** before the due point (`MaintenanceEstimate.DUE_SOON_KM` / `DUE_SOON_DAYS`), per spec §3.1.
- Build/test from repo root in Git Bash: `. .superpowers/env.sh` (sets `JAVA_HOME`, `GRADLE_USER_HOME` on F:, and `$F`), then `./gradlew <task> $F`.
- "Suite green" = only the known pre-existing failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` (exactly 1 failure, unrelated to this work).
- Commit trailer: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

## Rulings made while planning (spec gaps found while writing this plan)

1. **Trips aren't tagged with a bike today.** `Trip`/`TripStore` have no bike association at all (confirmed: no `bikeId`/`bikeRaw` field anywhere). The spec's `estimateKm` formula assumes "trips for this bike" exists. Fix: add `Trip.bikeRaw: String?` (default `null`), stamp it in `TripRecorder.finalizeLeg` from `BikeMemory.selected(appContext)?.raw`, persist it in `TripStore`. Trips recorded before this change (or with no bike selected) have `bikeRaw == null` and count toward **every** bike's estimate (simplest rule that doesn't silently drop a rider's existing ride history; see Task 2).
2. **Break-in items that also recur need a different first due point.** The spec's appendix (§9) says the break-in chart only seeds the *first* due km (1000 km) for items that also appear in the periodic chart, but §3.1's due-math pseudocode never captured that as a field. Fix: `MaintenanceItem.firstDueKm: Int?` — used only when the item has never been serviced; falls back to `kmInterval` when absent. Set on the 17 items that appear in both charts (see Task 1 data).
3. **Compose sub-screens off Me don't use `OcmTopBar`/back arrows today** (`ForestScreen`, `LogsScreen` are plain `OcmScreen` + title text; back is the system/nav-bar back button via `NavHost`). `MaintenanceScreen` follows the same convention — no `OcmTopBar`.
4. **No Compose `AlertDialog` precedent exists** (all dialogs today are either View-based `MaterialAlertDialogBuilder`, e.g. `GarageActivity`, or the Compose `AlertDialog` in `ui/dialog/DialogHost.kt` for `ReportProblem`/`MirrorReady`). `MaintenanceScreen`'s baseline/mark-serviced dialogs use `androidx.compose.material3.AlertDialog`, matching `DialogHost.kt`'s pattern (the only existing Compose dialog code).

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `maintenance/MaintenanceItem.kt` | Create | `MaintenanceItem` data class + `MaintenanceSchedule` (150SC data, model-name matching) |
| `maintenance/MaintenanceState.kt` | Create | `ItemState`, `MaintenanceState`, `MaintenanceStatus` |
| `maintenance/MaintenanceEstimate.kt` | Create | pure due-math: estimate, due points, status, notify list |
| `TripModel.kt` | Modify | `Trip.bikeRaw: String? = null` |
| `TripStore.kt` | Modify | persist/parse `bikeRaw` |
| `TripRecorder.kt` | Modify | stamp `bikeRaw` from the selected bike when saving |
| `maintenance/MaintenanceCodec.kt` | Create | pure JSON encode/decode of `MaintenanceState` |
| `maintenance/MaintenanceStore.kt` | Create | per-bike file IO (pattern: `TripStore`), baseline/mark-serviced/notified writers |
| `maintenance/MaintenanceSummary.kt` | Create | Me-tab loader: due count + setup state, off main thread |
| `ui/design/Components.kt` | Modify | `SettingRow` gets an optional `badge` parameter |
| `ui/tabs/MeTab.kt` | Modify | "Maintenance" row (badge + state-dependent subtitle) |
| `ui/shell/AppShell.kt` | Modify | `maintenance` nav route, summary loading alongside `HeroData` |
| `ui/maintenance/MaintenanceScreen.kt` | Create | odometer header, grouped item list, baseline + mark-serviced dialogs |
| `maintenance/MaintenanceCheckWorker.kt` | Create | daily `CoroutineWorker`, notification channel, `schedule()` |
| `OpenCfMotoApp.kt` | Modify | call `MaintenanceCheckWorker.schedule(this)` at startup |
| `gradle/libs.versions.toml` | Modify | `work` version + `androidx-work-runtime-ktx` library |
| `app/build.gradle.kts` | Modify | add the WorkManager dependency |
| `res/values/strings.xml` | Modify | new `maintenance_*` strings |

---

## Task 1: Maintenance schedule data (pure)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceItem.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceScheduleTest.kt`

**Interfaces:**
- Produces: `data class MaintenanceItem(val id: String, val category: String, val name: String, val action: String, val kmInterval: Int?, val monthInterval: Int?, val oneTime: Boolean = false, val firstDueKm: Int? = null)` and `object MaintenanceSchedule { fun forModel(model: String?): List<MaintenanceItem> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceScheduleTest.kt
package dev.zanderp.opencfmoto.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceScheduleTest {
    @Test
    fun unknownOrNullModelIsEmpty() {
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel(null))
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel(""))
        assertEquals(emptyList<MaintenanceItem>(), MaintenanceSchedule.forModel("CF450NK"))
    }

    @Test
    fun modelTextNormalizesToTheSameSchedule() {
        val plain = MaintenanceSchedule.forModel("150SC")
        assertTrue(plain.isNotEmpty())
        assertEquals(plain, MaintenanceSchedule.forModel("150 SC"))
        assertEquals(plain, MaintenanceSchedule.forModel(" 150-sc "))
        assertEquals(plain, MaintenanceSchedule.forModel("150Sc"))
    }

    @Test
    fun has42UniqueItemsWithOneOneTimeEntry() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertEquals(42, items.size)
        assertEquals(42, items.map { it.id }.toSet().size)
        assertEquals(listOf("engine_coarse_oil_filter"), items.filter { it.oneTime }.map { it.id })
    }

    @Test
    fun everyItemHasAtLeastOneInterval() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertTrue(items.all { it.kmInterval != null || it.monthInterval != null })
    }

    @Test
    fun seventeenItemsHaveABreakInFirstDuePoint() {
        val items = MaintenanceSchedule.forModel("150SC")
        assertEquals(17, items.count { it.firstDueKm != null })
        assertTrue(items.filter { it.firstDueKm != null }.all { it.firstDueKm == 1000 })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceScheduleTest $F`
Expected: FAIL — `MaintenanceSchedule`/`MaintenanceItem` unresolved references.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceItem.kt
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceScheduleTest $F`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceItem.kt \
        app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceScheduleTest.kt
git commit -m "$(cat <<'EOF'
feat(maintenance): 150SC schedule data

MaintenanceItem/MaintenanceSchedule transcribe the owner's manual's
Break-In and Periodic maintenance charts (42 items), with a normalized
model-name match so "150 SC"/"150sc"/"150-SC" all resolve.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Trip bike-tagging + maintenance due-math (pure)

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/TripModel.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/TripStore.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/TripRecorder.kt:160-175`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceState.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimate.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimateTest.kt`

**Interfaces:**
- Consumes: `MaintenanceItem` (Task 1).
- Produces: `Trip.bikeRaw: String?`; `data class ItemState(val lastServicedKm: Double, val lastServicedAtMs: Long, val notified: Boolean = false)`; `data class MaintenanceState(val baselineKm: Double, val baselineSetAtMs: Long, val items: Map<String, ItemState> = emptyMap())`; `enum class MaintenanceStatus { OK, DUE_SOON, OVERDUE }`; `object MaintenanceEstimate` with `estimateKm`, `dueKm`, `dueAtMs`, `remainingKm`, `remainingDays`, `status`, `visibleItems`, `itemsToNotify` (exact signatures in Step 3) — all consumed by Task 3 (`MaintenanceStore`), Task 4 (`MaintenanceSummary`), Task 5 (`MaintenanceScreen`) and Task 6 (`MaintenanceCheckWorker`).

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimateTest.kt
package dev.zanderp.opencfmoto.maintenance

import dev.zanderp.opencfmoto.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MaintenanceEstimateTest {
    private fun trip(endMs: Long, km: Double, bikeRaw: String? = null) =
        Trip("t$endMs-$bikeRaw", endMs - 60_000, endMs, km * 1000, 60_000, 0f, emptyList(), bikeRaw)

    private fun addMonthsUtc(fromMs: Long, months: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = fromMs
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    private val baseline = MaintenanceState(baselineKm = 10_000.0, baselineSetAtMs = 1_000_000L)

    @Test
    fun estimateIsBaselineWhenNoTrips() {
        assertEquals(10_000.0, MaintenanceEstimate.estimateKm(baseline, emptyList(), "bikeA"), 1e-9)
    }

    @Test
    fun estimateSumsTripsAfterBaselineForThisBikeOrUntagged() {
        val trips = listOf(
            trip(2_000_000L, 50.0, "bikeA"),   // after baseline, this bike: counts
            trip(500_000L, 999.0, "bikeA"),    // before baseline: excluded
            trip(2_100_000L, 30.0, "bikeB"),   // another bike: excluded
            trip(2_200_000L, 20.0, null),      // untagged/legacy: counts for any bike
        )
        assertEquals(10_000.0 + 50.0 + 20.0, MaintenanceEstimate.estimateKm(baseline, trips, "bikeA"), 1e-9)
    }

    @Test
    fun dueKmNeverServicedUsesFirstDueOverrideWhenPresent() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        assertEquals(1000.0, MaintenanceEstimate.dueKm(item, null)!!, 1e-9)
    }

    @Test
    fun dueKmNeverServicedFallsBackToIntervalWithoutOverride() {
        val item = MaintenanceItem("x", "Engine", "Oil filter", "Replace", kmInterval = 3000, monthInterval = 3)
        assertEquals(3000.0, MaintenanceEstimate.dueKm(item, null)!!, 1e-9)
    }

    @Test
    fun dueKmAfterServiceUsesLastServicedPlusInterval() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        val state = ItemState(lastServicedKm = 4000.0, lastServicedAtMs = 0L)
        assertEquals(7000.0, MaintenanceEstimate.dueKm(item, state)!!, 1e-9)
    }

    @Test
    fun dueKmNullWhenNoKmInterval() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 24)
        assertNull(MaintenanceEstimate.dueKm(item, null))
    }

    @Test
    fun dueAtNeverServicedCountsFromBaselineDate() {
        val item = MaintenanceItem("x", "Engine", "Air filter elements", "Clean", kmInterval = 5000, monthInterval = 12)
        val expected = addMonthsUtc(1_000_000L, 12)
        assertEquals(expected, MaintenanceEstimate.dueAtMs(item, null, baselineSetAtMs = 1_000_000L))
    }

    @Test
    fun dueAtAfterServiceCountsFromServiceDate() {
        val item = MaintenanceItem("x", "Engine", "Air filter elements", "Clean", kmInterval = 5000, monthInterval = 12)
        val state = ItemState(lastServicedKm = 0.0, lastServicedAtMs = 2_000_000L)
        val expected = addMonthsUtc(2_000_000L, 12)
        assertEquals(expected, MaintenanceEstimate.dueAtMs(item, state, baselineSetAtMs = 1_000_000L))
    }

    @Test
    fun statusOverdueByKmAlone() {
        val item = MaintenanceItem("x", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        assertEquals(
            MaintenanceStatus.OVERDUE,
            MaintenanceEstimate.status(item, null, estimateKm = 1000.0, nowMs = 0L, baselineSetAtMs = 1_000_000_000_000L),
        )
    }

    @Test
    fun statusOverdueByDateAloneWhenKmNotYetDue() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 24)
        val dueAt = addMonthsUtc(0L, 24)
        assertEquals(
            MaintenanceStatus.OVERDUE,
            MaintenanceEstimate.status(item, null, estimateKm = 0.0, nowMs = dueAt + 86_400_000L, baselineSetAtMs = 0L),
        )
    }

    @Test
    fun statusDueSoonWithinThreeHundredKm() {
        val item = MaintenanceItem("x", "Engine", "Oil filter", "Replace", kmInterval = 3000, monthInterval = null)
        assertEquals(MaintenanceStatus.DUE_SOON, MaintenanceEstimate.status(item, null, estimateKm = 2750.0, nowMs = 0L, baselineSetAtMs = 0L))
        assertEquals(MaintenanceStatus.OK, MaintenanceEstimate.status(item, null, estimateKm = 2600.0, nowMs = 0L, baselineSetAtMs = 0L))
    }

    @Test
    fun remainingKmIsDuePointMinusEstimate() {
        val item = MaintenanceItem("x", "Engine", "Spark plug", "Replace", kmInterval = 10_000, monthInterval = null)
        assertEquals(2_000.0, MaintenanceEstimate.remainingKm(item, null, estimateKm = 8_000.0)!!, 1e-9)
        assertEquals(-500.0, MaintenanceEstimate.remainingKm(item, null, estimateKm = 10_500.0)!!, 1e-9)
        assertNull(MaintenanceEstimate.remainingKm(MaintenanceItem("y", "Brake", "Brake fluid", "Replace", null, 24), null, 0.0))
    }

    @Test
    fun remainingDaysIsDuePointMinusNow() {
        val item = MaintenanceItem("x", "Brake", "Brake fluid", "Replace", kmInterval = null, monthInterval = 1)
        val dueAt = addMonthsUtc(0L, 1)
        assertEquals(0L, MaintenanceEstimate.remainingDays(item, null, baselineSetAtMs = 0L, nowMs = dueAt)!!)
        assertEquals(-1L, MaintenanceEstimate.remainingDays(item, null, baselineSetAtMs = 0L, nowMs = dueAt + 86_400_000L + 1)!!)
    }

    @Test
    fun visibleItemsDropsServicedOneTimeItemsOnly() {
        val oneTime = MaintenanceItem("coarse", "Engine", "Coarse oil filter", "Clean", kmInterval = 1000, monthInterval = null, oneTime = true)
        val recurring = MaintenanceItem("oil", "Engine", "Oil", "Replace", kmInterval = 3000, monthInterval = 3, firstDueKm = 1000)
        val servicedOneTime = mapOf("coarse" to ItemState(1000.0, 0L))
        assertEquals(listOf(recurring), MaintenanceEstimate.visibleItems(listOf(oneTime, recurring), servicedOneTime))
        assertEquals(listOf(oneTime, recurring), MaintenanceEstimate.visibleItems(listOf(oneTime, recurring), emptyMap()))
    }

    @Test
    fun itemsToNotifySkipsAlreadyNotifiedAndNonOverdue() {
        val due = MaintenanceItem("due", "Engine", "Spark plug", "Replace", kmInterval = 10_000, monthInterval = null)
        val notifiedAlready = MaintenanceItem("notified", "Engine", "Valve clearance", "Inspect and adjust if necessary", kmInterval = 40_000, monthInterval = null)
        val state = MaintenanceState(
            baselineKm = 0.0, baselineSetAtMs = 0L,
            items = mapOf("notified" to ItemState(0.0, 0L, notified = true)),
        )
        val result = MaintenanceEstimate.itemsToNotify(listOf(due, notifiedAlready), state, estimateKm = 45_000.0, nowMs = 0L)
        assertEquals(listOf(due), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceEstimateTest $F`
Expected: FAIL to compile — `Trip(...)` 8-arg constructor and `MaintenanceState`/`ItemState`/`MaintenanceEstimate` don't exist yet.

- [ ] **Step 3: Write the implementation**

First, tag trips with the recording bike (additive, default-`null` param — every existing call site keeps compiling):

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/TripModel.kt — add bikeRaw as the last constructor param
data class Trip(
    val id: String,
    val start: Long,
    val end: Long,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val maxSpeedMs: Float,
    val points: List<TrackPoint>,
    /** The bike selected in Garage when this ride was recorded; null for rides saved before this
     *  field existed, or when no bike was selected. */
    val bikeRaw: String? = null,
) {
    // ... rest of the class (distanceKm, maxKmh, avgKmh, durationText, distanceText, dateText,
    // timeRangeText, companion object) is unchanged.
```

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/TripStore.kt — persist/parse bikeRaw
// In save(): after the existing .put(...) chain, before building `pts`:
        trip.bikeRaw?.let { obj.put("bikeRaw", it) }

// In parse(): add to the Trip(...) construction
        Trip(
            id = o.optString("id"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            distanceMeters = o.optDouble("distanceMeters"),
            movingTimeMs = o.optLong("movingTimeMs"),
            maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(),
            points = points,
            bikeRaw = o.optString("bikeRaw").takeIf { it.isNotBlank() },
        )
```

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/TripRecorder.kt:163-171 — stamp the active bike
        val trip = Trip(
            id = startTime.toString(),
            start = startTime,
            end = System.currentTimeMillis(),
            distanceMeters = distanceMeters,
            movingTimeMs = movingTimeMs,
            maxSpeedMs = maxSpeedMs,
            points = ArrayList(points),
            bikeRaw = BikeMemory.selected(appContext)?.raw,
        )
```

Then the tracking-state shapes:

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceState.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

enum class MaintenanceStatus { OK, DUE_SOON, OVERDUE }

/** Present in [MaintenanceState.items] only once an item has been serviced at least once. */
data class ItemState(
    val lastServicedKm: Double,
    val lastServicedAtMs: Long,
    val notified: Boolean = false,
)

/** One bike's maintenance tracking: the odometer baseline and every serviced item's history. */
data class MaintenanceState(
    val baselineKm: Double,
    val baselineSetAtMs: Long,
    val items: Map<String, ItemState> = emptyMap(),
)
```

And the pure due-math:

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimate.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import dev.zanderp.opencfmoto.Trip
import java.util.Calendar
import java.util.TimeZone

/** Estimates where a bike stands against its [MaintenanceSchedule] from a baseline + ride history. */
object MaintenanceEstimate {
    const val DUE_SOON_KM = 300.0
    const val DUE_SOON_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    /** Baseline + every trip for [bikeRaw] (or untagged/legacy trips, which count for any bike) that
     *  ended after the baseline was set. */
    fun estimateKm(state: MaintenanceState, trips: List<Trip>, bikeRaw: String?): Double {
        val rideMeters = trips
            .filter { (it.bikeRaw == null || it.bikeRaw == bikeRaw) && it.end > state.baselineSetAtMs }
            .sumOf { it.distanceMeters }
        return state.baselineKm + rideMeters / 1000.0
    }

    /** Next km due-point, or null when the item has no km interval. */
    fun dueKm(item: MaintenanceItem, itemState: ItemState?): Double? {
        val interval = item.kmInterval ?: return null
        val last = itemState?.lastServicedKm
        return if (last != null) last + interval else (item.firstDueKm ?: interval).toDouble()
    }

    /** Next calendar due-point, or null when the item has no month interval. */
    fun dueAtMs(item: MaintenanceItem, itemState: ItemState?, baselineSetAtMs: Long): Long? {
        val interval = item.monthInterval ?: return null
        val from = itemState?.lastServicedAtMs ?: baselineSetAtMs
        return addMonthsUtc(from, interval)
    }

    /** Km left until due (negative = overdue by that much), or null when the item has no km interval. */
    fun remainingKm(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double): Double? =
        dueKm(item, itemState)?.let { it - estimateKm }

    /** Days left until due (negative = overdue), or null when the item has no month interval. */
    fun remainingDays(item: MaintenanceItem, itemState: ItemState?, baselineSetAtMs: Long, nowMs: Long): Long? =
        dueAtMs(item, itemState, baselineSetAtMs)?.let { (it - nowMs) / DAY_MS }

    fun status(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double, nowMs: Long, baselineSetAtMs: Long): MaintenanceStatus {
        val dueKm = dueKm(item, itemState)
        val dueAt = dueAtMs(item, itemState, baselineSetAtMs)
        val overdue = (dueKm != null && estimateKm >= dueKm) || (dueAt != null && nowMs >= dueAt)
        if (overdue) return MaintenanceStatus.OVERDUE
        val soonByKm = dueKm != null && estimateKm >= dueKm - DUE_SOON_KM
        val soonByDate = dueAt != null && nowMs >= dueAt - DUE_SOON_DAYS * DAY_MS
        return if (soonByKm || soonByDate) MaintenanceStatus.DUE_SOON else MaintenanceStatus.OK
    }

    /** [items] minus one-time items that have already been serviced once (they never recur). */
    fun visibleItems(items: List<MaintenanceItem>, states: Map<String, ItemState>): List<MaintenanceItem> =
        items.filterNot { it.oneTime && states.containsKey(it.id) }

    /** Visible items that are newly overdue and haven't already fired a notification. */
    fun itemsToNotify(items: List<MaintenanceItem>, state: MaintenanceState, estimateKm: Double, nowMs: Long): List<MaintenanceItem> =
        visibleItems(items, state.items).filter { item ->
            val itemState = state.items[item.id]
            itemState?.notified != true &&
                status(item, itemState, estimateKm, nowMs, state.baselineSetAtMs) == MaintenanceStatus.OVERDUE
        }

    private fun addMonthsUtc(fromMs: Long, months: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = fromMs
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceEstimateTest $F`
Expected: PASS (15 tests).

- [ ] **Step 5: Run the full suite to confirm the `Trip` change didn't break anything**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest $F`
Expected: suite green (only the known `YunmoFrameTest` failure).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/TripModel.kt \
        app/src/main/java/dev/zanderp/opencfmoto/TripStore.kt \
        app/src/main/java/dev/zanderp/opencfmoto/TripRecorder.kt \
        app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceState.kt \
        app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimate.kt \
        app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceEstimateTest.kt
git commit -m "$(cat <<'EOF'
feat(maintenance): tag trips with their bike, add due-math

Trip gains an optional bikeRaw (stamped by TripRecorder from the
selected Garage bike) so ride distance can be attributed per bike —
nothing tracked this before. MaintenanceEstimate turns a baseline +
ride history into an odometer estimate and OK/Due soon/Overdue status
per item, km-or-calendar whichever comes first.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Maintenance persistence (JSON codec + per-bike store)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodec.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceStore.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodecTest.kt`

**Interfaces:**
- Consumes: `MaintenanceState`, `ItemState` (Task 2); `BikeScope.idFor(raw: String): String` (existing, `BikeScope.kt`).
- Produces: `object MaintenanceCodec { fun encode(state: MaintenanceState): String; fun decode(json: String): MaintenanceState? }`; `object MaintenanceStore { fun load(ctx: Context, bikeRaw: String): MaintenanceState?; fun save(ctx: Context, bikeRaw: String, state: MaintenanceState); fun setBaseline(ctx: Context, bikeRaw: String, km: Double, atMs: Long); fun markServiced(ctx: Context, bikeRaw: String, itemId: String, km: Double, atMs: Long); fun setNotified(ctx: Context, bikeRaw: String, itemId: String, notified: Boolean) }` — consumed by Task 4 (`MaintenanceSummary`), Task 5 (`MaintenanceScreen`), Task 6 (`MaintenanceCheckWorker`).

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodecTest.kt
package dev.zanderp.opencfmoto.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceCodecTest {
    @Test
    fun roundTripsBaselineOnlyState() {
        val state = MaintenanceState(baselineKm = 12_345.0, baselineSetAtMs = 1_700_000_000_000L)
        assertEquals(state, MaintenanceCodec.decode(MaintenanceCodec.encode(state)))
    }

    @Test
    fun roundTripsItemsMap() {
        val state = MaintenanceState(
            baselineKm = 100.0, baselineSetAtMs = 0L,
            items = mapOf(
                "engine_oil" to ItemState(lastServicedKm = 3100.0, lastServicedAtMs = 86_400_000L, notified = true),
                "engine_oil_filter" to ItemState(lastServicedKm = 3100.0, lastServicedAtMs = 86_400_000L),
            ),
        )
        assertEquals(state, MaintenanceCodec.decode(MaintenanceCodec.encode(state)))
    }

    @Test
    fun decodeGarbageOrIncompleteJsonReturnsNull() {
        assertNull(MaintenanceCodec.decode("not json"))
        assertNull(MaintenanceCodec.decode("{}"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceCodecTest $F`
Expected: FAIL to compile — `MaintenanceCodec` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodec.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import org.json.JSONObject

/** Pure JSON <-> [MaintenanceState] (no Android dependency, so it's unit-testable directly). */
object MaintenanceCodec {
    fun encode(state: MaintenanceState): String {
        val items = JSONObject()
        for ((id, s) in state.items) {
            items.put(
                id,
                JSONObject()
                    .put("lastServicedKm", s.lastServicedKm)
                    .put("lastServicedAtMs", s.lastServicedAtMs)
                    .put("notified", s.notified),
            )
        }
        return JSONObject()
            .put("baselineKm", state.baselineKm)
            .put("baselineSetAtMs", state.baselineSetAtMs)
            .put("items", items)
            .toString()
    }

    /** null on any parse failure (corrupt/missing file) — callers treat that as "not set up yet". */
    fun decode(json: String): MaintenanceState? = runCatching {
        val o = JSONObject(json)
        val itemsObj = o.optJSONObject("items") ?: JSONObject()
        val items = buildMap {
            for (id in itemsObj.keys()) {
                val s = itemsObj.getJSONObject(id)
                put(id, ItemState(s.getDouble("lastServicedKm"), s.getLong("lastServicedAtMs"), s.optBoolean("notified", false)))
            }
        }
        MaintenanceState(o.getDouble("baselineKm"), o.getLong("baselineSetAtMs"), items)
    }.getOrNull()
}
```

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceStore.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.content.Context
import dev.zanderp.opencfmoto.BikeScope
import dev.zanderp.opencfmoto.LogBus
import java.io.File

/** Persists one [MaintenanceState] per bike as a JSON file under `filesDir/maintenance/` — same
 *  one-file-per-entity pattern as `TripStore`, keyed by the bike id `BikeScope` already derives. */
object MaintenanceStore {
    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "maintenance").apply { if (!exists()) mkdirs() }

    private fun file(ctx: Context, bikeRaw: String): File =
        File(dir(ctx), "${BikeScope.idFor(bikeRaw)}.json")

    /** null = this bike has no baseline set yet — the screen should ask for one. */
    fun load(ctx: Context, bikeRaw: String): MaintenanceState? {
        val f = file(ctx, bikeRaw)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val state = MaintenanceCodec.decode(text)
        if (state == null) LogBus.log("[MAINT] saved state unreadable for ${BikeScope.idFor(bikeRaw)} — treating as not set up")
        return state
    }

    fun save(ctx: Context, bikeRaw: String, state: MaintenanceState) {
        runCatching { file(ctx, bikeRaw).writeText(MaintenanceCodec.encode(state)) }
    }

    /** Sets (or re-sets) the baseline without touching any item's service history. */
    fun setBaseline(ctx: Context, bikeRaw: String, km: Double, atMs: Long) {
        val current = load(ctx, bikeRaw) ?: MaintenanceState(km, atMs)
        save(ctx, bikeRaw, current.copy(baselineKm = km, baselineSetAtMs = atMs))
    }

    /** Records [itemId] serviced at [km]/[atMs] and clears its notified flag for the next cycle. */
    fun markServiced(ctx: Context, bikeRaw: String, itemId: String, km: Double, atMs: Long) {
        val current = load(ctx, bikeRaw) ?: return
        save(ctx, bikeRaw, current.copy(items = current.items + (itemId to ItemState(km, atMs))))
    }

    fun setNotified(ctx: Context, bikeRaw: String, itemId: String, notified: Boolean) {
        val current = load(ctx, bikeRaw) ?: return
        val existing = current.items[itemId] ?: return
        save(ctx, bikeRaw, current.copy(items = current.items + (itemId to existing.copy(notified = notified))))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.maintenance.MaintenanceCodecTest $F`
Expected: PASS (3 tests). `MaintenanceStore` has no dedicated unit test — it's thin `Context`/file-IO glue over the tested codec, matching `TripStore`/`EcoStore` (neither has a test either); it's covered by the emulator check in Task 5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodec.kt \
        app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceStore.kt \
        app/src/test/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCodecTest.kt
git commit -m "$(cat <<'EOF'
feat(maintenance): per-bike JSON persistence

MaintenanceCodec (pure, tested) encodes/decodes MaintenanceState;
MaintenanceStore (pattern: TripStore) persists one file per bike and
exposes baseline/mark-serviced/notified writers.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Me tab entry point (badge + states)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceSummary.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/design/Components.kt:163-180`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/tabs/MeTab.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MaintenanceSchedule.forModel` (Task 1), `MaintenanceStore.load`, `MaintenanceEstimate.estimateKm`/`visibleItems`/`status` (Tasks 2–3), `BikeMemory.selected(ctx): SavedBike?` (existing), `TripStore.list(ctx): List<Trip>` (existing).
- Produces: `data class MaintenanceSummary(val dueCount: Int, val hasSchedule: Boolean, val hasBaseline: Boolean) { companion object { val EMPTY; fun load(ctx: Context, nowMs: Long): MaintenanceSummary } }`; `SettingRow(title, subtitle, icon, onClick, badge: String? = null)`. Consumed by Task 5 (route wiring already lands here) and nothing later needs new interfaces from this task beyond what AppShell wires internally.

This task has no automated test — it's Compose UI plus a thin Android loader, matching every other screen in `ui/tabs`/`ui/forest` (none have Compose UI tests; this codebase has no Compose-test dependency). Verify manually on the emulator per Step 5.

- [ ] **Step 1: Add the Me-tab summary loader**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceSummary.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.content.Context
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.TripStore

/** What the Me tab's Maintenance row shows. [hasSchedule] false = unknown/no model; [hasBaseline]
 *  false (with [hasSchedule] true) = model recognized but the rider hasn't set an odometer yet. */
data class MaintenanceSummary(val dueCount: Int, val hasSchedule: Boolean, val hasBaseline: Boolean) {
    companion object {
        val EMPTY = MaintenanceSummary(0, hasSchedule = false, hasBaseline = false)

        /** Reads prefs/trips from disk — call off the main thread. */
        fun load(ctx: Context, nowMs: Long): MaintenanceSummary {
            val bike = BikeMemory.selected(ctx) ?: return EMPTY
            val items = MaintenanceSchedule.forModel(bike.model)
            if (items.isEmpty()) return MaintenanceSummary(0, hasSchedule = false, hasBaseline = false)
            val state = MaintenanceStore.load(ctx, bike.raw)
                ?: return MaintenanceSummary(0, hasSchedule = true, hasBaseline = false)
            val estimate = MaintenanceEstimate.estimateKm(state, TripStore.list(ctx), bike.raw)
            val due = MaintenanceEstimate.visibleItems(items, state.items).count {
                MaintenanceEstimate.status(it, state.items[it.id], estimate, nowMs, state.baselineSetAtMs) != MaintenanceStatus.OK
            }
            return MaintenanceSummary(due, hasSchedule = true, hasBaseline = true)
        }
    }
}
```

- [ ] **Step 2: Add a badge to `SettingRow`**

Existing call sites pass exactly 4 positional args ending in the lambda/reference, e.g.
`SettingRow(stringResource(R.string.ui_me_garage), stringResource(R.string.ui_me_garage_desc), Icons.Rounded.Garage, onGarage)`.
For these to keep compiling unchanged, `onClick` must stay the 4th parameter — so `badge` is appended
**after** it, not before:

```kotlin
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val c = Ocm.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = OcmType.title, color = c.text)
            if (subtitle != null) Text(subtitle, style = OcmType.body, color = c.text2)
        }
        if (badge != null) {
            Box(Modifier.clip(CircleShape).background(c.error).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(badge, style = OcmType.label, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text2)
    }
}
```

- [ ] **Step 3: Add the strings**

```xml
<!-- app/src/main/res/values/strings.xml — insert right before </resources> -->
<string name="ui_me_maintenance">Maintenance</string>
<string name="maintenance_subtitle_no_schedule">No schedule for this bike</string>
<string name="maintenance_subtitle_setup">Set your odometer to start tracking</string>
<string name="maintenance_subtitle_due">%1$d item(s) need attention</string>
<string name="maintenance_subtitle_ok">Up to date</string>
```

- [ ] **Step 4: Add the Me-tab row and wire the route**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/ui/tabs/MeTab.kt — add imports, param, row
import androidx.compose.material.icons.rounded.Build
// ... (keep existing imports)

@Composable
fun MeTab(
    onGarage: () -> Unit, onSettings: () -> Unit, onForest: () -> Unit, onMaintenance: () -> Unit,
    maintenance: dev.zanderp.opencfmoto.maintenance.MaintenanceSummary,
    onLogs: () -> Unit, onReport: () -> Unit, onUpdate: () -> Unit, onAbout: () -> Unit, onDonate: () -> Unit,
) {
    val c = Ocm.colors
    val maintenanceSubtitle = when {
        !maintenance.hasSchedule -> stringResource(R.string.maintenance_subtitle_no_schedule)
        !maintenance.hasBaseline -> stringResource(R.string.maintenance_subtitle_setup)
        maintenance.dueCount > 0 -> stringResource(R.string.maintenance_subtitle_due, maintenance.dueCount)
        else -> stringResource(R.string.maintenance_subtitle_ok)
    }
    OcmScreen {
        Text(stringResource(R.string.ui_tab_me), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_garage), stringResource(R.string.ui_me_garage_desc), Icons.Rounded.Garage, onGarage)
            SettingRow(stringResource(R.string.ui_me_settings), stringResource(R.string.ui_me_settings_desc), Icons.Rounded.Settings, onSettings)
            SettingRow(stringResource(R.string.ui_me_forest), stringResource(R.string.ui_me_forest_desc), Icons.Rounded.Park, onForest)
            SettingRow(
                stringResource(R.string.ui_me_maintenance), maintenanceSubtitle, Icons.Rounded.Build, onMaintenance,
                badge = maintenance.dueCount.takeIf { it > 0 }?.toString(),
            )
        }
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_logs), stringResource(R.string.ui_me_logs_desc), Icons.AutoMirrored.Rounded.Article, onLogs)
            SettingRow(stringResource(R.string.ui_me_report), icon = Icons.Rounded.BugReport, onClick = onReport)
            SettingRow(stringResource(R.string.ui_me_update), icon = Icons.Rounded.SystemUpdate, onClick = onUpdate)
        }
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_about), icon = Icons.Rounded.Info, onClick = onAbout)
            SettingRow(stringResource(R.string.ui_me_donate), icon = Icons.Rounded.Favorite, onClick = onDonate)
        }
        Text(stringResource(R.string.ui_version, BuildConfig.VERSION_NAME), style = OcmType.body, color = c.text2)
    }
}
```

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt — load the summary alongside HeroData
// Add import:
import dev.zanderp.opencfmoto.maintenance.MaintenanceSummary

// Replace the hero state block with (adds a second state + loads it in the same effect):
    var heroTick by remember { mutableIntStateOf(0) }   // bumped after any action that changes hero/maintenance data
    var hero by remember { mutableStateOf(HeroData.EMPTY) }
    var maintenance by remember { mutableStateOf(MaintenanceSummary.EMPTY) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, bike, heroTick) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            hero = withContext(Dispatchers.IO) { HeroData.load(ctx.applicationContext) }
            maintenance = withContext(Dispatchers.IO) { MaintenanceSummary.load(ctx.applicationContext, System.currentTimeMillis()) }
        }
    }

// In the NavHost, replace the "me" composable with:
            composable("me") {
                MeTab(
                    onGarage = { GarageActivity.start(ctx) }, onSettings = { SetupActivity.start(ctx) }, onForest = { nav.navigate("forest") },
                    onMaintenance = { nav.navigate("maintenance") }, maintenance = maintenance,
                    onLogs = { nav.navigate("logs") }, onReport = c::reportProblem, onUpdate = c::checkUpdateManual,
                    onAbout = { AboutActivity.start(ctx) }, onDonate = c::donate,
                )
            }
            composable("forest") { dev.zanderp.opencfmoto.ui.forest.ForestScreen() }
            composable("maintenance") {
                dev.zanderp.opencfmoto.ui.maintenance.MaintenanceScreen(onChanged = { heroTick++ })
            }

// And extend the bottom-bar "me" highlight to include "maintenance":
                    val selected = route == t.route || (t.route == "me" && (route == "logs" || route == "forest" || route == "maintenance"))
```

(`MaintenanceScreen` itself is written in Task 5 — this task's "maintenance" route will show a compile error for the missing composable until Task 5 lands; that's expected mid-plan and is resolved by the end of Task 5. If running the app between tasks, stub `MaintenanceScreen` with an empty `OcmScreen { }` temporarily, or do Tasks 4 and 5 in the same sitting before building.)

- [ ] **Step 5: Manual verification (after Task 5's `MaintenanceScreen` exists)**

Build and install on the emulator (`./gradlew :app:assembleDebug -Pabi=x86_64 $F`), open the app:
1. No model set on the active bike (or a non-150SC model) → Me ▸ Maintenance shows "No schedule for this bike", no badge.
2. Set model to "150 SC" in Garage, return to Me → subtitle changes to "Set your odometer to start tracking", no badge.
3. After setting a baseline (Task 5) with at least one item overdue → badge shows the count and subtitle shows "N item(s) need attention".

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceSummary.kt \
        app/src/main/java/dev/zanderp/opencfmoto/ui/design/Components.kt \
        app/src/main/java/dev/zanderp/opencfmoto/ui/tabs/MeTab.kt \
        app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt \
        app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(maintenance): Me tab entry point

SettingRow gets an optional badge; the Me tab's new Maintenance row
shows the due/overdue count (or a setup prompt / no-schedule note)
via MaintenanceSummary, loaded alongside the Ride hero data.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Maintenance screen (Compose)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/maintenance/MaintenanceScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MaintenanceSchedule.forModel`, `MaintenanceItem` (Task 1); `MaintenanceState`, `ItemState`, `MaintenanceStatus`, `MaintenanceEstimate.*` (Task 2); `MaintenanceStore.*` (Task 3); `BikeMemory.selected`, `TripStore.list` (existing); `StatusChip`, `StatusKind`, `OcmScreen`, `OcmCard`, `OcmType`, `Ocm`, `PrimaryButton`, `SectionLabel` (existing, `ui/design`).
- Produces: `@Composable fun MaintenanceScreen(onChanged: () -> Unit)` — already wired into `AppShell`'s `"maintenance"` route by Task 4.

No automated test (Compose UI, no test harness in this project — see Global Constraints). Verify manually per Step 3.

- [ ] **Step 1: Add the remaining strings**

```xml
<!-- app/src/main/res/values/strings.xml — insert right after the ui_me_maintenance block from Task 4 -->
<string name="maintenance_title">Maintenance</string>
<string name="maintenance_no_schedule">No maintenance schedule for this bike yet. Set a model in Garage (e.g. \"150 SC\") to turn this on.</string>
<string name="maintenance_set_baseline_prompt">Type in your current odometer reading to start tracking service intervals.</string>
<string name="maintenance_set_odometer">Set odometer</string>
<string name="maintenance_odometer_label">Estimated odometer</string>
<string name="maintenance_odometer_value">%1$,.0f km</string>
<string name="maintenance_odometer_dialog_title">Set odometer</string>
<string name="maintenance_odometer_dialog_hint">Current reading, in km</string>
<string name="maintenance_mark_serviced_title">Mark as serviced?</string>
<string name="maintenance_mark_serviced_message">Records today\'s date and the current estimated odometer as the starting point for %1$s\'s next service.</string>
<string name="maintenance_status_ok">OK</string>
<string name="maintenance_status_due_soon">Due soon</string>
<string name="maintenance_status_overdue">Overdue</string>
<string name="maintenance_due_in_km">in %1$d km</string>
<string name="maintenance_due_in_days">in %1$d days</string>
<string name="maintenance_overdue_km">%1$d km overdue</string>
<string name="maintenance_overdue_days">%1$d days overdue</string>
```

- [ ] **Step 2: Write the screen**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/ui/maintenance/MaintenanceScreen.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.maintenance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.TripStore
import dev.zanderp.opencfmoto.maintenance.ItemState
import dev.zanderp.opencfmoto.maintenance.MaintenanceEstimate
import dev.zanderp.opencfmoto.maintenance.MaintenanceItem
import dev.zanderp.opencfmoto.maintenance.MaintenanceSchedule
import dev.zanderp.opencfmoto.maintenance.MaintenanceState
import dev.zanderp.opencfmoto.maintenance.MaintenanceStatus
import dev.zanderp.opencfmoto.maintenance.MaintenanceStore
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.StatusChip
import dev.zanderp.opencfmoto.ui.design.StatusKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Me ▸ Maintenance: estimated odometer, every schedule item with its status, mark-serviced. */
@Composable
fun MaintenanceScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val bike: SavedBike? = remember { BikeMemory.selected(ctx) }
    val items: List<MaintenanceItem> = remember(bike) { MaintenanceSchedule.forModel(bike?.model) }
    val now = remember { System.currentTimeMillis() }

    var reloadTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<MaintenanceState?>(null) }
    var estimateKm by remember { mutableStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(bike, reloadTick) {
        if (bike == null) { loaded = true; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            val s = MaintenanceStore.load(ctx.applicationContext, bike.raw)
            state = s
            estimateKm = if (s != null) MaintenanceEstimate.estimateKm(s, TripStore.list(ctx.applicationContext), bike.raw) else 0.0
        }
        loaded = true
    }

    // Android 13+: ask once, the first time this screen is opened, so an overdue notification can show.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showBaselineDialog by remember { mutableStateOf(false) }
    var serviceTarget by remember { mutableStateOf<MaintenanceItem?>(null) }

    OcmScreen {
        Text(stringResource(R.string.maintenance_title), style = OcmType.screenTitle, color = Ocm.colors.text)
        val currentState = state
        val currentBike = bike
        when {
            !loaded -> {}
            currentBike == null || items.isEmpty() -> OcmCard {
                Text(stringResource(R.string.maintenance_no_schedule), style = OcmType.body, color = Ocm.colors.text2)
            }
            currentState == null -> OcmCard {
                Text(stringResource(R.string.maintenance_set_baseline_prompt), style = OcmType.body, color = Ocm.colors.text2)
                PrimaryButton(stringResource(R.string.maintenance_set_odometer), Icons.Rounded.Speed, onClick = { showBaselineDialog = true })
            }
            else -> {
                OcmCard {
                    Text(stringResource(R.string.maintenance_odometer_label), style = OcmType.body, color = Ocm.colors.text2)
                    Text(stringResource(R.string.maintenance_odometer_value, estimateKm), style = OcmType.stat, color = Ocm.colors.text)
                    PrimaryButton(stringResource(R.string.maintenance_set_odometer), Icons.Rounded.Speed, onClick = { showBaselineDialog = true })
                }
                val visible = MaintenanceEstimate.visibleItems(items, currentState.items)
                val byCategory = visible.groupBy { it.category }
                for ((category, categoryItems) in byCategory) {
                    SectionLabel(category)
                    OcmCard {
                        val sorted = categoryItems.sortedBy {
                            statusRank(MaintenanceEstimate.status(it, currentState.items[it.id], estimateKm, now, currentState.baselineSetAtMs))
                        }
                        for (item in sorted) {
                            ItemRow(item, currentState, estimateKm, now, onClick = { serviceTarget = item })
                        }
                    }
                }
            }
        }
    }

    if (showBaselineDialog) {
        BaselineDialog(
            initialKm = estimateKm,
            onDismiss = { showBaselineDialog = false },
            onConfirm = { km ->
                if (bike != null) {
                    MaintenanceStore.setBaseline(ctx.applicationContext, bike.raw, km, now)
                    reloadTick++
                    onChanged()
                }
                showBaselineDialog = false
            },
        )
    }
    serviceTarget?.let { item ->
        ServiceConfirmDialog(
            item = item,
            onDismiss = { serviceTarget = null },
            onConfirm = {
                if (bike != null) {
                    MaintenanceStore.markServiced(ctx.applicationContext, bike.raw, item.id, estimateKm, now)
                    reloadTick++
                    onChanged()
                }
                serviceTarget = null
            },
        )
    }
}

private fun statusRank(status: MaintenanceStatus): Int = when (status) {
    MaintenanceStatus.OVERDUE -> 0
    MaintenanceStatus.DUE_SOON -> 1
    MaintenanceStatus.OK -> 2
}

@Composable
private fun ItemRow(item: MaintenanceItem, state: MaintenanceState, estimateKm: Double, nowMs: Long, onClick: () -> Unit) {
    val itemState = state.items[item.id]
    val status = MaintenanceEstimate.status(item, itemState, estimateKm, nowMs, state.baselineSetAtMs)
    val (kind, label) = when (status) {
        MaintenanceStatus.OK -> StatusKind.LIVE to stringResource(R.string.maintenance_status_ok)
        MaintenanceStatus.DUE_SOON -> StatusKind.BUSY to stringResource(R.string.maintenance_status_due_soon)
        MaintenanceStatus.OVERDUE -> StatusKind.ERROR to stringResource(R.string.maintenance_status_overdue)
    }
    val detail = dueDetailText(item, itemState, estimateKm, nowMs, state.baselineSetAtMs)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = OcmType.title, color = Ocm.colors.text)
                Text(item.action, style = OcmType.body, color = Ocm.colors.text2)
            }
            StatusChip(label, kind)
        }
        if (detail != null) Text(detail, style = OcmType.body, color = Ocm.colors.text2)
    }
}

@Composable
private fun dueDetailText(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double, nowMs: Long, baselineSetAtMs: Long): String? {
    val remKm = MaintenanceEstimate.remainingKm(item, itemState, estimateKm)
    val remDays = MaintenanceEstimate.remainingDays(item, itemState, baselineSetAtMs, nowMs)
    // Prefer whichever axis is closer to (or past) due; an item always has at least one axis set.
    val pick = listOfNotNull(remKm?.let { "km" to it }, remDays?.let { "days" to it.toDouble() })
        .minByOrNull { it.second } ?: return null
    return if (pick.first == "km") {
        val km = pick.second
        if (km >= 0) stringResource(R.string.maintenance_due_in_km, km.toInt()) else stringResource(R.string.maintenance_overdue_km, (-km).toInt())
    } else {
        val days = pick.second.toLong()
        if (days >= 0) stringResource(R.string.maintenance_due_in_days, days.toInt()) else stringResource(R.string.maintenance_overdue_days, (-days).toInt())
    }
}

@Composable
private fun BaselineDialog(initialKm: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (initialKm > 0) initialKm.toInt().toString() else "") }
    val parsed = text.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maintenance_odometer_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.maintenance_odometer_dialog_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConfirm(it) } }, enabled = parsed != null && parsed >= 0) {
                Text(stringResource(R.string.maintenance_set_odometer))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}

@Composable
private fun ServiceConfirmDialog(item: MaintenanceItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maintenance_mark_serviced_title)) },
        text = { Text(stringResource(R.string.maintenance_mark_serviced_message, item.name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.maintenance_status_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}
```

(`R.string.dash_cancel` already exists and is reused elsewhere for dialog cancel buttons — see `DialogHost.kt`.)

- [ ] **Step 3: Manual verification**

Build and install (`./gradlew :app:assembleDebug -Pabi=x86_64 $F`) on the emulator:
1. With no model set: Me ▸ Maintenance shows the no-schedule card.
2. Set Garage model to "150 SC", reopen Maintenance: baseline prompt + "Set odometer" shows.
3. Tap Set odometer, enter e.g. `12000`, confirm: header shows "Estimated odometer: 12,000 km", 42 items appear grouped by category (minus nothing yet), most showing "Due soon"/"Overdue" since every interval's first due point is well under 12,000 km.
4. Tap an overdue item (e.g. Oil), confirm "Mark as serviced?": it drops to OK and its due text becomes "in 3000 km" (or similar).
5. Tap Set odometer again with a smaller number than current: confirm the header updates and nothing crashes (re-baselining doesn't touch serviced items — the just-serviced Oil stays OK or recalculates per the new, lower estimate, per spec §3.3).
6. Return to Me tab: badge count reflects the remaining due/overdue items from Task 4's Step 5.
7. On an API 33+ emulator image, confirm the notification permission prompt appears the first time this screen opens.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/maintenance/MaintenanceScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(maintenance): Maintenance screen

Me > Maintenance: odometer baseline (set/re-set), every schedule item
grouped by category with an OK/Due soon/Overdue pill and remaining
km-or-days text, mark-serviced confirmation. Requests POST_NOTIFICATIONS
once, the first time the screen opens, on Android 13+.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Daily notification check

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCheckWorker.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/OpenCfMotoApp.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MaintenanceEstimate.estimateKm`/`itemsToNotify` (Task 2, already tested — this task adds no new pure logic, so no new unit test); `MaintenanceStore.load`/`setNotified` (Task 3); `BikeMemory.selected`, `TripStore.list` (existing).
- Produces: `class MaintenanceCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params)` with `companion object { fun schedule(ctx: Context) }`.

No new unit test — the decision logic (`itemsToNotify`) is already covered in Task 2; this task is WorkManager/notification plumbing with no branching beyond what's tested, same category as `MaintenanceStore` (Task 3) and `ProjectionService`'s existing notification code (neither has a test). Verify manually per Step 5.

- [ ] **Step 1: Add the WorkManager dependency**

```toml
# gradle/libs.versions.toml — add to [versions]
work = "2.10.0"

# add to [libraries]
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
```

```kotlin
// app/build.gradle.kts — add alongside the other androidx implementation lines (after androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
```

- [ ] **Step 2: Add the notification strings**

```xml
<!-- app/src/main/res/values/strings.xml — insert right before </resources> -->
<string name="maintenance_notif_channel">Maintenance reminders</string>
<string name="maintenance_notif_title">Maintenance due</string>
<string name="maintenance_notif_text">%1$s is due or overdue</string>
```

- [ ] **Step 3: Write the worker**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCheckWorker.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TripStore
import java.util.concurrent.TimeUnit

/** Once a day: for the active bike, notify about any item that just became overdue. */
class MaintenanceCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { check() }.onFailure { LogBus.log("[MAINT] daily check failed: $it") }
        return Result.success()
    }

    private fun check() {
        val ctx = applicationContext
        val bike = BikeMemory.selected(ctx) ?: return
        val items = MaintenanceSchedule.forModel(bike.model)
        if (items.isEmpty()) return
        val state = MaintenanceStore.load(ctx, bike.raw) ?: return
        val now = System.currentTimeMillis()
        val estimate = MaintenanceEstimate.estimateKm(state, TripStore.list(ctx), bike.raw)
        for (item in MaintenanceEstimate.itemsToNotify(items, state, estimate, now)) {
            notify(ctx, item)
            MaintenanceStore.setNotified(ctx, bike.raw, item.id, true)
        }
    }

    private fun notify(ctx: Context, item: MaintenanceItem) {
        ensureChannel(ctx)
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(ctx, item.id.hashCode(), openIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.maintenance_notif_title))
            .setContentText(ctx.getString(R.string.maintenance_notif_text, item.name))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(item.id.hashCode(), notification)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.maintenance_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "opencfmoto_maintenance"
        private const val WORK_NAME = "maintenance_check"

        /** Idempotent: KEEP means re-calling this on every app start never resets the schedule. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceCheckWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

- [ ] **Step 4: Schedule it at app start**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/OpenCfMotoApp.kt — add inside onCreate(), alongside the
// other try/catch-wrapped startup calls (after AnonymousTelemetry.onAppStart(this))
        try {
            dev.zanderp.opencfmoto.maintenance.MaintenanceCheckWorker.schedule(this)
        } catch (_: Exception) {
        }
```

- [ ] **Step 5: Build and run the full test suite**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest $F` → suite green (only the known `YunmoFrameTest` failure).
Run: `. .superpowers/env.sh; ./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL.

- [ ] **Step 6: Manual verification**

On the emulator, with a baseline set and at least one item overdue (per Task 5's manual check):
1. Force-stop and relaunch the app (or just wait — `WorkManager` runs the periodic worker on its own schedule; for a same-day check, temporarily lower the interval to `15, TimeUnit.MINUTES` locally, verify, then revert to 24 hours before committing).
2. Confirm a "Maintenance due" notification appears for the overdue item, and tapping it opens the app.
3. Confirm a second run doesn't re-notify the same item (check `[MAINT]` log lines / no duplicate notification).
4. Mark that item serviced in the Maintenance screen, then trigger another check (same temporary short-interval trick) — confirm no stale notification fires for it.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/dev/zanderp/opencfmoto/maintenance/MaintenanceCheckWorker.kt \
        app/src/main/java/dev/zanderp/opencfmoto/OpenCfMotoApp.kt \
        app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(maintenance): daily overdue notification

MaintenanceCheckWorker (WorkManager, daily) notifies once per item the
first time it goes overdue, reusing the already-tested
MaintenanceEstimate.itemsToNotify dedup logic. Scheduled at app start
with KEEP so re-launching the app never resets the cadence.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Review Focus

1. **Multi-bike Garage users** — switching the active bike in Garage switches which `MaintenanceStore` file is read (keyed by `BikeScope.idFor`), and legacy/untagged trips count toward every bike's estimate (Task 2's ruling #1). A rider with two 150SCs will see ride history double-count for any ride recorded before this feature shipped; acceptable per spec's non-goals, but worth a sanity look in Task 5's manual check if a second bike is handy.
2. **Re-baselining after a dealer visit** — confirm `MaintenanceStore.setBaseline` never touches `items` (Task 3), so previously-serviced items keep their own due points independent of the new baseline (spec §3.3).
3. **`SettingRow` call-site compatibility** — Task 4 adds `badge` *after* `onClick`, not before; double-check no existing call site passes `onClick` by position past where `badge` would land (grep `SettingRow(` — all current call sites use ≤4 positional args, confirmed in Task 4 Step 2).
4. **One-time item lifecycle** — `engine_coarse_oil_filter` should disappear from the Maintenance screen permanently once serviced (`MaintenanceEstimate.visibleItems`), not reappear after a re-baseline or app restart. Pinned by `visibleItemsDropsServicedOneTimeItemsOnly` (Task 2) but worth confirming visually in Task 5.
