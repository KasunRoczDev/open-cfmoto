# Rider Metrics Strip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a band of live rider metrics (next turn, speed, altitude, heading) under Android Auto on the bike dash, with Android Auto laid out above it.

**Architecture:** The pure Kotlin units (layout math, formatting, nav-message parsing) are unit-tested on the JVM. A coordinator (`MetricsStrip`) listens to GPS, formats values, and renders a small `Bitmap` with Android `Canvas`, only when a displayed value changes. The existing GL `AaCompositor` shrinks the Android Auto viewport by the band height and pastes the strip bitmap as a texture into the band. Phase 2 advertises Android Auto's navigation-status service and feeds parsed turns into the same coordinator.

**Tech Stack:** Kotlin, Android SDK (minSdk 29, targetSdk 36), OpenGL ES 2.0 via EGL14/GLES20, protobuf-java 3.25 (generated classes under `aa/proto`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-rider-metrics-strip-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto` (AA code under `dev.zanderp.opencfmoto.aa`). New Kotlin files start with the 3-line SPDX/AGPL header used in `BikeScope.kt`.
- Strip is **off by default**. Heights are Small 48 px, Medium 64 px, Large 80 px in dash pixels, clamped to `≥ 32 px` and `≤ canvasH / 4`. Disabled when `canvasH < 128`.
- UI decision (implements the spec's "switch + height" as one selector): **Metrics strip: Off · Small · Medium · Large**, default Off. Turn info (phase 2) is a separate On/Off selector, default Off.
- Settings are per bike via `BikeScope`, stored in SharedPreferences file `opencfmoto_bike`, and take effect on the next Connect.
- Speed/altitude are `--` when the last fix is older than **5000 ms**. Heading only updates above **5 km/h**. Altitude rounds to 5 m / 10 ft. Units follow `MapPrefs.units()`.
- Turn distance: metric `< 1000 m` → `"350 m"` (10 m steps), else `"1.2 km"`. Imperial `< 0.1 mi` → `"500 ft"` (50 ft steps), else `"0.3 mi"`. Always `Locale.US` number formatting.
- **Pure units must not call `LogBus`, `AaLog`, or any `android.*` API.** `LogBus` uses `android.util.Log`, which throws in JVM unit tests. Logging lives only in the Android-facing classes.
- Log tags: `[STRIP]` for the strip, `[NAV]` for turn info. Every meaningful step logs one line.
- With the strip Off and turn info Off, the video output and AA handshake must be unchanged.
- Strip failures never propagate into `AaCompositor.drawFrame()`. Catch, log once, leave the band black.
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Build/test commands run from the repo root in Git Bash: `./gradlew ...`. They require `local.properties` with `sdk.dir` (see Task 1, Step 0).

## Review Focus

1. **Touch on the strip band (touch dashes):** a tap inside the band must not reach Android Auto at shifted coordinates. `mapCanvasToSource` must return null. Pinned by `MetricsStripLayoutTest.touchInsideBandIsInStrip` (Task 1) + wiring in Task 5.
2. **Negative or large altitude values** (below sea level, 4-digit): no `-0`, correct rounding of negatives. Pinned by `RiderMetricsTest.altitudeRoundsNegativeAndLarge` (Task 2).
3. **No GPS permission / GPS off:** the strip still renders with `--`, and AA keeps running. Pinned by `RiderMetricsTest.noFixShowsDashes` (Task 2) + bike step in Task 7.
4. **Reconnect / park-resume cycles:** `MetricsStrip.start` must be idempotent (no duplicate GPS listeners or executors). Pinned by the `stop()`-first design in Task 6 + the reconnect bike step in Task 7.
5. **First frames before the first strip bitmap arrives:** the band shows black, and there's no GL error from drawing an unset texture. Pinned by the `stripReady` guard in Task 5 + the Task 7 log check (no `[STRIP] … failed`).

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripLayout.kt` | Create | Pure geometry: band size, cell rects, GL placement, touch test, AA panel shape |
| `app/src/main/java/dev/zanderp/opencfmoto/NextTurn.kt` | Create | `TurnKind`, `TurnSide`, `NextTurn` model |
| `app/src/main/java/dev/zanderp/opencfmoto/RiderMetrics.kt` | Create | `RiderMetrics` snapshot, `StripValues`, `MetricsFormat` (pure formatting) |
| `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt` | Create | `StripHeight` enum + per-bike prefs (height; turn info in phase 2) |
| `app/src/main/java/dev/zanderp/opencfmoto/MetricsSource.kt` | Create | GPS `LocationListener` wrapper |
| `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripRenderer.kt` | Create | Draws `StripValues` into a `Bitmap` (text + turn arrows) |
| `app/src/main/java/dev/zanderp/opencfmoto/MetricsStrip.kt` | Create | Coordinator: GPS → metrics → render on change → compositor sink |
| `app/src/main/java/dev/zanderp/opencfmoto/aa/NavTurnTracker.kt` | Create (phase 2) | Pure parser/state for AA nav messages → `NextTurn?` |
| `app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt` | Modify | Band inset, strip texture upload + draw, touch exclusion |
| `app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt` | Modify | Resolve band height, start/stop `MetricsStrip` |
| `app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt` | Modify | `aaMarginsFor` uses panel height minus band |
| `app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt` | Modify | Selectors for strip height (and turn info in phase 2) |
| `app/src/main/res/layout/activity_setup.xml` | Modify | Selector rows |
| `app/src/main/res/values/strings.xml` | Modify | New strings |
| `app/src/main/java/dev/zanderp/opencfmoto/BikeProfile.kt` | Modify (phase 2) | `BikeProfileHolder.aaTurnInfo` |
| `app/src/main/java/dev/zanderp/opencfmoto/AppSettings.kt` | Modify (phase 2) | Sync `aaTurnInfo` in `applyToHolder` |
| `app/src/main/java/dev/zanderp/opencfmoto/aa/ServiceDiscoveryResponse.kt` | Modify (phase 2) | Advertise nav-status service when opted in |
| `app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt` | Modify (phase 2) | `AapControlNav` + route `Channel.ID_NAV` |
| Tests: `app/src/test/java/dev/zanderp/opencfmoto/MetricsStripLayoutTest.kt`, `RiderMetricsTest.kt`, `StripAaMarginsTest.kt`, `aa/NavTurnTrackerTest.kt` | Create | Unit tests |
| `docs/02-CURRENT-APP.md`, `README.md` | Modify | Feature docs |

---

# Phase 1 — Strip with GPS metrics

### Task 1: Strip layout math

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripLayout.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/MetricsStripLayoutTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class StripRect(val x: Int, val y: Int, val w: Int, val h: Int)`
  - `object MetricsStripLayout` with `MIN_PX = 32`, `MIN_CANVAS_H = 128`, `internal const val STRIP_AT_GL_BOTTOM = true`, and:
    - `fun resolvePx(requestedPx: Int, canvasH: Int): Int`
    - `fun stripRect(canvasW: Int, canvasH: Int, stripPx: Int): StripRect`
    - `fun glStripY(canvasH: Int, stripPx: Int): Int`
    - `fun glAaShift(stripPx: Int): Int`
    - `fun isInStrip(cy: Int, canvasH: Int, stripPx: Int): Boolean`
    - `fun panelForAa(panelW: Int, panelH: Int, stripPx: Int): Pair<Int, Int>`
    - `fun cells(stripW: Int, stripH: Int, hasTurn: Boolean): List<StripRect>`

- [ ] **Step 0: Confirm the build works before changing anything**

`local.properties` must exist in the repo root with the SDK path. On Windows, for example:

```
sdk.dir=C\:\\Users\\user\\AppData\\Local\\Android\\Sdk
```

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. If it fails for a reason unrelated to this feature, stop and report. Don't fix unrelated tests.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsStripLayoutTest {
    @Test
    fun resolvePxKeepsRequestedWhenItFits() {
        assertEquals(64, MetricsStripLayout.resolvePx(64, 384))
        assertEquals(80, MetricsStripLayout.resolvePx(80, 944))
    }

    @Test
    fun resolvePxClampsToQuarterAndMinimum() {
        assertEquals(50, MetricsStripLayout.resolvePx(80, 200))   // 200 / 4
        assertEquals(32, MetricsStripLayout.resolvePx(20, 384))   // below MIN_PX
        assertEquals(32, MetricsStripLayout.resolvePx(64, 128))   // exactly MIN_CANVAS_H
    }

    @Test
    fun resolvePxZeroWhenOffOrCanvasTooSmall() {
        assertEquals(0, MetricsStripLayout.resolvePx(0, 384))
        assertEquals(0, MetricsStripLayout.resolvePx(64, 127))
    }

    @Test
    fun stripRectSitsAtBottomInTopOriginCoords() {
        assertEquals(StripRect(0, 320, 800, 64), MetricsStripLayout.stripRect(800, 384, 64))
    }

    @Test
    fun glPlacementPutsBandAtGlBottomAndShiftsAa() {
        assertTrue(MetricsStripLayout.STRIP_AT_GL_BOTTOM)
        assertEquals(0, MetricsStripLayout.glStripY(384, 64))
        assertEquals(64, MetricsStripLayout.glAaShift(64))
        assertEquals(0, MetricsStripLayout.glAaShift(0))
    }

    @Test
    fun touchInsideBandIsInStrip() {
        assertTrue(MetricsStripLayout.isInStrip(320, 384, 64))
        assertTrue(MetricsStripLayout.isInStrip(383, 384, 64))
        assertFalse(MetricsStripLayout.isInStrip(319, 384, 64))
        assertFalse(MetricsStripLayout.isInStrip(383, 384, 0))
    }

    @Test
    fun panelForAaSubtractsBandWithFloor() {
        assertEquals(800 to 320, MetricsStripLayout.panelForAa(800, 384, 64))
        assertEquals(800 to 384, MetricsStripLayout.panelForAa(800, 384, 0))
        assertEquals(800 to 16, MetricsStripLayout.panelForAa(800, 20, 64))
    }

    @Test
    fun fourEqualCellsWithTurn() {
        val c = MetricsStripLayout.cells(800, 64, hasTurn = true)
        assertEquals(
            listOf(StripRect(0, 0, 200, 64), StripRect(200, 0, 200, 64), StripRect(400, 0, 200, 64), StripRect(600, 0, 200, 64)),
            c,
        )
    }

    @Test
    fun speedTakesTwoUnitsWithoutTurn() {
        val c = MetricsStripLayout.cells(800, 64, hasTurn = false)
        assertEquals(listOf(StripRect(0, 0, 400, 64), StripRect(400, 0, 200, 64), StripRect(600, 0, 200, 64)), c)
    }

    @Test
    fun lastCellAbsorbsRemainder() {
        val c = MetricsStripLayout.cells(803, 48, hasTurn = true)
        assertEquals(803, c.sumOf { it.w })
        assertEquals(203, c.last().w)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.MetricsStripLayoutTest"`
Expected: compilation FAILS with `Unresolved reference: MetricsStripLayout`.

- [ ] **Step 3: Write the implementation**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Axis-aligned rectangle in pixels. Pure, so layout math is unit-testable without Android. */
data class StripRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Geometry for the rider metrics strip: the band reserved under Android Auto on the bike dash.
 * Canvas coordinates are top-left origin; GL placement goes through [glStripY]/[glAaShift] only.
 */
object MetricsStripLayout {
    const val MIN_PX = 32
    const val MIN_CANVAS_H = 128

    /**
     * GL is bottom-origin. The band is expected at the visual bottom = GL y 0. If a bike test shows
     * the strip at the top (and upside-down), flip this one flag — [AaCompositor] derives both the
     * band position and the texture orientation from it.
     */
    internal const val STRIP_AT_GL_BOTTOM = true

    /** Band height for [canvasH]: 0 when off or the canvas is too small, else clamped to 32..H/4. */
    fun resolvePx(requestedPx: Int, canvasH: Int): Int {
        if (requestedPx <= 0 || canvasH < MIN_CANVAS_H) return 0
        return requestedPx.coerceIn(MIN_PX, canvasH / 4)
    }

    fun stripRect(canvasW: Int, canvasH: Int, stripPx: Int): StripRect =
        StripRect(0, canvasH - stripPx, canvasW, stripPx)

    /** GL (bottom-origin) y of the band. */
    fun glStripY(canvasH: Int, stripPx: Int): Int = if (STRIP_AT_GL_BOTTOM) 0 else canvasH - stripPx

    /** GL y offset applied to the Android Auto viewport so it clears the band. */
    fun glAaShift(stripPx: Int): Int = if (STRIP_AT_GL_BOTTOM) stripPx else 0

    /** True when a top-origin canvas y (a dash touch) lands inside the band. */
    fun isInStrip(cy: Int, canvasH: Int, stripPx: Int): Boolean = stripPx > 0 && cy >= canvasH - stripPx

    /** Panel shape Android Auto should lay out for when the band takes [stripPx] off the bottom. */
    fun panelForAa(panelW: Int, panelH: Int, stripPx: Int): Pair<Int, Int> =
        panelW to (panelH - stripPx).coerceAtLeast(16)

    /**
     * Cell rects in strip-local coordinates. With a turn: 4 equal cells [turn, speed, altitude,
     * heading]. Without: [speed (2 units), altitude, heading]. The last cell absorbs the remainder.
     */
    fun cells(stripW: Int, stripH: Int, hasTurn: Boolean): List<StripRect> {
        val unit = stripW / 4
        val widths = if (hasTurn) {
            listOf(unit, unit, unit, stripW - 3 * unit)
        } else {
            listOf(2 * unit, unit, stripW - 3 * unit)
        }
        var x = 0
        return widths.map { w -> StripRect(x, 0, w, stripH).also { x += w } }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.MetricsStripLayoutTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStripLayout.kt app/src/test/java/dev/zanderp/opencfmoto/MetricsStripLayoutTest.kt
git commit -m "feat(strip): pure layout math for the rider metrics strip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Metrics model and formatting

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/NextTurn.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/RiderMetrics.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/RiderMetricsTest.kt`

**Interfaces:**
- Consumes: `MapUnits` (existing, `MapPrefs.kt`: `METRIC`, `IMPERIAL`).
- Produces:
  - `enum class TurnKind { STRAIGHT, SLIGHT, NORMAL, SHARP, U_TURN, ROUNDABOUT, MERGE, FORK, RAMP_ON, RAMP_OFF, DEPART, DESTINATION, UNKNOWN }`
  - `enum class TurnSide { LEFT, RIGHT, NONE }`
  - `data class NextTurn(val kind: TurnKind, val side: TurnSide, val exitNumber: Int? = null, val distanceM: Int? = null, val road: String? = null)`
  - `data class RiderMetrics(val speedMps: Float? = null, val altitudeM: Double? = null, val headingDeg: Float? = null, val fixAtMs: Long = 0L, val turn: NextTurn? = null)` with `fun withFix(speedMps: Float?, altitudeM: Double?, bearingDeg: Float?, atMs: Long): RiderMetrics`
  - `data class StripValues(val speed: String, val speedUnit: String, val altitude: String, val altitudeUnit: String, val compass: String, val degrees: String, val turn: NextTurn?, val turnDistance: String, val turnRoad: String)`
  - `object MetricsFormat` with `STALE_MS = 5_000L`, `HEADING_MIN_KMH = 5f`, `NONE = "--"`, `fun values(m: RiderMetrics, units: MapUnits, nowMs: Long): StripValues`, `fun speed(mps: Float, imperial: Boolean): String`, `fun altitude(meters: Double, imperial: Boolean): String`, `fun compass(deg: Float): String`, `fun degrees(deg: Float): String`, `fun turnDistance(meters: Int, imperial: Boolean): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RiderMetricsTest {
    private val now = 100_000L

    @Test
    fun metricSpeedAltitudeHeading() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 412.4, headingDeg = 45f, fixAtMs = now - 1_000)
        val v = MetricsFormat.values(m, MapUnits.METRIC, now)
        assertEquals("72", v.speed)
        assertEquals("km/h", v.speedUnit)
        assertEquals("410", v.altitude)
        assertEquals("m", v.altitudeUnit)
        assertEquals("NE", v.compass)
        assertEquals("45°", v.degrees)
    }

    @Test
    fun imperialUnits() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 412.4, headingDeg = 45f, fixAtMs = now)
        val v = MetricsFormat.values(m, MapUnits.IMPERIAL, now)
        assertEquals("45", v.speed)          // 20 m/s = 44.7 mph
        assertEquals("mph", v.speedUnit)
        assertEquals("1350", v.altitude)     // 1353 ft → nearest 10
        assertEquals("ft", v.altitudeUnit)
    }

    @Test
    fun noFixShowsDashes() {
        val v = MetricsFormat.values(RiderMetrics(), MapUnits.METRIC, now)
        assertEquals("--", v.speed)
        assertEquals("--", v.altitude)
        assertEquals("--", v.compass)
        assertEquals("", v.degrees)
    }

    @Test
    fun staleFixShowsDashesButKeepsHeading() {
        val m = RiderMetrics(speedMps = 20f, altitudeM = 100.0, headingDeg = 90f, fixAtMs = now - 5_001)
        val v = MetricsFormat.values(m, MapUnits.METRIC, now)
        assertEquals("--", v.speed)
        assertEquals("--", v.altitude)
        assertEquals("E", v.compass)
    }

    @Test
    fun fixExactlyAtStaleLimitIsFresh() {
        val m = RiderMetrics(speedMps = 10f, fixAtMs = now - 5_000)
        assertEquals("36", MetricsFormat.values(m, MapUnits.METRIC, now).speed)
    }

    @Test
    fun altitudeRoundsNegativeAndLarge() {
        assertEquals("-30", MetricsFormat.altitude(-32.0, imperial = false))
        assertEquals("0", MetricsFormat.altitude(-2.0, imperial = false))
        assertEquals("8850", MetricsFormat.altitude(8848.9, imperial = false))
        assertEquals("-100", MetricsFormat.altitude(-30.0, imperial = true))   // -98.4 ft
    }

    @Test
    fun speedNeverNegative() {
        assertEquals("0", MetricsFormat.speed(-0.4f, imperial = false))
    }

    @Test
    fun compassPointsAndWrap() {
        assertEquals("N", MetricsFormat.compass(0f))
        assertEquals("N", MetricsFormat.compass(359f))
        assertEquals("N", MetricsFormat.compass(22.4f))
        assertEquals("NE", MetricsFormat.compass(22.5f))
        assertEquals("S", MetricsFormat.compass(180f))
        assertEquals("NW", MetricsFormat.compass(-45f))
        assertEquals("0°", MetricsFormat.degrees(359.7f))
    }

    @Test
    fun headingHoldsWhenSlow() {
        val moving = RiderMetrics().withFix(speedMps = 10f, altitudeM = 5.0, bearingDeg = 90f, atMs = 1L)
        assertEquals(90f, moving.headingDeg!!, 0f)
        val slow = moving.withFix(speedMps = 1f, altitudeM = 5.0, bearingDeg = 270f, atMs = 2L)
        assertEquals(90f, slow.headingDeg!!, 0f)
        assertEquals(2L, slow.fixAtMs)
        val noBearing = moving.withFix(speedMps = 10f, altitudeM = 5.0, bearingDeg = null, atMs = 3L)
        assertEquals(90f, noBearing.headingDeg!!, 0f)
    }

    @Test
    fun turnDistanceMetric() {
        assertEquals("350 m", MetricsFormat.turnDistance(347, imperial = false))
        assertEquals("990 m", MetricsFormat.turnDistance(994, imperial = false))
        assertEquals("1.0 km", MetricsFormat.turnDistance(996, imperial = false))
        assertEquals("1.2 km", MetricsFormat.turnDistance(1234, imperial = false))
    }

    @Test
    fun turnDistanceImperial() {
        assertEquals("500 ft", MetricsFormat.turnDistance(150, imperial = true))   // 492 ft
        assertEquals("0.3 mi", MetricsFormat.turnDistance(483, imperial = true))
    }

    @Test
    fun turnFieldsAndEquality() {
        val t = NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 347, road = "Main St")
        val a = MetricsFormat.values(RiderMetrics(turn = t), MapUnits.METRIC, now)
        assertEquals("350 m", a.turnDistance)
        assertEquals("Main St", a.turnRoad)
        assertEquals(a, MetricsFormat.values(RiderMetrics(turn = t), MapUnits.METRIC, now))
        assertNotEquals(a, MetricsFormat.values(RiderMetrics(turn = t.copy(distanceM = 200)), MapUnits.METRIC, now))
        val none = MetricsFormat.values(RiderMetrics(), MapUnits.METRIC, now)
        assertEquals("", none.turnDistance)
        assertEquals("", none.turnRoad)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.RiderMetricsTest"`
Expected: compilation FAILS with `Unresolved reference: RiderMetrics`.

- [ ] **Step 3: Write `NextTurn.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Arrow family drawn in the strip's turn cell. */
enum class TurnKind { STRAIGHT, SLIGHT, NORMAL, SHARP, U_TURN, ROUNDABOUT, MERGE, FORK, RAMP_ON, RAMP_OFF, DEPART, DESTINATION, UNKNOWN }

enum class TurnSide { LEFT, RIGHT, NONE }

/** The next maneuver from Android Auto navigation (phase 2). Distance is raw meters; formatting is ours. */
data class NextTurn(
    val kind: TurnKind,
    val side: TurnSide,
    val exitNumber: Int? = null,
    val distanceM: Int? = null,
    val road: String? = null,
)
```

- [ ] **Step 4: Write `RiderMetrics.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Latest rider values for the metrics strip. Immutable; [fixAtMs] is `SystemClock.elapsedRealtime`
 * of the last GPS fix (0 = none yet). Pure — no Android calls, so it is unit-tested on the JVM.
 */
data class RiderMetrics(
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
    val headingDeg: Float? = null,
    val fixAtMs: Long = 0L,
    val turn: NextTurn? = null,
) {
    /** Apply a GPS fix. Heading only follows the GPS bearing while moving; otherwise it holds. */
    fun withFix(speedMps: Float?, altitudeM: Double?, bearingDeg: Float?, atMs: Long): RiderMetrics {
        val moving = speedMps != null && speedMps * 3.6f > MetricsFormat.HEADING_MIN_KMH
        return copy(
            speedMps = speedMps,
            altitudeM = altitudeM,
            headingDeg = if (moving && bearingDeg != null) bearingDeg else headingDeg,
            fixAtMs = atMs,
        )
    }
}

/** Display strings for one strip frame. Data class equality = "nothing visible changed". */
data class StripValues(
    val speed: String,
    val speedUnit: String,
    val altitude: String,
    val altitudeUnit: String,
    val compass: String,
    val degrees: String,
    val turn: NextTurn?,
    val turnDistance: String,
    val turnRoad: String,
)

object MetricsFormat {
    const val STALE_MS = 5_000L
    const val HEADING_MIN_KMH = 5f
    const val NONE = "--"
    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun values(m: RiderMetrics, units: MapUnits, nowMs: Long): StripValues {
        val fresh = m.fixAtMs > 0 && nowMs - m.fixAtMs <= STALE_MS
        val imperial = units == MapUnits.IMPERIAL
        val turn = m.turn
        return StripValues(
            speed = if (fresh && m.speedMps != null) speed(m.speedMps, imperial) else NONE,
            speedUnit = if (imperial) "mph" else "km/h",
            altitude = if (fresh && m.altitudeM != null) altitude(m.altitudeM, imperial) else NONE,
            altitudeUnit = if (imperial) "ft" else "m",
            compass = m.headingDeg?.let { compass(it) } ?: NONE,
            degrees = m.headingDeg?.let { degrees(it) } ?: "",
            turn = turn,
            turnDistance = turn?.distanceM?.let { turnDistance(it, imperial) } ?: "",
            turnRoad = turn?.road.orEmpty(),
        )
    }

    fun speed(mps: Float, imperial: Boolean): String =
        (mps * if (imperial) 2.23694f else 3.6f).roundToInt().coerceAtLeast(0).toString()

    fun altitude(meters: Double, imperial: Boolean): String =
        if (imperial) ((meters * 3.28084 / 10.0).roundToLong() * 10).toString()
        else ((meters / 5.0).roundToLong() * 5).toString()

    private fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun compass(deg: Float): String = COMPASS[((normalize(deg) + 22.5f) / 45f).toInt() % 8]

    fun degrees(deg: Float): String = "${normalize(deg).roundToInt() % 360}°"

    fun turnDistance(meters: Int, imperial: Boolean): String {
        if (!imperial) {
            val rounded = (meters / 10.0).roundToInt() * 10
            return if (rounded < 1000) "$rounded m" else String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
        val miles = meters / 1609.344
        return if (miles < 0.1) "${(meters * 3.28084 / 50.0).roundToInt() * 50} ft"
        else String.format(Locale.US, "%.1f mi", miles)
    }
}
```

Note: `(-0.4).roundToLong()` is `0L`, so no `-0` appears. `-6.4.roundToLong()` is `-6L`, giving `-30`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.RiderMetricsTest"`
Expected: PASS (12 tests). If `imperialUnits` altitude is off by 10, recompute: 412.4 m × 3.28084 = 1353.0 ft → `/10` = 135.3 → round 135 → `1350`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/NextTurn.kt app/src/main/java/dev/zanderp/opencfmoto/RiderMetrics.kt app/src/test/java/dev/zanderp/opencfmoto/RiderMetricsTest.kt
git commit -m "feat(strip): rider metrics model and display formatting

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Strip setting (prefs + Setup UI)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt`
- Modify: `app/src/main/res/values/strings.xml` (append before `</resources>`)
- Modify: `app/src/main/res/layout/activity_setup.xml` (insert before the `<TextView` whose text is `@string/setup_disable_touchscreen`, currently around line 626)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt` (listeners near line 123–127, setter near `setAaDpi` ~line 305, `refreshOptions()` ~line 468)

**Interfaces:**
- Consumes: `BikeScope.getString/putString` (existing).
- Produces:
  - `enum class StripHeight(val px: Int, @StringRes val labelRes: Int) { OFF(0,…), SMALL(48,…), MEDIUM(64,…), LARGE(80,…) }`
  - `object MetricsStripPrefs { fun height(ctx: Context): StripHeight; fun setHeight(ctx: Context, h: StripHeight) }`

- [ ] **Step 1: Add strings** (append inside `<resources>` in `values/strings.xml`)

```xml
    <string name="setup_metrics_strip">Metrics strip under Android Auto</string>
    <string name="setup_metrics_strip_desc">Speed, altitude and heading in a band below the map on the dash. Android Auto is drawn above it. Applies next connect.</string>
    <string name="pref_strip_off">Off</string>
    <string name="pref_strip_small">Small</string>
    <string name="pref_strip_medium">Medium</string>
    <string name="pref_strip_large">Large</string>
    <string name="setup_toast_metrics_strip">Metrics strip: %1$s</string>
```

- [ ] **Step 2: Write `MetricsStripPrefs.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/** Rider choice for the metrics strip under Android Auto. [px] is the requested band in dash pixels. */
enum class StripHeight(val px: Int, @StringRes val labelRes: Int) {
    OFF(0, R.string.pref_strip_off),
    SMALL(48, R.string.pref_strip_small),
    MEDIUM(64, R.string.pref_strip_medium),
    LARGE(80, R.string.pref_strip_large),
}

/** Per-bike metrics-strip settings (scoped via [BikeScope]). Read at Connect; changes apply next connect. */
object MetricsStripPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_HEIGHT = "metrics_strip_height"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun height(ctx: Context): StripHeight {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_HEIGHT, StripHeight.OFF.name)
        return runCatching { StripHeight.valueOf(name!!) }.getOrDefault(StripHeight.OFF)
    }

    fun setHeight(ctx: Context, h: StripHeight) {
        BikeScope.putString(prefs(ctx), ctx, KEY_HEIGHT, h.name)
    }
}
```

- [ ] **Step 3: Add the selector to `activity_setup.xml`**

Insert immediately before the `<TextView` element whose `android:text="@string/setup_disable_touchscreen"`:

```xml
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:text="@string/setup_metrics_strip"
                    android:textColor="@color/text_primary"
                    android:textSize="16sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:layout_marginBottom="6dp"
                    android:text="@string/setup_metrics_strip_desc"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_off"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_off" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_small"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_small" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_medium"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_medium" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_large"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_large" />
                </LinearLayout>
```

- [ ] **Step 4: Wire it in `SetupActivity.kt`**

After the line `findViewById<MaterialButton>(R.id.dpi_320).setOnClickListener { setAaDpi(320) }`, add:

```kotlin
        findViewById<MaterialButton>(R.id.strip_off).setOnClickListener { setStripHeight(StripHeight.OFF) }
        findViewById<MaterialButton>(R.id.strip_small).setOnClickListener { setStripHeight(StripHeight.SMALL) }
        findViewById<MaterialButton>(R.id.strip_medium).setOnClickListener { setStripHeight(StripHeight.MEDIUM) }
        findViewById<MaterialButton>(R.id.strip_large).setOnClickListener { setStripHeight(StripHeight.LARGE) }
```

Directly after the `private fun setAaDpi(dpi: Int?) { … }` function, add:

```kotlin
    private fun setStripHeight(h: StripHeight) {
        MetricsStripPrefs.setHeight(this, h)
        refreshOptions()
        toast(getString(R.string.setup_toast_metrics_strip, getString(h.labelRes)))
    }
```

In `refreshOptions()`, directly after the `highlight<Int?>(dpi, … R.id.dpi_320 to 320, )` call, add:

```kotlin
        highlight(MetricsStripPrefs.height(this),
            R.id.strip_off to StripHeight.OFF,
            R.id.strip_small to StripHeight.SMALL,
            R.id.strip_medium to StripHeight.MEDIUM,
            R.id.strip_large to StripHeight.LARGE)
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt app/src/main/res/layout/activity_setup.xml app/src/main/res/values/strings.xml
git commit -m "feat(strip): per-bike metrics strip setting in Setup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Android Auto lays out above the band

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt` (`aaMarginsFor`, ~line 258)
- Test: `app/src/test/java/dev/zanderp/opencfmoto/StripAaMarginsTest.kt`

**Interfaces:**
- Consumes: `MetricsStripLayout.resolvePx`, `MetricsStripLayout.panelForAa` (Task 1). `MetricsStripPrefs.height` (Task 3). Existing `AaMargins.forAspect(coded: AaVideoSpec, targetW: Int, targetH: Int)`, `AaVideoSpec(AaResolution, dpi)`.
- Produces: `VideoPrefs.aaMarginsFor(ctx, spec, ssid)`, same signature, now band-aware.

- [ ] **Step 1: Write the failing test** (pins the math `aaMarginsFor` will use)

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class StripAaMarginsTest {
    private val landscape = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    private val portrait = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    private fun marginsFor(spec: AaVideoSpec, panelW: Int, panelH: Int, requestedPx: Int): AaMargins {
        val stripPx = MetricsStripLayout.resolvePx(requestedPx, panelH)
        val (w, h) = MetricsStripLayout.panelForAa(panelW, panelH, stripPx)
        return AaMargins.forAspect(spec, w, h)
    }

    @Test
    fun landscapeWithoutStripUnchanged() {
        assertEquals(AaMargins(0, 96), marginsFor(landscape, 800, 384, 0))
    }

    @Test
    fun landscapeMediumStripShortensAa() {
        // 800x384 panel - 64 band = 800x320 → AA usable 800x320 → 160 px height margin.
        assertEquals(AaMargins(0, 160), marginsFor(landscape, 800, 384, 64))
    }

    @Test
    fun portraitMediumStrip() {
        // 800x944 - 64 = 800x880; coded 720x1280 → usable height round(720*880/800)=792 → margin 488.
        assertEquals(AaMargins(0, 488), marginsFor(portrait, 800, 944, 64))
    }

    @Test
    fun oversizedRequestIsClampedByPanel() {
        // Large 80 on a 200-tall panel clamps to 50 → 800x150.
        assertEquals(MetricsStripLayout.panelForAa(800, 200, 50), 800 to 150)
        assertEquals(AaMargins.forAspect(landscape, 800, 150), marginsFor(landscape, 800, 200, 80))
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.StripAaMarginsTest"`
Expected: PASS (these pin the pure math from Tasks 1 and existing `AaMargins`). If any value differs, **stop**. Don't change the test to match. Re-derive by hand using `AaMargins.forAspect` in `BikeProfile.kt:49` and report the discrepancy.

- [ ] **Step 3: Change `aaMarginsFor` in `VideoPrefs.kt`**

Replace the whole function with:

```kotlin
    /** Margins to advertise for the current AA [spec], or [AaMargins.NONE]. */
    fun aaMarginsFor(ctx: Context, spec: AaVideoSpec, ssid: String? = null): AaMargins {
        val mode = matchAspectMode(ctx)
        val stripReq = MetricsStripPrefs.height(ctx).px
        val panel = when (mode) {
            MatchAspectMode.OFF -> {
                // OFF means "no margins" — except the metrics strip still needs AA laid out for the
                // shorter area above the band, so use the known panel when the strip is on.
                val detected = if (stripReq > 0) detectedPanelSize(ctx, ssid) else null
                if (detected == null) {
                    LogBus.log("[match-aspect] OFF — margins 0")
                    return AaMargins.NONE
                }
                detected
            }
            MatchAspectMode.MANUAL -> aspectTarget(ctx)
            MatchAspectMode.AUTO -> {
                val detected = detectedPanelSize(ctx, ssid)
                if (detected == null) {
                    LogBus.log("[match-aspect] AUTO — no panel size yet (no DashMemory / profile.panelSize); margins 0 until learned")
                    return AaMargins.NONE
                }
                detected
            }
        }
        val stripPx = MetricsStripLayout.resolvePx(stripReq, panel.second)
        val (targetW, targetH) = MetricsStripLayout.panelForAa(panel.first, panel.second, stripPx)
        val margins = AaMargins.forAspect(spec, targetW, targetH)
        val stripNote = if (stripPx > 0) " strip −${stripPx}px" else ""
        LogBus.log(
            "[match-aspect] $mode panel ${panel.first}x${panel.second}$stripNote → " +
                "AA ${spec.width}x${spec.height} margins ${margins.marginW}x${margins.marginH}",
        )
        return margins
    }
```

- [ ] **Step 4: Build and run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt app/src/test/java/dev/zanderp/opencfmoto/StripAaMarginsTest.kt
git commit -m "feat(strip): lay Android Auto out above the metrics band

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Compositor reserves the band and draws the strip texture

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt`

**Interfaces:**
- Consumes: `MetricsStripLayout.glStripY`, `glAaShift`, `isInStrip`, `stripRect`, `STRIP_AT_GL_BOTTOM` (Task 1).
- Produces (public on `AaCompositor`):
  - `fun setStrip(px: Int)` — band height for the bike output (0 = off). Must be called **before** `setOutput`.
  - `fun setStripBitmap(bmp: Bitmap)` — uploads and redraws. Takes ownership and recycles `bmp`.

There's no JVM unit test for GL code. Verification is by build here plus the Task 7 bike test. Keep every change exactly as below.

- [ ] **Step 1: Add imports**

Add to the import list:

```kotlin
import android.graphics.Bitmap
import android.opengl.GLUtils
```

- [ ] **Step 2: Add fields** (directly after `@Volatile private var fitMode: ScreenFit = ScreenFit.FILL`)

```kotlin

    // Rider metrics strip: a band of [stripPx] dash pixels reserved under Android Auto on the BIKE
    // output only (never the phone preview). AA is viewported above it; the strip bitmap is pasted in.
    @Volatile private var stripPx = 0
    private var stripProgram = 0
    private var sPosition = 0
    private var sTexCoord = 0
    private var stripTexId = 0
    private var stripTexW = 0
    private var stripTexH = 0
    private var stripReady = false
    private var stripFailLogged = false

    // Strip quad: pos.xy + tex.uv. Bitmap row 0 is the visual top; pick v so it lands upright for the
    // GL orientation chosen in MetricsStripLayout.STRIP_AT_GL_BOTTOM.
    private val stripQuad: FloatBuffer = run {
        val vBottom = if (MetricsStripLayout.STRIP_AT_GL_BOTTOM) 1f else 0f
        val vTop = 1f - vBottom
        ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, vBottom,
                 1f, -1f, 1f, vBottom,
                -1f,  1f, 0f, vTop,
                 1f,  1f, 1f, vTop,
            ))
            position(0)
        }
    }
```

- [ ] **Step 3: Log strip placement in `setOutput`**

In `setOutput`, directly after the existing `log("[COMPOSITOR] output set …")` line, add:

```kotlin
                if (stripPx > 0) {
                    val r = MetricsStripLayout.stripRect(cw, ch, stripPx)
                    log("[STRIP] on canvas=${cw}x$ch strip=${stripPx}px rect(top-origin)=${r.x},${r.y},${r.w},${r.h} " +
                        "glY=${MetricsStripLayout.glStripY(ch, stripPx)} aaRect=${vpW}x$vpH @($vpX,$vpY)")
                }
```

- [ ] **Step 4: Add `setStrip` and `setStripBitmap`** (directly after `refreshMargins()`)

```kotlin
    /** Reserve a band of [px] dash pixels under Android Auto on the bike output (0 = off). Call before [setOutput]. */
    fun setStrip(px: Int) {
        handler.post {
            stripPx = px.coerceAtLeast(0)
            if (stripPx == 0) stripReady = false
            computeViewport()
        }
    }

    /** Upload a freshly rendered strip and re-emit a frame. Takes ownership of [bmp] (recycled here). */
    fun setStripBitmap(bmp: Bitmap) {
        handler.post {
            try {
                if (eglDisplay == EGL14.EGL_NO_DISPLAY || stripPx <= 0) return@post
                if (stripTexId == 0) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    stripTexId = ids[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripTexId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripTexId)
                }
                if (bmp.width != stripTexW || bmp.height != stripTexH) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    stripTexW = bmp.width
                    stripTexH = bmp.height
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                }
                stripReady = true
                if (hasContent) drawFrame()
            } catch (e: Exception) {
                logStripFailure("upload", e)
            } finally {
                bmp.recycle()
            }
        }
    }

    private fun logStripFailure(what: String, e: Exception) {
        if (stripFailLogged) return
        stripFailLogged = true
        log("[STRIP] $what failed: $e — band stays black, Android Auto unaffected")
    }
```

- [ ] **Step 5: Exclude the band in `computeViewport`**

Replace these four lines at the top of `computeViewport()`:

```kotlin
        val mT = ScreenMargins.top.coerceIn(0, canvasH)
        val mB = ScreenMargins.bottom.coerceIn(0, canvasH - mT)
```
and
```kotlin
        val areaH = (canvasH - mT - mB).coerceAtLeast(1)
```

with:

```kotlin
        // The metrics band (if any) comes off the canvas first; AA + rider margins share the rest.
        val aaCanvasH = (canvasH - stripPx).coerceAtLeast(1)
        val mT = ScreenMargins.top.coerceIn(0, aaCanvasH)
        val mB = ScreenMargins.bottom.coerceIn(0, aaCanvasH - mT)
```
and
```kotlin
        val areaH = (aaCanvasH - mT - mB).coerceAtLeast(1)
```

- [ ] **Step 6: Drop touches on the band in `mapCanvasToSource`**

As the first line of `mapCanvasToSource`'s body, add:

```kotlin
        if (MetricsStripLayout.isInStrip(cy, canvasH, stripPx)) return null
```

- [ ] **Step 7: Shift AA and draw the strip in `renderTo`**

In `renderTo`, replace:

```kotlin
        val clipW = if (target === windowSurface) canvasW else previewW
        val clipH = if (target === windowSurface) canvasH else previewH
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, 0, clipW, clipH)
        }

        GLES20.glViewport(vx, vy, vw, vh)
```

with:

```kotlin
        val isBike = target === windowSurface
        val clipW = if (isBike) canvasW else previewW
        val clipH = if (isBike) canvasH else previewH
        val band = if (isBike) stripPx else 0
        val shift = if (band > 0) MetricsStripLayout.glAaShift(band) else 0
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, shift, clipW, clipH - band)
        }

        GLES20.glViewport(vx, vy + shift, vw, vh)
```

Then replace:

```kotlin
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // Monotonic presentation time
```

with:

```kotlin
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        if (band > 0) {
            try {
                drawStrip(band)
            } catch (e: Exception) {
                logStripFailure("draw", e)
            }
        }

        // Monotonic presentation time
```

- [ ] **Step 8: Add `drawStrip`** (directly after `renderTo`)

```kotlin
    /** Paste the latest strip texture into the band. Band stays black (from glClear) until one exists. */
    private fun drawStrip(band: Int) {
        if (!stripReady || stripTexId == 0 || stripProgram == 0 || canvasW == 0) return
        val y = MetricsStripLayout.glStripY(canvasH, band)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, y, canvasW, band)
        GLES20.glViewport(0, y, canvasW, band)
        GLES20.glUseProgram(stripProgram)
        stripQuad.position(0)
        GLES20.glVertexAttribPointer(sPosition, 2, GLES20.GL_FLOAT, false, 16, stripQuad)
        GLES20.glEnableVertexAttribArray(sPosition)
        stripQuad.position(2)
        GLES20.glVertexAttribPointer(sTexCoord, 2, GLES20.GL_FLOAT, false, 16, stripQuad)
        GLES20.glEnableVertexAttribArray(sTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripTexId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(sPosition)
        GLES20.glDisableVertexAttribArray(sTexCoord)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
```

- [ ] **Step 9: Compile the strip shader program in `initGl`**

At the end of `initGl()` (after the OES texture parameters), add:

```kotlin

        val svs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """.trimIndent()
        val sfs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        // A strip shader failure must never take down the AA compositor: leave stripProgram = 0
        // (drawStrip then skips) and log once.
        try {
            stripProgram = linkProgram(svs, sfs)
            sPosition = GLES20.glGetAttribLocation(stripProgram, "aPosition")
            sTexCoord = GLES20.glGetAttribLocation(stripProgram, "aTexCoord")
        } catch (e: Exception) {
            stripProgram = 0
            logStripFailure("shader", e)
        }
```

- [ ] **Step 10: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt
git commit -m "feat(strip): compositor reserves the band and draws the strip texture

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: GPS source, renderer, coordinator, pipeline wiring

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MetricsSource.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripRenderer.kt`
- Create: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStrip.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt` (`configureBikeCanvas` ~line 262–308, `stop` ~line 724)

**Interfaces:**
- Consumes: `RiderMetrics`, `StripValues`, `MetricsFormat`, `NextTurn`, `TurnKind`, `TurnSide` (Task 2). `MetricsStripLayout` (Task 1). `MetricsStripPrefs.height`, `StripHeight.px` (Task 3). `AaCompositor.setStrip`, `setStripBitmap` (Task 5). `MapPrefs.units(ctx): MapUnits` (existing).
- Produces:
  - `class MetricsSource(ctx: Context, onFix: (Location) -> Unit) { fun start(): Boolean; fun stop() }`
  - `object MetricsStripRenderer { fun render(v: StripValues, w: Int, h: Int): Bitmap }`
  - `object MetricsStrip { fun start(ctx: Context, canvasW: Int, stripPx: Int, sink: (Bitmap) -> Unit, log: (String) -> Unit); fun stop(); fun setTurn(turn: NextTurn?) }`. Phase 2 calls `setTurn` from the AA transport thread.

- [ ] **Step 1: Write `MetricsSource.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * GPS feed for the metrics strip. Owns its own listener (TripRecorder only runs when trip logging
 * is on); Android shares the GPS hardware between listeners. Callbacks arrive on the main looper.
 */
class MetricsSource(
    private val ctx: Context,
    private val onFix: (Location) -> Unit,
) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Returns false when permission is missing or GPS is off (the strip then shows `--`). */
    fun start(): Boolean {
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) = onFix(location)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
```

- [ ] **Step 2: Write `MetricsStripRenderer.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one strip frame. Called only from the single `metrics-strip` thread, so the shared Paints
 * are safe. Returns a new bitmap each call; the compositor recycles it after upload (≤ 1/s).
 */
object MetricsStripRenderer {
    private const val LABEL_GREY = 0xFFAAAAAA.toInt()
    private const val DIVIDER = 0xFF333333.toInt()

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_GREY; textAlign = Paint.Align.CENTER
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val dividerPaint = Paint().apply { color = DIVIDER; strokeWidth = 2f }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun render(v: StripValues, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val hasTurn = v.turn != null
        val cells = MetricsStripLayout.cells(w, h, hasTurn)
        var i = 0
        if (hasTurn) drawTurn(c, cells[i++], v)
        drawValue(c, cells[i++], v.speed, v.speedUnit, if (hasTurn) 0.55f else 0.65f)
        drawValue(c, cells[i++], v.altitude, "ALT ${v.altitudeUnit}", 0.55f)
        drawValue(c, cells[i], v.compass, v.degrees, 0.55f)
        for (k in 1 until cells.size) {
            val x = cells[k].x.toFloat()
            c.drawLine(x, h * 0.15f, x, h * 0.85f, dividerPaint)
        }
        return bmp
    }

    private fun drawValue(c: Canvas, r: StripRect, value: String, label: String, frac: Float) {
        val cx = r.x + r.w / 2f
        valuePaint.textSize = r.h * frac
        labelPaint.textSize = r.h * 0.22f
        val valueBaseline = r.h * 0.08f + valuePaint.textSize * 0.9f
        c.drawText(fit(value, valuePaint, r.w * 0.92f), cx, valueBaseline, valuePaint)
        if (label.isNotEmpty()) c.drawText(fit(label, labelPaint, r.w * 0.92f), cx, r.h * 0.95f, labelPaint)
    }

    private fun drawTurn(c: Canvas, r: StripRect, v: StripValues) {
        val t = v.turn ?: return
        val box = min(r.h.toFloat(), r.w * 0.38f)
        val pad = box * 0.12f
        drawArrow(c, t, r.x + pad, (r.h - box) / 2f + pad, box - 2 * pad)
        val text = StripRect(r.x + box.toInt(), 0, r.w - box.toInt(), r.h)
        drawValue(c, text, v.turnDistance.ifEmpty { MetricsFormat.NONE }, v.turnRoad, 0.45f)
    }

    private fun drawArrow(c: Canvas, t: NextTurn, left: Float, top: Float, s: Float) {
        arrowStroke.strokeWidth = s * 0.12f
        val cx = left + s / 2f
        val bottom = top + s
        val mid = top + s * 0.5f
        when (t.kind) {
            TurnKind.DESTINATION -> {
                c.drawCircle(cx, mid, s * 0.22f, arrowFill)
                return
            }
            TurnKind.ROUNDABOUT -> {
                c.drawCircle(cx, mid, s * 0.28f, arrowStroke)
                t.exitNumber?.let { n ->
                    numberPaint.textSize = s * 0.34f
                    c.drawText(n.toString(), cx, mid + s * 0.12f, numberPaint)
                }
                return
            }
            TurnKind.U_TURN -> {
                val dir = if (t.side == TurnSide.RIGHT) 1f else -1f
                val x2 = cx + dir * s * 0.3f
                val path = Path().apply {
                    moveTo(cx, bottom)
                    lineTo(cx, top + s * 0.35f)
                    cubicTo(cx, top, x2, top, x2, top + s * 0.35f)
                    lineTo(x2, top + s * 0.55f)
                }
                c.drawPath(path, arrowStroke)
                arrowHead(c, x2, top + s * 0.8f, Math.PI.toFloat(), s)
                return
            }
            else -> Unit
        }
        val deg = when (t.kind) {
            TurnKind.SLIGHT, TurnKind.MERGE, TurnKind.FORK, TurnKind.RAMP_ON, TurnKind.RAMP_OFF -> 45f
            TurnKind.NORMAL -> 90f
            TurnKind.SHARP -> 135f
            else -> 0f
        }
        val sign = when (t.side) { TurnSide.LEFT -> -1f; TurnSide.RIGHT -> 1f; TurnSide.NONE -> 0f }
        val a = Math.toRadians((deg * sign).toDouble()).toFloat()
        val len = s * 0.42f
        val tipX = cx + sin(a) * len
        val tipY = mid - cos(a) * len
        val headLen = s * 0.22f
        val baseX = tipX - sin(a) * headLen
        val baseY = tipY + cos(a) * headLen
        val path = Path().apply {
            moveTo(cx, bottom)
            lineTo(cx, mid)
            lineTo(baseX, baseY)
        }
        c.drawPath(path, arrowStroke)
        arrowHead(c, tipX, tipY, a, s)
    }

    /** Filled triangle with its point at ([tipX],[tipY]) heading [angle] rad (0 = up, +π/2 = right). */
    private fun arrowHead(c: Canvas, tipX: Float, tipY: Float, angle: Float, s: Float) {
        val headLen = s * 0.22f
        val halfW = s * 0.16f
        val dx = sin(angle); val dy = -cos(angle)
        val bx = tipX - dx * headLen; val by = tipY - dy * headLen
        val px = cos(angle); val py = sin(angle)
        val p = Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px * halfW, by + py * halfW)
            lineTo(bx - px * halfW, by - py * halfW)
            close()
        }
        c.drawPath(p, arrowFill)
    }

    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var end = text.length
        val ell = p.measureText("…")
        while (end > 1 && p.measureText(text, 0, end) + ell > maxW) end--
        return text.substring(0, end) + "…"
    }
}
```

- [ ] **Step 3: Write `MetricsStrip.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Metrics strip coordinator for one Android Auto session: GPS ([MetricsSource]) and AA turns
 * ([setTurn]) update [RiderMetrics]; a single `metrics-strip` thread formats them and renders a
 * bitmap only when a displayed value changes (plus a 1 s tick so stale values fall back to `--`).
 * [start] is idempotent — it always stops a previous session first.
 */
object MetricsStrip {
    private const val TICK_MS = 1_000L

    private var executor: ScheduledExecutorService? = null
    private var source: MetricsSource? = null
    @Volatile private var sink: ((Bitmap) -> Unit)? = null
    @Volatile private var log: (String) -> Unit = {}
    @Volatile private var units = MapUnits.METRIC
    @Volatile private var stripW = 0
    @Volatile private var stripH = 0
    @Volatile private var metrics = RiderMetrics()
    private var lastValues: StripValues? = null   // metrics-strip thread only
    private var failLogged = false                // metrics-strip thread only

    @Synchronized
    fun start(ctx: Context, canvasW: Int, stripPx: Int, sink: (Bitmap) -> Unit, log: (String) -> Unit) {
        stop()
        val app = ctx.applicationContext
        this.sink = sink
        this.log = log
        units = MapPrefs.units(app)
        stripW = canvasW
        stripH = stripPx
        lastValues = null
        failLogged = false
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "metrics-strip").apply { isDaemon = true }
        }
        executor = ex
        ex.scheduleWithFixedDelay({ refresh() }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
        val src = MetricsSource(app) { loc -> onFix(loc) }
        source = src
        if (!src.start()) log("[STRIP] no GPS (permission or GPS off) — showing --")
        log("[STRIP] started ${canvasW}x$stripPx units=$units")
    }

    @Synchronized
    fun stop() {
        source?.stop()
        source = null
        executor?.shutdownNow()
        executor = null
        sink = null
        metrics = RiderMetrics()
    }

    /** Phase 2: next turn from Android Auto (null = no active route). Any thread. */
    fun setTurn(turn: NextTurn?) = update { it.copy(turn = turn) }

    private fun onFix(loc: Location) {
        val alt = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        update {
            it.withFix(
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                altitudeM = alt,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                atMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    private fun update(f: (RiderMetrics) -> RiderMetrics) {
        val ex: ScheduledExecutorService?
        synchronized(this) {
            metrics = f(metrics)
            ex = executor
        }
        try { ex?.execute { refresh() } } catch (_: RejectedExecutionException) {}
    }

    private fun refresh() {
        try {
            val out = sink ?: return
            val v = MetricsFormat.values(metrics, units, SystemClock.elapsedRealtime())
            if (v == lastValues) return
            val bmp = MetricsStripRenderer.render(v, stripW, stripH)
            lastValues = v
            out(bmp)
        } catch (e: Exception) {
            if (!failLogged) {
                failLogged = true
                log("[STRIP] render failed: $e — band stays black, Android Auto unaffected")
            }
        }
    }
}
```

- [ ] **Step 4: Wire into `VideoPipeline.configureBikeCanvas`**

In `configureBikeCanvas`, inside `if (surf != null) { … }`, replace:

```kotlin
            aaCompositor?.setOutput(surf, w, h, usableW, usableH, fit)
```

with:

```kotlin
            val stripReq = MetricsStripPrefs.height(context).px
            val stripPx = MetricsStripLayout.resolvePx(stripReq, h)
            if (stripReq > 0 && stripPx == 0) {
                log("[STRIP] canvas ${w}x$h too small (< ${MetricsStripLayout.MIN_CANVAS_H}px tall) — strip off this session")
            }
            aaCompositor?.setStrip(stripPx)
            aaCompositor?.setOutput(surf, w, h, usableW, usableH, fit)
            MetricsStrip.stop()
            val comp = aaCompositor
            if (stripPx > 0 && comp != null) {
                MetricsStrip.start(context, w, stripPx, comp::setStripBitmap, log)
            }
```

- [ ] **Step 5: Stop it with the pipeline**

In `VideoPipeline.stop(...)`, replace:

```kotlin
        try { aaCompositor?.release() } catch (_: Exception) {}
```

with:

```kotlin
        MetricsStrip.stop()
        try { aaCompositor?.release() } catch (_: Exception) {}
```

- [ ] **Step 6: Build and run all unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsSource.kt app/src/main/java/dev/zanderp/opencfmoto/MetricsStripRenderer.kt app/src/main/java/dev/zanderp/opencfmoto/MetricsStrip.kt app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt
git commit -m "feat(strip): GPS metrics rendered into the band while Android Auto projects

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Phase 1 docs + bike verification checkpoint

**Files:**
- Modify: `README.md` (Features table, after the `🖼️ **Screen margins**` row)
- Modify: `docs/02-CURRENT-APP.md` (append a section at the end)

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: phase-1 release candidate + a bike-test log.

- [ ] **Step 1: README feature row**

After the row starting `| 🖼️ **Screen margins** |`, add:

```markdown
| 📊 **Metrics strip** | Optional band under Android Auto on the dash: GPS speed, altitude and heading (Setup → Metrics strip: Small / Medium / Large). Android Auto is laid out above it. |
```

- [ ] **Step 2: Append to `docs/02-CURRENT-APP.md`**

```markdown

## Rider metrics strip (phase 1)

Optional band under Android Auto on the **bike output only** (not the in-app Dash view). Spec:
`docs/superpowers/specs/2026-09-30-rider-metrics-strip-design.md`.

- `MetricsStripLayout` — pure geometry (band height clamp 32..H/4, cells, GL placement flag
  `STRIP_AT_GL_BOTTOM`, touch exclusion, AA panel shape).
- `RiderMetrics` / `MetricsFormat` — pure values + formatting (5 s staleness, units from `MapPrefs`).
- `MetricsStrip` — per-session coordinator: `MetricsSource` (own GPS listener) → render on change
  (`MetricsStripRenderer`, Canvas → Bitmap) → `AaCompositor.setStripBitmap`.
- `AaCompositor` — `setStrip(px)` shrinks the AA viewport; the strip texture is drawn into the band.
- `VideoPrefs.aaMarginsFor` — AA is laid out for `panelH − band` (also when match-aspect is OFF).
- Logs: `[STRIP] on canvas=… rect(top-origin)=… glY=… aaRect=…`, `[STRIP] started …`,
  `[STRIP] no GPS …`, `[STRIP] … failed`.
```

- [ ] **Step 3: Full build + tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/02-CURRENT-APP.md
git commit -m "docs: metrics strip phase 1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Bike test (owner). Stop here and hand over the APK + checklist**

Install `app/build/outputs/apk/debug/*.apk`. For each step, Share Logs:

1. Strip **Off** → Connect. No `[STRIP]` lines. The dash looks exactly as before.
2. Strip **Medium** → Connect. The band is at the **bottom**, and AA is not cropped or distorted. The log shows `[match-aspect] … strip −64px` and `[STRIP] on canvas=…`. **If the band is at the top and upside-down:** set `STRIP_AT_GL_BOTTOM = false` in `MetricsStripLayout.kt`, update the `glPlacementPutsBandAtGlBottomAndShiftsAa` test expectations to `glStripY(384, 64) == 320` and `glAaShift(64) == 0`, rebuild, and retest.
3. Ride. Speed matches the bike speedometer within ±2 km/h, heading changes on turns, altitude is plausible.
4. On a touch dash, tap inside the band. Nothing happens in AA. Taps above the band still hit the right spot.
5. Revoke location permission or turn GPS off → Connect. Cells show `--`, AA works, and the log shows `[STRIP] no GPS …`.
6. Stop → Connect twice in a row, and let it park/resume once. The log shows one `[STRIP] started` per session, with no duplicates and no `[STRIP] … failed`.

Proceed to Phase 2 only after the owner confirms steps 1–6.

---

# Phase 2 — Turn info from Android Auto

### Task 8: Nav message tracker (pure)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/aa/NavTurnTracker.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/aa/NavTurnTrackerTest.kt`

**Interfaces:**
- Consumes: `NextTurn`, `TurnKind`, `TurnSide` (Task 2). Generated protos in `dev.zanderp.opencfmoto.aa.proto.NavigationStatus`: `MsgType`, `NavigationClusterStatus` (+ `NavigationStatusEnum`), `NextTurnDetail` (+ `NextEvent`, `Side`), `NextTurnDistanceEvent`, `NavigationState`, `NavigationStep`, `NavigationManeuver` (+ `NavigationType`), `NavigationRoad`, `NavigationCurrentPosition`, `NavigationStepDistance`, `NavigationDistance`.
- Produces:
  - `class NavTurnTracker { val current: NextTurn?; fun onMessage(type: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean }` (returns true when `current` changed)
  - `companion object { fun typeName(type: Int): String; internal fun fromNextEvent(e: NextTurnDetail.NextEvent, s: NextTurnDetail.Side): Pair<TurnKind, TurnSide>; internal fun fromNavigationType(t: NavigationManeuver.NavigationType): Pair<TurnKind, TurnSide> }`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.NextTurn
import dev.zanderp.opencfmoto.TurnKind
import dev.zanderp.opencfmoto.TurnSide
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.MsgType
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationClusterStatus
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationCurrentPosition
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationManeuver
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationRoad
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationState
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStep
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationStepDistance
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDetail
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDistanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTurnTrackerTest {
    private fun detail(e: NextTurnDetail.NextEvent, s: NextTurnDetail.Side, road: String = "Main St", exit: Int? = null) =
        NextTurnDetail.newBuilder().setRoad(road).setNextTurn(e).setSide(s)
            .apply { if (exit != null) setTurnNumber(exit) }.build().toByteArray()

    private fun distance(m: Int) = NextTurnDistanceEvent.newBuilder().setDistanceMeters(m).build().toByteArray()

    private fun status(s: NavigationClusterStatus.NavigationStatusEnum) =
        NavigationClusterStatus.newBuilder().setStatus(s).build().toByteArray()

    @Test
    fun legacyDetailThenDistance() {
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.RIGHT)))
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, road = "Main St"), t.current)
        assertTrue(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(347)))
        assertEquals(347, t.current!!.distanceM)
        assertFalse(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(347)))
    }

    @Test
    fun distanceBeforeDetailIsKept() {
        val t = NavTurnTracker()
        assertFalse(t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(900)))
        assertNull(t.current)
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.SLIGHT_TURN, NextTurnDetail.Side.LEFT))
        assertEquals(NextTurn(TurnKind.SLIGHT, TurnSide.LEFT, distanceM = 900, road = "Main St"), t.current)
    }

    @Test
    fun roundaboutExitOnlyForRoundabout() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT, NextTurnDetail.Side.RIGHT, exit = 3))
        assertEquals(3, t.current!!.exitNumber)
        assertEquals(TurnKind.ROUNDABOUT, t.current!!.kind)
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT, exit = 2))
        assertNull(t.current!!.exitNumber)
    }

    @Test
    fun statusInactiveClearsAndReroutingKeeps() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        assertFalse(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.REROUTING)))
        assertTrue(t.current != null)
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, status(NavigationClusterStatus.NavigationStatusEnum.INACTIVE)))
        assertNull(t.current)
    }

    @Test
    fun stopClears() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, ByteArray(0)))
        assertNull(t.current)
    }

    @Test
    fun newerStateAndPosition() {
        val state = NavigationState.newBuilder().addSteps(
            NavigationStep.newBuilder()
                .setManeuver(NavigationManeuver.newBuilder().setType(NavigationManeuver.NavigationType.TURN_SHARP_RIGHT))
                .setRoad(NavigationRoad.newBuilder().setName("High St")),
        ).build().toByteArray()
        val pos = NavigationCurrentPosition.newBuilder().setStepDistance(
            NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(120)),
        ).build().toByteArray()
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))
        assertEquals(NextTurn(TurnKind.SHARP, TurnSide.RIGHT, distanceM = 120, road = "High St"), t.current)
    }

    @Test
    fun honoursOffsetAndLength() {
        val payload = distance(500)
        val framed = ByteArray(4) + payload + ByteArray(3)
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.STRAIGHT, NextTurnDetail.Side.UNSPECIFIED))
        t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, framed, 4, payload.size)
        assertEquals(500, t.current!!.distanceM)
        assertEquals(TurnSide.NONE, t.current!!.side)
    }

    @Test(expected = Exception::class)
    fun malformedPayloadThrows() {
        NavTurnTracker().onMessage(MsgType.NEXTTURNDETAILS_VALUE, byteArrayOf(0x0A, 0x7F))
    }

    @Test
    fun unknownTypeIgnored() {
        assertFalse(NavTurnTracker().onMessage(1234, ByteArray(0)))
    }

    @Test
    fun navigationTypeMappingCoversEveryValue() {
        for (t in NavigationManeuver.NavigationType.values()) NavTurnTracker.fromNavigationType(t)  // must not throw
        val m = NavTurnTracker.Companion
        assertEquals(TurnKind.SLIGHT to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.KEEP_LEFT))
        assertEquals(TurnKind.NORMAL to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT))
        assertEquals(TurnKind.U_TURN to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.U_TURN_LEFT))
        assertEquals(TurnKind.RAMP_ON to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.ON_RAMP_U_TURN_RIGHT))
        assertEquals(TurnKind.RAMP_OFF to TurnSide.LEFT, m.fromNavigationType(NavigationManeuver.NavigationType.OFF_RAMP_SLIGHT_LEFT))
        assertEquals(TurnKind.MERGE to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.MERGE_SIDE_UNSPECIFIED))
        assertEquals(TurnKind.ROUNDABOUT to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW))
        assertEquals(TurnKind.DESTINATION to TurnSide.RIGHT, m.fromNavigationType(NavigationManeuver.NavigationType.DESTINATION_RIGHT))
        assertEquals(TurnKind.STRAIGHT to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.NAME_CHANGE))
        assertEquals(TurnKind.UNKNOWN to TurnSide.NONE, m.fromNavigationType(NavigationManeuver.NavigationType.FERRY_BOAT))
    }

    @Test
    fun nextEventMappingCoversEveryValue() {
        for (e in NextTurnDetail.NextEvent.values()) for (s in NextTurnDetail.Side.values()) NavTurnTracker.fromNextEvent(e, s)
        val m = NavTurnTracker.Companion
        assertEquals(TurnKind.SHARP to TurnSide.LEFT, m.fromNextEvent(NextTurnDetail.NextEvent.SHARP_TURN, NextTurnDetail.Side.LEFT))
        assertEquals(TurnKind.RAMP_OFF to TurnSide.RIGHT, m.fromNextEvent(NextTurnDetail.NextEvent.OFFRAMP, NextTurnDetail.Side.RIGHT))
        assertEquals(TurnKind.DESTINATION to TurnSide.NONE, m.fromNextEvent(NextTurnDetail.NextEvent.DESTINATION, NextTurnDetail.Side.UNSPECIFIED))
        assertEquals(TurnKind.UNKNOWN to TurnSide.NONE, m.fromNextEvent(NextTurnDetail.NextEvent.FERRY_TRAIN, NextTurnDetail.Side.UNSPECIFIED))
    }

    @Test
    fun typeNames() {
        assertEquals("NEXTTURNDETAILS", NavTurnTracker.typeName(MsgType.NEXTTURNDETAILS_VALUE))
        assertEquals("UNKNOWN(1234)", NavTurnTracker.typeName(1234))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.aa.NavTurnTrackerTest"`
Expected: compilation FAILS with `Unresolved reference: NavTurnTracker`.

- [ ] **Step 3: Write `NavTurnTracker.kt`**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import dev.zanderp.opencfmoto.NextTurn
import dev.zanderp.opencfmoto.TurnKind
import dev.zanderp.opencfmoto.TurnSide
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.MsgType
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationClusterStatus
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationCurrentPosition
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationManeuver
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationState
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDetail
import dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NextTurnDistanceEvent

/**
 * Folds Android Auto instrument-cluster navigation messages (both the legacy NEXTTURN* family and
 * the newer NAVIGATION_STATE/CURRENT_POSITION family) into one [NextTurn]. Pure: no logging, no
 * Android — [AapControlNav] logs. Malformed payloads throw (the caller catches and logs).
 */
class NavTurnTracker {
    var current: NextTurn? = null
        private set

    private var kind: TurnKind? = null
    private var side = TurnSide.NONE
    private var exit: Int? = null
    private var road: String? = null
    private var distanceM: Int? = null

    /** @return true when [current] changed. */
    fun onMessage(type: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
        when (type) {
            MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> clear()
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                val s = NavigationClusterStatus.newBuilder().mergeFrom(data, offset, length).buildPartial()
                when (s.status) {
                    NavigationClusterStatus.NavigationStatusEnum.ACTIVE,
                    NavigationClusterStatus.NavigationStatusEnum.REROUTING -> Unit
                    else -> clear()
                }
            }
            MsgType.NEXTTURNDETAILS_VALUE -> {
                val d = NextTurnDetail.newBuilder().mergeFrom(data, offset, length).buildPartial()
                val (k, sd) = fromNextEvent(d.nextTurn, d.side)
                kind = k
                side = sd
                exit = if (k == TurnKind.ROUNDABOUT && d.hasTurnNumber() && d.turnNumber > 0) d.turnNumber else null
                road = d.road.takeIf { d.hasRoad() && it.isNotBlank() }
            }
            MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                val e = NextTurnDistanceEvent.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (e.hasDistanceMeters()) distanceM = e.distanceMeters
            }
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                val st = NavigationState.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (st.stepsCount > 0) {
                    val step = st.getSteps(0)
                    val (k, sd) = fromNavigationType(step.maneuver.type)
                    kind = k
                    side = sd
                    val man = step.maneuver
                    exit = if (k == TurnKind.ROUNDABOUT && man.hasRoundaboutExitNumber() && man.roundaboutExitNumber > 0) {
                        man.roundaboutExitNumber
                    } else null
                    road = step.road.name.takeIf { step.hasRoad() && it.isNotBlank() }
                }
            }
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                val p = NavigationCurrentPosition.newBuilder().mergeFrom(data, offset, length).buildPartial()
                if (p.hasStepDistance() && p.stepDistance.hasDistance()) distanceM = p.stepDistance.distance.meters
            }
            MsgType.INSTRUMENT_CLUSTER_START_VALUE -> Unit
            else -> return false
        }
        val next = kind?.let { NextTurn(it, side, exit, distanceM, road) }
        if (next == current) return false
        current = next
        return true
    }

    private fun clear() {
        kind = null
        side = TurnSide.NONE
        exit = null
        road = null
        distanceM = null
    }

    companion object {
        fun typeName(type: Int): String = MsgType.forNumber(type)?.name ?: "UNKNOWN($type)"

        internal fun fromNextEvent(e: NextTurnDetail.NextEvent, s: NextTurnDetail.Side): Pair<TurnKind, TurnSide> {
            val kind = when (e) {
                NextTurnDetail.NextEvent.DEPART -> TurnKind.DEPART
                NextTurnDetail.NextEvent.NAME_CHANGE, NextTurnDetail.NextEvent.STRAIGHT -> TurnKind.STRAIGHT
                NextTurnDetail.NextEvent.SLIGHT_TURN -> TurnKind.SLIGHT
                NextTurnDetail.NextEvent.TURN -> TurnKind.NORMAL
                NextTurnDetail.NextEvent.SHARP_TURN -> TurnKind.SHARP
                NextTurnDetail.NextEvent.U_TURN -> TurnKind.U_TURN
                NextTurnDetail.NextEvent.ON_RAMP -> TurnKind.RAMP_ON
                NextTurnDetail.NextEvent.OFFRAMP -> TurnKind.RAMP_OFF
                NextTurnDetail.NextEvent.FORK -> TurnKind.FORK
                NextTurnDetail.NextEvent.MERGE -> TurnKind.MERGE
                NextTurnDetail.NextEvent.ROUNDABOUT_ENTER,
                NextTurnDetail.NextEvent.ROUNDABOUT_EXIT,
                NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> TurnKind.ROUNDABOUT
                NextTurnDetail.NextEvent.DESTINATION -> TurnKind.DESTINATION
                else -> TurnKind.UNKNOWN
            }
            val side = when (s) {
                NextTurnDetail.Side.LEFT -> TurnSide.LEFT
                NextTurnDetail.Side.RIGHT -> TurnSide.RIGHT
                else -> TurnSide.NONE
            }
            return kind to (if (kind == TurnKind.UNKNOWN) TurnSide.NONE else side)
        }

        internal fun fromNavigationType(t: NavigationManeuver.NavigationType): Pair<TurnKind, TurnSide> {
            val n = t.name
            val side = when {
                n.endsWith("LEFT") -> TurnSide.LEFT
                n.endsWith("RIGHT") -> TurnSide.RIGHT
                else -> TurnSide.NONE
            }
            val kind = when {
                n.startsWith("ON_RAMP") -> TurnKind.RAMP_ON
                n.startsWith("OFF_RAMP") -> TurnKind.RAMP_OFF
                n.startsWith("ROUNDABOUT") -> TurnKind.ROUNDABOUT
                n.startsWith("DESTINATION") -> TurnKind.DESTINATION
                n.startsWith("U_TURN") -> TurnKind.U_TURN
                n.startsWith("FORK") -> TurnKind.FORK
                n.startsWith("MERGE") -> TurnKind.MERGE
                n.startsWith("KEEP") || n.startsWith("TURN_SLIGHT") -> TurnKind.SLIGHT
                n.startsWith("TURN_SHARP") -> TurnKind.SHARP
                n.startsWith("TURN_NORMAL") -> TurnKind.NORMAL
                n == "DEPART" -> TurnKind.DEPART
                n == "STRAIGHT" || n == "NAME_CHANGE" -> TurnKind.STRAIGHT
                else -> TurnKind.UNKNOWN
            }
            return kind to (if (kind == TurnKind.ROUNDABOUT || kind == TurnKind.UNKNOWN) TurnSide.NONE else side)
        }
    }
}
```

Notes for the implementer:
- Roundabout side is forced to `NONE`, because the `_CW`/`_CCW` suffixes aren't left/right. That's why `ROUNDABOUT_ENTER_AND_EXIT_CCW` maps to `NONE` in the test.
- `DESTINATION_STRAIGHT` ends in neither LEFT nor RIGHT, so it maps to `DESTINATION`/`NONE`.
- If `MsgType.forNumber` doesn't exist in the generated class (protobuf 3.25 generates it for proto2 enums), use `MsgType.values().firstOrNull { it.number == type }`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.aa.NavTurnTrackerTest"`
Expected: PASS (12 tests). If `malformedPayloadThrows` doesn't throw (protobuf may parse `0x0A 0x7F` as a truncated field and throw `InvalidProtocolBufferException`, which *is* an `Exception`), keep the test. It must throw so the caller's catch path is exercised. If it truly parses without error, change the payload to `byteArrayOf(0x0A, 0x05, 0x41)` (declared length 5, only 1 byte).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/aa/NavTurnTracker.kt app/src/test/java/dev/zanderp/opencfmoto/aa/NavTurnTrackerTest.kt
git commit -m "feat(nav): parse Android Auto cluster navigation messages into NextTurn

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Advertise turn info, route nav messages, Setup toggle

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/BikeProfile.kt` (`BikeProfileHolder`, next to `aaContentMargins` ~line 241)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/AppSettings.kt` (`applyToHolder`, ~line 153)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/aa/ServiceDiscoveryResponse.kt` (`makeProto`, before `return Control.ServiceDiscoveryResponse…`)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt` (`AapControlGateway`, ~line 270–297)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt`, `app/src/main/res/layout/activity_setup.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `NavTurnTracker` (Task 8). `MetricsStrip.setTurn` (Task 6). `LogBus.log(String)` (existing). `Channel.ID_NAV = 10` (existing).
- Produces: `MetricsStripPrefs.turnInfo(ctx)`, `setTurnInfo(ctx, on)`, `turnInfoActive(ctx)`. `BikeProfileHolder.aaTurnInfo: Boolean`. `internal class AapControlNav : AapControl`.

- [ ] **Step 1: Prefs**

In `MetricsStripPrefs`, add a key constant next to `KEY_HEIGHT`:

```kotlin
    private const val KEY_TURN_INFO = "metrics_strip_turn_info"
```

Replace `setHeight` and add the turn-info functions:

```kotlin
    fun setHeight(ctx: Context, h: StripHeight) {
        BikeScope.putString(prefs(ctx), ctx, KEY_HEIGHT, h.name)
        BikeProfileHolder.aaTurnInfo = turnInfoActive(ctx)
    }

    /** Experimental: ask Android Auto for next-turn data (instrument cluster service). */
    fun turnInfo(ctx: Context): Boolean = BikeScope.getBoolean(prefs(ctx), ctx, KEY_TURN_INFO, false)

    fun setTurnInfo(ctx: Context, on: Boolean) {
        BikeScope.putBoolean(prefs(ctx), ctx, KEY_TURN_INFO, on)
        BikeProfileHolder.aaTurnInfo = turnInfoActive(ctx)
    }

    /** Turn info only makes sense with a visible strip. */
    fun turnInfoActive(ctx: Context): Boolean = turnInfo(ctx) && height(ctx) != StripHeight.OFF
```

- [ ] **Step 2: Holder flag + sync**

In `BikeProfileHolder` (`BikeProfile.kt`), directly after `@Volatile var aaContentMargins: AaMargins = AaMargins.NONE`, add:

```kotlin

    /**
     * Advertise Android Auto's navigation-status (instrument cluster) service so Maps/Waze send
     * next-turn data for the metrics strip. Synced from [MetricsStripPrefs.turnInfoActive]; read by
     * [dev.zanderp.opencfmoto.aa.ServiceDiscoveryResponse]. Off = handshake unchanged.
     */
    @Volatile var aaTurnInfo: Boolean = false
```

In `AppSettings.applyToHolder`, directly after `BikeProfileHolder.aaDpiOverride = VideoPrefs.dpiOverride(ctx)`, add:

```kotlin
        BikeProfileHolder.aaTurnInfo = MetricsStripPrefs.turnInfoActive(ctx)
```

- [ ] **Step 3: Advertise the service**

In `ServiceDiscoveryResponse.makeProto()`, directly before `return Control.ServiceDiscoveryResponse.newBuilder().apply {`, add:

```kotlin
            // --- Navigation status (instrument cluster) — next-turn data for the metrics strip.
            //     Only when the rider opted in (Setup → Turn info); otherwise the handshake is unchanged. ---
            if (BikeProfileHolder.aaTurnInfo) {
                services.add(Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_NAV
                    service.navigationStatusService = Control.Service.NavigationStatusService.newBuilder().apply {
                        minimumIntervalMs = 1000
                        type = Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly
                    }.build()
                }.build())
            }
```

Also update the file's header comment line `// AA bring-up). Audio sink, navigation-status, media-playback and bluetooth services from HUR are` to read `// AA bring-up). Audio sink, media-playback and bluetooth services from HUR are`. Then add a line after it: `// (Navigation-status is advertised only when Setup → Turn info is on — see BikeProfileHolder.aaTurnInfo.)`

- [ ] **Step 4: Route nav messages**

In `aa/AapControl.kt`, add this class directly before `internal class AapControlGateway(`:

```kotlin
/** Instrument-cluster navigation (channel 10): next-turn data for the metrics strip. */
internal class AapControlNav : AapControl {
    private val tracker = NavTurnTracker()
    private val seenTypes = HashSet<Int>()

    override fun execute(message: AapMessage): Int {
        val len = message.size - message.dataOffset
        if (seenTypes.add(message.type)) {
            dev.zanderp.opencfmoto.LogBus.log("[NAV] first ${NavTurnTracker.typeName(message.type)} len=$len")
        }
        val changed = try {
            tracker.onMessage(message.type, message.data, message.dataOffset, len)
        } catch (e: Exception) {
            dev.zanderp.opencfmoto.LogBus.log("[NAV] parse failed ${NavTurnTracker.typeName(message.type)}: $e")
            false
        }
        if (changed) {
            val t = tracker.current
            dev.zanderp.opencfmoto.LogBus.log(
                if (t == null) "[NAV] turn cleared"
                else "[NAV] turn → ${t.kind} ${t.side} ${t.distanceM ?: "?"} m" +
                    (t.exitNumber?.let { " exit $it" } ?: "") + " \"${t.road.orEmpty()}\"",
            )
            dev.zanderp.opencfmoto.MetricsStrip.setTurn(t)
        }
        return 0
    }
}
```

In `AapControlGateway`, add a constructor parameter after `sensorControl`:

```kotlin
    private val sensorControl: AapControl,
    private val navControl: AapControl
```

Update the secondary constructor to pass it:

```kotlin
    constructor(aapTransport: AapTransport) : this(
        aapTransport,
        AapControlService(aapTransport),
        AapControlMedia(aapTransport),
        AapControlTouch(aapTransport),
        AapControlSensor(aapTransport),
        AapControlNav()
    )
```

In `execute`'s `when (message.channel)`, add a branch after `Channel.ID_SEN -> …`:

```kotlin
            Channel.ID_NAV -> return navControl.execute(message)
```

- [ ] **Step 5: Setup toggle**

Append to `values/strings.xml`:

```xml
    <string name="setup_turn_info">Turn info on the strip (experimental)</string>
    <string name="setup_turn_info_desc">Shows the next turn from Google Maps / Waze in the metrics strip. Needs the strip on. If Android Auto stops connecting, turn this off. Applies next connect.</string>
    <string name="pref_turn_info_on">On</string>
    <string name="pref_turn_info_off">Off</string>
    <string name="setup_toast_turn_info">Turn info: %1$s</string>
```

In `activity_setup.xml`, directly after the `</LinearLayout>` that closes the `strip_off … strip_large` row (added in Task 3), insert:

```xml
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:text="@string/setup_turn_info"
                    android:textColor="@color/text_primary"
                    android:textSize="15sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:layout_marginBottom="6dp"
                    android:text="@string/setup_turn_info_desc"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/turninfo_on"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_turn_info_on" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/turninfo_off"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_turn_info_off" />
                </LinearLayout>
```

In `SetupActivity.kt`, after the `strip_large` listener line, add:

```kotlin
        findViewById<MaterialButton>(R.id.turninfo_on).setOnClickListener { setTurnInfo(true) }
        findViewById<MaterialButton>(R.id.turninfo_off).setOnClickListener { setTurnInfo(false) }
```

After `setStripHeight(...)`, add:

```kotlin
    private fun setTurnInfo(on: Boolean) {
        MetricsStripPrefs.setTurnInfo(this, on)
        refreshOptions()
        toast(getString(R.string.setup_toast_turn_info,
            getString(if (on) R.string.pref_turn_info_on else R.string.pref_turn_info_off)))
    }
```

In `refreshOptions()`, after the `highlight(MetricsStripPrefs.height(this), …)` call, add:

```kotlin
        highlight(MetricsStripPrefs.turnInfo(this),
            R.id.turninfo_on to true,
            R.id.turninfo_off to false)
        val stripOn = MetricsStripPrefs.height(this) != StripHeight.OFF
        findViewById<MaterialButton>(R.id.turninfo_on).isEnabled = stripOn
        findViewById<MaterialButton>(R.id.turninfo_off).isEnabled = stripOn
```

- [ ] **Step 6: Build and run all unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt app/src/main/java/dev/zanderp/opencfmoto/BikeProfile.kt app/src/main/java/dev/zanderp/opencfmoto/AppSettings.kt app/src/main/java/dev/zanderp/opencfmoto/aa/ServiceDiscoveryResponse.kt app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt app/src/main/res/layout/activity_setup.xml app/src/main/res/values/strings.xml
git commit -m "feat(nav): experimental turn info from Android Auto into the metrics strip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Phase 2 docs + bike verification

**Files:**
- Modify: `README.md` (the Metrics strip row from Task 7)
- Modify: `docs/02-CURRENT-APP.md` (append)

- [ ] **Step 1: README row update**

Replace the Metrics strip row with:

```markdown
| 📊 **Metrics strip** | Optional band under Android Auto on the dash: GPS speed, altitude and heading, plus **next turn** from Google Maps / Waze (experimental). Setup → Metrics strip / Turn info. Android Auto is laid out above it. |
```

- [ ] **Step 2: Append to `docs/02-CURRENT-APP.md`**

```markdown

## Rider metrics strip (phase 2 — turn info, experimental)

- Setup → *Turn info* (needs the strip) sets `BikeProfileHolder.aaTurnInfo`; `ServiceDiscoveryResponse`
  then advertises `NavigationStatusService` on `Channel.ID_NAV` (`ImageCodesOnly`, 1000 ms). Off =
  handshake unchanged.
- `aa/AapControlNav` → `aa/NavTurnTracker` (pure; handles NEXTTURNDETAILS/NEXTTURNDISTANCEANDTIME and
  NAVIGATION_STATE/CURRENT_POSITION) → `MetricsStrip.setTurn`.
- Logs: `[NAV] first <TYPE> len=…`, `[NAV] turn → KIND SIDE N m "road"`, `[NAV] turn cleared`,
  `[NAV] parse failed …`.
```

- [ ] **Step 3: Full build + tests, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
git add README.md docs/02-CURRENT-APP.md
git commit -m "docs: metrics strip phase 2 turn info

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Bike test (owner). Hand over the APK + checklist**

1. Strip on, turn info **Off** → Connect. The log has **no** `Channel Open Request on chan 10`. Phase-1 behavior is unchanged.
2. Turn info **On** → Connect. AA connects normally. The log shows `Channel Open Request on chan 10 NAVIGATION_DIRECTIONS`.
3. Start a Google Maps route. `[NAV] first …` lines appear, and `[NAV] turn → …` lines match the Maps UI. The strip shows the arrow + distance, and speed shrinks to one cell.
4. End the route. `[NAV] turn cleared` appears, and the cell disappears.
5. Repeat step 3 with Waze.
6. If AA drops right after service discovery with turn info on, record the AA version from the log banner, turn turn info off, and report. Don't ship it enabled by default.
