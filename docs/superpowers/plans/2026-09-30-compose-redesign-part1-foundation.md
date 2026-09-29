# Compose Redesign Part 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `MainActivity` into a Jetpack Compose host with a light/dark design system, 4 bottom tabs (Ride · Map · Trips · Me), a Compose Logs screen and Compose dialogs, with the connect flow moved (unchanged) into a tested `ConnectionController`.

**Architecture:** `ConnectionController` (plain class, one process-wide instance in `AppGraph`) owns the connect-flow orchestration copied from `MainActivity`; everything that touches Android statics goes through a `ConnectionEngine` interface (`RealConnectionEngine` holds the moved bodies) and screen-only needs go through `UiPort`. Compose screens read `ConnectionState.flow`, `LogBus.revision` and `controller.dialog`/`controller.bike` StateFlows. Existing View screens keep working and follow the same light/dark colour tokens.

**Tech Stack:** Kotlin (AGP 9.2.1 built-in Kotlin 2.2.10), Jetpack Compose BOM 2026.09.00 (Material 3 1.4.0), Navigation-Compose 2.10.2, Lifecycle 2.11.0, Activity-Compose 1.13.0, kotlinx-coroutines 1.11.0, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-compose-redesign-part1-foundation-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto`; new UI code under `dev.zanderp.opencfmoto.ui` (`ui/design`, `ui/shell`, `ui/ride`, `ui/tabs`, `ui/logs`, `ui/dialog`); controller code under `dev.zanderp.opencfmoto.connect`. New files start with the 3-line SPDX/AGPL header (copy from `BikeScope.kt`).
- `MainActivity` keeps its class name, manifest entry, `launchMode="singleTop"`, `configChanges`, `dispatchKeyEvent → RemotePad.consume`, and the constants `EXTRA_START_GPX = "start_gpx"`, `EXTRA_BT_TRIGGER = "bt_trigger"`, `EXTRA_GPX_TO_BIKE = "gpx_to_bike"`.
- Moved code keeps **the same log strings, order, guards and delays** (900 ms / 4 500 ms / 18 000 ms AA triggers, 1 200 ms auto-connect, 1 500 ms rival retry, 1 000 ms rival resume retry, 2 500 ms dependency check, 100 ms × 50 mirror FGS poll).
- Colour tokens (exact hex) — light / dark: `bg #F4F7FA / #0F1419`, `bg_top #E6EDF2 / #1C2430`, `surface #FFFFFF / #182029`, `surface_high #EEF2F5 / #212B36`, `accent #0B8C9B / #27C1D3`, `on_accent #FFFFFF / #06262B`, `text_primary #14191F / #EEF2F6`, `text_secondary #5B6773 / #9AA7B4`, `outline #DCE3E9 / #2A3440`, `status_live #1FA66A / #34D399`, `status_busy #C98A00 / #FBBF24`, `status_error #D93A3A / #F87171`, `status_idle #8A93A3 / #8A93A3`. `brand_orange #FF6A2C`, `brand_orange_dark #E85A1E`, `on_brand #FFFFFF` stay (both modes).
- Dash-rendered UI (`GpxDashUi`) must look exactly as today in both phone modes → uses fixed `dash_surface_high #1F242E` and `dash_text_primary #F2F5FA`.
- Tap targets ≥ 56 dp; primary Connect/Stop button 64 dp tall.
- Build/test from repo root in Git Bash with this environment (C: is full; Gradle home + temp on F:):
  `export JAVA_HOME="/d/Program Files/Java/jdk-21" GRADLE_USER_HOME="F:/opencfmoto-gradle/home" TMP="F:\\opencfmoto-gradle\\tmp" TEMP="F:\\opencfmoto-gradle\\tmp" GRADLE_OPTS="-Djava.io.tmpdir=F:/opencfmoto-gradle/tmp"; F="--console=plain --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process"` then `./gradlew <task> $F`.
- The known pre-existing failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` is unrelated; "suite green" means **only that test fails** (count must stay exactly 1).
- **Plan decisions (deviations from spec, recorded):**
  1. Dialogs implemented inside *other helper classes* (`WifiGate.ensureEnabledOrPrompt`, `PhoneHotspotAssist.showSetupDialog`, the hotspot "no interface" dialog, `DependencyPrompt`) stay View dialogs in Part 1 (shown on the attached activity, restyled by the theme); they're converted in Part 3. Spec §2.1 listed WifiGate's prompt as Compose — deferred.
  2. `runBleWakeUpThenProber` and the `bleWakeUp` field are dead code (never called) and are **not** moved.
  3. Ride tiles: Controls stays always enabled (it also hosts Navigate-to and Map theme); Dash view enabled only while Streaming/Mirroring; Mirror enabled when `!busy || live`; Scan enabled when `!busy` (matches today's rules).
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Activity recreated mid-connect** (launching Google AA destroys/recreates MainActivity): the 900/4500/18000 ms callbacks must use the *current* attached activity (or skip with a log when none), never a dead one. Pinned by `ConnectionControllerTest.selfModeTriggerUsesCurrentActivityOrSkips` (Task 5).
2. **Auto-connect scheduled, then activity destroyed before it fires**: latch must reset so the next instance retries. Pinned by `ConnectionControllerTest.autoConnectResetsLatchWhenNoScreenAtFireTime` (Task 5).
3. **Dialog raised while the app is in the background** (e.g. rival error while AA is in front): must still show when the rider returns. Pinned by `ConnectionControllerTest.dialogPersistsAcrossDetachAttach` (Task 5).
4. **Phone in light mode while the Map is on the dash**: dash widgets keep dark colours. Pinned by the Task 1 grep step + Task 8 emulator/bike check.
5. **Very long logs** (512 KB buffer) on the Logs screen: must stay responsive. Pinned by `LogBusTest.tailReturnsOnlyTheEnd` (Task 3) + the 300 ms debounce in Task 7.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts` | Modify | Compose plugin + deps; unit-test default values |
| `app/src/main/res/values/colors.xml` / `values-night/colors.xml` | Modify / Create | Light / dark tokens (+ fixed `dash_*`) |
| `app/src/main/res/values/themes.xml`, `values-night/themes.xml` | Modify / Delete | One DayNight theme using tokens |
| `app/src/main/java/dev/zanderp/opencfmoto/GpxDashUi.kt` | Modify | Fixed dash colours |
| `ui/design/OcmTheme.kt` | Create | Colour tokens, typography, `OcmTheme` |
| `ui/design/Components.kt` | Create | `PrimaryButton`, `StatusChip`, `ActionTile`, `OcmCard`, `SectionLabel`, `SettingRow`, `OcmScreen` |
| `ConnectionState.kt`, `LogBus.kt` | Modify | `flow`, `revision`, `tail()` |
| `ui/ride/RideUiState.kt` | Create | Pure phase → UI mapping |
| `connect/ConnectionEngine.kt` | Create | Engine interface |
| `connect/UiPort.kt` | Create | Screen port interface |
| `connect/AppDialog.kt` | Create | Dialog model |
| `connect/ConnectionController.kt` | Create | Orchestration (moved from MainActivity) |
| `connect/AppGraph.kt` | Create | Process-wide controller instance |
| `connect/RealConnectionEngine.kt` | Create | Moved Android-touching bodies |
| `OpenCfMotoApp.kt` | Modify | Install controller |
| `MainActivity.kt` | Rewrite | Compose host (lifecycle forwarding, launchers, port) |
| `ui/shell/AppShell.kt` | Create | Scaffold, bottom tabs, NavHost |
| `ui/ride/RideScreen.kt` | Create | Ride tab |
| `ui/tabs/MapTab.kt`, `TripsTab.kt`, `MeTab.kt` | Create | Other tabs (Part 1 versions) |
| `ui/logs/LogsScreen.kt` | Create | Logs |
| `ui/dialog/DialogHost.kt` | Create | Compose dialogs |
| `res/layout/activity_main.xml` | Delete | Replaced by Compose |
| `res/values/strings.xml` | Modify | `ui_*` strings |
| Tests: `ui/design/OcmColorsTest.kt`, `ConnectionStateFlowTest.kt`, `LogBusTest.kt`, `ui/ride/RideUiStateTest.kt`, `connect/ConnectionControllerTest.kt`, `connect/FakeConnectionEngine.kt`, `connect/FakeUiPort.kt` | Create | Unit tests (paths under `app/src/test/java/dev/zanderp/opencfmoto/`) |

---

### Task 1: Compose build + light/dark theme foundation

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`
- Modify: `app/src/main/res/values/colors.xml`; Create: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`; Delete: `app/src/main/res/values-night/themes.xml`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/GpxDashUi.kt:467-470`

**Interfaces:**
- Consumes: nothing.
- Produces: Compose compiles in `:app`; colour resources `bg, bg_top, surface, surface_high, accent, on_accent, text_primary, text_secondary, outline, status_live, status_busy, status_error, status_idle, dash_surface_high, dash_text_primary`; unit tests run with `isReturnDefaultValues = true`.

- [ ] **Step 1: Version catalog.** In `gradle/libs.versions.toml` add under `[versions]`:

```toml
kotlin = "2.2.10"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
navigationCompose = "2.10.2"
lifecycle = "2.11.0"
coroutines = "1.11.0"
```

under `[libraries]`:

```toml
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
```

under `[plugins]`:

```toml
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Root plugin.** `build.gradle.kts` becomes:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 3: App module.** In `app/build.gradle.kts`: add `alias(libs.plugins.kotlin.compose)` to `plugins { }`; change `buildFeatures { buildConfig = true }` to `buildFeatures { buildConfig = true; compose = true }`; add inside `android { }`:

```kotlin
    testOptions {
        // android.jar stubs return defaults instead of throwing, so plain JVM tests can touch
        // classes that call android.util.Log (LogBus) or construct an Activity for a fake port.
        unitTests.isReturnDefaultValues = true
    }
```

and append to `dependencies { }`:

```kotlin
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
```

- [ ] **Step 4: Build-only check (spike).** Run: `./gradlew :app:assembleDebug $F`
Expected: `BUILD SUCCESSFUL`. If the Compose plugin version is rejected ("Compose compiler plugin … requires Kotlin"), read the Kotlin version from the error / `F:/opencfmoto-gradle/home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin/` and set `kotlin = "<that>"`; ledger it.

- [ ] **Step 5: Light colours.** Replace `app/src/main/res/values/colors.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>

    <!-- Brand (app icon, legacy highlights) — same in light and dark. -->
    <color name="brand_orange">#FF6A2C</color>
    <color name="brand_orange_dark">#E85A1E</color>
    <color name="on_brand">#FFFFFF</color>

    <!-- Light tokens (dark values live in values-night/colors.xml). -->
    <color name="bg">#F4F7FA</color>
    <color name="bg_top">#E6EDF2</color>
    <color name="surface">#FFFFFF</color>
    <color name="surface_high">#EEF2F5</color>
    <color name="outline">#DCE3E9</color>
    <color name="accent">#0B8C9B</color>
    <color name="on_accent">#FFFFFF</color>
    <color name="text_primary">#14191F</color>
    <color name="text_secondary">#5B6773</color>
    <color name="status_live">#1FA66A</color>
    <color name="status_busy">#C98A00</color>
    <color name="status_error">#D93A3A</color>
    <color name="status_idle">#8A93A3</color>

    <!-- Drawn on the bike dash (GpxDashUi): fixed, never follows the phone's light/dark mode. -->
    <color name="dash_surface_high">#1F242E</color>
    <color name="dash_text_primary">#F2F5FA</color>
</resources>
```

- [ ] **Step 6: Dark colours.** Create `app/src/main/res/values-night/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="bg">#0F1419</color>
    <color name="bg_top">#1C2430</color>
    <color name="surface">#182029</color>
    <color name="surface_high">#212B36</color>
    <color name="outline">#2A3440</color>
    <color name="accent">#27C1D3</color>
    <color name="on_accent">#06262B</color>
    <color name="text_primary">#EEF2F6</color>
    <color name="text_secondary">#9AA7B4</color>
    <color name="status_live">#34D399</color>
    <color name="status_busy">#FBBF24</color>
    <color name="status_error">#F87171</color>
    <color name="status_idle">#8A93A3</color>
</resources>
```

- [ ] **Step 7: One DayNight theme.** Delete `app/src/main/res/values-night/themes.xml` (it re-declared `Base.Theme.OpenCfMoto` with no colours — the cause of the purple buttons in dark mode). In `app/src/main/res/values/themes.xml` replace the `Base.Theme.OpenCfMoto` style (lines 5–21) with:

```xml
    <!-- Follows the phone (DayNight). Colours come from values/ and values-night/ colors.xml. -->
    <style name="Base.Theme.OpenCfMoto" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/accent</item>
        <item name="colorOnPrimary">@color/on_accent</item>
        <item name="colorPrimaryContainer">@color/accent</item>
        <item name="colorOnPrimaryContainer">@color/on_accent</item>
        <item name="colorSecondaryContainer">@color/surface_high</item>
        <item name="colorOnSecondaryContainer">@color/text_primary</item>

        <item name="android:colorBackground">@color/bg</item>
        <item name="colorSurface">@color/surface</item>
        <item name="colorSurfaceVariant">@color/surface_high</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorOnSurfaceVariant">@color/text_secondary</item>
        <item name="colorOutline">@color/outline</item>

        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
```

Keep every other style in the file unchanged.

- [ ] **Step 8: Dash colours fixed.** In `GpxDashUi.kt` lines 469–470 change `R.color.surface_high` → `R.color.dash_surface_high` and `R.color.text_primary` → `R.color.dash_text_primary` (leave `brand_orange`/`on_brand`). Then verify nothing else drawn on the dash uses a mode-dependent colour:

Run: `grep -rn "R.color\.\|@color/" app/src/main/java/dev/zanderp/opencfmoto/GpxDashUi.kt app/src/main/java/dev/zanderp/opencfmoto/DashMapController.kt app/src/main/java/dev/zanderp/opencfmoto/MapLibreDashController.kt app/src/main/res/layout/presentation_gpx.xml app/src/main/res/layout-land/presentation_gpx.xml app/src/main/res/layout/include_gpx_dash_settings.xml`
Expected: only `brand_orange`, `on_brand`, `dash_surface_high`, `dash_text_primary`.

- [ ] **Step 9: Build + suite.** Run: `./gradlew :app:assembleDebug $F` then `./gradlew :app:testDebugUnitTest $F`
Expected: build succeeds; tests: only `YunmoFrameTest.parseOkDimension_xCape1200Payload` fails.

- [ ] **Step 10: Commit.**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/res/values/colors.xml app/src/main/res/values-night/colors.xml app/src/main/res/values/themes.xml app/src/main/java/dev/zanderp/opencfmoto/GpxDashUi.kt
git rm app/src/main/res/values-night/themes.xml
git commit -m "build: add Jetpack Compose; one DayNight theme with light/dark tokens

Fixes the purple default buttons in dark mode (values-night theme had no
colours). Dash-drawn UI uses fixed dash_* colours so it never turns light.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Design system (theme + components)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/design/OcmTheme.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/design/Components.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/ui/design/OcmColorsTest.kt`

**Interfaces:**
- Consumes: Task 1 dependencies.
- Produces:
  - `data class OcmColors(bg, bgTop, surface, surface2, accent, onAccent, text, text2, outline, live, busy, error, idle: Color)`; `val LightOcmColors`, `val DarkOcmColors`; `val LocalOcmColors`; `object Ocm { val colors: OcmColors @Composable get() }`.
  - `object OcmType { val heroModel; val screenTitle; val title; val body; val label; val stat: TextStyle }`
  - `@Composable fun OcmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  - `enum class StatusKind { IDLE, BUSY, LIVE, ERROR }` (in Components.kt)
  - `@Composable fun OcmScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit, danger: Boolean = false, modifier: Modifier = Modifier)`
  - `@Composable fun StatusChip(text: String, kind: StatusKind)`
  - `@Composable fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier)`
  - `@Composable fun OcmCard(onClick: (() -> Unit)? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun SectionLabel(text: String)`
  - `@Composable fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit)`

- [ ] **Step 1: Failing test.**

```kotlin
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class OcmColorsTest {
    @Test
    fun lightTokensMatchSpec() {
        assertEquals(Color(0xFFF4F7FA), LightOcmColors.bg)
        assertEquals(Color(0xFF0B8C9B), LightOcmColors.accent)
        assertEquals(Color(0xFFFFFFFF), LightOcmColors.onAccent)
        assertEquals(Color(0xFF14191F), LightOcmColors.text)
        assertEquals(Color(0xFFD93A3A), LightOcmColors.error)
    }

    @Test
    fun darkTokensMatchSpec() {
        assertEquals(Color(0xFF0F1419), DarkOcmColors.bg)
        assertEquals(Color(0xFF27C1D3), DarkOcmColors.accent)
        assertEquals(Color(0xFF06262B), DarkOcmColors.onAccent)
        assertEquals(Color(0xFFEEF2F6), DarkOcmColors.text)
        assertEquals(Color(0xFF34D399), DarkOcmColors.live)
    }
}
```

- [ ] **Step 2: Run to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.ui.design.OcmColorsTest" $F`
Expected: compile error `Unresolved reference 'LightOcmColors'`.

- [ ] **Step 3: `OcmTheme.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** OpenCfMoto colour tokens (spec §3.1). Same names as the XML colours used by the View screens. */
data class OcmColors(
    val bg: Color, val bgTop: Color, val surface: Color, val surface2: Color,
    val accent: Color, val onAccent: Color, val text: Color, val text2: Color, val outline: Color,
    val live: Color, val busy: Color, val error: Color, val idle: Color,
)

val LightOcmColors = OcmColors(
    bg = Color(0xFFF4F7FA), bgTop = Color(0xFFE6EDF2), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEEF2F5),
    accent = Color(0xFF0B8C9B), onAccent = Color(0xFFFFFFFF), text = Color(0xFF14191F), text2 = Color(0xFF5B6773),
    outline = Color(0xFFDCE3E9), live = Color(0xFF1FA66A), busy = Color(0xFFC98A00), error = Color(0xFFD93A3A),
    idle = Color(0xFF8A93A3),
)

val DarkOcmColors = OcmColors(
    bg = Color(0xFF0F1419), bgTop = Color(0xFF1C2430), surface = Color(0xFF182029), surface2 = Color(0xFF212B36),
    accent = Color(0xFF27C1D3), onAccent = Color(0xFF06262B), text = Color(0xFFEEF2F6), text2 = Color(0xFF9AA7B4),
    outline = Color(0xFF2A3440), live = Color(0xFF34D399), busy = Color(0xFFFBBF24), error = Color(0xFFF87171),
    idle = Color(0xFF8A93A3),
)

val LocalOcmColors = staticCompositionLocalOf { DarkOcmColors }

object Ocm {
    val colors: OcmColors
        @Composable get() = LocalOcmColors.current
}

/** Type scale (spec §3.2). */
object OcmType {
    val heroModel = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, lineHeight = 70.sp)
    val screenTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)
    val stat = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun OcmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkOcmColors else LightOcmColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            outline = c.outline, error = c.error, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            outline = c.outline, error = c.error, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        )
    }
    CompositionLocalProvider(LocalOcmColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
```

- [ ] **Step 4: `Components.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class StatusKind { IDLE, BUSY, LIVE, ERROR }

private val TileShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(20.dp)

/** Screen background: soft vertical gradient + scrolling column with the standard gutter. */
@Composable
fun OcmScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Ocm.colors
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgTop, 0.45f to c.bg, 1f to c.bg)),
    ) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = Ocm.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) c.error else c.accent,
            contentColor = if (danger) androidx.compose.ui.graphics.Color.White else c.onAccent,
        ),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(text, style = OcmType.title, maxLines = 1)
    }
}

@Composable
fun StatusChip(text: String, kind: StatusKind) {
    val c = Ocm.colors
    val color = when (kind) {
        StatusKind.IDLE -> c.idle
        StatusKind.BUSY -> c.busy
        StatusKind.LIVE -> c.live
        StatusKind.ERROR -> c.error
    }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = OcmType.label.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = color)
    }
}

@Composable
fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = Ocm.colors
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(TileShape)
            .background(c.surface)
            .border(1.dp, c.outline, TileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.heightIn(min = 6.dp))
        Text(label, style = OcmType.body, color = c.text, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun OcmCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ocm.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.outline, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = OcmType.label, color = Ocm.colors.text2, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
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
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text2)
    }
}
```

- [ ] **Step 5: Run the test.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.ui.design.OcmColorsTest" $F`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/design app/src/test/java/dev/zanderp/opencfmoto/ui/design
git commit -m "feat(ui): OpenCfMoto Compose design system (light/dark tokens, type, components)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: State streams (`ConnectionState.flow`, `LogBus.revision`, `LogBus.tail`)

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ConnectionState.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/LogBus.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/ConnectionStateFlowTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/LogBusTest.kt`

**Interfaces:**
- Produces: `ConnectionState.flow: StateFlow<Pair<Phase, String>>`; `LogBus.revision: StateFlow<Long>` (increments on every `log`/`logThrottled` line and on `clear`); `LogBus.tail(maxChars: Int): String`.

- [ ] **Step 1: Failing tests.**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateFlowTest {
    @Test
    fun flowFollowsSet() {
        ConnectionState.set(Phase.JOINING_WIFI, "CFMOTO4288")
        assertEquals(Phase.JOINING_WIFI to "CFMOTO4288", ConnectionState.flow.value)
        ConnectionState.set(Phase.STREAMING)          // keeps detail
        assertEquals(Phase.STREAMING to "CFMOTO4288", ConnectionState.flow.value)
        ConnectionState.set(Phase.IDLE, "")
    }
}
```

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBusTest {
    @Test
    fun revisionIncrementsPerLineAndOnClear() {
        val r0 = LogBus.revision.value
        LogBus.log("one")
        LogBus.log("two")
        assertEquals(r0 + 2, LogBus.revision.value)
        LogBus.clear()
        assertEquals(r0 + 3, LogBus.revision.value)
    }

    @Test
    fun tailReturnsOnlyTheEnd() {
        LogBus.clear()
        repeat(200) { LogBus.log("line-$it-" + "x".repeat(40)) }
        val t = LogBus.tail(500)
        assertTrue(t.length <= 500)
        assertTrue(t.trimEnd().endsWith("line-199-" + "x".repeat(40)))
    }
}
```

- [ ] **Step 2: Run to see them fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.ConnectionStateFlowTest" --tests "dev.zanderp.opencfmoto.LogBusTest" $F`
Expected: compile errors `Unresolved reference 'flow'` / `'revision'` / `'tail'`.

- [ ] **Step 3: `ConnectionState`.** Add imports `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.asStateFlow`. Directly after the `listener` property add:

```kotlin

    private val _flow = MutableStateFlow(Phase.IDLE to "")

    /** Compose screens observe this; [listener] stays for existing callers. */
    val flow: StateFlow<Pair<Phase, String>> = _flow.asStateFlow()
```

In `set(...)`, directly after `val d = detail` add `_flow.value = newPhase to d`.

- [ ] **Step 4: `LogBus`.** Add the same three flow imports. After the `listener` property add:

```kotlin

    private val _revision = MutableStateFlow(0L)

    /** Bumped on every appended line and on [clear] — the Logs screen re-reads [tail] on change. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Last [maxChars] characters of the log (the Logs screen shows only the end of a 512 KB buffer). */
    @Synchronized
    fun tail(maxChars: Int): String {
        val start = (sb.length - maxChars).coerceAtLeast(0)
        return sb.substring(start)
    }
```

In `log(...)`, directly after `sb.append(line).append('\n')` add `_revision.value = _revision.value + 1`. Do the same in `logThrottled(...)` right after its `sb.append(...)` line. In `clear()` change the body to `{ sb.setLength(0); _revision.value = _revision.value + 1 }`.

- [ ] **Step 5: Run the tests.** Same command as Step 2. Expected: PASS (3 tests).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ConnectionState.kt app/src/main/java/dev/zanderp/opencfmoto/LogBus.kt app/src/test/java/dev/zanderp/opencfmoto/ConnectionStateFlowTest.kt app/src/test/java/dev/zanderp/opencfmoto/LogBusTest.kt
git commit -m "feat: StateFlow views of connection state and log for Compose

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Ride UI state mapping

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideUiState.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideUiStateTest.kt`

**Interfaces:**
- Consumes: `Phase` (existing), `StatusKind` (Task 2).
- Produces:
  - `enum class PrimaryAction { CONNECT, SCAN, STOP }`
  - `data class RideUiState(val phase: Phase, val statusKind: StatusKind, val primary: PrimaryAction, val bikeName: String?, val detail: String, val scanEnabled: Boolean, val mirrorEnabled: Boolean, val dashViewEnabled: Boolean, val controlsEnabled: Boolean)`
  - `fun rideUiState(phase: Phase, detail: String, bikeName: String?, hasSavedBike: Boolean): RideUiState`

- [ ] **Step 1: Failing test.**

```kotlin
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.design.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideUiStateTest {
    @Test
    fun idleWithSavedBikeOffersConnect() {
        val s = rideUiState(Phase.IDLE, "", "CFMOTO4288", hasSavedBike = true)
        assertEquals(PrimaryAction.CONNECT, s.primary)
        assertEquals(StatusKind.IDLE, s.statusKind)
        assertTrue(s.scanEnabled); assertTrue(s.mirrorEnabled); assertTrue(s.controlsEnabled)
        assertFalse(s.dashViewEnabled)
    }

    @Test
    fun noSavedBikeOffersScan() {
        assertEquals(PrimaryAction.SCAN, rideUiState(Phase.IDLE, "", null, hasSavedBike = false).primary)
    }

    @Test
    fun busyPhasesOfferStopAndBlockScan() {
        for (p in Phase.values().filter { it.busy }) {
            val s = rideUiState(p, "", "Bike", hasSavedBike = true)
            assertEquals(p.name, PrimaryAction.STOP, s.primary)
            assertEquals(p.name, StatusKind.BUSY, s.statusKind)
            assertFalse(p.name, s.scanEnabled)
            assertFalse(p.name, s.mirrorEnabled)
        }
    }

    @Test
    fun liveOffersStopAndDashView() {
        for (p in listOf(Phase.STREAMING, Phase.MIRRORING)) {
            val s = rideUiState(p, "", "Bike", hasSavedBike = true)
            assertEquals(PrimaryAction.STOP, s.primary)
            assertEquals(StatusKind.LIVE, s.statusKind)
            assertTrue(s.dashViewEnabled)
            assertTrue(s.mirrorEnabled)      // switch dash content while live
        }
    }

    @Test
    fun errorAndStoppedOfferConnect() {
        assertEquals(StatusKind.ERROR, rideUiState(Phase.ERROR, "boom", "Bike", true).statusKind)
        assertEquals(PrimaryAction.CONNECT, rideUiState(Phase.ERROR, "boom", "Bike", true).primary)
        assertEquals(StatusKind.IDLE, rideUiState(Phase.STOPPED, "", "Bike", true).statusKind)
    }

    @Test
    fun detailPassesThrough() {
        assertEquals("Linking…", rideUiState(Phase.PXC_CONNECTING, "Linking…", "Bike", true).detail)
    }
}
```

- [ ] **Step 2: Run to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.ui.ride.RideUiStateTest" $F`
Expected: compile error `Unresolved reference 'rideUiState'`.

- [ ] **Step 3: Implement.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.ui.design.StatusKind

enum class PrimaryAction { CONNECT, SCAN, STOP }

/** Everything the Ride tab shows, derived purely from the connection phase (spec §2.3). */
data class RideUiState(
    val phase: Phase,
    val statusKind: StatusKind,
    val primary: PrimaryAction,
    val bikeName: String?,
    val detail: String,
    val scanEnabled: Boolean,
    val mirrorEnabled: Boolean,
    val dashViewEnabled: Boolean,
    val controlsEnabled: Boolean,
)

fun rideUiState(phase: Phase, detail: String, bikeName: String?, hasSavedBike: Boolean): RideUiState {
    val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
    val busy = phase.busy
    return RideUiState(
        phase = phase,
        statusKind = when {
            live -> StatusKind.LIVE
            phase == Phase.ERROR -> StatusKind.ERROR
            busy -> StatusKind.BUSY
            else -> StatusKind.IDLE
        },
        primary = when {
            busy || live -> PrimaryAction.STOP
            hasSavedBike -> PrimaryAction.CONNECT
            else -> PrimaryAction.SCAN
        },
        bikeName = bikeName,
        detail = detail,
        scanEnabled = !busy,
        mirrorEnabled = !busy || live,
        dashViewEnabled = live,
        controlsEnabled = true,
    )
}
```

- [ ] **Step 4: Run the test.** Same command. Expected: PASS (6 tests).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/ride/RideUiState.kt app/src/test/java/dev/zanderp/opencfmoto/ui/ride/RideUiStateTest.kt
git commit -m "feat(ui): pure Ride tab state mapping from connection phase

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: ConnectionController orchestration (+ interfaces, fakes, tests)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/connect/ConnectionEngine.kt`, `UiPort.kt`, `AppDialog.kt`, `ConnectionController.kt`, `AppGraph.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/connect/FakeConnectionEngine.kt`, `FakeUiPort.kt`, `ConnectionControllerTest.kt`

**Interfaces:**
- Consumes: `Phase`, `ConnectionState`, `QrData`, `UpdateChecker.Release`, `RivalClient.shouldRetryOnResume`, `R.string.*` (existing).
- Produces (used by Tasks 6–7):
  - `interface ConnectionEngine` — exactly the members in Step 3.
  - `interface UiPort { val activity: android.app.Activity; fun launchQrScan(); fun launchMirrorConsent(intent: android.content.Intent); fun moveTaskToBack() }`
  - `sealed interface AppDialog { data class Rival(val installed: Boolean); data object VpnKillSwitch; data class NearbyBt(val needNearby: Boolean); data object CrashRecovery; data class Update(val release: UpdateChecker.Release); data object MirrorReady; data class ReportProblem(val defaultModel: String) }`
  - `data class BikeInfo(val name: String?, val hasSaved: Boolean)`
  - `class ConnectionController(private val engine: ConnectionEngine)` with `val dialog: StateFlow<AppDialog?>`, `val bike: StateFlow<BikeInfo>` and the public methods listed in Step 4.
  - `object AppGraph { lateinit var connection: ConnectionController }`

- [ ] **Step 1: Fakes (test sources).** `FakeUiPort.kt`:

```kotlin
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent

/** Screen stand-in: android.jar stubs return defaults (isReturnDefaultValues), so Activity() is safe. */
class FakeUiPort(override val activity: Activity = Activity()) : UiPort {
    val calls = mutableListOf<String>()
    override fun launchQrScan() { calls += "launchQrScan" }
    override fun launchMirrorConsent(intent: Intent) { calls += "launchMirrorConsent" }
    override fun moveTaskToBack() { calls += "moveTaskToBack" }
}
```

`FakeConnectionEngine.kt`:

```kotlin
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.UpdateChecker

/** Records every engine call; delayed blocks are queued and run by [runDelayed]. */
class FakeConnectionEngine : ConnectionEngine {
    val calls = mutableListOf<String>()
    val delayed = mutableListOf<Pair<Long, () -> Unit>>()
    val activitiesSeenBySelfMode = mutableListOf<Activity?>()

    override var phase: Phase = Phase.IDLE
    var saved: QrData? = QrData.parse(SAMPLE_QR)
    var name: String? = "CFMOTO4288"
    var aaRunning = false
    var parked = false
    var sessionActive = false
    var sessionSeen = false
    var autoConnect = true
    var installed = true
    var wifiOn = true
    var inRange: Boolean? = true
    var hur = true
    var needNearby = false
    var vpn = false
    var setupDone = true
    var now = 1_000_000L
    var gpx = false
    var aaPipeline = false
    var dashRemote = false
    var lastFrameMs = Long.MAX_VALUE
    var crash = false
    var depsBlock = false
    var rivalInstalledFlag = true
    var openRivalOk = true
    var mirrorIntent: Intent? = Intent()
    var updateResult: UpdateChecker.Release? = null

    override fun savedQr() = saved
    override fun bikeName() = name
    override fun hasSavedBike() = saved != null
    override fun aaServiceRunning() = aaRunning
    override fun aaParked() = parked
    override fun aaSessionActive() = sessionActive
    override fun aaSessionSeen() = sessionSeen
    override fun autoConnectEnabled() = autoConnect
    override fun aaInstalled() = installed
    override fun wifiEnabled() = wifiOn
    override fun ssidInRange(ssid: String) = inRange
    override fun canAttemptHur() = hur
    override fun needsNearbyPermission() = needNearby
    override fun vpnActive() = vpn
    override fun setupSeen() = setupDone
    override fun elapsedRealtime() = now
    override fun gpxActive() = gpx
    override fun gpxDescription() = "FREE ''"
    override fun aaPipelineLive() = aaPipeline
    override fun dashRemoteAvailable() = dashRemote
    override fun msSinceLastFrame() = lastFrameMs
    override fun crashPending() = crash
    override fun string(resId: Int, vararg args: Any) = "str$resId" + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")

    override fun setPhase(phase: Phase, detail: String?) { this.phase = phase; calls += "setPhase:$phase" }
    override fun log(msg: String) { calls += "log:$msg" }
    override fun toast(text: String, long: Boolean) { calls += "toast:$text" }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { delayed += delayMs to block; calls += "postDelayed:$delayMs" }
    override fun reportError(detail: String) { calls += "reportError" }
    override fun persistSession() { calls += "persistSession" }
    override fun applySettingsToHolder() { calls += "applySettingsToHolder" }
    override fun ensureProber() { calls += "ensureProber" }
    override fun requestNotificationPermission(activity: Activity?) { calls += "requestNotificationPermission" }
    override fun dependencyLaunchCheck(activity: Activity) { calls += "dependencyLaunchCheck" }
    override fun clearWifiOffNoticeIfEnabled() { calls += "clearWifiOffNotice" }
    override fun ensureWifiOrPrompt(activity: Activity?): Boolean { calls += "ensureWifi"; return wifiOn }
    override fun dependencyBlocks(activity: Activity, forScan: Boolean): Boolean { calls += "deps:$forScan"; return depsBlock }
    override fun ensureLocationPermission(activity: Activity?) { calls += "ensureLocation" }
    override fun requestNearbyPermission(activity: Activity?) { calls += "requestNearby" }
    override fun armCommute(arm: Boolean) { calls += "armCommute:$arm" }
    override fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) { calls += "tearDown:$clearMap,$clearMirror" }
    override fun applyProfile(qr: QrData) { calls += "applyProfile" }
    override fun startAaHandoff() { calls += "startAaHandoff" }
    override fun triggerSelfMode(activity: Activity?) { activitiesSeenBySelfMode += activity; calls += "triggerSelfMode" }
    override fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) { calls += "joinWifi:$gateOnAaSteady" }
    override fun stopEverything() { calls += "stopEverything" }
    override fun clearProjection() { calls += "clearProjection" }
    override fun clearGpxSession() { calls += "clearGpxSession" }
    override fun abandonStaleNavigation(reason: String) { calls += "abandonNav:$reason" }
    override fun notifyForegroundResuming() { calls += "notifyForegroundResuming" }
    override fun saveBike(raw: String, qr: QrData) { calls += "saveBike" }
    override fun applyMirrorOrientation(activity: Activity?) { calls += "mirrorOrientation" }
    override fun clearMirrorOrientation() { calls += "clearMirrorOrientation" }
    override fun createMirrorConsentIntent(): Intent? { calls += "createMirrorIntent"; return mirrorIntent }
    override fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit) { calls += "armMirror"; onReady() }
    override fun closeRival(): Boolean { calls += "closeRival"; return true }
    override fun openRivalAppInfo(): Boolean { calls += "openRivalAppInfo"; return openRivalOk }
    override fun rivalInstalled() = rivalInstalledFlag
    override fun dashRemoteSubmit(query: String): Boolean { calls += "dashSubmit:$query"; return dashRemote }
    override fun sendDestinationToAndroidAuto(query: String) { calls += "sendToAa:$query" }
    override fun openMapSearch(activity: Activity?, query: String) { calls += "openMapSearch:$query" }
    override fun openMapHub(activity: Activity?) { calls += "openMapHub" }
    override fun openGpxPreview(activity: Activity?) { calls += "openGpxPreview" }
    override fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit) { calls += "checkUpdate:$manual"; onResult(updateResult) }
    override fun skipUpdate(version: String) { calls += "skipUpdate" }
    override fun openUrl(url: String): Boolean { calls += "openUrl"; return true }
    override fun openSettingsAction(action: String, forThisApp: Boolean): Boolean { calls += "openSettings:$action"; return true }
    override fun shareLog(activity: Activity?) { calls += "shareLog" }
    override fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String) { calls += "shareReport" }
    override fun clearCrash() { calls += "clearCrash" }
    override fun startSetup(activity: Activity?) { calls += "startSetup" }
    override fun hostDestroyedTeardown() { calls += "hostTeardown" }

    /** Run queued delayed blocks (in order); blocks may queue more. */
    fun runDelayed() {
        while (delayed.isNotEmpty()) delayed.removeAt(0).second.invoke()
    }

    companion object {
        const val SAMPLE_QR = "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37426&sn=Vasr&action=1" +
            "&ssid=CFMOTO4288&pwd=e278450c&uid=abc"
    }
}
```

- [ ] **Step 2: Failing controller tests.** `ConnectionControllerTest.kt`:

```kotlin
package dev.zanderp.opencfmoto.connect

import dev.zanderp.opencfmoto.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionControllerTest {
    private lateinit var e: FakeConnectionEngine
    private lateinit var port: FakeUiPort
    private lateinit var c: ConnectionController

    @Before
    fun setUp() {
        e = FakeConnectionEngine()
        port = FakeUiPort()
        c = ConnectionController(e)
        c.attach(port)
    }

    private fun idx(call: String) = e.calls.indexOfFirst { it == call || it.startsWith(call) }

    @Test
    fun connectWithSavedBikeRunsFlowInOrder() {
        c.onConnectPressed()
        val order = listOf("deps:false", "clearProjection", "clearGpxSession", "ensureLocation", "ensureWifi",
            "armCommute:true", "tearDown:true,true", "applyProfile", "setPhase:STARTING_AA", "startAaHandoff",
            "postDelayed:900", "postDelayed:4500", "postDelayed:18000", "joinWifi:true")
        val positions = order.map { idx(it) }
        assertTrue("missing: ${order.filterIndexed { i, _ -> positions[i] < 0 }}", positions.none { it < 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun connectWhileBusyOrLiveStops() {
        for (p in listOf(Phase.JOINING_WIFI, Phase.STREAMING, Phase.MIRRORING)) {
            e.calls.clear(); e.phase = p
            c.onConnectPressed()
            assertTrue(e.calls.contains("stopEverything"))
            assertFalse(e.calls.contains("startAaHandoff"))
        }
    }

    @Test
    fun connectWithoutSavedBikeScans() {
        e.saved = null
        c.onConnectPressed()
        assertTrue(port.calls.contains("launchQrScan"))
    }

    @Test
    fun wifiOffDoesNotStartAa() {
        e.wifiOn = false
        c.onConnectPressed()
        assertFalse(e.calls.contains("startAaHandoff"))
        assertFalse(e.calls.any { it.startsWith("joinWifi") })
    }

    @Test
    fun nearbyMissingShowsDialogAndStops() {
        e.hur = false; e.needNearby = true
        c.onConnectPressed()
        assertTrue(e.calls.contains("requestNearby"))
        assertEquals(Phase.ERROR, e.phase)
        assertEquals(AppDialog.NearbyBt(needNearby = true), c.dialog.value)
        assertFalse(e.calls.contains("startAaHandoff"))
    }

    @Test
    fun parkedResumeDoesNotArmCommute() {
        c.onHostResumed(resumeExtra = true)
        assertTrue(e.calls.contains("armCommute:false"))
        assertFalse(e.calls.contains("armCommute:true"))
    }

    @Test
    fun autoConnectFiresOnce() {
        c.onHostCreated(startGpx = false, resume = false, btTrigger = false)
        e.runDelayed()
        c.onHostResumed(resumeExtra = false)
        e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun autoConnectRetriesWhenBikeAppears() {
        e.inRange = false
        c.onHostCreated(false, false, false); e.runDelayed()
        assertEquals(0, e.calls.count { it == "startAaHandoff" })
        e.inRange = true
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun autoConnectResetsLatchWhenNoScreenAtFireTime() {
        c.onHostCreated(false, false, false)
        c.detach(port)                       // activity recreated before the 1 200 ms start fired
        e.runDelayed()
        assertEquals(0, e.calls.count { it == "startAaHandoff" })
        c.attach(port)
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
    }

    @Test
    fun selfModeTriggerUsesCurrentActivityOrSkips() {
        c.onConnectPressed()
        val newPort = FakeUiPort()
        c.detach(port); c.attach(newPort)    // Google AA recreated MainActivity
        e.runDelayed()
        assertTrue(e.activitiesSeenBySelfMode.isNotEmpty())
        assertTrue(e.activitiesSeenBySelfMode.all { it === newPort.activity })
    }

    @Test
    fun rivalErrorShowsDialogOnce() {
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
        c.dismissDialog()
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertNull(c.dialog.value)            // same error: not again
        c.onPhaseChanged(Phase.IDLE, "")
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
    }

    @Test
    fun rivalAppSettingsThenResumeRetriesOnce() {
        e.autoConnect = false                // isolate the rival retry from auto-connect
        c.onRivalAppSettings()
        e.now += 20_000
        c.onHostResumed(false); e.runDelayed()
        c.onHostResumed(false); e.runDelayed()
        assertEquals(1, e.calls.count { it == "startAaHandoff" })
        assertEquals(1, e.calls.count { it == "log:→ back from CFMoto app settings — retrying" })
    }

    @Test
    fun vpnDialogOnlyWithActiveVpn() {
        c.onPhaseChanged(Phase.ERROR, "VPN kill-switch blocked")
        assertNull(c.dialog.value)
        e.vpn = true
        c.onPhaseChanged(Phase.IDLE, "")
        c.onPhaseChanged(Phase.ERROR, "VPN kill-switch blocked")
        assertSame(AppDialog.VpnKillSwitch, c.dialog.value)
    }

    @Test
    fun dialogPersistsAcrossDetachAttach() {
        c.detach(port)
        c.onPhaseChanged(Phase.ERROR, "Close the official CFMoto app")
        c.attach(FakeUiPort())
        assertEquals(AppDialog.Rival(installed = true), c.dialog.value)
    }

    @Test
    fun navigateToTypedRoutes() {
        assertFalse(c.navigateToTyped("  "))
        e.aaPipeline = true
        assertTrue(c.navigateToTyped("Colombo"))
        assertTrue(e.calls.contains("sendToAa:Colombo"))
        e.aaPipeline = false
        assertFalse(c.navigateToTyped("Kandy"))
        assertTrue(e.calls.contains("openMapSearch:Kandy"))
    }

    @Test
    fun mirrorConsentShowsReadyDialog() {
        c.onMirrorPressed()
        assertTrue(port.calls.contains("launchMirrorConsent"))
        c.onMirrorConsent(ok = true, resultCode = -1, data = android.content.Intent())
        assertSame(AppDialog.MirrorReady, c.dialog.value)
    }

    @Test
    fun crashPendingShowsRecoveryDialogOnCreate() {
        e.crash = true
        c.onHostCreated(false, false, false)
        assertSame(AppDialog.CrashRecovery, c.dialog.value)
    }
}
```

- [ ] **Step 3: Interfaces + model (main sources).** `connect/UiPort.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent

/** What [ConnectionController] needs from the visible screen. Attached in onCreate, detached in onDestroy. */
interface UiPort {
    val activity: Activity
    fun launchQrScan()
    fun launchMirrorConsent(intent: Intent)
    fun moveTaskToBack()
}
```

`connect/AppDialog.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import dev.zanderp.opencfmoto.UpdateChecker

/** One pending dialog, rendered by the Compose DialogHost. Held as state so it survives recreation. */
sealed interface AppDialog {
    data class Rival(val installed: Boolean) : AppDialog
    data object VpnKillSwitch : AppDialog
    data class NearbyBt(val needNearby: Boolean) : AppDialog
    data object CrashRecovery : AppDialog
    data class Update(val release: UpdateChecker.Release) : AppDialog
    data object MirrorReady : AppDialog
    data class ReportProblem(val defaultModel: String) : AppDialog
}

data class BikeInfo(val name: String?, val hasSaved: Boolean)
```

`connect/ConnectionEngine.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.app.Activity
import android.content.Intent
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.UpdateChecker

/**
 * Everything the connect flow does to Android / the existing engine (Wi-Fi, AA service, prober…).
 * [RealConnectionEngine] holds the bodies moved from the old MainActivity; tests use a fake.
 */
interface ConnectionEngine {
    // --- reads ---
    val phase: Phase
    fun savedQr(): QrData?
    fun bikeName(): String?
    fun hasSavedBike(): Boolean
    fun aaServiceRunning(): Boolean
    fun aaParked(): Boolean
    fun aaSessionActive(): Boolean
    fun aaSessionSeen(): Boolean
    fun autoConnectEnabled(): Boolean
    fun aaInstalled(): Boolean
    fun wifiEnabled(): Boolean
    fun ssidInRange(ssid: String): Boolean?
    fun canAttemptHur(): Boolean
    fun needsNearbyPermission(): Boolean
    fun vpnActive(): Boolean
    fun setupSeen(): Boolean
    fun elapsedRealtime(): Long
    fun gpxActive(): Boolean
    fun gpxDescription(): String
    fun aaPipelineLive(): Boolean
    fun dashRemoteAvailable(): Boolean
    fun msSinceLastFrame(): Long
    fun crashPending(): Boolean
    fun string(resId: Int, vararg args: Any): String

    // --- plumbing ---
    fun setPhase(phase: Phase, detail: String? = null)
    fun log(msg: String)
    fun toast(text: String, long: Boolean = false)
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun reportError(detail: String)
    fun persistSession()

    // --- startup ---
    fun applySettingsToHolder()
    fun ensureProber()
    fun requestNotificationPermission(activity: Activity?)
    fun dependencyLaunchCheck(activity: Activity)
    fun clearWifiOffNoticeIfEnabled()

    // --- connect flow ---
    fun ensureWifiOrPrompt(activity: Activity?): Boolean
    fun dependencyBlocks(activity: Activity, forScan: Boolean): Boolean
    fun ensureLocationPermission(activity: Activity?)
    fun requestNearbyPermission(activity: Activity?)
    fun armCommute(arm: Boolean)
    fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean)
    fun applyProfile(qr: QrData)
    fun startAaHandoff()
    fun triggerSelfMode(activity: Activity?)
    fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?)
    fun stopEverything()
    fun clearProjection()
    fun clearGpxSession()
    fun abandonStaleNavigation(reason: String)
    fun notifyForegroundResuming()
    fun saveBike(raw: String, qr: QrData)

    // --- mirror / map ---
    fun applyMirrorOrientation(activity: Activity?)
    fun clearMirrorOrientation()
    fun createMirrorConsentIntent(): Intent?
    fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit)
    fun openMapHub(activity: Activity?)
    fun openGpxPreview(activity: Activity?)
    fun openMapSearch(activity: Activity?, query: String)
    fun dashRemoteSubmit(query: String): Boolean
    fun sendDestinationToAndroidAuto(query: String)

    // --- dialogs' actions ---
    fun closeRival(): Boolean
    fun openRivalAppInfo(): Boolean
    fun rivalInstalled(): Boolean
    fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit)
    fun skipUpdate(version: String)
    fun openUrl(url: String): Boolean
    fun openSettingsAction(action: String, forThisApp: Boolean = false): Boolean
    fun shareLog(activity: Activity?)
    fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String)
    fun clearCrash()
    fun startSetup(activity: Activity?)
    fun hostDestroyedTeardown()
}
```

`connect/AppGraph.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

/** Process-wide instances, created in OpenCfMotoApp.onCreate. */
object AppGraph {
    lateinit var connection: ConnectionController
}
```

- [ ] **Step 4: `connect/ConnectionController.kt`.** Orchestration moved from `MainActivity` (same logs, order and guards):

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.content.Intent
import android.provider.Settings
import dev.zanderp.opencfmoto.AboutActivity
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.RivalClient
import dev.zanderp.opencfmoto.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connect flow formerly inside MainActivity: Connect/Stop, scan, auto-connect, parked resume,
 * Bluetooth trigger, mirror, map hand-off, rival/VPN prompts. Lives for the whole process so the
 * flow survives MainActivity being recreated (Google AA foregrounding does that mid-connect).
 */
class ConnectionController(private val engine: ConnectionEngine) {

    private var port: UiPort? = null
    private val _dialog = MutableStateFlow<AppDialog?>(null)
    val dialog: StateFlow<AppDialog?> = _dialog.asStateFlow()
    private val _bike = MutableStateFlow(BikeInfo(null, false))
    val bike: StateFlow<BikeInfo> = _bike.asStateFlow()

    private var pendingAaStart = false
    private var rivalPromptShown = false
    private var rivalSettingsSentAt: Long? = null
    private var vpnPromptShown = false
    @Volatile private var autoConnectStarted = false
    @Volatile private var autoConnectSkipLogged = false

    private fun log(msg: String) = engine.log(msg)

    // ---------------------------------------------------------------- screen lifecycle

    fun attach(port: UiPort) { this.port = port; refreshBike() }

    fun detach(port: UiPort) { if (this.port === port) this.port = null }

    fun refreshBike() { _bike.value = BikeInfo(engine.bikeName(), engine.hasSavedBike()) }

    fun dismissDialog() { _dialog.value = null }

    /** MainActivity.onCreate (after setContent + attach). */
    fun onHostCreated(startGpx: Boolean, resume: Boolean, btTrigger: Boolean, gpxToBike: Boolean = false) {
        engine.applySettingsToHolder()
        if (engine.crashPending()) _dialog.value = AppDialog.CrashRecovery
        // Soft dep check after auto-connect has had time to start — never race Connect.
        engine.postDelayed(2500) { port?.activity?.let { engine.dependencyLaunchCheck(it) } }
        // Opening the home screen must not resume a killed NAV_TO (looks like "Arrived at X").
        val projecting = engine.phase == Phase.STREAMING || engine.phase == Phase.MIRRORING
        if (!startGpx && !projecting) engine.abandonStaleNavigation("app open")
        if (startGpx) engine.postDelayed(0) { beginGpxProjection(gpxToBike) }
        engine.ensureProber()
        engine.requestNotificationPermission(port?.activity)
        log("Ready. Tap Connect to project Android Auto to your dash.")
        try {
            if (!engine.setupSeen()) engine.startSetup(port?.activity) else maybeAutoConnect()
            maybeResumeFromParked(resume)
            maybeStartFromBtTrigger(btTrigger)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            engine.persistSession()
        }
    }

    /** MainActivity.onResume. */
    fun onHostResumed(resumeExtra: Boolean) {
        refreshBike()
        engine.clearWifiOffNoticeIfEnabled()
        // Retry auto-connect on resume: after finishing first-run setup, or once the bike's Wi-Fi
        // comes into range shortly after launch. Guarded so it only ever starts one attempt.
        if (engine.setupSeen()) maybeAutoConnect()
        maybeRetryAfterRivalStop()
        maybeCheckUpdate()
        maybeResumeFromParked(resumeExtra)
    }

    /** MainActivity.onNewIntent. */
    fun onNewIntent(resume: Boolean, btTrigger: Boolean, startGpx: Boolean, gpxToBike: Boolean) {
        maybeResumeFromParked(resume)
        maybeStartFromBtTrigger(btTrigger)
        if (startGpx) beginGpxProjection(gpxToBike)
    }

    /** MainActivity.onDestroy (after detach). */
    fun onHostDestroyed() {
        // The AA→bike chain must OUTLIVE the activity while the AA service runs (recreation
        // mid-hand-off left a black dash). Only tear down for the mirror path / genuine exit.
        if (!engine.aaServiceRunning()) engine.hostDestroyedTeardown()
    }

    // ---------------------------------------------------------------- connect

    /** Connect while idle; Stop while busy / projecting. */
    fun onConnectPressed() {
        val phase = engine.phase
        if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
            stop()
            return
        }
        val act = port?.activity ?: return
        if (engine.dependencyBlocks(act, forScan = false)) {
            log("→ Connect blocked — missing dependencies (see dialog)")
            return
        }
        val saved = engine.savedQr()
        if (saved != null) {
            log("→ Connect: reusing saved bike '${engine.bikeName()}' (no scan needed)")
            engine.clearProjection()
            engine.clearGpxSession()
            engine.ensureLocationPermission(act)
            startAaFlow(saved)
        } else {
            log("→ Connect: no saved bike — scan the dash QR.")
            startAaScan()
        }
    }

    fun stop() = engine.stopEverything()

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    fun startAaScan() {
        val act = port?.activity ?: return
        if (engine.dependencyBlocks(act, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        pendingAaStart = true
        engine.clearProjection()
        engine.ensureLocationPermission(act)
        try {
            port?.launchQrScan()
        } catch (e: Exception) {
            log("scan launch failed ($e)")
            pendingAaStart = false
        }
    }

    /** QR scan result; [raw] null = cancelled. */
    fun onQrScanned(raw: String?) {
        if (raw == null) {
            log("QR scan cancelled")
            return
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            val hint = QrData.parseFailureHint(raw)
            log("QR parse FAILED — missing ssid/pwd?" + (hint?.let { " ($it)" } ?: ""))
            engine.toast(hint ?: engine.string(R.string.main_invalid_qr), long = true)
            return
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}, hotspot=${qr.supportsPhoneHotspot}) " +
                "modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        engine.saveBike(raw, qr)
        refreshBike()
        if (pendingAaStart) {
            pendingAaStart = false
            startAaFlow(qr)
        } else {
            // Mirror path (screen projection already armed): connect straight away.
            engine.applyProfile(qr)
            engine.applyMirrorOrientation(port?.activity)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: qr.ssid)
            engine.joinWifi(qr, gateOnAaSteady = false, activity = port?.activity)
            // Same as startMirrorLink: single-app capture needs the shared app visible.
            port?.moveTaskToBack()
            engine.toast(engine.string(R.string.main_mirror_leave_shared_app), long = true)
        }
    }

    /**
     * Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect and a
     * fresh scan. @param armCommute false for a parked-resume: only a real Connect may start commute nav.
     */
    fun startAaFlow(qr: QrData, armCommute: Boolean = true) {
        try {
            val act = port?.activity
            if (!engine.ensureWifiOrPrompt(act)) return
            // AA Connect needs Nearby + Bluetooth so HUR (START_WIRELESS_PROJECTION) can run when
            // WirelessStartupActivity is a no-op (common on AA 16.4+/17.4+). Mirror/Map unchanged.
            if (!ensureNearbyBtForAaConnect()) return
            engine.armCommute(armCommute)
            // Map / Mirror / stale PXC must die first — half-switches leave framesSent=0.
            engine.tearDownForModeSwitch(clearMap = true, clearMirror = true)
            engine.applyProfile(qr)
            val bikeName = engine.bikeName() ?: qr.ssid
            engine.setPhase(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
            // Parallel startup: AA reaching steady video and the bike Wi-Fi join overlap; BikeLink gates
            // the bike probe until BOTH complete (see RealConnectionEngine.startAaHandoff).
            engine.startAaHandoff()
            // Trigger Google AA from the FOREGROUND activity (BAL-safe) after the :5288 server binds.
            // Uses whichever activity is attached *when it fires* — AA can recreate MainActivity.
            engine.postDelayed(900) {
                try {
                    engine.triggerSelfMode(port?.activity)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if a session
            // is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            engine.postDelayed(4_500) {
                if (aaAlreadyLiveOrTerminal()) {
                    if (engine.aaSessionActive()) log("[AA] skip re-trigger — AA already connected/decoding")
                    return@postDelayed
                }
                try {
                    log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    engine.triggerSelfMode(port?.activity)
                } catch (e: Exception) {
                    log("AA self-mode re-trigger failed: $e")
                }
            }
            // Give up the silent black/QR reconnect loop — surface a clear error after several tries.
            engine.postDelayed(18_000) {
                val p = engine.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (engine.aaSessionActive()) return@postDelayed
                log("[AA] Android Auto never attached — stopping silent retry")
                val detail = if (engine.aaSessionSeen()) {
                    engine.string(R.string.conn_detail_aa_dropped)
                } else {
                    engine.string(R.string.conn_detail_aa_not_started)
                }
                engine.setPhase(Phase.ERROR, detail)
            }
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            engine.joinWifi(qr, gateOnAaSteady = true, activity = act)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            engine.persistSession()
            engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_connect_failed))
        }
    }

    private fun aaAlreadyLiveOrTerminal(): Boolean {
        val p = engine.phase
        return engine.aaSessionActive() ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
            p == Phase.MIRRORING
    }

    /** Block AA Connect until Nearby (API 31+) is granted and Bluetooth is on. */
    private fun ensureNearbyBtForAaConnect(): Boolean {
        if (engine.canAttemptHur()) return true
        val needNearby = engine.needsNearbyPermission()
        if (needNearby) {
            engine.requestNearbyPermission(port?.activity)
            log("→ Nearby/Bluetooth required for AA Connect — waiting for grant + BT on")
        } else {
            log("→ Bluetooth is off — turn it on, then tap Connect again")
        }
        engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_need_bluetooth))
        _dialog.value = AppDialog.NearbyBt(needNearby)
        return false
    }

    /** BAL-safe resume after the service parked AA (notification tap or plain open while parked). */
    private fun maybeResumeFromParked(explicit: Boolean) {
        if (!explicit && !engine.aaParked() && engine.phase != Phase.WAITING_FOR_BIKE) return
        val saved = engine.savedQr() ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && engine.ssidInRange(saved.ssid) == false) return
        log("→ Resuming projection to '${engine.bikeName()}' from the foreground")
        autoConnectStarted = true
        engine.notifyForegroundResuming()
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved, armCommute = false)
    }

    /** Helmet remote / watch ACL → same one-tap Connect as a saved-QR tap. */
    private fun maybeStartFromBtTrigger(trigger: Boolean) {
        if (!trigger) return
        if (engine.aaServiceRunning()) return
        val p = engine.phase
        if (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING) return
        val saved = engine.savedQr() ?: return
        log("→ Bluetooth trigger: starting Connect for '${engine.bikeName()}'")
        autoConnectStarted = true
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved)
    }

    /** Auto-connect on launch — at most once per process; only a real start latches it. */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!engine.autoConnectEnabled()) return
        if (engine.aaServiceRunning()) return
        val p = engine.phase
        if (p.busy || p == Phase.STREAMING || p == Phase.MIRRORING) return
        val saved = engine.savedQr()
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!engine.aaInstalled()) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!engine.wifiEnabled()) {
            // Don't latch — retry once the rider turns Wi‑Fi back on.
            engine.ensureWifiOrPrompt(port?.activity)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }
        val inRange = engine.ssidInRange(saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${engine.bikeName()}' not in range — will retry when its Wi-Fi appears")
            return
        }
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${engine.bikeName()}' ($why). Disable in Setup ▸ Startup.")
        engine.clearProjection()   // bike uses the AA pipeline, not mirror
        engine.ensureLocationPermission(port?.activity)
        engine.postDelayed(1200) {
            try {
                if (port == null) {
                    // Recreated before we fired — let the next instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (!engine.aaServiceRunning() && !engine.phase.busy) startAaFlow(saved)
            } catch (e: Exception) {
                log("auto-connect failed (app stays open): $e")
                engine.persistSession()
                engine.setPhase(Phase.ERROR, engine.string(R.string.conn_detail_auto_connect_failed))
            }
        }
    }

    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    private fun reconnectSavedBike() {
        val saved = engine.savedQr() ?: return
        engine.clearProjection()
        engine.ensureLocationPermission(port?.activity)
        startAaFlow(saved)
    }

    /** Back from the rival's App-info (Force stop) within the window → one retry. */
    private fun maybeRetryAfterRivalStop() {
        val sentAt = rivalSettingsSentAt ?: return
        rivalSettingsSentAt = null
        if (!RivalClient.shouldRetryOnResume(sentAt, engine.elapsedRealtime())) return
        log("→ back from CFMoto app settings — retrying")
        engine.postDelayed(1000) { reconnectSavedBike() }
    }

    // ---------------------------------------------------------------- phase → prompts

    /** Every ConnectionState transition (wired in RealConnectionEngine.install). */
    fun onPhaseChanged(phase: Phase, detail: String) {
        if (phase == Phase.ERROR && detail.isNotBlank() && !isHusConnectNoise(detail)) {
            try { engine.reportError(detail) } catch (_: Exception) {}
        }
        // The bike's link ports are held by the official CFMoto app — offer to close it. Once per error.
        if (phase == Phase.ERROR && detail.contains("CFMoto app", ignoreCase = true)) {
            if (!rivalPromptShown) {
                rivalPromptShown = true
                _dialog.value = AppDialog.Rival(engine.rivalInstalled())
            }
        } else {
            rivalPromptShown = false
        }
        // Only a confirmed kill-switch with a live internet VPN.
        if (phase == Phase.ERROR && detail.contains("kill-switch", ignoreCase = true) && engine.vpnActive()) {
            if (!vpnPromptShown) {
                vpnPromptShown = true
                _dialog.value = AppDialog.VpnKillSwitch
            }
        } else {
            vpnPromptShown = false
        }
    }

    /** AA 17.4 HUS banner — riders see it often; don't flood telemetry. */
    private fun isHusConnectNoise(detail: String): Boolean {
        if (detail == engine.string(R.string.conn_detail_aa_not_started)) return true
        val d = detail.lowercase()
        return d.contains("head unit server") || d.contains("start head unit")
    }

    // ---------------------------------------------------------------- dialog actions

    fun onRivalCloseAndRetry() {
        _dialog.value = null
        val killed = engine.closeRival()
        log(if (killed) "→ asked Android to close the official CFMoto app; retrying…"
            else "→ couldn't auto-close the official CFMoto app — open its settings to Force stop.")
        engine.postDelayed(1500) { reconnectSavedBike() }
    }

    fun onRivalAppSettings() {
        _dialog.value = null
        if (engine.openRivalAppInfo()) {
            // Coming back after Force stop reconnects on its own (see onHostResumed).
            rivalSettingsSentAt = engine.elapsedRealtime()
        } else {
            engine.toast(engine.string(R.string.main_settings_failed))
        }
    }

    fun onRivalDismiss() {
        _dialog.value = null
        rivalSettingsSentAt = null
    }

    fun onVpnSettings() {
        _dialog.value = null
        if (!engine.openSettingsAction("android.net.vpn.SETTINGS") &&
            !engine.openSettingsAction(Settings.ACTION_SETTINGS)
        ) {
            engine.toast("Open Settings ▸ Network ▸ VPN", long = true)
        }
    }

    fun onNearbySettings(needNearby: Boolean) {
        _dialog.value = null
        val ok = if (needNearby) {
            engine.openSettingsAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, forThisApp = true)
        } else {
            engine.openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (!ok) engine.openSettingsAction(Settings.ACTION_SETTINGS)
    }

    fun onCrashShareLogs() { _dialog.value = null; engine.shareLog(port?.activity) }
    fun onCrashKeepLogs() { _dialog.value = null }
    fun onCrashDismiss() { _dialog.value = null; engine.clearCrash() }

    fun onUpdateDownload(release: UpdateChecker.Release) {
        _dialog.value = null
        if (!engine.openUrl(release.downloadUrl)) engine.toast(engine.string(R.string.main_download_failed))
    }

    fun onUpdateSkip(release: UpdateChecker.Release) { _dialog.value = null; engine.skipUpdate(release.version) }

    fun onMirrorContinue() { _dialog.value = null; startMirrorLink() }

    fun onReportShare(problem: String, model: String, year: String) {
        _dialog.value = null
        engine.shareProblemReport(port?.activity, problem, model, year)
    }

    // ---------------------------------------------------------------- Me tab helpers

    fun shareLog() = engine.shareLog(port?.activity)

    fun reportProblem() { _dialog.value = AppDialog.ReportProblem(engine.bikeName().orEmpty()) }

    fun checkUpdateManual() {
        engine.toast(engine.string(R.string.main_checking_update))
        engine.checkUpdate(manual = true) { release ->
            if (release == null) engine.toast(engine.string(R.string.main_up_to_date))
            else _dialog.value = AppDialog.Update(release)
        }
    }

    private fun maybeCheckUpdate() {
        engine.checkUpdate(manual = false) { release -> if (release != null) _dialog.value = AppDialog.Update(release) }
    }

    fun donate() {
        if (!engine.openUrl(AboutActivity.URL_KOFI)) engine.toast(engine.string(R.string.main_donate_failed))
    }

    // ---------------------------------------------------------------- mirror / map / search

    /** Mirror: arm screen capture and link as mirror (ignores duplicate taps while frames flow). */
    fun onMirrorPressed() {
        val mirroring = engine.phase == Phase.MIRRORING || engine.phase == Phase.RECONNECTING
        val ms = engine.msSinceLastFrame()
        if (mirroring && ms < 15_000L) {
            log("→ Mirror: already mirroring (last frame ${ms}ms ago) — ignore duplicate tap")
            engine.toast("Already mirroring — wait for the dash, or tap Stop first")
            return
        }
        log("→ Mirror: cast phone screen to dash")
        pendingAaStart = false
        engine.applyMirrorOrientation(port?.activity)
        engine.ensureLocationPermission(port?.activity)
        val intent = engine.createMirrorConsentIntent() ?: return
        port?.launchMirrorConsent(intent)
    }

    /** Screen-capture consent result. */
    fun onMirrorConsent(ok: Boolean, resultCode: Int, data: Intent?) {
        if (!ok || data == null) {
            log("screen-capture consent declined")
            engine.clearMirrorOrientation()
            return
        }
        engine.armMirrorProjection(resultCode, data) { _dialog.value = AppDialog.MirrorReady }
    }

    /** After screen-capture consent: connect using saved bike or scan. */
    private fun startMirrorLink() {
        val saved = engine.savedQr()
        if (saved != null) {
            log("→ Mirror: reusing saved bike '${engine.bikeName()}'")
            // Drop AA / Map / old PXC; keep the MediaProjection token we just armed.
            engine.tearDownForModeSwitch(clearMap = true, clearMirror = false)
            engine.applyProfile(saved)
            engine.applyMirrorOrientation(port?.activity)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: saved.ssid)
            engine.joinWifi(saved, gateOnAaSteady = false, activity = port?.activity)
            // Single-app MediaProjection only emits while the shared app is visible.
            port?.moveTaskToBack()
            engine.toast(engine.string(R.string.main_mirror_leave_shared_app), long = true)
        } else {
            log("→ Mirror: scan the dash QR…")
            port?.launchQrScan()
        }
    }

    /** Map tab "Open map": always the Hub. */
    fun onMapPressed() {
        if (engine.phase != Phase.STREAMING && engine.phase != Phase.MIRRORING) {
            engine.abandonStaleNavigation("map hub")
        }
        log("→ Map: open Hub")
        engine.openMapHub(port?.activity)
    }

    /** Map / GPX session ready — hard switch the dash to our map, or phone preview. */
    fun beginGpxProjection(gpxToBike: Boolean) {
        if (!engine.gpxActive()) {
            log("→ Map: nothing prepared — open Map / GPX first")
            engine.openMapHub(port?.activity)
            return
        }
        pendingAaStart = false
        engine.ensureLocationPermission(port?.activity)
        val p = engine.phase
        val live = p == Phase.STREAMING || p == Phase.MIRRORING || p == Phase.RECONNECTING ||
            p == Phase.PXC_CONNECTING || p == Phase.STARTING_AA || p == Phase.AA_VIDEO_LIVE ||
            p == Phase.JOINING_WIFI
        val wantBike = gpxToBike || live
        val saved = engine.savedQr()
        if (wantBike && saved != null) {
            log("→ Map: ${engine.gpxDescription()} → '${engine.bikeName()}' (hard switch)")
            engine.tearDownForModeSwitch(clearMap = false, clearMirror = true)
            engine.applyProfile(saved)
            engine.setPhase(Phase.MIRRORING, engine.bikeName() ?: saved.ssid)
            engine.joinWifi(saved, gateOnAaSteady = false, activity = port?.activity)
            return
        }
        log("→ Map: phone Dash preview (${engine.gpxDescription()})")
        engine.openGpxPreview(port?.activity)
    }

    /**
     * Search box: our map on the bike → dash search; AA live → Google Maps; else Map hub on phone.
     * @return true when the field should be cleared.
     */
    fun navigateToTyped(text: String): Boolean {
        val dest = text.trim()
        if (dest.isEmpty()) {
            engine.toast(engine.string(R.string.main_type_place))
            return false
        }
        val aaLive = engine.aaPipelineLive()
        val ourMapOnBike = engine.gpxActive() && engine.dashRemoteAvailable()
        if (ourMapOnBike && engine.dashRemoteSubmit(dest)) {
            engine.toast(engine.string(R.string.main_searching_dash, dest))
            return true
        }
        if (aaLive && !engine.gpxActive()) {
            engine.sendDestinationToAndroidAuto(dest)
            return true
        }
        if (!aaLive && engine.dashRemoteSubmit(dest)) {
            engine.toast(engine.string(R.string.main_searching_dash, dest))
            return true
        }
        engine.openMapSearch(port?.activity, dest)
        return false
    }

    /** Live suggestions on the dash while typing (only while OUR map is on the bike). */
    fun onSearchTextChanged(text: String) {
        val q = text.trim()
        if (q.length >= 2 && engine.gpxActive() && engine.dashRemoteAvailable()) engine.dashRemoteSubmit(q)
    }
}
```

- [ ] **Step 5: Run the controller tests.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.connect.ConnectionControllerTest" $F`
Expected: PASS (17 tests). A failing ordering test means a call was moved out of order — fix the controller, not the test.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/connect app/src/test/java/dev/zanderp/opencfmoto/connect
git commit -m "feat(connect): ConnectionController — connect flow out of MainActivity, with tests

Same logs, order, guards and delays as the old MainActivity; Android access
goes through ConnectionEngine and the screen through UiPort. Not wired yet.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: RealConnectionEngine (moved Android bodies) + install

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/connect/RealConnectionEngine.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/OpenCfMotoApp.kt` (end of `onCreate`)

**Interfaces:**
- Consumes: `ConnectionEngine` (Task 5, incl. `gpxDescription()`), `ConnectionController`, `AppGraph`.
- Produces: `class RealConnectionEngine(app: Application) : ConnectionEngine` and `fun install()` wiring `ConnectionState.listener`.

The old `MainActivity.kt` is **unchanged in this task** (it keeps compiling; the duplicate code is removed in Task 7).

- [ ] **Step 1: Substitution rules** for every body copied from `MainActivity.kt` (line numbers refer to the current file):

| In MainActivity | In RealConnectionEngine |
|---|---|
| `this` / `applicationContext` used as a `Context` | `ctx` (the `Application`) |
| `this` used as an `Activity` (dialogs, `PhoneHotspotAssist.showSetupDialog`, `WifiGate.ensureEnabledOrPrompt`, `DependencyPrompt`, `ActivityCompat.requestPermissions`, `AaSelfMode.trigger`) | the `activity: Activity?` parameter; if null → `log("[ui] <what> skipped — no screen")` and return the neutral value |
| `getString(...)` | `ctx.getString(...)` |
| `runOnUiThread { … }` | `main.post { … }` |
| `logView.post {…}` / `logView.postDelayed({…}, n)` | `main.post {…}` / `main.postDelayed({…}, n)` |
| `Toast.makeText(this, …)` | `Toast.makeText(ctx, …)` |
| `prober` / `(BikeLink.prober ?: prober)` | `prober()` (defined below) |
| `log(...)` / `::log` | `LogBus.log(...)` / `LogBus::log` |

- [ ] **Step 2: Write `RealConnectionEngine.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connect

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.zanderp.opencfmoto.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Android side of the connect flow: bodies moved verbatim from the old MainActivity. */
class RealConnectionEngine(private val app: Application) : ConnectionEngine {
    private val ctx: Context get() = app
    private val main = Handler(Looper.getMainLooper())

    /** Wire the controller to ConnectionState (formerly MainActivity's listener → renderStatus). */
    fun install(controller: ConnectionController) {
        ConnectionState.listener = { phase, detail -> main.post { controller.onPhaseChanged(phase, detail) } }
    }

    private fun prober(): EasyConnProber =
        BikeLink.prober ?: EasyConnProber(ctx, LogBus::log).also { BikeLink.prober = it }

    private fun noScreen(what: String) = LogBus.log("[ui] $what skipped — no screen")

    // ------------------------------------------------------------ reads
    override val phase: Phase get() = ConnectionState.phase
    override fun savedQr() = BikeMemory.lastQr(ctx)
    override fun bikeName() = BikeMemory.lastBikeName(ctx)
    override fun hasSavedBike() = BikeMemory.hasSaved(ctx)
    override fun aaServiceRunning() = AndroidAutoService.isRunning
    override fun aaParked() = AndroidAutoService.isParked
    override fun aaSessionActive() = AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding
    override fun aaSessionSeen() = AaVideoBridge.aaSessionSeen
    override fun autoConnectEnabled() = AppSettings.autoConnect(ctx)
    override fun aaInstalled() = SetupHelper.isAndroidAutoInstalled(ctx)
    override fun wifiEnabled() = WifiGate.isWifiEnabled(ctx)
    override fun ssidInRange(ssid: String) = BikeWifi.isSsidInRange(ctx, ssid)
    override fun canAttemptHur() = dev.zanderp.opencfmoto.aa.AaSelfMode.canAttemptHur(ctx)
    override fun needsNearbyPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    override fun vpnActive() = BikeWifi.isVpnActive(ctx)
    override fun setupSeen() = SetupActivity.hasSeen(ctx)
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    override fun gpxActive() = GpxSession.active
    override fun gpxDescription() = "${GpxSession.mode} '${GpxSession.trackName}'"
    override fun aaPipelineLive() = AaVideoBridge.pipeline != null
    override fun dashRemoteAvailable() = DashRemote.isAvailable
    override fun msSinceLastFrame() = BikeLink.prober?.msSinceLastFrame() ?: Long.MAX_VALUE
    override fun crashPending() = CrashGuard.pendingCrashText(ctx) != null
    override fun string(resId: Int, vararg args: Any) = ctx.getString(resId, *args)

    // ------------------------------------------------------------ plumbing
    override fun setPhase(phase: Phase, detail: String?) = ConnectionState.set(phase, detail)
    override fun log(msg: String) = LogBus.log(msg)
    override fun toast(text: String, long: Boolean) {
        main.post { Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
    override fun reportError(detail: String) = AnonymousTelemetry.reportError(ctx, detail)
    override fun persistSession() = CrashGuard.persistSession(ctx)

    // ------------------------------------------------------------ startup
    override fun applySettingsToHolder() = AppSettings.applyToHolder(ctx)
    override fun ensureProber() { prober() }
    override fun requestNotificationPermission(activity: Activity?) {
        // Android 13+: the mediaProjection FGS notification needs it (MainActivity.onCreate, lines 520-529).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val a = activity ?: return noScreen("notification permission")
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }
    override fun dependencyLaunchCheck(activity: Activity) { DependencyPrompt.showOnLaunchIfNeeded(activity) }
    override fun clearWifiOffNoticeIfEnabled() { if (WifiGate.isWifiEnabled(ctx)) WifiGate.cancelNotification(ctx) }

    // ------------------------------------------------------------ connect flow
    override fun ensureWifiOrPrompt(activity: Activity?): Boolean =
        if (activity != null) WifiGate.ensureEnabledOrPrompt(activity) else WifiGate.ensureEnabledOrNotify(ctx)

    override fun dependencyBlocks(activity: Activity, forScan: Boolean) = DependencyPrompt.showForConnect(activity, forScan)

    override fun ensureLocationPermission(activity: Activity?) {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier (MainActivity 1372-1381).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val a = activity ?: return noScreen("location permission")
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun requestNearbyPermission(activity: Activity?) {
        val a = activity ?: return noScreen("Nearby permission")
        ActivityCompat.requestPermissions(
            a, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQ_BT_FOR_AA,
        )
    }

    override fun armCommute(arm: Boolean) { if (arm) CommuteNav.armForConnect(ctx) else CommuteNav.disarm() }

    override fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) {
        // MainActivity lines 158-183, verbatim with the substitution rules. The `::prober.isInitialized`
        // branch is dropped: the only prober is now BikeLink.prober.
        LogBus.log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(ctx) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        if (clearMirror) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(ctx) } catch (_: Exception) {}
            MirrorOrientationLock.clear(ctx)
        }
        if (clearMap) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        BikeLink.beginHandoff(ctx)
    }

    override fun applyProfile(qr: QrData) {
        // COPY MainActivity lines 117-151 (body of applyProfile) verbatim, applying the substitution rules
        // (every `this` → `ctx`, `log(` → `LogBus.log(`).
    }

    override fun startAaHandoff() {
        // MainActivity lines 207-215.
        BikeLink.beginHandoff(ctx)
        AaVideoBridge.onSteadyVideo = {
            AaVideoBridge.onSteadyVideo = null
            ConnectionState.set(Phase.AA_VIDEO_LIVE)
            LogBus.log("→ Android Auto video is live")
            AndroidAutoService.upgradeForegroundForMicrophone()
            BikeLink.markAaVideoSteady()
        }
        AndroidAutoService.start(ctx)
    }

    override fun triggerSelfMode(activity: Activity?) {
        val a = activity ?: return noScreen("AA self-mode trigger")
        dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(a, log = LogBus::log)
    }

    override fun joinWifi(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        // COPY MainActivity lines 1052-1135 (body of joinWifi) verbatim with the substitution rules;
        // `WifiGate.ensureEnabledOrPrompt(this)` → `ensureWifiOrPrompt(activity)`,
        // `joinPhoneHotspot(qr, gateOnAaSteady)` → `joinPhoneHotspot(qr, gateOnAaSteady, activity)`,
        // `joinWifiP2p(qr, gateOnAaSteady)` → `joinWifiP2p(qr, gateOnAaSteady, activity)`.
    }

    private fun joinPhoneHotspot(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        // COPY MainActivity lines 1143-1155 verbatim; `activity = this` → `activity = activity ?: run {
        //   noScreen("phone-hotspot setup"); ConnectionState.set(Phase.ERROR, ctx.getString(R.string.main_phone_hotspot_cancelled)); return }`;
        // `startPhoneHotspotScan(gateOnAaSteady)` → `startPhoneHotspotScan(gateOnAaSteady, activity)`.
    }

    private fun startPhoneHotspotScan(gateOnAaSteady: Boolean, activity: Activity?) {
        // COPY MainActivity lines 1160-1259 verbatim with the substitution rules. The "no tether
        // interface" MaterialAlertDialog uses `activity` (skip the dialog with noScreen(...) when null,
        // still setting Phase.ERROR); `PhoneHotspotAssist.openHotspotSettings(this)` → `(activity)`.
    }

    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean, activity: Activity?) {
        // COPY MainActivity lines 1264-1333 verbatim; `WifiGate.ensureEnabledOrPrompt(this)` →
        // `ensureWifiOrPrompt(activity)`; `BikeMemory.winningTransport(this, …)` → `(ctx, …)`.
    }

    override fun stopEverything() {
        // MainActivity lines 1481-1495 (bleWakeUp lines dropped — dead code).
        LogBus.log("→ stopping everything (Android Auto + bike)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(ctx) } catch (e: Exception) { LogBus.log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("prober stop: $e") }
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(ctx) } catch (e: Exception) { LogBus.log("projection stop: $e") }
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(ctx, LogBus::log) } catch (e: Exception) { LogBus.log("wifi leave: $e") }
        ConnectionState.set(Phase.STOPPED, "")
    }

    override fun clearProjection() { ProjectionHolder.projection = null }
    override fun clearGpxSession() { GpxSession.clear() }
    override fun abandonStaleNavigation(reason: String) { GpxSession.abandonStaleNavigation(reason) }
    override fun notifyForegroundResuming() = AndroidAutoService.notifyForegroundResuming()
    override fun saveBike(raw: String, qr: QrData) = BikeMemory.save(ctx, raw, qr)

    // ------------------------------------------------------------ mirror / map
    override fun applyMirrorOrientation(activity: Activity?) {
        val a = activity ?: return noScreen("mirror orientation")
        MirrorOrientationLock.apply(a)
    }
    override fun clearMirrorOrientation() = MirrorOrientationLock.clear(ctx)

    override fun createMirrorConsentIntent(): Intent? = try {
        // MainActivity lines 1536-1546.
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            toast("Single-app mirror needs Android 14+. Whole screen will be used.", long = true)
            mpm.createScreenCaptureIntent()
        }
    } catch (e: Exception) {
        LogBus.log("mirror start failed ($e)")
        null
    }

    override fun armMirrorProjection(resultCode: Int, data: Intent, onReady: () -> Unit) {
        // MainActivity lines 365-409: FGS of type mediaProjection must be RUNNING before
        // getMediaProjection() on API 34+ → poll its foreground flag every 100 ms (max 5 s).
        ProjectionService.start(ctx)
        val maxTries = 50
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(resultCode, data)
                        GpxSession.clear()
                        LogBus.log("screen-capture armed (FGS up after ${tries * 100}ms) — pick Entire screen or a single app (Android 14+)")
                        onReady()
                    } catch (e: Exception) {
                        LogBus.log("getMediaProjection failed: $e")
                        ProjectionService.stop(ctx)
                        MirrorOrientationLock.clear(ctx)
                    }
                } else if (tries++ < maxTries) {
                    main.postDelayed(this, 100)
                } else {
                    LogBus.log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(ctx)
                    MirrorOrientationLock.clear(ctx)
                }
            }
        }
        main.post(poll)
    }

    // Always triggered from the screen; the start helpers need an Activity context (no NEW_TASK flag).
    override fun openMapHub(activity: Activity?) { activity?.let { GpxActivity.start(it) } ?: noScreen("open map") }
    override fun openGpxPreview(activity: Activity?) { activity?.let { HudViewActivity.startGpxPreview(it) } ?: noScreen("GPX preview") }
    override fun openMapSearch(activity: Activity?, query: String) { activity?.let { GpxActivity.startSearch(it, query) } ?: noScreen("map search") }
    override fun dashRemoteSubmit(query: String) = DashRemote.submit(query)

    override fun sendDestinationToAndroidAuto(query: String) {
        // COPY MainActivity lines 1430-1460 (body of sendDestinationToAndroidAuto) verbatim with the
        // substitution rules; `lastKnownLatLon()` is the private copy of lines 1463-1479 below;
        // `NavLauncher.navigate(this, …, ::log)` → `NavLauncher.navigate(ctx, …, LogBus::log)`.
    }

    private fun lastKnownLatLon(): Pair<Double, Double>? {
        // COPY MainActivity lines 1464-1478 verbatim; `getSystemService(LOCATION_SERVICE)` →
        // `ctx.getSystemService(Context.LOCATION_SERVICE)`.
        return null // replaced by the copied body
    }

    // ------------------------------------------------------------ dialog actions
    override fun closeRival() = RivalClient.closeBestEffort(ctx)
    override fun openRivalAppInfo() = RivalClient.openAppInfo(ctx)
    override fun rivalInstalled() = RivalClient.isInstalled(ctx)

    override fun checkUpdate(manual: Boolean, onResult: (UpdateChecker.Release?) -> Unit) {
        Thread {
            val release = try { UpdateChecker.check(ctx, manual = manual) } catch (_: Exception) { null }
            main.post { onResult(release) }
        }.start()
    }

    override fun skipUpdate(version: String) = UpdateChecker.skip(ctx, version)

    override fun openUrl(url: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    override fun openSettingsAction(action: String, forThisApp: Boolean): Boolean = try {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forThisApp) i.data = Uri.fromParts("package", ctx.packageName, null)
        ctx.startActivity(i); true
    } catch (_: Exception) { false }

    override fun shareLog(activity: Activity?) {
        // COPY MainActivity lines 1554-1588 (body of shareLog) verbatim with the substitution rules;
        // the final `startActivity(Intent.createChooser(send, "Share log"))` uses `activity` when
        // non-null, else `ctx.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))`.
    }

    override fun shareProblemReport(activity: Activity?, problem: String, model: String, year: String) {
        // COPY MainActivity lines 1674-1710 (body of shareProblemReport) verbatim with the substitution
        // rules (`this@MainActivity` → `ctx`); the chooser uses `activity` or NEW_TASK as in shareLog.
    }

    override fun clearCrash() = CrashGuard.clearCrash(ctx)
    override fun startSetup(activity: Activity?) { activity?.let { SetupActivity.start(it) } ?: noScreen("setup") }

    override fun hostDestroyedTeardown() {
        // MainActivity lines 826-838 (bleWakeUp lines dropped — dead code).
        AaVideoBridge.onSteadyVideo = null
        BikeLink.prober?.stop()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(ctx)
        BikeWifi.leave(ctx, LogBus::log)
        BikeWifiP2p.stop(LogBus::log)
    }

    private companion object {
        const val REQ_BT_FOR_AA = 4
    }
}
```

The `// COPY … verbatim` bodies are copied from the named line ranges; after copying, no `this`, `getString(`, `runOnUiThread`, `logView`, `Toast.makeText(this` may remain in the file:

Run: `grep -nE "\bthis\b|[^.]getString\(|runOnUiThread|logView|makeText\(this|return null // replaced" app/src/main/java/dev/zanderp/opencfmoto/connect/RealConnectionEngine.kt`
Expected: no output except `this` inside `object : Runnable` (`main.postDelayed(this, 100)`).

- [ ] **Step 3: Install in `OpenCfMotoApp`.** At the end of `onCreate()` add:

```kotlin
        val engine = dev.zanderp.opencfmoto.connect.RealConnectionEngine(this)
        dev.zanderp.opencfmoto.connect.AppGraph.connection =
            dev.zanderp.opencfmoto.connect.ConnectionController(engine).also { engine.install(it) }
```

(The old `MainActivity.onCreate` still overwrites `ConnectionState.listener` until Task 7 — expected.)

- [ ] **Step 4: Build + suite.** Run: `./gradlew :app:assembleDebug $F` then `./gradlew :app:testDebugUnitTest $F`
Expected: build succeeds; only the known YunmoFrameTest fails.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/connect/RealConnectionEngine.kt app/src/main/java/dev/zanderp/opencfmoto/OpenCfMotoApp.kt
git commit -m "feat(connect): RealConnectionEngine — MainActivity's Android bodies moved verbatim

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Compose host — MainActivity, shell, tabs, Logs, dialogs

**Files:**
- Rewrite: `app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt`
- Create: `ui/shell/AppShell.kt`, `ui/ride/RideScreen.kt`, `ui/tabs/MapTab.kt`, `ui/tabs/TripsTab.kt`, `ui/tabs/MeTab.kt`, `ui/logs/LogsScreen.kt`, `ui/dialog/DialogHost.kt` (all under `app/src/main/java/dev/zanderp/opencfmoto/`)
- Modify: `app/src/main/res/values/strings.xml`
- Delete: `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: everything above; `AppGraph.connection`.
- Produces: the running app.

- [ ] **Step 1: Strings** (append inside `<resources>`):

```xml
    <string name="ui_tab_ride">Ride</string>
    <string name="ui_tab_map">Map</string>
    <string name="ui_tab_trips">Trips</string>
    <string name="ui_tab_me">Me</string>
    <string name="ui_ride_title">My moto</string>
    <string name="ui_quick_actions">Quick actions</string>
    <string name="ui_tile_dash_view">Dash view</string>
    <string name="ui_tile_controls">Controls</string>
    <string name="ui_tile_mirror">Mirror</string>
    <string name="ui_tile_scan">Scan new bike</string>
    <string name="ui_scan_your_bike">Scan your bike</string>
    <string name="ui_map_title">Map &amp; GPX</string>
    <string name="ui_map_desc">Free ride, search, GPX and offline maps — on the dash or the phone.</string>
    <string name="ui_map_open">Open map</string>
    <string name="ui_search_label">Search a place</string>
    <string name="ui_search_hint">Place or address…</string>
    <string name="ui_search_go">Go</string>
    <string name="ui_trips_saved">Saved rides</string>
    <string name="ui_trips_saved_desc">Your rides by day, route maps and GPX export.</string>
    <string name="ui_trips_computer">Trip computer</string>
    <string name="ui_trips_computer_desc">Live speed, distance and ride time.</string>
    <string name="ui_me_garage">Garage</string>
    <string name="ui_me_garage_desc">Your bikes, names and photos</string>
    <string name="ui_me_settings">Settings</string>
    <string name="ui_me_settings_desc">Display, metrics strip, handlebar, navigation, connection</string>
    <string name="ui_me_logs">Logs</string>
    <string name="ui_me_logs_desc">Connection log — share it when asking for help</string>
    <string name="ui_me_report">Report a problem</string>
    <string name="ui_me_update">Check for update</string>
    <string name="ui_me_about">About &amp; credits</string>
    <string name="ui_me_donate">Donate</string>
    <string name="ui_logs_share">Share</string>
    <string name="ui_logs_clear">Clear</string>
    <string name="ui_version">OpenCfMoto %1$s</string>
```

- [ ] **Step 2: `ui/dialog/DialogHost.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.dialog

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ReleaseNotes
import dev.zanderp.opencfmoto.connect.AppDialog
import dev.zanderp.opencfmoto.connect.ConnectionController
import dev.zanderp.opencfmoto.ui.design.Ocm

/** Renders the controller's pending dialog. Texts are the old MainActivity dialogs' texts. */
@Composable
fun DialogHost(dialog: AppDialog?, c: ConnectionController) {
    when (dialog) {
        null -> Unit
        is AppDialog.Rival -> AlertDialog(
            onDismissRequest = c::onRivalDismiss,
            title = { Text(stringResource(R.string.main_rival_title)) },
            text = {
                Text(
                    if (dialog.installed) {
                        "The official CFMoto/EasyConnect app is running and holding the bike's link ports, so " +
                            "the dash stays blank. Close it, then reconnect.\n\n" +
                            "\"Close & retry\" tries to stop it for you; if the dash is still blank, use " +
                            "\"App settings\" and tap Force stop."
                    } else {
                        "Something is holding the bike's link ports (10920-10922). Close any other CFMoto/EasyConnect app and reconnect."
                    },
                )
            },
            confirmButton = { TextButton(onClick = c::onRivalCloseAndRetry) { Text("Close & retry") } },
            dismissButton = {
                Column {
                    TextButton(onClick = c::onRivalAppSettings) { Text("App settings") }
                    TextButton(onClick = c::onRivalDismiss) { Text("Dismiss") }
                }
            },
        )
        AppDialog.VpnKillSwitch -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text("VPN is blocking the bike") },
            text = {
                Text(
                    "Android Auto reached the phone, but a VPN kill-switch is blocking the bike Wi‑Fi " +
                        "(Network.bindSocket → EPERM).\n\nFix (any one):\n• Turn the VPN off for this ride\n" +
                        "• Disable Always-on VPN → \"Block connections without VPN\"\n" +
                        "• Allow LAN / local network in the VPN app (PCAPdroid, AdGuard, etc.)\n\nThen tap Connect again.",
                )
            },
            confirmButton = { TextButton(onClick = c::onVpnSettings) { Text("VPN settings") } },
            dismissButton = { TextButton(onClick = c::dismissDialog) { Text("Dismiss") } },
        )
        is AppDialog.NearbyBt -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text(stringResource(R.string.main_aa_need_nearby_title)) },
            text = {
                Text(
                    stringResource(
                        if (dialog.needNearby) R.string.main_aa_need_nearby_message else R.string.main_aa_need_bt_on_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { c.onNearbySettings(dialog.needNearby) }) {
                    Text(stringResource(R.string.main_aa_need_nearby_settings))
                }
            },
            dismissButton = { TextButton(onClick = c::dismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
        AppDialog.CrashRecovery -> AlertDialog(
            onDismissRequest = c::onCrashKeepLogs,
            title = { Text(stringResource(R.string.main_crash_title)) },
            text = {
                Text(
                    "OpenCfMoto closed unexpectedly last time. The log and crash report were saved — " +
                        "open Logs and tap Share Logs so we can see what happened.",
                )
            },
            confirmButton = { TextButton(onClick = c::onCrashShareLogs) { Text("Share Logs") } },
            dismissButton = {
                Column {
                    TextButton(onClick = c::onCrashKeepLogs) { Text("Keep logs") }
                    TextButton(onClick = c::onCrashDismiss) { Text("Dismiss") }
                }
            },
        )
        is AppDialog.Update -> {
            val textColor = Ocm.colors.text.toArgb()
            AlertDialog(
                onDismissRequest = c::dismissDialog,
                title = { Text("Update ${dialog.release.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AndroidView(factory = { ctx ->
                            TextView(ctx).apply {
                                text = ReleaseNotes.toSpanned(dialog.release.notes)
                                movementMethod = LinkMovementMethod.getInstance()
                                setTextColor(textColor)
                            }
                        })
                    }
                },
                confirmButton = { TextButton(onClick = { c.onUpdateDownload(dialog.release) }) { Text("Download") } },
                dismissButton = {
                    Column {
                        TextButton(onClick = { c.onUpdateSkip(dialog.release) }) { Text("Skip") }
                        TextButton(onClick = c::dismissDialog) { Text("Later") }
                    }
                },
            )
        }
        AppDialog.MirrorReady -> AlertDialog(
            onDismissRequest = {},  // not cancelable (as before)
            title = { Text(stringResource(R.string.main_mirror_ready_title)) },
            text = {
                Text(
                    "Entire screen — best for riding. Uses Setup ▸ Screen fit and Setup ▸ Mirror orientation " +
                        "(Match dash rotates the phone so the TFT fills).\n\nSingle app — that app must stay on screen; " +
                        "Android sends no frames in the background. Prefer GPX / Tracks or Android Auto for pocket use.\n\n" +
                        "Bike touch does not drive mirrored apps. Continue connects and sends OpenCfMoto to the background.",
                )
            },
            confirmButton = { TextButton(onClick = c::onMirrorContinue) { Text("Continue") } },
        )
        is AppDialog.ReportProblem -> {
            var problem by remember { mutableStateOf("") }
            var model by remember { mutableStateOf(dialog.defaultModel) }
            var year by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = c::dismissDialog,
                title = { Text(stringResource(R.string.main_report_a_problem)) },
                text = {
                    Column {
                        Text(stringResource(R.string.main_report_problem_message))
                        OutlinedTextField(problem, { problem = it }, Modifier.fillMaxWidth(), minLines = 3,
                            placeholder = { Text(stringResource(R.string.main_report_hint_problem)) })
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.main_report_hint_bike_model)) })
                        OutlinedTextField(year, { year = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.main_report_hint_year)) })
                    }
                },
                confirmButton = { TextButton(onClick = { c.onReportShare(problem, model, year) }) { Text(stringResource(R.string.main_share)) } },
                dismissButton = { TextButton(onClick = c::dismissDialog) { Text(stringResource(R.string.dash_cancel)) } },
            )
        }
    }
}
```

- [ ] **Step 3: `ui/ride/RideScreen.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.ActionTile
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.StatusChip

@Composable
fun RideScreen(
    s: RideUiState,
    onPrimary: () -> Unit,
    onDashView: () -> Unit,
    onControls: () -> Unit,
    onMirror: () -> Unit,
    onScan: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_ride_title), style = OcmType.screenTitle, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(stringResource(s.phase.labelRes), s.statusKind)
            Spacer(Modifier.width(10.dp))
            Text(
                s.bikeName?.let { stringResource(R.string.main_paired, it) } ?: stringResource(R.string.main_no_bike_paired),
                style = OcmType.body, color = c.text2, maxLines = 1,
            )
        }
        if (s.detail.isNotBlank()) Text(s.detail, style = OcmType.body, color = c.text2)
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
        SectionLabel(stringResource(R.string.ui_quick_actions))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_dash_view), Icons.Rounded.CastConnected, onDashView, s.dashViewEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_controls), Icons.Rounded.Gamepad, onControls, s.controlsEnabled, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(stringResource(R.string.ui_tile_mirror), Icons.Rounded.ScreenShare, onMirror, s.mirrorEnabled, Modifier.weight(1f))
            ActionTile(stringResource(R.string.ui_tile_scan), Icons.Rounded.QrCodeScanner, onScan, s.scanEnabled, Modifier.weight(1f))
        }
    }
}
```

- [ ] **Step 4: `ui/tabs/MapTab.kt`, `TripsTab.kt`, `MeTab.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import kotlinx.coroutines.delay

/**
 * @param searchFocusTick increments when the bike asks to "type on phone" → focus the field.
 * @param onSearch returns true when the field should be cleared.
 */
@Composable
fun MapTab(
    searchFocusTick: Int,
    onOpenMap: () -> Unit,
    onSearch: (String) -> Boolean,
    onSearchTextChanged: (String) -> Unit,
) {
    val c = Ocm.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchFocusTick) { if (searchFocusTick > 0) focus.requestFocus() }
    LaunchedEffect(text) { delay(350); onSearchTextChanged(text) }   // live dash suggestions (debounced)
    OcmScreen {
        Text(stringResource(R.string.ui_tab_map), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            Text(stringResource(R.string.ui_map_title), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_map_desc), style = OcmType.body, color = c.text2)
            PrimaryButton(stringResource(R.string.ui_map_open), Icons.Rounded.Map, onOpenMap)
        }
        SectionLabel(stringResource(R.string.ui_search_label))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.ui_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (onSearch(text)) text = "" }),
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { if (onSearch(text)) text = "" }, modifier = Modifier.heightIn(min = 56.dp)) {
                androidx.compose.material3.Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.ui_search_go))
            }
        }
    }
}
```

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType

@Composable
fun TripsTab(onSavedRides: () -> Unit, onTripComputer: () -> Unit) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_tab_trips), style = OcmType.screenTitle, color = c.text)
        OcmCard(onClick = onSavedRides) {
            Text(stringResource(R.string.ui_trips_saved), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_trips_saved_desc), style = OcmType.body, color = c.text2)
        }
        OcmCard(onClick = onTripComputer) {
            Text(stringResource(R.string.ui_trips_computer), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_trips_computer_desc), style = OcmType.body, color = c.text2)
        }
    }
}
```

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SettingRow

@Composable
fun MeTab(
    onGarage: () -> Unit, onSettings: () -> Unit, onLogs: () -> Unit, onReport: () -> Unit,
    onUpdate: () -> Unit, onAbout: () -> Unit, onDonate: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_tab_me), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_garage), stringResource(R.string.ui_me_garage_desc), Icons.Rounded.Garage, onGarage)
            SettingRow(stringResource(R.string.ui_me_settings), stringResource(R.string.ui_me_settings_desc), Icons.Rounded.Settings, onSettings)
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

- [ ] **Step 5: `ui/logs/LogsScreen.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val TAIL_CHARS = 64 * 1024

@Composable
fun LogsScreen(onShare: () -> Unit, onClear: () -> Unit) {
    val c = Ocm.colors
    var text by remember { mutableStateOf(LogBus.tail(TAIL_CHARS)) }
    val scroll = rememberScrollState()
    // Debounced: a burst of log lines re-reads the tail at most every 300 ms.
    LaunchedEffect(Unit) {
        LogBus.revision.collectLatest {
            delay(300)
            text = LogBus.tail(TAIL_CHARS)
        }
    }
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().background(c.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_me_logs), style = OcmType.screenTitle, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.ui_logs_share)) }
            OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.ui_logs_clear)) }
        }
        SelectionContainer(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp),
        ) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.text, modifier = Modifier.verticalScroll(scroll))
        }
    }
}
```

- [ ] **Step 6: `ui/shell/AppShell.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.shell

import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zanderp.opencfmoto.AboutActivity
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.ControlsActivity
import dev.zanderp.opencfmoto.CrashGuard
import dev.zanderp.opencfmoto.GarageActivity
import dev.zanderp.opencfmoto.HudViewActivity
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SetupActivity
import dev.zanderp.opencfmoto.TripActivity
import dev.zanderp.opencfmoto.TripsListActivity
import dev.zanderp.opencfmoto.connect.ConnectionController
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.dialog.DialogHost
import dev.zanderp.opencfmoto.ui.logs.LogsScreen
import dev.zanderp.opencfmoto.ui.ride.RideScreen
import dev.zanderp.opencfmoto.ui.ride.rideUiState
import dev.zanderp.opencfmoto.ui.tabs.MapTab
import dev.zanderp.opencfmoto.ui.tabs.MeTab
import dev.zanderp.opencfmoto.ui.tabs.TripsTab

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab("ride", R.string.ui_tab_ride, Icons.Rounded.TwoWheeler),
    Tab("map", R.string.ui_tab_map, Icons.Rounded.Map),
    Tab("trips", R.string.ui_tab_trips, Icons.Rounded.Insights),
    Tab("me", R.string.ui_tab_me, Icons.Rounded.Person),
)

/** @param searchFocusTick bumped by MainActivity when the bike asks to type on the phone. */
@Composable
fun AppShell(c: ConnectionController, searchFocusTick: Int) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val (phase, detail) = ConnectionState.flow.collectAsStateWithLifecycle().value
    val bike by c.bike.collectAsStateWithLifecycle()
    val dialog by c.dialog.collectAsStateWithLifecycle()
    val ride = remember(phase, detail, bike) { rideUiState(phase, detail, bike.name, bike.hasSaved) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(searchFocusTick) { if (searchFocusTick > 0) nav.navigate("map") { launchSingleTop = true } }

    Scaffold(
        containerColor = Ocm.colors.bg,
        bottomBar = {
            NavigationBar(containerColor = Ocm.colors.surface) {
                for (t in Tabs) {
                    val selected = route == t.route || (t.route == "me" && route == "logs")
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ocm.colors.accent, selectedTextColor = Ocm.colors.accent,
                            indicatorColor = Ocm.colors.accent.copy(alpha = 0.14f),
                            unselectedIconColor = Ocm.colors.text2, unselectedTextColor = Ocm.colors.text2,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "ride", modifier = Modifier.padding(pad)) {
            composable("ride") {
                RideScreen(
                    s = ride,
                    onPrimary = c::onConnectPressed,
                    onDashView = { ctx.startActivity(android.content.Intent(ctx, HudViewActivity::class.java)) },
                    onControls = { ctx.startActivity(android.content.Intent(ctx, ControlsActivity::class.java)) },
                    onMirror = c::onMirrorPressed,
                    onScan = c::startAaScan,
                )
            }
            composable("map") {
                MapTab(searchFocusTick, onOpenMap = c::onMapPressed, onSearch = c::navigateToTyped, onSearchTextChanged = c::onSearchTextChanged)
            }
            composable("trips") {
                TripsTab(onSavedRides = { TripsListActivity.start(ctx) }, onTripComputer = { TripActivity.start(ctx) })
            }
            composable("me") {
                MeTab(
                    onGarage = { GarageActivity.start(ctx) }, onSettings = { SetupActivity.start(ctx) },
                    onLogs = { nav.navigate("logs") }, onReport = c::reportProblem, onUpdate = c::checkUpdateManual,
                    onAbout = { AboutActivity.start(ctx) }, onDonate = c::donate,
                )
            }
            composable("logs") {
                LogsScreen(
                    onShare = c::shareLog,
                    onClear = {
                        LogBus.clear()
                        CrashGuard.clearSession(ctx)
                        LogBus.logSessionBanner()
                    },
                )
            }
        }
    }
    DialogHost(dialog, c)
}
```

- [ ] **Step 7: Rewrite `MainActivity.kt`** (replace the whole file):

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import dev.zanderp.opencfmoto.connect.AppGraph
import dev.zanderp.opencfmoto.connect.UiPort
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.shell.AppShell

/**
 * Compose host. The connect flow lives in [dev.zanderp.opencfmoto.connect.ConnectionController]
 * (AppGraph.connection); this activity forwards lifecycle + intents and provides the [UiPort].
 */
class MainActivity : AppCompatActivity() {

    private val controller get() = AppGraph.connection
    private val searchFocusTick = mutableIntStateOf(0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        controller.onQrScanned(if (result.resultCode == RESULT_OK) raw else null)
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onMirrorConsent(result.resultCode == RESULT_OK, result.resultCode, result.data)
    }

    private val port = object : UiPort {
        override val activity: Activity get() = this@MainActivity
        override fun launchQrScan() { scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java)) }
        override fun launchMirrorConsent(intent: Intent) { projectionLauncher.launch(intent) }
        override fun moveTaskToBack() { this@MainActivity.moveTaskToBack(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OcmTheme { AppShell(controller, searchFocusTick.intValue) } }
        controller.attach(port)
        installTypeOnPhoneHandler()
        val flags = takeIntentFlags(intent)
        controller.onHostCreated(
            startGpx = flags.startGpx, resume = flags.resume, btTrigger = flags.btTrigger, gpxToBike = flags.gpxToBike,
        )
    }

    override fun onPause() {
        CrashGuard.persistSession(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val f = takeIntentFlags(intent)
        controller.onNewIntent(resume = f.resume, btTrigger = f.btTrigger, startGpx = f.startGpx, gpxToBike = f.gpxToBike)
    }

    override fun onResume() {
        super.onResume()
        controller.attach(port)
        controller.onHostResumed(resumeExtra = takeIntentFlags(intent).resume)
    }

    override fun onDestroy() {
        DashRemote.setTypeOnPhoneHandler(null)
        controller.detach(port)
        controller.onHostDestroyed()
        super.onDestroy()
    }

    private data class IntentFlags(val resume: Boolean, val btTrigger: Boolean, val startGpx: Boolean, val gpxToBike: Boolean)

    /** Read the one-shot extras and remove them so a later onResume doesn't re-fire them. */
    private fun takeIntentFlags(i: Intent?): IntentFlags {
        if (i == null) return IntentFlags(false, false, false, false)
        val f = IntentFlags(
            resume = i.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false),
            btTrigger = i.getBooleanExtra(EXTRA_BT_TRIGGER, false),
            startGpx = i.getBooleanExtra(EXTRA_START_GPX, false),
            gpxToBike = i.getBooleanExtra(EXTRA_GPX_TO_BIKE, false),
        )
        i.removeExtra(AndroidAutoService.EXTRA_RESUME)
        i.removeExtra(EXTRA_BT_TRIGGER)
        i.removeExtra(EXTRA_START_GPX)
        i.removeExtra(EXTRA_GPX_TO_BIKE)
        return f
    }

    /** Bike Search tap → bring the app forward, open the Map tab and focus the search field. */
    private fun installTypeOnPhoneHandler() {
        DashRemote.setTypeOnPhoneHandler {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                } catch (_: Exception) {}
                searchFocusTick.intValue += 1
                try {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(40)
                } catch (_: Exception) {}
                Toast.makeText(this, "Bike search open — type here. Results appear on the dash.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_START_GPX = "start_gpx"
        const val EXTRA_BT_TRIGGER = "bt_trigger"
        /** When set with [EXTRA_START_GPX], join the bike even if not already live. */
        const val EXTRA_GPX_TO_BIKE = "gpx_to_bike"
    }
}
```

Note: the old onCreate-then-onResume double resume call is preserved (onCreate passes the extra, onResume passes `false` because the extras were consumed), matching the old `maybeResumeFromParked(intent)` in both callbacks.

- [ ] **Step 8: Delete the old layout and orphaned references.** `git rm app/src/main/res/layout/activity_main.xml`. Then:

Run: `grep -rn "activity_main\|R.id.log_view\|R.id.btn_connect\|R.id.et_destination" app/src/main/java`
Expected: no output.

- [ ] **Step 9: Build + suite.** Run: `./gradlew :app:assembleDebug $F` then `./gradlew :app:testDebugUnitTest $F`
Expected: build succeeds; only the known YunmoFrameTest fails. Unresolved icon names (e.g. `TwoWheeler`, `Garage`) → pick the nearest existing `Icons.Rounded.*` and ledger it.

- [ ] **Step 10: Commit.**

```bash
git add -A app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt app/src/main/java/dev/zanderp/opencfmoto/ui app/src/main/res/values/strings.xml app/src/main/res/layout
git commit -m "feat(ui): Compose home — 4 tabs (Ride, Map, Trips, Me), Logs and dialogs

MainActivity is now a thin Compose host; the connect flow runs in
ConnectionController. Old home layout removed.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Emulator verification, docs, bike checklist

**Files:**
- Modify: `docs/02-CURRENT-APP.md` (append section)

- [ ] **Step 1: Emulator run.** Build `./gradlew :app:assembleRelease -Pabi=x86_64 $F`, boot the AVD `ocm_test` (`ANDROID_AVD_HOME=F:\opencfmoto-gradle\avd`), install, and check with screenshots (`adb exec-out screencap -p`), in **light** (`adb shell cmd uimode night no`) and **dark** (`adb shell cmd uimode night yes`):
  1. Ride tab: title, chip "Ready", "Connect to …" or "Scan your bike", 4 tiles; Dash view tile dimmed when idle.
  2. Bottom tabs switch; Back from Map/Trips/Me returns to Ride; Back on Ride leaves the app.
  3. Map tab: Open map opens the Map hub; typing a place + Go opens the hub search (nothing projected).
  4. Trips tab: both cards open their screens. Me tab: Garage, Settings, Logs (Share opens the chooser, Clear empties), About, Report a problem dialog, Check for update toast.
  5. Rotate on Ride with the Report dialog open → dialog still shown.
  6. Old screens (Settings, Garage) follow light/dark; no purple buttons.
  7. `adb logcat -d -b crash | grep dev.zanderp.opencfmoto` → empty.

- [ ] **Step 2: Docs.** Append to `docs/02-CURRENT-APP.md`:

```markdown

## Compose redesign — Part 1 (foundation)

Spec: `docs/superpowers/specs/2026-09-30-compose-redesign-part1-foundation-design.md`.

- `MainActivity` is a thin Compose host (lifecycle/intents/launchers + `UiPort`); the connect flow is
  `connect/ConnectionController` (orchestration, unit-tested with a fake engine) over
  `connect/RealConnectionEngine` (Android bodies moved verbatim from the old MainActivity).
- UI: `ui/design` (OcmTheme light/dark tokens, components), `ui/shell/AppShell` (bottom tabs Ride · Map ·
  Trips · Me), `ui/ride`, `ui/tabs`, `ui/logs`, `ui/dialog`.
- One DayNight theme; View screens use the same tokens (`values/` light, `values-night/` dark). Dash-drawn UI
  uses fixed `dash_*` colours.
- Parts 2–5 replace Ride hero, Me/Settings, Trips, Map with Compose screens.
```

Commit: `git add docs/02-CURRENT-APP.md && git commit -m "docs: Compose redesign part 1" ` (with trailer).

- [ ] **Step 3: Bike checklist (owner).** With the new ARM release APK:
  1. Connect → Android Auto on the dash. Share Logs and compare with a pre-redesign log: same `[state]` sequence and `→` lines.
  2. Stop; auto-connect on launch; park (bike Wi-Fi off > 3 min) → "Bike reconnected" notification tap → resumes.
  3. Bluetooth trigger device connects → app starts Connect.
  4. Commute navigation fires once after Connect (not after resume).
  5. Official CFMoto app open → rival dialog → App settings → Force stop → back → auto-retry.
  6. Map on the dash with the phone in light mode → dash widgets still dark.
