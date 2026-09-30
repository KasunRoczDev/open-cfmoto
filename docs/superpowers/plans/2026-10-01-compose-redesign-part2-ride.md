# Compose Redesign Part 2 — Ride Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Ride tab gets its "My moto" hero (model name, photo, last ride / this month, Parked here), and Dash view, Controls, Button mapping and QR scan are redrawn in Compose with the Part 1 design system.

**Architecture:** Approach A — every screen keeps its Activity, manifest entry, intents and result contract; `setContentView(R.layout…)` becomes `setContent { OcmTheme { … } }`, Activity fields that drove `View.visibility` become Compose `mutableStateOf`, and the Views that must stay Views (AA `SurfaceView`, CameraX `PreviewView`, GPX preview `FrameLayout`) are created once in the Activity and hosted with `AndroidView`. Rules are pure and unit-tested (`RideSummary`, `heroText`, `SavedBike.normalizeModel`, `AaKeys.target`, `ButtonMap.mappedCount`). The duplicated pad key/scroll code becomes `AaKeys` + an `AaPad` composable.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.03.00, Material3 1.4.0), CameraX, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-01-compose-redesign-part2-ride-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto`; new Compose screens under `dev.zanderp.opencfmoto.ui.ride`. New files start with the 3-line SPDX/AGPL header (copy from `ui/ride/RideScreen.kt`).
- Tap targets ≥ **56 dp**; pad keys ≥ **64 dp**; Connect/Stop stays **64 dp**.
- Colours only from `Ocm.colors`; type only from `OcmType`; no CFMoto artwork (neutral bike = `Icons.Rounded.TwoWheeler`).
- Every moved action keeps its `AaInput` code, scroll delta, `LogBus` line, toast text and prefs write. `RESULT_QR` contract unchanged.
- Reuse existing (translated) strings where one exists (`controls_*`, `buttons_*`, `qr_*`, `hud_*`); new strings go in `res/values/strings.xml` only, prefixed `ui_`. Do **not** delete strings (translations in 15 locales reference them).
- Build/test from repo root in Git Bash: `. .superpowers/env.sh` (sets `JAVA_HOME`, `GRADLE_USER_HOME` on F:, and `$F`), then `./gradlew <task> $F`.
- "Suite green" = only the known failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` (exactly 1).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Rulings made while planning (spec gaps)

1. **Parked here (pin set)** opens **Google Maps** directions to the pin (`NavLauncher.navigateLatLon`). The Map hub's "Go to parked" runs an in-app map session wired to `GpxActivity` internals (`overlays()`); sharing it is out of scope. Rider can switch to walking in Maps.
2. **Manual Wi-Fi entry** keeps the existing `ManualWifiPairing.show` dialog (it is shared with Garage; Part 3 redraws it). It already follows light/dark (Material3 DayNight).
3. **QR camera permission denied** no longer closes the screen: the toast stays and the screen shows an inline message; photo + manual entry still work.
4. **Dash view map-hub button** is shown in the top bar (the old code hid it in every mode; the mockup and spec show it). It opens `GpxActivity`, as the old button did.

## Review Focus

1. **Rotation / return to Dash view while Android Auto is live** → the preview surface re-attaches once and touch still maps correctly. The `SurfaceView` is one instance owned by the Activity; `AndroidView` factory detaches it from any old parent. Checked on the emulator in Task 4 Step 5.
2. **Ride that ended just after midnight / month boundary in the rider's zone** → "Today" and this month's km are right. Pinned by `RideSummaryTest.dayLabelsAcrossMidnight` and `monthSumUsesStartInZone` (Task 1).
3. **Saved bike JSON from older versions (no `model` key)** → loads with `model = null` and the hero falls back to the bike name. Pinned by `BikeModelTest` + `RideUiStateTest.heroTextFallsBackToBikeName` (Task 1); JSON read path mirrors the `photo` key.
4. **Pad pressed with no session** → the same toast as before ("Connect to the bike first" in Dash view, "Start Android Auto or Map first" in Controls), nothing sent. Pinned by `AaKeysTest` (Task 3).
5. **Teach-remote dialog dismissed by Back / rotation** → `RemotePad.teachListener` is cleared (no ghost capture). Enforced in Task 5 by clearing the listener in the dialog's `onDismissRequest` and in `onDestroy`; checked on the emulator.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `BikeMemory.kt` | Modify | `SavedBike.model`, `normalizeModel`, `setModel`, JSON read/write |
| `ui/ride/RideSummary.kt` | Create | last ride + month km (pure) |
| `ui/ride/RideUiState.kt` | Modify | `heroText` |
| `ui/ride/HeroData.kt` | Create | loads model/photo/summary/parked (disk, off main) + photo decode |
| `ui/ride/ParkHere.kt` | Create | save current spot as the parked pin |
| `ui/ride/RideScreen.kt` | Modify | hero layout, ride card, five tiles |
| `ui/shell/AppShell.kt` | Modify | hero loading on resume, new callbacks |
| `GarageActivity.kt` | Modify | "Set model" action + model line on the card |
| `ui/design/Components.kt` | Modify | `SegmentedChoice`, `OcmTopBar` |
| `AaKeys.kt` | Create | pad key/scroll routing + mic permission (shared) |
| `ButtonMap.kt` | Modify | `mappedCount` |
| `ui/ride/AaPad.kt` | Create | on-screen pad composable |
| `ui/ride/ControlsScreen.kt`, `ControlsActivity.kt` | Create/Modify | Controls in Compose |
| `ui/ride/DashViewScreen.kt`, `HudViewActivity.kt` | Create/Modify | Dash view in Compose |
| `ui/ride/ButtonMappingScreen.kt`, `ButtonMappingActivity.kt` | Create/Modify | Button mapping in Compose |
| `ui/ride/QrScanScreen.kt`, `QrScanActivity.kt` | Create/Modify | QR scan in Compose |
| `res/layout/activity_{hud_view,controls,button_mapping,qr_scan}.xml`, `row_button_mapping.xml`, `row_saved_place.xml` | Delete | replaced |
| `res/values/strings.xml` | Modify | new `ui_` strings |
| Tests: `RideSummaryTest.kt`, `BikeModelTest.kt`, `RideUiStateTest.kt`, `AaKeysTest.kt`, `ButtonMapCountTest.kt` | Create/Modify | |

---

### Task 1: Hero data — model field and ride summary

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/BikeMemory.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideSummary.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideUiState.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideSummaryTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/BikeModelTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideUiStateTest.kt`

**Interfaces:**
- Produces: `SavedBike(raw, name, photoPath = null, model: String? = null)`; `SavedBike.normalizeModel(input: String?): String?`; `BikeMemory.setModel(ctx, raw, model: String?)`; `enum class RideDay { TODAY, YESTERDAY, EARLIER }`; `data class LastRide(day: RideDay, endMs: Long, km: Double, movingMs: Long)`; `data class RideSummary(last: LastRide, monthKm: Int)` with `RideSummary.of(trips: List<Trip>, nowMs: Long, zone: ZoneId): RideSummary?` and `RideSummary.durationText(ms: Long): String`; `heroText(model: String?, bikeName: String?): String`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideSummaryTest.kt`:

```kotlin
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RideSummaryTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun trip(start: Long, end: Long, km: Double, movingMin: Long = 10) =
        Trip("t$start", start, end, km * 1000, movingMin * 60_000, 0f, emptyList())

    @Test
    fun emptyIsNull() {
        assertNull(RideSummary.of(emptyList(), at(2026, 10, 1, 12, 0), zone))
    }

    @Test
    fun latestEndWins() {
        val a = trip(at(2026, 10, 1, 10, 0), at(2026, 10, 1, 12, 0), 30.0)
        val b = trip(at(2026, 10, 1, 11, 0), at(2026, 10, 1, 11, 30), 5.0)
        assertEquals(30.0, RideSummary.of(listOf(b, a), at(2026, 10, 1, 13, 0), zone)!!.last.km, 0.0)
    }

    @Test
    fun dayLabelsAcrossMidnight() {
        val now = at(2026, 10, 1, 0, 10)
        fun dayOf(end: Long) = RideSummary.of(listOf(trip(end - 60_000, end, 1.0)), now, zone)!!.last.day
        assertEquals(RideDay.TODAY, dayOf(at(2026, 10, 1, 0, 5)))
        assertEquals(RideDay.YESTERDAY, dayOf(at(2026, 9, 30, 23, 50)))
        assertEquals(RideDay.EARLIER, dayOf(at(2026, 9, 29, 12, 0)))
    }

    @Test
    fun clockBehindRideCountsAsToday() {
        val now = at(2026, 10, 1, 9, 0)
        val s = RideSummary.of(listOf(trip(at(2026, 10, 2, 8, 0), at(2026, 10, 2, 9, 0), 1.0)), now, zone)!!
        assertEquals(RideDay.TODAY, s.last.day)
    }

    @Test
    fun monthSumUsesStartInZone() {
        val now = at(2026, 10, 15, 12, 0)
        val trips = listOf(
            trip(at(2026, 10, 1, 0, 30), at(2026, 10, 1, 1, 0), 10.4),
            trip(at(2026, 9, 30, 23, 30), at(2026, 10, 1, 0, 20), 50.0),   // started in September
            trip(at(2026, 10, 14, 8, 0), at(2026, 10, 14, 9, 0), 5.3),
        )
        assertEquals(16, RideSummary.of(trips, now, zone)!!.monthKm)   // 15.7 → 16
    }

    @Test
    fun kmRoundsToTenths() {
        val now = at(2026, 10, 1, 12, 0)
        fun kmOf(km: Double) = RideSummary.of(listOf(trip(now - 120_000, now - 60_000, km)), now, zone)!!.last.km
        assertEquals(12.4, kmOf(12.44), 0.0)
        assertEquals(12.5, kmOf(12.46), 0.0)
    }

    @Test
    fun durationText() {
        assertEquals("28 min", RideSummary.durationText(28 * 60_000L))
        assertEquals("1 h 05 min", RideSummary.durationText(65 * 60_000L))
        assertEquals("0 min", RideSummary.durationText(30_000L))
        assertEquals("0 min", RideSummary.durationText(-5L))
    }
}
```

`app/src/test/java/dev/zanderp/opencfmoto/BikeModelTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeModelTest {
    @Test
    fun modelIsTrimmedAndBlankIsNull() {
        assertEquals("150 SC", SavedBike.normalizeModel("  150 SC "))
        assertNull(SavedBike.normalizeModel("   "))
        assertNull(SavedBike.normalizeModel(null))
    }

    @Test
    fun defaultModelIsNullAndRenameKeepsIt() {
        assertNull(SavedBike("raw", "Bike").model)
        assertEquals("150 SC", SavedBike("raw", "Bike", null, "150 SC").copy(name = "Mine").model)
    }
}
```

Append to `app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideUiStateTest.kt` (inside the class):

```kotlin
    @Test
    fun heroTextPrefersModel() {
        assertEquals("150 SC", heroText(" 150 SC ", "CFMOTO4288"))
    }

    @Test
    fun heroTextFallsBackToBikeName() {
        assertEquals("CFMOTO4288", heroText("  ", "CFMOTO4288"))
        assertEquals("CFMOTO4288", heroText(null, "CFMOTO4288"))
        assertEquals("", heroText(null, null))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `. .superpowers/env.sh; ./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.ui.ride.RideSummaryTest --tests dev.zanderp.opencfmoto.BikeModelTest --tests dev.zanderp.opencfmoto.ui.ride.RideUiStateTest $F`
Expected: compilation FAIL — `RideSummary`, `normalizeModel`, `heroText` unresolved.

- [ ] **Step 3: Implement**

`BikeMemory.kt` — replace the `SavedBike` class:

```kotlin
data class SavedBike(
    val raw: String,
    val name: String,
    val photoPath: String? = null,
    /** Rider-typed model shown big on the Ride hero (e.g. "150 SC"); null = show [name]. */
    val model: String? = null,
) {
    val qr: QrData? get() = QrData.parse(raw)

    companion object {
        /** Trimmed model text, or null when blank. */
        fun normalizeModel(input: String?): String? = input?.trim()?.takeIf { it.isNotEmpty() }
    }
}
```

In `devices()`, replace the two lines reading `photo` and adding the bike:

```kotlin
                val photo = o.optString("photo").takeIf { it.isNotBlank() }
                val model = SavedBike.normalizeModel(o.optString("model"))
                if (raw.isNotBlank()) add(SavedBike(raw, name.ifBlank { raw }, photo, model))
```

In `save()`, replace `add(SavedBike(raw, name, prior?.photoPath))` with:

```kotlin
            add(prior?.copy(name = name) ?: SavedBike(raw, name))
```

After `setPhoto`, add:

```kotlin
    /** Set (or clear, with null/blank) a bike's model text. */
    fun setModel(ctx: Context, raw: String, model: String?) {
        val m = SavedBike.normalizeModel(model)
        writeList(ctx, devices(ctx).map { if (it.raw == raw) it.copy(model = m) else it })
    }
```

In `writeList`, after `b.photoPath?.let { o.put("photo", it) }` add:

```kotlin
            b.model?.let { o.put("model", it) }
```

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideSummary.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Trip
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class RideDay { TODAY, YESTERDAY, EARLIER }

data class LastRide(val day: RideDay, val endMs: Long, val km: Double, val movingMs: Long)

/** The Ride hero's card: the latest ride and this calendar month's distance (spec §2.2). */
data class RideSummary(val last: LastRide, val monthKm: Int) {
    companion object {
        fun of(trips: List<Trip>, nowMs: Long, zone: ZoneId): RideSummary? {
            val latest = trips.maxByOrNull { it.end } ?: return null
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val rideDay = Instant.ofEpochMilli(latest.end).atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(rideDay, today)
            val day = when {
                daysAgo <= 0L -> RideDay.TODAY
                daysAgo == 1L -> RideDay.YESTERDAY
                else -> RideDay.EARLIER
            }
            val month = YearMonth.from(today)
            val monthKm = trips
                .filter { YearMonth.from(Instant.ofEpochMilli(it.start).atZone(zone)) == month }
                .sumOf { it.distanceKm }
            return RideSummary(
                LastRide(day, latest.end, (latest.distanceKm * 10).roundToLong() / 10.0, latest.movingTimeMs.coerceAtLeast(0)),
                monthKm.roundToInt(),
            )
        }

        /** "28 min" or "1 h 05 min". */
        fun durationText(ms: Long): String {
            val min = ms.coerceAtLeast(0) / 60_000
            return if (min < 60) "$min min" else "${min / 60} h ${String.format(Locale.US, "%02d", min % 60)} min"
        }
    }
}
```

`RideUiState.kt` — add at the end of the file (and `import dev.zanderp.opencfmoto.SavedBike`):

```kotlin
/** The big faded text on the hero: the rider's model, else the bike name (spec §2.2). */
fun heroText(model: String?, bikeName: String?): String = SavedBike.normalizeModel(model) ?: bikeName.orEmpty()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/BikeMemory.kt app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideSummary.kt app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideUiState.kt app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideSummaryTest.kt app/src/test/java/dev/zanderp/opencfmoto/BikeModelTest.kt app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideUiStateTest.kt
git commit -m "feat(ride): bike model field and ride summary for the hero"
```

---

### Task 2: Ride hero screen, Parked here, Garage "Set model"

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/HeroData.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ParkHere.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideScreen.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt`, `app/src/main/java/dev/zanderp/opencfmoto/GarageActivity.kt`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 1 (`RideSummary`, `RideDay`, `heroText`, `BikeMemory.setModel`, `SavedBike.model`).
- Produces: `data class HeroData(model: String?, photoPath: String?, summary: RideSummary?, parked: MapPlace?)` with `HeroData.EMPTY` and `HeroData.load(ctx): HeroData` (blocking); `decodeHeroPhoto(path: String, maxPx: Int = 1080): Bitmap?`; `ParkHere.mark(activity: Activity): Boolean`.

- [ ] **Step 1: Strings**

Add to `res/values/strings.xml` after `ui_me_forest_desc`:

```xml
    <string name="ui_last_ride">Last ride</string>
    <string name="ui_this_month">This month</string>
    <string name="ui_day_today">Today</string>
    <string name="ui_day_yesterday">Yesterday</string>
    <string name="ui_km">%1$s km</string>
    <string name="ui_tile_parked">Parked here</string>
    <string name="ui_tile_park">Park here</string>
    <string name="ui_parked_saved">Parking spot saved</string>
```

- [ ] **Step 2: Hero data + photo decode**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/HeroData.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.TripStore
import java.time.ZoneId

/** What the Ride hero shows besides the connection state. */
data class HeroData(
    val model: String?,
    val photoPath: String?,
    val summary: RideSummary?,
    val parked: MapPlace?,
) {
    companion object {
        val EMPTY = HeroData(null, null, null, null)
        @Volatile private var tripsFailLogged = false

        /** Reads prefs and the saved rides from disk — call off the main thread. */
        fun load(ctx: Context): HeroData {
            val bike = BikeMemory.selected(ctx)
            val summary = try {
                RideSummary.of(TripStore.list(ctx), System.currentTimeMillis(), ZoneId.systemDefault())
            } catch (e: Exception) {
                if (!tripsFailLogged) {
                    tripsFailLogged = true
                    LogBus.log("[RIDE] saved rides unreadable for the hero card: $e")
                }
                null
            }
            return HeroData(bike?.model, bike?.photoPath, summary, MapPlaces.parked(ctx))
        }
    }
}

/** Downsampled decode of the Garage photo (blocking). Null when missing or unreadable. */
fun decodeHeroPhoto(path: String, maxPx: Int = 1080): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    LogBus.log("[RIDE] bike photo unreadable: $e")
    null
}
```

- [ ] **Step 3: Park here helper**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ParkHere.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.R

/** "Park here" tile: saves the phone's last fix as the single parked pin (same shape as the Map hub's). */
object ParkHere {
    private const val REQ_LOCATION = 41

    /** @return true when a pin was saved. Without permission it asks and returns false (tap again). */
    fun mark(activity: Activity): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return false
        }
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        if (loc == null) {
            Toast.makeText(activity, activity.getString(R.string.gpx_need_gps_mark_parking), Toast.LENGTH_SHORT).show()
            return false
        }
        MapPlaces.setParked(activity, MapPlace("Parked", loc.latitude, loc.longitude, "parking", "Marked from Ride"))
        LogBus.log("→ parked spot saved from the Ride tab")
        Toast.makeText(activity, activity.getString(R.string.ui_parked_saved), Toast.LENGTH_SHORT).show()
        return true
    }
}
```

- [ ] **Step 4: Ride screen**

Replace the body of `ui/ride/RideScreen.kt` (keep the header) with:

```kotlin
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.ActionTile
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RideScreen(
    s: RideUiState,
    hero: HeroData,
    onPrimary: () -> Unit,
    onDashView: () -> Unit,
    onControls: () -> Unit,
    onParked: () -> Unit,
    onMirror: () -> Unit,
    onScan: () -> Unit,
    onRides: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_ride_title), style = OcmType.screenTitle, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(stringResource(s.phase.labelRes), s.statusKind)
            Spacer(Modifier.width(10.dp))
            Text(
                s.bikeName ?: stringResource(R.string.main_no_bike_paired),
                style = OcmType.body, color = c.text2, maxLines = 1,
            )
        }
        Hero(heroText(hero.model, s.bikeName), hero.photoPath)
        PrimaryButton(
            text = when (s.primary) {
                PrimaryAction.STOP -> stringResource(R.string.main_stop)
                PrimaryAction.CONNECT -> stringResource(R.string.main_connect_to, s.bikeName ?: "")
                PrimaryAction.SCAN -> stringResource(R.string.ui_scan_your_bike)
            },
            icon = if (s.primary == PrimaryAction.STOP) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
            onClick = onPrimary,
            danger = s.primary == PrimaryAction.STOP,
        )
        if (s.detail.isNotBlank()) Text(s.detail, style = OcmType.body, color = c.text2)
        hero.summary?.let { RideCard(it, onRides) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_dash_view), Icons.Rounded.CastConnected, onDashView, s.dashViewEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_controls), Icons.Rounded.Gamepad, onControls, s.controlsEnabled, Modifier.weight(1f))
            ActionTile(
                stringResource(if (hero.parked != null) R.string.ui_tile_parked else R.string.ui_tile_park),
                Icons.Rounded.LocalParking, onParked, true, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_mirror), Icons.Rounded.ScreenShare, onMirror, s.mirrorEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_scan), Icons.Rounded.QrCodeScanner, onScan, s.scanEnabled, Modifier.weight(1f))
        }
    }
}

/** Big faded model name behind the rider's photo (or a neutral bike drawing). */
@Composable
private fun Hero(text: String, photoPath: String?) {
    val c = Ocm.colors
    val photo by produceState<ImageBitmap?>(null, photoPath) {
        value = photoPath?.let { p -> withContext(Dispatchers.IO) { decodeHeroPhoto(p)?.asImageBitmap() } }
    }
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        BasicText(
            text,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            style = OcmType.heroModel.copy(color = c.text.copy(alpha = 0.15f), textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 72.sp),
        )
        val p = photo
        if (p != null) {
            Image(
                p, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.BottomCenter).size(width = 280.dp, height = 160.dp).clip(RoundedCornerShape(24.dp)),
            )
        } else {
            Icon(
                Icons.Rounded.TwoWheeler, contentDescription = null, tint = c.text2,
                modifier = Modifier.align(Alignment.BottomCenter).size(150.dp),
            )
        }
    }
}

@Composable
private fun RideCard(sum: RideSummary, onClick: () -> Unit) {
    val c = Ocm.colors
    val day = when (sum.last.day) {
        RideDay.TODAY -> stringResource(R.string.ui_day_today)
        RideDay.YESTERDAY -> stringResource(R.string.ui_day_yesterday)
        RideDay.EARLIER -> DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
            .format(Instant.ofEpochMilli(sum.last.endMs).atZone(ZoneId.systemDefault()))
    }
    val km = String.format(Locale.getDefault(), "%.1f", sum.last.km)
    OcmCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_last_ride).uppercase(), style = OcmType.label, color = c.text2)
                Text(
                    "$day · ${stringResource(R.string.ui_km, km)} · ${RideSummary.durationText(sum.last.movingMs)}",
                    style = OcmType.body, color = c.text,
                )
            }
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.ui_this_month).uppercase(), style = OcmType.label, color = c.text2)
                Text(stringResource(R.string.ui_km, sum.monthKm.toString()), style = OcmType.stat, color = c.accent)
            }
        }
    }
}
```

- [ ] **Step 5: AppShell wiring**

In `ui/shell/AppShell.kt` add imports:

```kotlin
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zanderp.opencfmoto.NavLauncher
import dev.zanderp.opencfmoto.ui.ride.HeroData
import dev.zanderp.opencfmoto.ui.ride.ParkHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

After `val route = backStack?.destination?.route` add:

```kotlin
    // Hero data (model, photo, rides, parked pin) reloads on every resume — a ride just saved or a
    // model just typed in Garage shows when the rider comes back.
    var heroTick by remember { mutableIntStateOf(0) }
    var hero by remember { mutableStateOf(HeroData.EMPTY) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, bike, heroTick) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hero = withContext(Dispatchers.IO) { HeroData.load(ctx.applicationContext) }
        }
    }
```

Replace the `RideScreen(...)` call with:

```kotlin
                RideScreen(
                    s = ride,
                    hero = hero,
                    onPrimary = c::onConnectPressed,
                    onDashView = { ctx.startActivity(android.content.Intent(ctx, HudViewActivity::class.java)) },
                    onControls = { ctx.startActivity(android.content.Intent(ctx, ControlsActivity::class.java)) },
                    onParked = {
                        val p = hero.parked
                        if (p != null) NavLauncher.navigateLatLon(ctx, p.lat, p.lon, p.name, LogBus::log)
                        else if (ParkHere.mark(ctx as Activity)) heroTick++
                    },
                    onMirror = c::onMirrorPressed,
                    onScan = c::startAaScan,
                    onRides = { TripsListActivity.start(ctx) },
                )
```

(`android.app.Activity` is already imported in `AppShell.kt`.)

- [ ] **Step 6: Garage "Set model"**

In `GarageActivity.showActions`, change the list so "Set model" follows "Rename":

```kotlin
            add("Rename")
            add("Set model")
```

and in the `when (items[which])` add after `"Rename" -> promptRename(bike)`:

```kotlin
                    "Set model" -> promptModel(bike)
```

Add after `promptRename`:

```kotlin
    private fun promptModel(bike: SavedBike) {
        val input = EditText(this).apply {
            setText(bike.model.orEmpty())
            setSelection(text.length)
            hint = "e.g. 150 SC"
        }
        val pad = dp(20)
        val wrap = LinearLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bike model")
            .setMessage("Shown big on the Ride screen. Leave empty to show the bike name.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                BikeMemory.setModel(this, bike.raw, input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
```

In `buildCard`, after the name `TextView` is added to `col`, add:

```kotlin
        bike.model?.let { m ->
            col.addView(TextView(this).apply {
                text = m
                setTextColor(ContextCompat.getColor(this@GarageActivity, R.color.text_secondary))
                textSize = 14f
            })
        }
```

- [ ] **Step 7: Build, suite, emulator check**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green. Run: `./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL.
Emulator (`ocm_test`): install; Ride shows bike name big + neutral bike when no model/photo; Garage → bike → Set model "150 SC" → back → hero shows "150 SC"; Add photo → hero shows photo; with no trips the ride card is hidden; seed a trip (record a short trip via Trip computer or push a trip file) → card shows "Today · x km · y min" and month km; Park here with emulator location set → toast + tile becomes "Parked here"; tap → Google Maps intent (or the "navigation failed" log when Maps is missing). Light and dark.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/ride app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt app/src/main/java/dev/zanderp/opencfmoto/GarageActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(ride): My moto hero with model, photo, last ride and Parked here"
```

---

### Task 3: Shared pad and Controls in Compose

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/AaKeys.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/AaPad.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ControlsScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ButtonMap.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/design/Components.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ControlsActivity.kt`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/AaKeysTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/ButtonMapCountTest.kt`

**Interfaces:**
- Produces: `AaKeys.Target { MAP, AA, NONE }`; `AaKeys.target(mapFirst: Boolean, hasMap: Boolean, hasAa: Boolean): Target`; `AaKeys.noSinkText(mapFirst: Boolean): String`; `AaKeys.key(ctx: Context, code: Int, mapFirst: Boolean)`; `AaKeys.scroll(ctx: Context, delta: Int, mapFirst: Boolean)`; `AaKeys.ensureMic(activity: Activity, requestCode: Int)`; `ButtonMap.mappedCount(actions: Collection<ButtonAction>): Int` and `ButtonMap.mappedCount(context: Context): Int`; `@Composable AaPad(onKey: (Int) -> Unit, onScroll: (Int) -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier, fill: Boolean = false)`; `@Composable SegmentedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)`; `@Composable OcmTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {})`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/zanderp/opencfmoto/AaKeysTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class AaKeysTest {
    @Test
    fun controlsSendsToMapPreviewFirst() {
        assertEquals(AaKeys.Target.MAP, AaKeys.target(mapFirst = true, hasMap = true, hasAa = true))
        assertEquals(AaKeys.Target.AA, AaKeys.target(mapFirst = true, hasMap = false, hasAa = true))
    }

    @Test
    fun dashViewIgnoresMapPreview() {
        assertEquals(AaKeys.Target.AA, AaKeys.target(mapFirst = false, hasMap = true, hasAa = true))
        assertEquals(AaKeys.Target.NONE, AaKeys.target(mapFirst = false, hasMap = true, hasAa = false))
    }

    @Test
    fun noSessionSendsNothingWithTheScreensOwnMessage() {
        assertEquals(AaKeys.Target.NONE, AaKeys.target(mapFirst = true, hasMap = false, hasAa = false))
        assertEquals("Start Android Auto or Map first", AaKeys.noSinkText(mapFirst = true))
        assertEquals("Connect to the bike first", AaKeys.noSinkText(mapFirst = false))
    }
}
```

`app/src/test/java/dev/zanderp/opencfmoto/ButtonMapCountTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonMapCountTest {
    @Test
    fun countsEverythingButDoNothing() {
        assertEquals(0, ButtonMap.mappedCount(emptyList()))
        assertEquals(
            2,
            ButtonMap.mappedCount(listOf(ButtonAction.SELECT, ButtonAction.NONE, ButtonAction.HOME, ButtonAction.NONE)),
        )
    }

    @Test
    fun shippedDefaultsMapSevenOfNine() {
        assertEquals(7, ButtonMap.mappedCount(ButtonGesture.entries.map { it.default }))
    }
}
```

(`ButtonGesture.default` is the shipped action; 9 gestures, two of them default to `NONE`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests dev.zanderp.opencfmoto.AaKeysTest --tests dev.zanderp.opencfmoto.ButtonMapCountTest $F`
Expected: compilation FAIL — `AaKeys`, `mappedCount` unresolved.

- [ ] **Step 3: Implement `AaKeys` and `mappedCount`**

`app/src/main/java/dev/zanderp/opencfmoto/AaKeys.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * On-screen pad routing shared by Controls and Dash view. Controls drives the phone Map preview
 * first ([MapInputBridge]) then Android Auto; Dash view drives Android Auto only.
 */
object AaKeys {
    enum class Target { MAP, AA, NONE }

    fun target(mapFirst: Boolean, hasMap: Boolean, hasAa: Boolean): Target = when {
        mapFirst && hasMap -> Target.MAP
        hasAa -> Target.AA
        else -> Target.NONE
    }

    fun noSinkText(mapFirst: Boolean): String =
        if (mapFirst) "Start Android Auto or Map first" else "Connect to the bike first"

    fun key(ctx: Context, code: Int, mapFirst: Boolean) {
        val map = MapInputBridge.keySink
        val aa = AaVideoBridge.keySink
        when (target(mapFirst, map != null, aa != null)) {
            Target.MAP -> map?.invoke(code)
            Target.AA -> aa?.invoke(code)
            Target.NONE -> Toast.makeText(ctx, noSinkText(mapFirst), Toast.LENGTH_SHORT).show()
        }
    }

    fun scroll(ctx: Context, delta: Int, mapFirst: Boolean) {
        val map = MapInputBridge.scrollSink
        val aa = AaVideoBridge.scrollSink
        when (target(mapFirst, map != null, aa != null)) {
            Target.MAP -> map?.invoke(delta)
            Target.AA -> aa?.invoke(delta)
            Target.NONE -> Toast.makeText(ctx, noSinkText(mapFirst), Toast.LENGTH_SHORT).show()
        }
    }

    /** The Assistant needs the mic to hear you — ask for it the first time voice is used. */
    fun ensureMic(activity: Activity, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestCode)
        }
    }
}
```

In `ButtonMap.kt`, inside `object ButtonMap`, after `isAllDefault`:

```kotlin
    /** Gestures that do something (for the "N buttons mapped" summary). */
    fun mappedCount(actions: Collection<ButtonAction>): Int = actions.count { it != ButtonAction.NONE }

    fun mappedCount(context: Context): Int = mappedCount(ButtonGesture.entries.map { get(context, it) })
```

- [ ] **Step 4: Run tests to verify they pass**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 5: Design components**

Append to `ui/design/Components.kt` (add imports `androidx.compose.foundation.layout.RowScope`, `androidx.compose.material.icons.automirrored.rounded.ArrowBack`, `androidx.compose.material3.IconButton`, `androidx.compose.foundation.layout.statusBarsPadding`, `androidx.compose.ui.graphics.Color`):

```kotlin
/** Equal-width choice pills (map theme, on/off). 48 dp pills + 4 dp frame = 56 dp target. */
@Composable
fun SegmentedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ocm.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) c.accent else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = OcmType.title, color = if (on) c.onAccent else c.text, maxLines = 1)
            }
        }
    }
}

/** Back arrow + title + optional trailing actions, below the status bar. */
@Composable
fun OcmTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Ocm.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = c.text)
        }
        Text(title, style = OcmType.screenTitle, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        actions()
    }
}
```

- [ ] **Step 6: AaPad**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/AaPad.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType

/**
 * On-screen Android Auto pad: knob (⟲/⟳), d-pad + OK, then Back · Home · Voice. [fill] stretches the
 * rows to the available height (Controls full screen); otherwise keys are 64 dp tall.
 */
@Composable
fun AaPad(
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_prev)) { onScroll(-1) }
            PadKey(stringResource(R.string.controls_str)) { onKey(AaInput.KEY_UP) }
            PadKey(stringResource(R.string.controls_next)) { onScroll(+1) }
        }
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_str_2)) { onKey(AaInput.KEY_LEFT) }
            PadKey("OK", accent = true) { onKey(AaInput.KEY_ENTER) }
            PadKey(stringResource(R.string.controls_str_3)) { onKey(AaInput.KEY_RIGHT) }
        }
        PadRow(fill) {
            Spacer(Modifier.weight(1f))
            PadKey(stringResource(R.string.controls_str_4)) { onKey(AaInput.KEY_DOWN) }
            Spacer(Modifier.weight(1f))
        }
        PadRow(fill) {
            PadKey(stringResource(R.string.controls_back)) { onKey(AaInput.KEY_BACK) }
            PadKey(stringResource(R.string.controls_home)) { onKey(AaInput.KEY_HOME) }
            PadKey(stringResource(R.string.controls_voice)) { onVoice() }
        }
    }
}

@Composable
private fun ColumnScope.PadRow(fill: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        (if (fill) Modifier.weight(1f) else Modifier.height(64.dp)).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.PadKey(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Ocm.colors
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
            .background(if (accent) c.accent else c.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcmType.title, color = if (accent) c.onAccent else c.text, maxLines = 1)
    }
}
```

- [ ] **Step 7: Strings for Controls**

Add to `res/values/strings.xml` after the Task 2 strings:

```xml
    <string name="ui_controls_mapped">%1$s · %2$d buttons mapped</string>
    <string name="ui_controls_exit">Exit</string>
```

- [ ] **Step 8: Controls screen**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ControlsScreen.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.MapTheme
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SegmentedChoice
import dev.zanderp.opencfmoto.ui.design.SettingRow
import kotlin.math.roundToInt

data class PlaceChip(val slot: Int, val name: String)

data class ControlsUi(
    val places: List<PlaceChip>,
    val theme: MapTheme,
    val touchDash: Boolean,
    val volume: Int,
    val volumeMax: Int,
    val controlAa: Boolean,
    val btStatus: String,
    val mapped: Int,
    val padFullscreen: Boolean,
)

private val Themes = listOf(MapTheme.AUTO, MapTheme.DAY, MapTheme.NIGHT)

@Composable
fun ControlsScreen(
    ui: ControlsUi,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onPlace: (Int) -> Unit,
    onTheme: (MapTheme) -> Unit,
    onPadFullscreen: (Boolean) -> Unit,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
    onVolume: (Int) -> Unit,
    onControlAa: (Boolean) -> Unit,
    onButtonMapping: () -> Unit,
) {
    val c = Ocm.colors
    if (ui.padFullscreen) {
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_on_screen_pad), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { onPadFullscreen(false) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.ui_controls_exit), style = OcmType.title, color = c.accent)
                }
            }
            AaPad(onKey, onScroll, onVoice, Modifier.weight(1f).fillMaxWidth(), fill = true)
        }
        return
    }
    OcmScreen {
        OcmTopBar(stringResource(R.string.controls_controls), onBack)
        // 1. Navigate to…
        OcmCard {
            Text(stringResource(R.string.controls_navigate_to), style = OcmType.title, color = c.text)
            var dest by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    dest, { dest = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.controls_destination)) },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onNavigate(dest) }, modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) { Text(stringResource(R.string.controls_navigate)) }
            }
            if (ui.places.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in ui.places) PlacePill(p) { onPlace(p.slot) }
                }
            }
        }
        // 2. Map theme
        OcmCard {
            Text(stringResource(R.string.controls_map_theme), style = OcmType.title, color = c.text)
            SegmentedChoice(
                listOf(stringResource(R.string.controls_auto), stringResource(R.string.controls_day), stringResource(R.string.controls_night)),
                Themes.indexOf(ui.theme).coerceAtLeast(0),
                { onTheme(Themes[it]) },
            )
        }
        // 3. On-screen pad
        OcmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_on_screen_pad), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { onPadFullscreen(true) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = null, tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.controls_fullscreen), color = c.accent)
                }
            }
            if (ui.touchDash) {
                Text(stringResource(R.string.controls_your_dash_is_a_touchscreen_just_tap_the_screen_t), style = OcmType.body, color = c.busy)
            }
            AaPad(onKey, onScroll, onVoice, Modifier.fillMaxWidth())
        }
        // 4. Volume
        OcmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.controls_volume), style = OcmType.title, color = c.text, modifier = Modifier.weight(1f))
                Text(ui.volume.toString(), style = OcmType.title, color = c.accent)
            }
            Slider(
                value = ui.volume.toFloat(),
                onValueChange = { onVolume(it.roundToInt()) },
                valueRange = 0f..ui.volumeMax.coerceAtLeast(1).toFloat(),
                steps = (ui.volumeMax - 1).coerceAtLeast(0),
            )
        }
        // 5. Handlebar buttons
        OcmCard {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.controls_handlebar_buttons_drive_android_auto),
                    style = OcmType.title, color = c.text, modifier = Modifier.weight(1f),
                )
                Switch(
                    ui.controlAa, onControlAa,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                )
            }
            SettingRow(
                title = stringResource(R.string.controls_customize_buttons),
                subtitle = stringResource(R.string.ui_controls_mapped, ui.btStatus, ui.mapped),
                onClick = onButtonMapping,
            )
        }
    }
}

@Composable
private fun PlacePill(p: PlaceChip, onClick: () -> Unit) {
    val c = Ocm.colors
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(c.surface2).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (p.slot) { 0 -> Icons.Rounded.Home; 1 -> Icons.Rounded.Work; else -> Icons.Rounded.Star },
            contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(p.name, style = OcmType.title, color = c.text, maxLines = 1)
    }
}
```

- [ ] **Step 9: ControlsActivity in Compose**

Replace `ControlsActivity.kt` (keep the header) with:

```kotlin
package dev.zanderp.opencfmoto

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.ControlsScreen
import dev.zanderp.opencfmoto.ui.ride.ControlsUi
import dev.zanderp.opencfmoto.ui.ride.PlaceChip

/** Drive Android Auto from the phone: navigate-to, map theme, pad, volume, handlebar mode. */
class ControlsActivity : AppCompatActivity() {

    private var ui by mutableStateOf(
        ControlsUi(emptyList(), MapTheme.AUTO, false, 0, 15, false, "", 0, false),
    )
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ui.padFullscreen) setPadFullscreen(false) else finish()
            }
        })

        // Touchscreen dashes (800MT, 1000 MT-X) run Android Auto's touch UI, which has no focus
        // cursor for a rotary knob to move — so handlebar-button navigation can't work there and only
        // costs the rider their music. Warn, and let them just tap the dash. The live profile is
        // authoritative once connected; otherwise fall back to what the last connect remembered.
        AppSettings.applyToHolder(this)
        val live = BikeProfileHolder.active
        val touchDash: Boolean? = if (live !== BikeProfiles.legacy) {
            DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
            BikeProfileHolder.advertisesScreenTouch
        } else {
            DashMemory.lastDashTouch(this)
        }
        ui = ui.copy(touchDash = touchDash == true)

        setContent {
            OcmTheme {
                ControlsScreen(
                    ui = ui,
                    onBack = { finish() },
                    onNavigate = { dest ->
                        if (dest.isBlank()) Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
                        else NavLauncher.navigate(this, dest.trim(), LogBus::log)
                    },
                    onPlace = { slot -> NavLauncher.navigate(this, SavedPlaces.query(this, slot), LogBus::log) },
                    onTheme = ::setMapTheme,
                    onPadFullscreen = ::setPadFullscreen,
                    onKey = { AaKeys.key(this, it, mapFirst = true) },
                    onScroll = { AaKeys.scroll(this, it, mapFirst = true) },
                    onVoice = {
                        AaKeys.ensureMic(this, REQ_MIC)
                        AaKeys.key(this, AaInput.KEY_ASSISTANT, mapFirst = true)
                    },
                    onVolume = { v ->
                        ui = ui.copy(volume = v)
                        MediaButtonBridge.setVolume(this, v)
                    },
                    onControlAa = { checked ->
                        ButtonMode.set(this, checked)
                        MediaButtonBridge.instance?.setCaptureActive(checked)
                        LogBus.log("→ handlebar buttons ${if (checked) "control Android Auto" else "control media"}")
                        if (checked) AaKeys.ensureMic(this, REQ_MIC)
                        ui = ui.copy(controlAa = checked)
                    },
                    onButtonMapping = { startActivity(Intent(this, ButtonMappingActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val (now, max) = MediaButtonBridge.volumeLevels(this)
        ui = ui.copy(
            places = (0 until SavedPlaces.COUNT).filter { SavedPlaces.isSet(this, it) }.map { slot ->
                PlaceChip(slot, SavedPlaces.name(this, slot).ifBlank { SavedPlaces.query(this, slot) })
            },
            theme = NightPrefs.theme(this),
            volume = now,
            volumeMax = max,
            controlAa = ButtonMode.isControlAa(this),
            btStatus = BluetoothHelper.status(this).shortLine(),
            mapped = ButtonMap.mappedCount(this),
        )
    }

    /**
     * Driving mode: pad fills the screen, system bars hide, and [FLAG_KEEP_SCREEN_ON] stops the
     * phone from dimming/locking while the pad is up. Back exits fullscreen first.
     */
    private fun setPadFullscreen(on: Boolean) {
        ui = ui.copy(padFullscreen = on)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Pad fullscreen — screen stays on. Press Back to exit.", Toast.LENGTH_SHORT).show()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        ui = ui.copy(theme = theme)
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_MIC = 71
    }
}
```

- [ ] **Step 10: Build and check**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green; `./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL.
Emulator: Ride → Controls: every card renders light + dark; empty Go → "Type a destination first"; place chips appear after setting places in Button mapping; theme segment switches + toast; pad keys with no session → "Start Android Auto or Map first"; Full screen → pad fills, bars hide; Back exits full screen, second Back closes; rotation keeps full screen; volume slider moves; switch toggles and logs `→ handlebar buttons …`; row opens Button mapping.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/AaKeys.kt app/src/main/java/dev/zanderp/opencfmoto/ButtonMap.kt app/src/main/java/dev/zanderp/opencfmoto/ui/design/Components.kt app/src/main/java/dev/zanderp/opencfmoto/ui/ride/AaPad.kt app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ControlsScreen.kt app/src/main/java/dev/zanderp/opencfmoto/ControlsActivity.kt app/src/main/res/values/strings.xml app/src/test/java/dev/zanderp/opencfmoto/AaKeysTest.kt app/src/test/java/dev/zanderp/opencfmoto/ButtonMapCountTest.kt
git commit -m "feat(ride): Controls in Compose with the shared on-screen pad"
```

---

### Task 4: Dash view in Compose

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/DashViewScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/HudViewActivity.kt`

**Interfaces:**
- Consumes: `AaKeys` (Task 3), `AaPad` (Task 3).
- Produces: `data class DashViewUi(title: String, topBar: Boolean, pad: Boolean, hint: Boolean, gpxPreview: Boolean)`; `@Composable DashViewScreen(ui: DashViewUi, surface: SurfaceView, gpxHost: FrameLayout, onClose, onMapHub, onTheme, onTogglePad, onFullscreen: () -> Unit, onKey: (Int) -> Unit, onScroll: (Int) -> Unit, onVoice: () -> Unit)`.

- [ ] **Step 1: Dash view screen**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/DashViewScreen.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.content.res.Configuration
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType

data class DashViewUi(
    val title: String,
    val topBar: Boolean,
    val pad: Boolean,
    val hint: Boolean,
    val gpxPreview: Boolean,
)

/** A View owned by the Activity, moved into whichever AndroidView hosts it now. */
private fun <T : View> T.detached(): T = apply { (parent as? ViewGroup)?.removeView(this) }

@Composable
fun DashViewScreen(
    ui: DashViewUi,
    surface: SurfaceView,
    gpxHost: FrameLayout,
    onClose: () -> Unit,
    onMapHub: () -> Unit,
    onTheme: () -> Unit,
    onTogglePad: () -> Unit,
    onFullscreen: () -> Unit,
    onKey: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onVoice: () -> Unit,
) {
    val c = Ocm.colors
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (ui.topBar) {
            Row(
                Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().displayCutoutPadding().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.Rounded.Close, onClose)
                Text(ui.title, style = OcmType.title, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                BarIcon(Icons.Rounded.Map, onMapHub)
                BarIcon(Icons.Rounded.Contrast, onTheme)
                BarIcon(Icons.Rounded.Gamepad, onTogglePad)
                BarIcon(Icons.Rounded.Fullscreen, onFullscreen)
            }
        }
        val video: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                AndroidView(
                    factory = { surface.detached() }, modifier = Modifier.fillMaxSize(),
                    update = { it.visibility = if (ui.gpxPreview) View.GONE else View.VISIBLE },
                )
                AndroidView(
                    factory = { gpxHost.detached() }, modifier = Modifier.fillMaxSize(),
                    update = { it.visibility = if (ui.gpxPreview) View.VISIBLE else View.GONE },
                )
                if (ui.hint) {
                    Text(
                        stringResource(R.string.hud_start_android_auto_and_connect_to_your_bike_to_s),
                        style = OcmType.body, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                video(Modifier.weight(1f).fillMaxHeight())
                if (ui.pad) {
                    AaPad(
                        onKey, onScroll, onVoice,
                        Modifier.width(300.dp).fillMaxHeight().background(c.bg).navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        } else {
            video(Modifier.weight(1f).fillMaxWidth())
            if (ui.pad) {
                AaPad(onKey, onScroll, onVoice, Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(12.dp))
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = null, tint = Ocm.colors.accent)
    }
}
```

- [ ] **Step 2: HudViewActivity in Compose**

Edit `HudViewActivity.kt`:

1. Imports: remove `android.view.LayoutInflater`? — **keep** (used for `presentation_gpx`). Remove `android.widget.TextView`, `androidx.core.view.ViewCompat`, `android.Manifest`, `android.content.pm.PackageManager`, `androidx.core.content.ContextCompat`. Add:

```kotlin
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.DashViewScreen
import dev.zanderp.opencfmoto.ui.ride.DashViewUi
```

2. Replace the fields `surface`, `gpxHost`, `hint`, `titleView`, `fullscreen`, `gpxPreview` with:

```kotlin
    private lateinit var surface: SurfaceView
    private lateinit var gpxHost: FrameLayout
    private var fullscreen = false
    private var gpxPreview = false
    private var padShown = true
    private var ui by mutableStateOf(DashViewUi("Dash view", topBar = true, pad = true, hint = true, gpxPreview = false))
```

and add after the fields:

```kotlin
    /** Recompute what the screen shows from the mode flags (replaces the old per-View visibility). */
    private fun render(title: String = ui.title, hint: Boolean = ui.hint) {
        ui = DashViewUi(
            title = title,
            topBar = !fullscreen && !gpxPreview,
            pad = !fullscreen && !gpxPreview && padShown,
            hint = hint && !gpxPreview,
            gpxPreview = gpxPreview,
        )
    }
```

3. In `onCreate`, replace everything from `setContentView(R.layout.activity_hud_view)` down to (but not including) `onBackPressedDispatcher.addCallback` with:

```kotlin
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        gpxHost = FrameLayout(this).apply { visibility = View.GONE }
        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
```

Keep the `onBackPressedDispatcher` callback, the `surface.holder.addCallback(...)` block and `surface.setOnTouchListener` unchanged. Replace everything from `findViewById<View>(R.id.hud_close)` to the end of `onCreate` with:

```kotlin
        setContent {
            OcmTheme {
                DashViewScreen(
                    ui = ui,
                    surface = surface,
                    gpxHost = gpxHost,
                    onClose = { finish() },
                    onMapHub = { GpxActivity.start(this) },
                    onTheme = ::cycleMapTheme,
                    onTogglePad = ::toggleControls,
                    onFullscreen = { setFullscreen(!fullscreen) },
                    onKey = ::key,
                    onScroll = ::scroll,
                    onVoice = {
                        AaKeys.ensureMic(this, REQ_MIC)
                        key(AaInput.KEY_ASSISTANT)
                    },
                )
            }
        }
```

4. In `refreshMode`, replace the whole `else { … }` branch with:

```kotlin
        } else {
            stopGpxPreview()
            render(title = "Dash view", hint = AaVideoBridge.pipeline == null)
        }
```

5. In `startGpxPreview`: replace the early-return body `hint.visibility = View.GONE` with `render()`; delete the lines `surface.visibility = View.GONE`, `gpxHost.visibility = View.VISIBLE`, `hint.visibility = View.GONE` and the four `findViewById<View>(R.id.hud_…).visibility = View.GONE` lines; replace `titleView.text = when (GpxSession.mode) { … }` with:

```kotlin
        render(
            title = when (GpxSession.mode) {
                GpxSession.Mode.GPX -> "GPX · ${GpxSession.trackName}"
                GpxSession.Mode.NAV_TO -> "Nav · ${GpxSession.trackName}"
                GpxSession.Mode.FREE_RIDE -> "Map preview"
            },
        )
```

(`gpxPreview = true` is already set earlier in the function; `render()` reads it.) Keep `gpxHost.removeAllViews()` / `addView(root, …)` unchanged.

6. In `stopGpxPreview`, delete `gpxHost.visibility = View.GONE` and add `render()` as the last line of the non-early-return path.

7. Replace `toggleControls` and `setFullscreen` with:

```kotlin
    private fun toggleControls() {
        if (gpxPreview) return
        padShown = !padShown
        render()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        render()
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Fullscreen — press Back to exit", Toast.LENGTH_SHORT).show()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
```

8. Replace `key`, `scroll` and delete `ensureMicPermission`:

```kotlin
    private fun key(code: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        AaKeys.key(this, code, mapFirst = false)
    }

    private fun scroll(delta: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        AaKeys.scroll(this, delta, mapFirst = false)
    }
```

`onSurfaceTouch`, `forward`, `mapToSource`, `cycleMapTheme`, `onNewIntent`, `onResume`, `onPause`, `onDestroy`, `onConfigurationChanged`, `sessionKey`, `dispatchKeyEvent` and the companion stay as they are.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL. Fix any leftover `findViewById`/`R.id.hud_*` references (there must be none: `grep -n "R.id.hud_\|findViewById" app/src/main/java/dev/zanderp/opencfmoto/HudViewActivity.kt` prints nothing).

- [ ] **Step 4: Suite**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green.

- [ ] **Step 5: Emulator check**

Without a session: Dash view shows the top bar, black video area with the hint, and the pad; pad key → "Connect to the bike first"; theme icon → toast; pad toggle hides/shows the pad; full screen hides bars + toast, Back exits, next Back closes; rotate portrait↔landscape (pad moves to the right, scrolls) with no crash and `[HUD]` log shows no repeated attach/detach loop. Map hub → Map preview (free ride) → preview renders full screen with its own chrome; Back returns. Light and dark.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/ride/DashViewScreen.kt app/src/main/java/dev/zanderp/opencfmoto/HudViewActivity.kt
git commit -m "feat(ride): Dash view in Compose"
```

---

### Task 5: Button mapping in Compose

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ButtonMappingScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ButtonMappingActivity.kt`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `SegmentedChoice`, `OcmTopBar` (Task 3), `ButtonMap`, `ButtonClusterPreset`, `RemotePad`, `SavedPlaces`, `ButtonPresencePrefs`, `BluetoothHelper` (existing).
- Produces: `data class MappingRow(gesture: ButtonGesture, action: String)`; `data class MappingUi(btStatus, presence: String, rows: List<MappingRow>, placesSummary: String, remoteOn: Boolean, presetText: String, presetActive: Boolean, overlayNeeded: Boolean, resetEnabled: Boolean)`; `sealed interface MappingDialog`; `@Composable ButtonMappingScreen(...)`.

- [ ] **Step 1: Strings**

Add after the Task 3 strings:

```xml
    <string name="ui_mapping_places_none">No places yet</string>
    <string name="ui_mapping_place_n">Place %1$d</string>
    <string name="ui_save">Save</string>
```

- [ ] **Step 2: Screen**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ButtonMappingScreen.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.ButtonClusterPreset
import dev.zanderp.opencfmoto.ButtonGesture
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SegmentedChoice
import dev.zanderp.opencfmoto.ui.design.SettingRow

data class MappingRow(val gesture: ButtonGesture, val action: String)

data class MappingUi(
    val btStatus: String,
    val presence: String,
    val rows: List<MappingRow>,
    val placesSummary: String,
    val remoteOn: Boolean,
    val presetText: String,
    val presetActive: Boolean,
    val overlayNeeded: Boolean,
    val resetEnabled: Boolean,
)

/** At most one dialog at a time; the Activity owns it so actions stay next to the prefs they write. */
sealed interface MappingDialog {
    data class PickAction(val gesture: ButtonGesture, val labels: List<String>, val current: Int) : MappingDialog
    data class Places(val names: List<String>, val queries: List<String>) : MappingDialog
    data object TeachHandlebar : MappingDialog
    data object TeachRemotePick : MappingDialog
    data class TeachRemoteWait(val gesture: ButtonGesture) : MappingDialog
    data object PresetPick : MappingDialog
    data class PresetConfirm(val preset: ButtonClusterPreset) : MappingDialog
    data object ClearPreset : MappingDialog
    data object Reset : MappingDialog
}

@Composable
fun ButtonMappingScreen(
    ui: MappingUi,
    dialog: MappingDialog?,
    onBack: () -> Unit,
    onBtSettings: () -> Unit,
    onTeachHandlebar: () -> Unit,
    onRow: (ButtonGesture) -> Unit,
    onPlaces: () -> Unit,
    onRemote: (Boolean) -> Unit,
    onTeachRemote: () -> Unit,
    onPreset: () -> Unit,
    onClearPreset: () -> Unit,
    onOverlay: () -> Unit,
    onReset: () -> Unit,
    // dialog results
    onDismiss: () -> Unit,
    onPickAction: (ButtonGesture, Int) -> Unit,
    onSavePlaces: (List<String>, List<String>) -> Unit,
    onVolumePresence: (Int) -> Unit,          // 1 present, 0 absent, -1 reset
    onRemoteGesture: (ButtonGesture) -> Unit,
    onPresetChosen: (ButtonClusterPreset) -> Unit,
    onPresetApply: (ButtonClusterPreset) -> Unit,
    onClearConfirmed: () -> Unit,
    onResetConfirmed: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        OcmTopBar(stringResource(R.string.buttons_button_mapping), onBack)
        // 1. Bluetooth
        OcmCard {
            Text(ui.btStatus, style = OcmType.title, color = c.text, modifier = Modifier.fillMaxWidth().clickable(onClick = onBtSettings).heightIn(min = 48.dp))
            Text(ui.presence, style = OcmType.body, color = c.text2)
            ActionButton(stringResource(R.string.buttons_teach_handlebar), onTeachHandlebar)
        }
        // 2. What each press does
        OcmCard {
            Text(stringResource(R.string.buttons_gestures), style = OcmType.title, color = c.text)
            for (r in ui.rows) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onRow(r.gesture) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.gesture.label, style = OcmType.title, color = c.text)
                        Text(r.gesture.hint, style = OcmType.body, color = c.text2)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(r.action, style = OcmType.body, color = c.accent, maxLines = 2)
                }
            }
        }
        // 3. Saved places
        OcmCard {
            SettingRow(stringResource(R.string.buttons_saved_places), ui.placesSummary, onClick = onPlaces)
        }
        // 4. Helmet / remote pad
        OcmCard {
            Text(stringResource(R.string.buttons_remote_pad), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.buttons_remote_pad_desc), style = OcmType.body, color = c.text2)
            SegmentedChoice(
                listOf(stringResource(R.string.gpx_on), stringResource(R.string.res_off)),
                if (ui.remoteOn) 0 else 1,
                { onRemote(it == 0) },
            )
            ActionButton(stringResource(R.string.buttons_teach_remote), onTeachRemote)
        }
        // 5. Dash preset
        OcmCard {
            Text(stringResource(R.string.buttons_your_left_switch_cluster), style = OcmType.title, color = c.text)
            Text(ui.presetText, style = OcmType.body, color = c.text2)
            Row {
                ActionButton(stringResource(R.string.buttons_apply_preset), onPreset, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClearPreset, enabled = ui.presetActive, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.buttons_clear_preset))
                }
            }
        }
        // 6. Overlay permission
        if (ui.overlayNeeded) {
            OcmCard {
                Text(stringResource(R.string.buttons_allow_navigate_from_handlebars), style = OcmType.title, color = c.text)
                Text(stringResource(R.string.buttons_starting_navigation_from_a_handlebar_button_whil), style = OcmType.body, color = c.text2)
                ActionButton(stringResource(R.string.buttons_enable_display_over_other_apps), onOverlay)
            }
        }
        // 7. Reset
        OutlinedButton(onClick = onReset, enabled = ui.resetEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.buttons_reset_to_defaults))
        }
    }

    when (dialog) {
        null -> Unit
        is MappingDialog.PickAction -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.gesture.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    dialog.labels.forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(i == dialog.current) { onPickAction(dialog.gesture, i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(i == dialog.current, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MappingDialog.Places -> {
            val names = rememberSaveable(dialog) { dialog.names.toMutableList() }.toMutableStateList()
            val queries = rememberSaveable(dialog) { dialog.queries.toMutableList() }.toMutableStateList()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.buttons_saved_places)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.buttons_map_a_gesture_to_navigate_to_saved_place_and_a_p), style = OcmType.body)
                        for (i in names.indices) {
                            Text(stringResource(R.string.ui_mapping_place_n, i + 1), style = OcmType.label, modifier = Modifier.padding(top = 12.dp))
                            OutlinedTextField(names[i], { names[i] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.row_saved_place_name_e_g_home)) })
                            OutlinedTextField(queries[i], { queries[i] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.row_saved_place_address_or_place)) })
                        }
                    }
                },
                confirmButton = { TextButton({ onSavePlaces(names.toList(), queries.toList()) }) { Text(stringResource(R.string.ui_save)) } },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        MappingDialog.TeachHandlebar -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.buttons_teach_title)) },
            text = { Text(stringResource(R.string.buttons_teach_message)) },
            confirmButton = { TextButton({ onVolumePresence(1) }) { Text(stringResource(R.string.buttons_teach_volume_present)) } },
            dismissButton = {
                Row {
                    TextButton({ onVolumePresence(-1) }) { Text(stringResource(R.string.buttons_teach_reset)) }
                    TextButton({ onVolumePresence(0) }) { Text(stringResource(R.string.buttons_teach_volume_absent)) }
                }
            },
        )
        MappingDialog.TeachRemotePick -> ListDialog(
            stringResource(R.string.buttons_teach_remote_pick), ButtonGesture.entries.map { it.label }, onDismiss,
        ) { onRemoteGesture(ButtonGesture.entries[it]) }
        is MappingDialog.TeachRemoteWait -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.buttons_teach_remote_wait)) },
            text = { Text(dialog.gesture.label) },
            confirmButton = {},
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        MappingDialog.PresetPick -> ListDialog(
            "Left switch cluster", ButtonClusterPreset.entries.map { it.title }, onDismiss,
        ) { onPresetChosen(ButtonClusterPreset.entries[it]) }
        is MappingDialog.PresetConfirm -> ConfirmDialog(
            dialog.preset.title,
            dialog.preset.summary + "\n\nReplace the current mapping for this bike? Saved places are kept.",
            "Apply", onDismiss,
        ) { onPresetApply(dialog.preset) }
        MappingDialog.ClearPreset -> ConfirmDialog(
            "Clear cluster preset?",
            "Removes the active cluster tag and restores shipped gesture defaults. " +
                "Saved places stay. Some bikes work fine with no preset — just tweak rows below if needed.",
            "Clear", onDismiss, onClearConfirmed,
        )
        MappingDialog.Reset -> ConfirmDialog(
            "Reset to defaults?",
            "Every gesture goes back to the shipped defaults: ◀/▶ = knob, " +
                "★ = Select, ★ hold = Home, ◀◀/▶▶ = D-pad ←→, ★★ = Back.\n\n" +
                "Does not change which cluster preset is active. Use Clear preset to drop the tag.",
            "Reset", onDismiss, onResetConfirmed,
        )
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val c = Ocm.colors
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
    ) { Text(text) }
}

@Composable
private fun ListDialog(title: String, items: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, label ->
                    Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(i) }.padding(vertical = 16.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
```


- [ ] **Step 3: ButtonMappingActivity in Compose**

Replace `ButtonMappingActivity.kt` from the `import` block down to the closing brace of the class (keep the header lines 1–5 and the two private extension properties `isNav` / `navSlot` at the bottom of the file) with:

```kotlin
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.ButtonMappingScreen
import dev.zanderp.opencfmoto.ui.ride.MappingDialog
import dev.zanderp.opencfmoto.ui.ride.MappingRow
import dev.zanderp.opencfmoto.ui.ride.MappingUi

/**
 * Remap what each handlebar gesture does, and keep the destinations a button can navigate to. Rows
 * are built from [ButtonGesture]'s entries, and everything is read live by [MediaButtonBridge] on
 * each press — no reconnect needed to try a change.
 */
class ButtonMappingActivity : AppCompatActivity() {

    private var ui by mutableStateOf(MappingUi("", "", emptyList(), "", false, "", false, false, false))
    private var dialog by mutableStateOf<MappingDialog?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OcmTheme {
                ButtonMappingScreen(
                    ui = ui,
                    dialog = dialog,
                    onBack = { finish() },
                    onBtSettings = { BluetoothHelper.openBluetoothSettings(this) },
                    onTeachHandlebar = { dialog = MappingDialog.TeachHandlebar },
                    onRow = ::pickAction,
                    onPlaces = {
                        dialog = MappingDialog.Places(
                            (0 until SavedPlaces.COUNT).map { SavedPlaces.name(this, it) },
                            (0 until SavedPlaces.COUNT).map { SavedPlaces.query(this, it) },
                        )
                    },
                    onRemote = ::setRemotePad,
                    onTeachRemote = ::teachRemote,
                    onPreset = { dialog = MappingDialog.PresetPick },
                    onClearPreset = { dialog = MappingDialog.ClearPreset },
                    onOverlay = ::openOverlaySettings,
                    onReset = { dialog = MappingDialog.Reset },
                    onDismiss = ::dismissDialog,
                    onPickAction = ::applyAction,
                    onSavePlaces = ::savePlaces,
                    onVolumePresence = ::setVolumePresence,
                    onRemoteGesture = ::waitForRemoteKey,
                    onPresetChosen = { dialog = MappingDialog.PresetConfirm(it) },
                    onPresetApply = ::applyPreset,
                    onClearConfirmed = ::clearPreset,
                    onResetConfirmed = ::resetAll,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (RemotePad.teachListener != null) RemotePad.teachListener = null
        super.onDestroy()
    }

    /** Closing any dialog also stops a pending remote-key capture (Back / outside tap / Cancel). */
    private fun dismissDialog() {
        if (dialog is MappingDialog.TeachRemoteWait) RemotePad.teachListener = null
        dialog = null
    }

    // ─────────────────────────── state ───────────────────────────

    private fun refresh() {
        val rows = ButtonGesture.entries.map { MappingRow(it, ButtonMap.get(this, it).displayLabel(this)) }
        val navMapped = ButtonGesture.entries.any { ButtonMap.get(this, it).isNav }
        val active = ButtonClusterPreset.active(this)
        val placeNames = (0 until SavedPlaces.COUNT).filter { SavedPlaces.isSet(this, it) }.map { slot ->
            SavedPlaces.name(this, slot).ifBlank { getString(R.string.ui_mapping_place_n, slot + 1) }
        }
        ui = MappingUi(
            btStatus = BluetoothHelper.status(this).shortLine(),
            presence = "Handlebar sources: ${ButtonPresencePrefs.summarize(this)}",
            rows = rows,
            placesSummary = if (placeNames.isEmpty()) getString(R.string.ui_mapping_places_none) else placeNames.joinToString(" · "),
            remoteOn = RemotePad.enabled(this),
            presetText = if (active != null) "Active: ${active.title}" else "No preset — shipped defaults / your custom map",
            presetActive = active != null,
            // The overlay grant only matters once a button can actually launch Maps.
            overlayNeeded = navMapped && !NavLauncher.canLaunchFromBackground(this),
            resetEnabled = !ButtonMap.isAllDefault(this),
        )
    }

    // ─────────────────────────── gestures ───────────────────────────

    private fun pickAction(gesture: ButtonGesture) {
        val actions = ButtonAction.entries
        dialog = MappingDialog.PickAction(
            gesture, actions.map { it.displayLabel(this) }, actions.indexOf(ButtonMap.get(this, gesture)),
        )
    }

    private fun applyAction(gesture: ButtonGesture, which: Int) {
        val action = ButtonAction.entries[which]
        ButtonMap.set(this, gesture, action)
        LogBus.log("→ button mapping: ${gesture.label} = ${action.label}")
        if (action.isNav && !SavedPlaces.isSet(this, action.navSlot)) {
            Toast.makeText(this, "Set that place's address in Saved places", Toast.LENGTH_SHORT).show()
        }
        dialog = null
        refresh()
    }

    private fun savePlaces(names: List<String>, queries: List<String>) {
        for (slot in 0 until SavedPlaces.COUNT) {
            SavedPlaces.set(this, slot, names.getOrNull(slot) ?: "", queries.getOrNull(slot) ?: "")
        }
        dialog = null
        refresh()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(NavLauncher.overlayPermissionIntent(this))
        } catch (e: Exception) {
            LogBus.log("couldn't open the overlay permission screen ($e)")
            Toast.makeText(this, "Open Settings → Apps → OpenCfMoto → Display over other apps", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────── remote pad ───────────────────────────

    private fun setRemotePad(on: Boolean) {
        RemotePad.setEnabled(this, on)
        LogBus.log("→ remote pad ${if (on) "on" else "off"}")
        refresh()
        Toast.makeText(
            this,
            if (on) "Remote pad on — HID keys map to handlebar gestures" else "Remote pad off",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun teachRemote() {
        if (!RemotePad.enabled(this)) {
            Toast.makeText(this, "Turn remote pad on first", Toast.LENGTH_SHORT).show()
            return
        }
        dialog = MappingDialog.TeachRemotePick
    }

    private fun waitForRemoteKey(gesture: ButtonGesture) {
        dialog = MappingDialog.TeachRemoteWait(gesture)
        RemotePad.teachListener = listen@{ ev ->
            if (ev.device?.isVirtual == true) return@listen false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                RemotePad.teach(this, ev.keyCode, gesture)
                RemotePad.teachListener = null
                dialog = null
                Toast.makeText(
                    this,
                    getString(R.string.buttons_teach_remote_ok, KeyEvent.keyCodeToString(ev.keyCode), gesture.label),
                    Toast.LENGTH_SHORT,
                ).show()
                LogBus.log("→ remote pad teach: ${KeyEvent.keyCodeToString(ev.keyCode)} → ${gesture.label}")
            }
            true
        }
    }

    // ─────────────────────────── handlebar teach ───────────────────────────

    /** 1 = volume rocker present, 0 = absent, -1 = reset presence (same effects as the old dialog). */
    private fun setVolumePresence(choice: Int) {
        when (choice) {
            1 -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.PRESENT)
                LogBus.log("→ teach handlebar: volume rocker PRESENT")
                Toast.makeText(this, "▲/▼ marked present — volume pin stays on", Toast.LENGTH_SHORT).show()
            }
            0 -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.ABSENT)
                LogBus.log("→ teach handlebar: volume rocker ABSENT")
                Toast.makeText(this, "▲/▼ absent — phone volume no longer pinned", Toast.LENGTH_SHORT).show()
            }
            else -> {
                ButtonPresencePrefs.setVolumeRocker(this, ButtonPresence.UNKNOWN)
                ButtonPresencePrefs.setTrackKeys(this, ButtonPresence.UNKNOWN)
                LogBus.log("→ teach handlebar: presence reset to UNKNOWN")
                Toast.makeText(this, "Presence reset — will auto-probe again", Toast.LENGTH_SHORT).show()
            }
        }
        MediaButtonBridge.instance?.refreshVolumePresencePolicy()
        dialog = null
        refresh()
    }

    // ─────────────────────────── presets / reset ───────────────────────────

    private fun applyPreset(preset: ButtonClusterPreset) {
        preset.apply(this)
        LogBus.log("→ button mapping preset: ${preset.title}")
        dialog = null
        refresh()
        // Cluster only picks the bar map + turns handlebar→AA on. Touch stays as-is
        // (800MT etc. keep touch + bars together — never force Disable touchscreen).
        Toast.makeText(this, "Applied: ${preset.title} (handlebar → AA on)", Toast.LENGTH_SHORT).show()
    }

    private fun clearPreset() {
        ButtonClusterPreset.clear(this)
        LogBus.log("→ button mapping preset cleared")
        dialog = null
        refresh()
        Toast.makeText(this, "Preset cleared", Toast.LENGTH_SHORT).show()
    }

    private fun resetAll() {
        ButtonMap.resetAll(this)
        LogBus.log("→ button mapping reset to defaults")
        dialog = null
        refresh()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }
}
```

Note: dialog state lives in a plain `mutableStateOf` field, so a rotation (Activity recreated) closes an open dialog; `onDestroy` clears a pending teach listener — no ghost capture (Review Focus 5). The Places dialog keeps typed text across recomposition via `rememberSaveable`.

- [ ] **Step 4: Build, suite, emulator**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green; `./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL.
Emulator: every card in light + dark; tap a gesture row → radio list, pick → row updates + log; map a gesture to "Navigate to saved place 1" with no place → toast; Saved places → edit + Save → summary + Controls chips update; remote pad On/Off + toast; Teach remote with pad off → toast; with pad on → pick → wait dialog → Back closes it (then pressing a hardware key does not teach); preset pick → confirm → Apply toast; Clear enabled only with a preset; Reset enabled only when changed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/ride/ButtonMappingScreen.kt app/src/main/java/dev/zanderp/opencfmoto/ButtonMappingActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(ride): Button mapping in Compose"
```

---

### Task 6: QR scan in Compose

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/QrScanScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/QrScanActivity.kt`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `data class QrUi(hint: String?, cameraDenied: Boolean)`; `@Composable QrScanScreen(ui: QrUi, preview: PreviewView, onClose: () -> Unit, onZoom: (Float) -> Unit, onPhoto: () -> Unit, onManual: () -> Unit)`.

- [ ] **Step 1: Strings**

Add after the Task 5 strings:

```xml
    <string name="ui_qr_title">Scan your dash</string>
    <string name="ui_qr_denied">Camera permission is off. You can still scan a photo of the QR or enter the Wi‑Fi name and password.</string>
```

- [ ] **Step 2: Screen**

`app/src/main/java/dev/zanderp/opencfmoto/ui/ride/QrScanScreen.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmTopBar
import dev.zanderp.opencfmoto.ui.design.OcmType

data class QrUi(val hint: String?, val cameraDenied: Boolean)

@Composable
fun QrScanScreen(
    ui: QrUi,
    preview: PreviewView,
    onClose: () -> Unit,
    onZoom: (Float) -> Unit,
    onPhoto: () -> Unit,
    onManual: () -> Unit,
) {
    val c = Ocm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        OcmTopBar(stringResource(R.string.ui_qr_title), onClose)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ui.hint ?: stringResource(R.string.qr_point_the_camera_at_the_bike_s_motoplay_qr_if_it), style = OcmType.body, color = c.text2)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                if (ui.cameraDenied) {
                    Text(
                        stringResource(R.string.ui_qr_denied), style = OcmType.body, color = androidx.compose.ui.graphics.Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    AndroidView(factory = { preview.apply { (parent as? ViewGroup)?.removeView(this) } }, modifier = Modifier.fillMaxSize())
                    // Viewfinder frame.
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).aspectRatio(1f).border(3.dp, c.accent, RoundedCornerShape(20.dp)))
                }
            }
            if (!ui.cameraDenied) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, ratio) in listOf(R.string.qr_1x to 1f, R.string.qr_2x to 2f, R.string.qr_3x to 3f)) {
                        OutlinedButton(onClick = { onZoom(ratio) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null)
                Text(stringResource(R.string.qr_from_photo), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, tint = c.accent)
                Text(stringResource(R.string.garage_enter_wi_fi_manually), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
```

- [ ] **Step 3: QrScanActivity in Compose**

Edit `QrScanActivity.kt`:

1. Imports: remove `android.widget.Button`, `android.widget.TextView`; add:

```kotlin
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.QrScanScreen
import dev.zanderp.opencfmoto.ui.ride.QrUi
```

2. Add fields after `private var camera: Camera? = null`:

```kotlin
    private lateinit var previewView: PreviewView
    private var ui by mutableStateOf(QrUi(hint = null, cameraDenied = false))
```

3. Replace the `cameraPermission` callback body (ruling 3 — stay open):

```kotlin
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            ui = ui.copy(cameraDenied = true)
        }
    }
```

4. Replace `onCreate` with:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previewView = PreviewView(this)
        setContent {
            OcmTheme {
                QrScanScreen(
                    ui = ui,
                    preview = previewView,
                    onClose = { finish() },
                    onZoom = ::setZoom,
                    onPhoto = { photoPicker.launch("image/*") },
                    onManual = {
                        ManualWifiPairing.show(this) { raw, _ ->
                            if (!handled.compareAndSet(false, true)) return@show
                            setResult(RESULT_OK, Intent().putExtra(RESULT_QR, raw))
                            finish()
                        }
                    },
                )
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
```

5. In `startCamera`, delete `val previewView = findViewById<PreviewView>(R.id.preview_view)` (the field is used), delete the `setupZoomButtons()` call, and replace `findViewById<TextView>(R.id.hint).text = "Camera bind failed: ${e.message}"` with:

```kotlin
                ui = ui.copy(hint = "Camera bind failed: ${e.message}")
```

6. Delete `setupZoomButtons()`. `setZoom`, `decodePhoto`, `analyze`, `onDestroy` stay unchanged.

- [ ] **Step 4: Build, suite, emulator**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green; `./gradlew :app:assembleDebug -Pabi=x86_64 $F` → BUILD SUCCESSFUL.
Emulator: Ride → Scan new bike → QR screen with the emulator's virtual camera preview inside the frame, zoom chips work (no crash); deny camera on a fresh install → toast + inline message, photo and manual buttons still work; manual entry → dialog → result returns to the app (Garage "Scan" path and Ride scan path both). Light and dark.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/ride/QrScanScreen.kt app/src/main/java/dev/zanderp/opencfmoto/QrScanActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(ride): QR scan in Compose"
```

---

### Task 7: Remove old layouts, docs, full verification

**Files:**
- Delete: `app/src/main/res/layout/activity_hud_view.xml`, `activity_controls.xml`, `activity_button_mapping.xml`, `activity_qr_scan.xml`, `row_button_mapping.xml`, `row_saved_place.xml`
- Delete (only if unused after the layouts go): `app/src/main/res/drawable/hint_pill.xml`
- Modify: `docs/` user-facing feature doc that lists screens (find with `grep -rln "Dash view\|Button mapping" docs/*.md README.md`)

- [ ] **Step 1: Check nothing else uses the layouts**

Run: `grep -rn "activity_hud_view\|activity_controls\|activity_button_mapping\|activity_qr_scan\|row_button_mapping\|row_saved_place" app/src/main --include=*.kt --include=*.xml`
Expected: no matches outside the six layout files themselves.

- [ ] **Step 2: Delete**

```bash
git rm app/src/main/res/layout/activity_hud_view.xml app/src/main/res/layout/activity_controls.xml app/src/main/res/layout/activity_button_mapping.xml app/src/main/res/layout/activity_qr_scan.xml app/src/main/res/layout/row_button_mapping.xml app/src/main/res/layout/row_saved_place.xml
grep -rn "hint_pill" app/src/main || git rm app/src/main/res/drawable/hint_pill.xml
```

- [ ] **Step 3: Docs**

In the doc found above, update the Ride section: the hero (model from Garage → Set model, photo, last ride / this month, Park here / Parked here) and note that Dash view, Controls, Button mapping and QR scan use the new design. Add bike checks: Dash view touch + pad on the dash, handlebar teach + mapping, QR pairing, Controls place chip → route on the dash.

- [ ] **Step 4: Full verification**

Run: `./gradlew :app:testDebugUnitTest $F` → suite green (only `YunmoFrameTest.parseOkDimension_xCape1200Payload`). Run: `./gradlew :app:assembleRelease $F` → BUILD SUCCESSFUL (R8 + resource shrink catch any dangling reference).

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/res docs README.md
git commit -m "chore(ride): drop the old Ride screen layouts; docs for Part 2"
```
