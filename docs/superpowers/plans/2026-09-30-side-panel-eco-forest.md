# Side Panel + Eco Tree + Forest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A metrics-strip mode "Side panel" that reserves a left/right column beside Android Auto showing directions (while navigating) and a live eco tree, plus a Forest screen collecting completed trees.

**Architecture:** Pure units carry the rules — `SidePanelLayout` (geometry/touch), `EcoScorer` (per-fix eco decision), `EcoForest` (tree growth + month totals) — all unit-tested. `MetricsStrip` gains a SIDE mode that feeds GPS fixes to the scorer, persists progress via `EcoStore`, and renders the panel with `SidePanelRenderer` (reusing the directions arrows and a shared `TreeDrawing`). `AaCompositor` treats the panel like an extra left/right screen margin and paints the panel texture in that column. A Compose `ForestScreen` shows the forest.

**Tech Stack:** Kotlin, Android Canvas + GLES20 (existing compositor), Jetpack Compose (existing), SharedPreferences, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-side-panel-eco-forest-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto` (Compose screen under `dev.zanderp.opencfmoto.ui.forest`). New files start with the 3-line SPDX/AGPL header (copy from `BikeScope.kt`).
- Side panel width: **Narrow 25% / Medium 30% / Wide 35%** of the canvas width (mapped from StripHeight SMALL/MEDIUM/LARGE); **min 120 px**, max 45%, and Android Auto keeps **≥ 200 px** — otherwise the panel is off for the session. Default side **Right**.
- Eco second (moving ≥ 3 km/h): |acceleration| **< 1.5 m/s²**, max − min speed over the last **10 s < 10 km/h**, speed **30–70 km/h**. Step time < 1 s counts as 1 s; a gap > 5 s resets the window. Eco % shown once moving distance **≥ 200 m**.
- Tree = **20 000 eco metres**, 5 stages of **4 000 m**; completion carries the remainder over; growth never goes backwards.
- Progress saved at least every **30 s** while riding and on stop; shared across bikes (not `BikeScope`).
- Selecting Side panel forces Turn info on. Metrics and Directions modes unchanged.
- The trees are a game: no CO₂ / fuel text anywhere.
- Build/test from repo root in Git Bash: `export JAVA_HOME="/d/Program Files/Java/jdk-21" GRADLE_USER_HOME="F:/opencfmoto-gradle/home" TMP="F:\\opencfmoto-gradle\\tmp" TEMP="F:\\opencfmoto-gradle\\tmp" GRADLE_OPTS="-Djava.io.tmpdir=F:/opencfmoto-gradle/tmp"; F="--console=plain --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process"` then `./gradlew <task> $F`.
- "Suite green" = only the known failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` (exactly 1).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **FILL fit mode makes the AA picture wider than its area** → a tap on the panel must not reach Android Auto; a drag ending on it clamps to the panel edge. Pinned by `SidePanelLayoutTest.touchInPanel*` (Task 1) + the compositor using `touchX` (Task 4).
2. **Small dash (e.g. 340 px wide)** → panel turns off instead of squeezing Android Auto. Pinned by `SidePanelLayoutTest.tooNarrowTurnsOff` (Task 1).
3. **GPS jitter / gaps (tunnel)** → no eco credit across a gap, no inflated acceleration. Pinned by `EcoScorerTest.gapResetsWindow` + `jitterFloor` (Task 2).
4. **Riding past midnight on the last day of the month** → month eco % resets for the new month. Pinned by `EcoForestTest.newMonthResetsMonthTotals` (Task 2).
5. **A ride that crosses 20 eco km twice (long ride)** → two trees, remainder kept. Pinned by `EcoForestTest.multipleTreesInOneAdd` (Task 2).

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `SidePanelLayout.kt` | Create | panel px, AA shape, panel x, touch rule (pure) |
| `MetricsStripPrefs.kt` | Modify | `StripMode.SIDE`, side-left pref, snapshot `side/sidePercent/sideLeft` |
| `VideoPrefs.kt` | Modify | AA margins horizontal in SIDE |
| `Eco.kt` | Create | `EcoStep`, `EcoScorer`, `EcoForest` (pure) |
| `EcoStore.kt` | Create | SharedPreferences persistence |
| `TreeDrawing.kt` | Create | procedural tree per stage (android Canvas) |
| `SidePanelRenderer.kt` | Create | `SidePanelView` + Canvas render |
| `MetricsStrip.kt`, `AaCompositor.kt`, `VideoPipeline.kt` | Modify | SIDE wiring |
| `ui/forest/ForestScreen.kt`, `ui/shell/AppShell.kt`, `ui/tabs/MeTab.kt` | Create/Modify | Forest screen + Me row |
| `SetupActivity.kt`, `res/layout/activity_setup.xml`, `res/values/strings.xml` | Modify | Setup rows |
| Tests: `SidePanelLayoutTest.kt`, `EcoScorerTest.kt`, `EcoForestTest.kt`, `StripModeTest.kt` (extend) | Create/Modify | |

---

### Task 1: Side panel geometry and settings

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/SidePanelLayout.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt`, `app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/SidePanelLayoutTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/StripModeTest.kt` (append)

**Interfaces:**
- Produces: `object SidePanelLayout { const val MIN_PX = 120; const val MIN_AA_PX = 200; const val MAX_FRACTION = 0.45f; fun percentFor(h: StripHeight): Int; fun panelPx(canvasW: Int, percent: Int): Int; fun aaWidth(panelW: Int, sidePx: Int): Int; fun panelX(canvasW: Int, sidePx: Int, left: Boolean): Int; fun isInPanel(cx: Int, canvasW: Int, sidePx: Int, left: Boolean): Boolean; fun touchX(cx: Int, canvasW: Int, sidePx: Int, left: Boolean, isDown: Boolean): Int? }`; `StripMode.SIDE`; `StripSnapshot(…, sidePercent: Int = 0, sideLeft: Boolean = false)` with `val side: Boolean`; `StripSnapshot.of(height, turnInfo, cells = ALL, mode = METRICS, sideLeft = false)`; `MetricsStripPrefs.sideLeft(ctx)/setSideLeft(ctx, left)`.

- [ ] **Step 1: Failing tests.** `SidePanelLayoutTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidePanelLayoutTest {
    @Test
    fun percentFromSize() {
        assertEquals(25, SidePanelLayout.percentFor(StripHeight.SMALL))
        assertEquals(30, SidePanelLayout.percentFor(StripHeight.MEDIUM))
        assertEquals(35, SidePanelLayout.percentFor(StripHeight.LARGE))
        assertEquals(0, SidePanelLayout.percentFor(StripHeight.OFF))
    }

    @Test
    fun panelPixels() {
        assertEquals(240, SidePanelLayout.panelPx(800, 30))
        assertEquals(200, SidePanelLayout.panelPx(800, 25))
        assertEquals(350, SidePanelLayout.panelPx(1000, 35))
        assertEquals(0, SidePanelLayout.panelPx(800, 0))
    }

    @Test
    fun tooNarrowTurnsOff() {
        assertEquals(0, SidePanelLayout.panelPx(340, 30))     // 102 px < 120
        assertEquals(0, SidePanelLayout.panelPx(310, 35))     // 109 px < 120
    }

    @Test
    fun aaGetsTheRest() {
        assertEquals(560, SidePanelLayout.aaWidth(800, 240))
        assertEquals(800, SidePanelLayout.aaWidth(800, 0))
    }

    @Test
    fun panelPosition() {
        assertEquals(560, SidePanelLayout.panelX(800, 240, left = false))
        assertEquals(0, SidePanelLayout.panelX(800, 240, left = true))
        assertTrue(SidePanelLayout.isInPanel(560, 800, 240, left = false))
        assertFalse(SidePanelLayout.isInPanel(559, 800, 240, left = false))
        assertTrue(SidePanelLayout.isInPanel(239, 800, 240, left = true))
        assertFalse(SidePanelLayout.isInPanel(240, 800, 240, left = true))
        assertFalse(SidePanelLayout.isInPanel(700, 800, 0, left = false))
    }

    @Test
    fun touchInPanelDownDropped() {
        assertNull(SidePanelLayout.touchX(700, 800, 240, left = false, isDown = true))
        assertNull(SidePanelLayout.touchX(10, 800, 240, left = true, isDown = true))
    }

    @Test
    fun touchInPanelMoveUpClampedToEdge() {
        assertEquals(559, SidePanelLayout.touchX(700, 800, 240, left = false, isDown = false))
        assertEquals(240, SidePanelLayout.touchX(10, 800, 240, left = true, isDown = false))
        assertEquals(300, SidePanelLayout.touchX(300, 800, 240, left = false, isDown = true))
    }
}
```

Append to `StripModeTest.kt` (inside the class):

```kotlin
    @Test
    fun sideReservesWidthNotHeight() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false, mode = StripMode.SIDE, sideLeft = true)
        assertTrue(s.side)
        assertTrue(s.turnInfo)
        assertEquals(30, s.sidePercent)
        assertTrue(s.sideLeft)
        assertEquals(0, s.aaReservePx)
        assertFalse(s.overlay)
    }

    @Test
    fun sideOffWhenStripOff() {
        val s = StripSnapshot.of(StripHeight.OFF, turnInfo = true, mode = StripMode.SIDE)
        assertFalse(s.side)
        assertFalse(s.turnInfo)
    }
```

- [ ] **Step 2: Run to see them fail.** `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.SidePanelLayoutTest" --tests "dev.zanderp.opencfmoto.StripModeTest" $F` — Expected: compile errors (`SidePanelLayout`, `StripMode.SIDE`).

- [ ] **Step 3: `SidePanelLayout.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import kotlin.math.roundToInt

/**
 * Geometry of the side panel (a column beside Android Auto on the bike output). Pure — unit-tested.
 * Android Auto is laid out in the rest of the width; the panel acts like an extra left/right margin.
 */
object SidePanelLayout {
    const val MIN_PX = 120
    const val MIN_AA_PX = 200
    const val MAX_FRACTION = 0.45f

    /** Narrow / Medium / Wide reuse the strip size choice. */
    fun percentFor(h: StripHeight): Int = when (h) {
        StripHeight.OFF -> 0
        StripHeight.SMALL -> 25
        StripHeight.MEDIUM -> 30
        StripHeight.LARGE -> 35
    }

    /** Panel width in canvas pixels, or 0 when the canvas is too narrow for a useful panel + AA. */
    fun panelPx(canvasW: Int, percent: Int): Int {
        if (percent <= 0 || canvasW <= 0) return 0
        val px = (canvasW * percent / 100f).roundToInt().coerceAtMost((canvasW * MAX_FRACTION).toInt())
        return if (px < MIN_PX || canvasW - px < MIN_AA_PX) 0 else px
    }

    fun aaWidth(panelW: Int, sidePx: Int): Int = (panelW - sidePx).coerceAtLeast(16)

    fun panelX(canvasW: Int, sidePx: Int, left: Boolean): Int = if (left) 0 else canvasW - sidePx

    fun isInPanel(cx: Int, canvasW: Int, sidePx: Int, left: Boolean): Boolean =
        sidePx > 0 && if (left) cx < sidePx else cx >= canvasW - sidePx

    /** Touch x to map into Android Auto, or null: DOWN on the panel dropped; MOVE/UP clamped to its edge. */
    fun touchX(cx: Int, canvasW: Int, sidePx: Int, left: Boolean, isDown: Boolean): Int? = when {
        !isInPanel(cx, canvasW, sidePx, left) -> cx
        isDown -> null
        left -> sidePx
        else -> canvasW - sidePx - 1
    }
}
```

- [ ] **Step 4: Snapshot + prefs.** In `MetricsStripPrefs.kt`:
  1. `enum class StripMode { METRICS, DIRECTIONS, SIDE }` (update its KDoc: "…or a side panel beside Android Auto (directions + eco tree)").
  2. Replace the `StripSnapshot` class with:

```kotlin
data class StripSnapshot(
    val requestPx: Int,
    val turnInfo: Boolean,
    val cells: Set<StripCell> = StripCells.ALL,
    val mode: StripMode = StripMode.METRICS,
    val sidePercent: Int = 0,
    val sideLeft: Boolean = false,
) {
    /** Directions: the band is drawn over Android Auto (which keeps the full screen) only while navigating. */
    val overlay: Boolean get() = mode == StripMode.DIRECTIONS && requestPx > 0

    /** Side panel: a column beside Android Auto (directions + eco tree). */
    val side: Boolean get() = mode == StripMode.SIDE && sidePercent > 0

    /** Dash rows Android Auto must be laid out without (only Metrics mode reserves the bottom band). */
    val aaReservePx: Int get() = if (overlay || side) 0 else requestPx

    companion object {
        val OFF = StripSnapshot(0, false)

        /** Turn info only makes sense with a visible strip; Directions and Side panel require it. */
        fun of(
            height: StripHeight,
            turnInfo: Boolean,
            cells: Set<StripCell> = StripCells.ALL,
            mode: StripMode = StripMode.METRICS,
            sideLeft: Boolean = false,
        ): StripSnapshot = StripSnapshot(
            requestPx = height.px,
            turnInfo = (turnInfo || mode != StripMode.METRICS) && height != StripHeight.OFF,
            cells = cells,
            mode = mode,
            sidePercent = if (mode == StripMode.SIDE) SidePanelLayout.percentFor(height) else 0,
            sideLeft = sideLeft,
        )
    }
}
```

  3. In `object MetricsStripPrefs` add `private const val KEY_SIDE_LEFT = "metrics_strip_side_left"` and before `snapshot`:

```kotlin
    /** Side panel on the left (default right, where Android Auto would put its media card). */
    fun sideLeft(ctx: Context): Boolean = BikeScope.getBoolean(prefs(ctx), ctx, KEY_SIDE_LEFT, false)

    fun setSideLeft(ctx: Context, left: Boolean) {
        BikeScope.putBoolean(prefs(ctx), ctx, KEY_SIDE_LEFT, left)
    }
```

  and change `snapshot` to `StripSnapshot.of(height(ctx), turnInfo(ctx), cells(ctx), mode(ctx), sideLeft(ctx))`.

- [ ] **Step 5: AA margins.** In `VideoPrefs.aaMarginsFor`:
  1. After `val stripReq = BikeProfileHolder.stripSession.aaReservePx` add `val session = BikeProfileHolder.stripSession`.
  2. Change the OFF-mode detection line `val detected = if (stripReq > 0) detectedPanelSize(ctx, ssid) else null` to `val detected = if (stripReq > 0 || session.side) detectedPanelSize(ctx, ssid) else null`.
  3. Replace

```kotlin
        val stripPx = MetricsStripLayout.resolvePx(stripReq, panel.second)
        val (targetW, targetH) = MetricsStripLayout.panelForAa(panel.first, panel.second, stripPx)
        val margins = AaMargins.forAspect(spec, targetW, targetH)
        val stripNote = if (stripPx > 0) " strip −${stripPx}px" else ""
```

  with

```kotlin
        val stripPx = MetricsStripLayout.resolvePx(stripReq, panel.second)
        val sidePx = if (session.side) SidePanelLayout.panelPx(panel.first, session.sidePercent) else 0
        val (targetW, targetH) = if (sidePx > 0) {
            SidePanelLayout.aaWidth(panel.first, sidePx) to panel.second
        } else {
            MetricsStripLayout.panelForAa(panel.first, panel.second, stripPx)
        }
        val margins = AaMargins.forAspect(spec, targetW, targetH)
        val stripNote = when {
            sidePx > 0 -> " side −${sidePx}px"
            stripPx > 0 -> " strip −${stripPx}px"
            else -> ""
        }
```

- [ ] **Step 6: Run tests.** Same command as Step 2 — Expected: PASS. Then `./gradlew :app:testDebugUnitTest $F` — only YunmoFrameTest fails.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/SidePanelLayout.kt app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt app/src/test/java/dev/zanderp/opencfmoto/SidePanelLayoutTest.kt app/src/test/java/dev/zanderp/opencfmoto/StripModeTest.kt
git commit -m "feat(strip): side panel mode — geometry, settings, AA laid out beside it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Eco scorer and forest growth

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/Eco.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/EcoScorerTest.kt`, `app/src/test/java/dev/zanderp/opencfmoto/EcoForestTest.kt`

**Interfaces:**
- Produces: `data class EcoStep(val ecoMeters: Double, val movingMeters: Double)`; `class EcoScorer { val ecoMeters: Double; val movingMeters: Double; val ecoPercent: Int?; fun onFix(speedMs: Float, timeMs: Long): EcoStep }`; `data class EcoForest(trees: Int = 0, currentMeters: Double = 0.0, allTimeMeters: Double = 0.0, month: String = "", monthEcoMeters: Double = 0.0, monthMovingMeters: Double = 0.0)` with `val stage: Int` (1–5), `val progress: Float` (0–1), `val monthEcoPercent: Int?`, `fun add(ecoMeters: Double, movingMeters: Double, month: String): Pair<EcoForest, Int>`, and `companion { const val TREE_METERS = 20_000.0; const val STAGE_METERS = 4_000.0; fun monthKey(epochMs: Long, zone: java.util.TimeZone): String }`.

- [ ] **Step 1: Failing tests.** `EcoScorerTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcoScorerTest {
    /** Feed km/h values 1 s apart (or [dts]); returns the scorer. */
    private fun feed(kmh: List<Float>, dts: List<Long>? = null): EcoScorer {
        val s = EcoScorer()
        var t = 0L
        kmh.forEachIndexed { i, v ->
            if (i > 0) t += dts?.get(i - 1) ?: 1000L
            s.onFix(v / 3.6f, t)
        }
        return s
    }

    @Test
    fun steadyInBandIsEco() {
        val s = feed(List(20) { 50f })
        assertEquals(19 * 50 / 3.6, s.ecoMeters, 0.01)
        assertEquals(s.movingMeters, s.ecoMeters, 0.01)
    }

    @Test
    fun outsideSpeedBandIsNotEco() {
        assertEquals(0.0, feed(List(20) { 25f }).ecoMeters, 0.0)
        assertEquals(0.0, feed(List(20) { 75f }).ecoMeters, 0.0)
        assertEquals(19 * 30 / 3.6, feed(List(20) { 30f }).ecoMeters, 0.01)
    }

    @Test
    fun hardAccelerationStepIsNotEco() {
        // 40 → 46 km/h in 1 s = 1.67 m/s² (≥ 1.5): that step earns nothing.
        val s = feed(listOf(40f, 40f, 46f))
        assertEquals(40 / 3.6, s.ecoMeters, 0.01)
    }

    @Test
    fun gentleChangeIsEco() {
        val s = feed(listOf(40f, 44f))          // 1.11 m/s²
        assertEquals(44 / 3.6, s.ecoMeters, 0.01)
    }

    @Test
    fun unsteadySpeedIsNotEco() {
        // Gentle steps but a 12 km/h swing within 10 s → not steady.
        val kmh = listOf(40f, 44f, 48f, 52f, 48f, 44f, 40f)
        val s = feed(kmh)
        assertEquals(44 / 3.6 + 48 / 3.6, s.ecoMeters, 0.01)   // only while the swing is < 10 km/h
    }

    @Test
    fun jitterFloor() {
        // 1.0 km/h change delivered 0.4 s apart is 0.28 m/s² with the 1 s floor, not 0.69.
        val s = feed(listOf(50f, 51f), dts = listOf(400L))
        assertEquals(51 / 3.6 * 0.4, s.ecoMeters, 0.01)
    }

    @Test
    fun gapResetsWindow() {
        val s = feed(listOf(50f, 50f, 50f), dts = listOf(1000L, 6000L))
        assertEquals(50 / 3.6, s.ecoMeters, 0.01)       // the step across the 6 s gap earns nothing
    }

    @Test
    fun ecoPercentNeeds200m() {
        assertNull(feed(listOf(50f, 50f, 50f)).ecoPercent)               // ~28 m
        assertEquals(100, feed(List(20) { 50f }).ecoPercent)              // ~264 m, all eco
        val mixed = feed(List(10) { 50f } + List(10) { 75f })            // eco then too fast
        // eco 9 × 13.89 m = 125 m; moving 125 m + 10 × 20.83 m = 333 m → 37.5 % → 38
        assertEquals(38, mixed.ecoPercent)
    }
}
```

`EcoForestTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EcoForestTest {
    @Test
    fun stagesEveryFourKm() {
        assertEquals(1, EcoForest(currentMeters = 0.0).stage)
        assertEquals(1, EcoForest(currentMeters = 3_999.0).stage)
        assertEquals(2, EcoForest(currentMeters = 4_000.0).stage)
        assertEquals(5, EcoForest(currentMeters = 16_000.0).stage)
        assertEquals(5, EcoForest(currentMeters = 19_999.0).stage)
        assertEquals(0.5f, EcoForest(currentMeters = 10_000.0).progress, 1e-6f)
    }

    @Test
    fun treeCompletesAtTwentyKmWithCarryOver() {
        val (f, done) = EcoForest(trees = 3, currentMeters = 19_500.0).add(1_000.0, 1_000.0, "2026-09")
        assertEquals(1, done)
        assertEquals(4, f.trees)
        assertEquals(500.0, f.currentMeters, 1e-9)
        assertEquals(1, f.stage)
    }

    @Test
    fun multipleTreesInOneAdd() {
        val (f, done) = EcoForest().add(45_000.0, 50_000.0, "2026-09")
        assertEquals(2, done)
        assertEquals(2, f.trees)
        assertEquals(5_000.0, f.currentMeters, 1e-9)
        assertEquals(45_000.0, f.allTimeMeters, 1e-9)
    }

    @Test
    fun neverGoesBackwards() {
        val start = EcoForest(trees = 1, currentMeters = 2_000.0)
        assertEquals(start.copy(month = "2026-09"), start.add(-50.0, 10.0, "2026-09").first.copy(monthMovingMeters = 0.0))
        assertEquals(0, start.add(Double.NaN, 0.0, "2026-09").second)
    }

    @Test
    fun monthTotalsAndPercent() {
        var f = EcoForest()
        f = f.add(800.0, 1_000.0, "2026-09").first
        f = f.add(100.0, 1_000.0, "2026-09").first
        assertEquals(45, f.monthEcoPercent)
        assertNull(EcoForest().monthEcoPercent)
    }

    @Test
    fun newMonthResetsMonthTotals() {
        val f = EcoForest(month = "2026-09", monthEcoMeters = 5_000.0, monthMovingMeters = 6_000.0)
            .add(300.0, 400.0, "2026-10").first
        assertEquals("2026-10", f.month)
        assertEquals(300.0, f.monthEcoMeters, 1e-9)
        assertEquals(400.0, f.monthMovingMeters, 1e-9)
    }

    @Test
    fun monthKeyInPhoneZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = Calendar.getInstance(utc).apply { clear(); set(2026, 8, 30, 20, 0) }.timeInMillis
        assertEquals("2026-10", EcoForest.monthKey(t, TimeZone.getTimeZone("Asia/Colombo")))
        assertEquals("2026-09", EcoForest.monthKey(t, utc))
    }
}
```

- [ ] **Step 2: Run to see them fail.** `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.EcoScorerTest" --tests "dev.zanderp.opencfmoto.EcoForestTest" $F` — Expected: compile errors (`EcoScorer`, `EcoForest`).

- [ ] **Step 3: `Eco.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** What one GPS fix added: distance ridden eco, and distance moved. */
data class EcoStep(val ecoMeters: Double, val movingMeters: Double)

/**
 * Live eco-riding decision per GPS fix (spec §2.2). Pure — unit-tested. One instance per ride/session.
 * A second is eco when moving, gentle (|a| < 1.5 m/s²), steady (swing < 10 km/h over 10 s) and in 30–70 km/h.
 */
class EcoScorer {
    var ecoMeters = 0.0
        private set
    var movingMeters = 0.0
        private set

    private var prevSpeed = Float.NaN
    private var prevTime = 0L
    private val window = ArrayDeque<Pair<Long, Float>>()

    val ecoPercent: Int?
        get() = if (movingMeters < MIN_PERCENT_M) null else (100.0 * ecoMeters / movingMeters).roundToInt()

    fun onFix(speedMs: Float, timeMs: Long): EcoStep {
        if (!speedMs.isFinite() || speedMs < 0f) return EcoStep(0.0, 0.0)
        val first = prevSpeed.isNaN()
        val dt = (timeMs - prevTime) / 1000.0
        if (!first && dt <= 0) return EcoStep(0.0, 0.0)
        if (first || dt > GAP_S) {
            window.clear()
            window.addLast(timeMs to speedMs)
            prevSpeed = speedMs
            prevTime = timeMs
            return EcoStep(0.0, 0.0)
        }
        val acc = (speedMs - prevSpeed) / maxOf(dt, MIN_DT_S)
        window.addLast(timeMs to speedMs)
        while (window.isNotEmpty() && timeMs - window.first().first > WINDOW_MS) window.removeFirst()
        val swing = window.maxOf { it.second } - window.minOf { it.second }
        prevSpeed = speedMs
        prevTime = timeMs

        val dist = speedMs * dt
        val moving = speedMs >= MOVING_MS
        if (!moving) return EcoStep(0.0, 0.0)
        movingMeters += dist
        val eco = abs(acc) < MAX_ACC && swing < MAX_SWING_MS && speedMs >= BAND_LOW_MS - EPS && speedMs <= BAND_HIGH_MS + EPS
        if (!eco) return EcoStep(0.0, dist)
        ecoMeters += dist
        return EcoStep(dist, dist)
    }

    companion object {
        const val MOVING_MS = 3f / 3.6f
        const val MAX_ACC = 1.5
        const val MAX_SWING_MS = 10f / 3.6f
        const val BAND_LOW_MS = 30f / 3.6f
        const val BAND_HIGH_MS = 70f / 3.6f
        const val WINDOW_MS = 10_000L
        const val GAP_S = 5.0
        const val MIN_DT_S = 1.0
        const val MIN_PERCENT_M = 200.0
        private const val EPS = 1e-4f
    }
}

/**
 * The rider's growing tree and forest (spec §2.2–2.3). Pure — unit-tested. A tree is 20 eco km in five
 * 4 km stages; completing it adds to [trees] and a new seed starts with the remainder. Never shrinks.
 */
data class EcoForest(
    val trees: Int = 0,
    val currentMeters: Double = 0.0,
    val allTimeMeters: Double = 0.0,
    val month: String = "",
    val monthEcoMeters: Double = 0.0,
    val monthMovingMeters: Double = 0.0,
) {
    /** 1 seed · 2 sprout · 3 sapling · 4 young · 5 full. */
    val stage: Int get() = (currentMeters / STAGE_METERS).toInt().coerceIn(0, 4) + 1

    val progress: Float get() = (currentMeters / TREE_METERS).toFloat().coerceIn(0f, 1f)

    val monthEcoPercent: Int?
        get() = if (monthMovingMeters < EcoScorer.MIN_PERCENT_M) null
        else (100.0 * monthEcoMeters / monthMovingMeters).roundToInt()

    /** Adds one step's distances; returns the new state and how many trees were completed. */
    fun add(ecoMeters: Double, movingMeters: Double, month: String): Pair<EcoForest, Int> {
        val eco = if (ecoMeters.isFinite() && ecoMeters > 0) ecoMeters else 0.0
        val moving = if (movingMeters.isFinite() && movingMeters > 0) movingMeters else 0.0
        var cur = currentMeters + eco
        var done = 0
        while (cur >= TREE_METERS) {
            cur -= TREE_METERS
            done++
        }
        val sameMonth = month == this.month
        return copy(
            trees = trees + done,
            currentMeters = cur,
            allTimeMeters = allTimeMeters + eco,
            month = month,
            monthEcoMeters = (if (sameMonth) monthEcoMeters else 0.0) + eco,
            monthMovingMeters = (if (sameMonth) monthMovingMeters else 0.0) + moving,
        ) to done
    }

    companion object {
        const val TREE_METERS = 20_000.0
        const val STAGE_METERS = 4_000.0

        fun monthKey(epochMs: Long, zone: TimeZone): String {
            val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
            return String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }
    }
}
```

- [ ] **Step 4: Run tests.** Same command — Expected: PASS (8 + 7). If a numeric expectation fails, recompute it from the rules in Global Constraints before touching code.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/Eco.kt app/src/test/java/dev/zanderp/opencfmoto/EcoScorerTest.kt app/src/test/java/dev/zanderp/opencfmoto/EcoForestTest.kt
git commit -m "feat(eco): live eco-riding scorer and tree/forest growth

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Persistence, tree drawing and panel renderer

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/EcoStore.kt`, `TreeDrawing.kt`, `SidePanelRenderer.kt`

**Interfaces:**
- Consumes: `EcoForest` (Task 2), `DirectionsView`, `NextTurn` (existing), `MetricsStripRenderer.drawArrow/fit` (existing internal).
- Produces: `object EcoStore { fun load(ctx: Context): EcoForest; fun save(ctx: Context, f: EcoForest) }`; `object TreeDrawing { fun draw(c: android.graphics.Canvas, stage: Int, cx: Float, baseY: Float, size: Float, shade: Int = 0) }`; `data class SidePanelView(val directions: DirectionsView?, val stage: Int, val progress: Float, val ecoKm: Double, val ecoPercent: Int?, val treeNumber: Int, val noGps: Boolean)`; `object SidePanelRenderer { fun render(v: SidePanelView, w: Int, h: Int): Bitmap }`.

No JVM tests (Android Canvas / SharedPreferences); verified by build and Tasks 4–6.

- [ ] **Step 1: `EcoStore.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/** Saves the eco forest (shared across bikes — not BikeScope). Unreadable data → an empty forest. */
object EcoStore {
    private const val PREFS = "opencfmoto_eco"

    fun load(ctx: Context): EcoForest = try {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        EcoForest(
            trees = p.getInt("trees", 0).coerceAtLeast(0),
            currentMeters = p.getFloat("current_m", 0f).toDouble().coerceIn(0.0, EcoForest.TREE_METERS),
            allTimeMeters = p.getFloat("all_time_m", 0f).toDouble().coerceAtLeast(0.0),
            month = p.getString("month", "") ?: "",
            monthEcoMeters = p.getFloat("month_eco_m", 0f).toDouble().coerceAtLeast(0.0),
            monthMovingMeters = p.getFloat("month_moving_m", 0f).toDouble().coerceAtLeast(0.0),
        )
    } catch (e: Exception) {
        LogBus.log("[ECO] saved forest unreadable ($e) — starting fresh")
        EcoForest()
    }

    fun save(ctx: Context, f: EcoForest) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("trees", f.trees)
            .putFloat("current_m", f.currentMeters.toFloat())
            .putFloat("all_time_m", f.allTimeMeters.toFloat())
            .putString("month", f.month)
            .putFloat("month_eco_m", f.monthEcoMeters.toFloat())
            .putFloat("month_moving_m", f.monthMovingMeters.toFloat())
            .apply()
    }
}
```

- [ ] **Step 2: `TreeDrawing.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** A simple drawn tree for each growth stage (1 seed … 5 full). Used on the dash panel and the Forest screen. */
object TreeDrawing {
    private val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B5A2B.toInt(); style = Paint.Style.FILL }
    private val soil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B4636.toInt(); style = Paint.Style.FILL }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val greens = intArrayOf(0xFF34D399.toInt(), 0xFF22C55E.toInt(), 0xFF16A34A.toInt())

    /**
     * Draws a tree standing on ([cx], [baseY]) fitting a [size]×[size] box. [shade] (0–2) varies the green
     * so a forest doesn't look copy-pasted. Call from one thread at a time (shared Paints).
     */
    fun draw(c: Canvas, stage: Int, cx: Float, baseY: Float, size: Float, shade: Int = 0) {
        leaf.color = greens[((shade % 3) + 3) % 3]
        val s = size
        when (stage.coerceIn(1, 5)) {
            1 -> { // seed in soil + a tiny shoot
                c.drawOval(RectF(cx - s * 0.18f, baseY - s * 0.08f, cx + s * 0.18f, baseY), soil)
                c.drawCircle(cx, baseY - s * 0.12f, s * 0.05f, leaf)
            }
            2 -> { // sprout: stem + two leaves
                c.drawRect(cx - s * 0.02f, baseY - s * 0.3f, cx + s * 0.02f, baseY, trunk)
                c.drawOval(RectF(cx - s * 0.16f, baseY - s * 0.36f, cx, baseY - s * 0.26f), leaf)
                c.drawOval(RectF(cx, baseY - s * 0.36f, cx + s * 0.16f, baseY - s * 0.26f), leaf)
            }
            3 -> { // sapling
                c.drawRect(cx - s * 0.03f, baseY - s * 0.5f, cx + s * 0.03f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.55f, s * 0.18f, leaf)
            }
            4 -> { // young tree
                c.drawRect(cx - s * 0.045f, baseY - s * 0.6f, cx + s * 0.045f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.66f, s * 0.24f, leaf)
                c.drawCircle(cx - s * 0.15f, baseY - s * 0.52f, s * 0.14f, leaf)
                c.drawCircle(cx + s * 0.15f, baseY - s * 0.52f, s * 0.14f, leaf)
            }
            else -> { // full tree
                c.drawRect(cx - s * 0.06f, baseY - s * 0.62f, cx + s * 0.06f, baseY, trunk)
                c.drawCircle(cx, baseY - s * 0.72f, s * 0.28f, leaf)
                c.drawCircle(cx - s * 0.22f, baseY - s * 0.55f, s * 0.2f, leaf)
                c.drawCircle(cx + s * 0.22f, baseY - s * 0.55f, s * 0.2f, leaf)
            }
        }
    }

    fun stageName(stage: Int): String = when (stage.coerceIn(1, 5)) {
        1 -> "Seed"
        2 -> "Sprout"
        3 -> "Sapling"
        4 -> "Young tree"
        else -> "Full tree"
    }
}
```

- [ ] **Step 3: `SidePanelRenderer.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/** Everything the side panel shows this frame. Data-class equality = "nothing visible changed". */
data class SidePanelView(
    val directions: DirectionsView?,
    val stage: Int,
    val progress: Float,
    val ecoKm: Double,
    val ecoPercent: Int?,
    val treeNumber: Int,
    val noGps: Boolean,
)

/**
 * Draws the side panel: directions block on top while a route is active, eco tree block below (or the whole
 * panel when there's no route). Called only from the `metrics-strip` thread. New bitmap per call.
 */
object SidePanelRenderer {
    private const val GREY = 0xFFAAAAAA.toInt()
    private const val DIM = 0xFF555555.toInt()
    private const val TRACK = 0xFF1A1A1A.toInt()
    private const val ECO = 0xFF34D399.toInt()

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val divider = Paint().apply { color = 0xFF222222.toInt(); strokeWidth = 2f }

    fun render(v: SidePanelView, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val pad = w * 0.06f
        val d = v.directions
        if (d != null) {
            val treeTop = drawDirections(c, d, w, h, pad)
            c.drawLine(pad, treeTop, w - pad, treeTop, divider)
            drawEcoCompact(c, v, w, treeTop + pad * 0.6f, h - pad, pad)
        } else {
            drawEcoFull(c, v, w, h, pad)
        }
        return bmp
    }

    /** @return y where the eco block starts. */
    private fun drawDirections(c: Canvas, d: DirectionsView, w: Int, h: Int, pad: Float): Float {
        val arrow = minOf(w * 0.38f, h * 0.22f)
        MetricsStripRenderer.drawArrow(c, d.turn, pad, pad, arrow)
        val textX = pad + arrow + pad * 0.6f
        val textW = w - textX - pad
        big.textSize = arrow * 0.42f
        big.textSize = DirectionsFormat.shrinkToFit(big.textSize, arrow * 0.25f, textW) { size ->
            big.textSize = size
            big.measureText(d.distance)
        }
        c.drawText(MetricsStripRenderer.fit(d.distance, big, textW), textX, pad + arrow * 0.55f, big)
        var y = pad + arrow + pad * 0.4f
        small.textSize = h * 0.045f
        d.road?.let {
            c.drawText(MetricsStripRenderer.fit(it, small, w - 2 * pad), pad, y + small.textSize, small)
            y += small.textSize * 1.5f
        }
        if (d.then != null || d.lanes.isNotEmpty()) {
            val rowH = h * 0.075f
            var x = pad
            if (d.then != null) {
                c.drawText("then", x, y + rowH * 0.7f, small)
                x += small.measureText("then ") 
                MetricsStripRenderer.drawArrow(c, d.then, x, y, rowH)
                x += rowH + pad
            }
            for (lane in d.lanes) {
                if (x + rowH * 0.8f > w - pad) break
                val (kind, side) = lane.arrows.firstOrNull() ?: (TurnKind.STRAIGHT to TurnSide.NONE)
                val sz = (rowH * 0.8f).toInt().coerceAtLeast(1)
                val lb = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                MetricsStripRenderer.drawArrow(Canvas(lb), NextTurn(kind, side), 0f, 0f, sz * 0.9f)
                val tint = Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        if (lane.highlighted) Color.WHITE else DIM, android.graphics.PorterDuff.Mode.SRC_IN,
                    )
                }
                c.drawBitmap(lb, x, y + rowH * 0.1f, tint)
                lb.recycle()
                x += rowH * 0.85f
            }
            y += rowH + pad * 0.3f
        }
        d.eta?.let {
            big.textSize = h * 0.055f
            val line = it + (d.remaining?.let { r -> " · $r" } ?: "")
            c.drawText(MetricsStripRenderer.fit(line, big, w - 2 * pad), pad, y + big.textSize, big)
            y += big.textSize * 1.6f
        }
        return y.coerceAtMost(h * 0.72f)
    }

    private fun drawEcoCompact(c: Canvas, v: SidePanelView, w: Int, top: Float, bottom: Float, pad: Float) {
        val size = (bottom - top).coerceAtMost(w * 0.3f)
        TreeDrawing.draw(c, v.stage, pad + size / 2f, top + size, size)
        val x = pad * 2 + size
        small.textSize = size * 0.22f
        val label = if (v.noGps) "No GPS" else "Eco ${v.ecoPercent?.let { "$it%" } ?: "—"} · tree ${v.stage}/5"
        c.drawText(MetricsStripRenderer.fit(label, small, w - x - pad), x, top + size * 0.4f, small)
        drawBar(c, x, top + size * 0.6f, w - x - pad, size * 0.1f, v.progress)
    }

    private fun drawEcoFull(c: Canvas, v: SidePanelView, w: Int, h: Int, pad: Float) {
        val size = minOf(w - 2 * pad, h * 0.5f)
        TreeDrawing.draw(c, v.stage, w / 2f, pad + size, size)
        var y = pad + size + pad
        small.textSize = h * 0.045f
        small.textAlign = Paint.Align.CENTER
        c.drawText(MetricsStripRenderer.fit("${TreeDrawing.stageName(v.stage)} · stage ${v.stage} of 5", small, w - 2 * pad), w / 2f, y, small)
        y += pad * 0.8f
        drawBar(c, pad * 2, y, w - pad * 4, h * 0.02f, v.progress)
        y += pad * 1.8f
        big.textSize = h * 0.08f
        big.textAlign = Paint.Align.CENTER
        val eco = if (v.noGps) "No GPS" else "Eco ${v.ecoPercent?.let { "$it%" } ?: "—"}"
        c.drawText(eco, w / 2f, y + big.textSize * 0.4f, big)
        y += big.textSize + pad * 0.4f
        c.drawText(
            MetricsStripRenderer.fit(String.format(Locale.US, "%.1f / 20 eco km · tree %d", v.ecoKm, v.treeNumber), small, w - 2 * pad),
            w / 2f, y, small,
        )
        big.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.LEFT
    }

    private fun drawBar(c: Canvas, x: Float, y: Float, width: Float, height: Float, f: Float) {
        bar.color = TRACK
        c.drawRoundRect(x, y, x + width, y + height, height / 2, height / 2, bar)
        bar.color = ECO
        c.drawRoundRect(x, y, x + width * f.coerceIn(0f, 1f), y + height, height / 2, height / 2, bar)
    }
}
```

- [ ] **Step 4: Build.** `./gradlew :app:assembleDebug $F` — Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/EcoStore.kt app/src/main/java/dev/zanderp/opencfmoto/TreeDrawing.kt app/src/main/java/dev/zanderp/opencfmoto/SidePanelRenderer.kt
git commit -m "feat(eco): forest persistence, drawn trees and the side panel renderer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Wire the side panel — compositor, pipeline, MetricsStrip

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt`, `VideoPipeline.kt`, `MetricsStrip.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `AaCompositor.setSidePanel(px: Int, left: Boolean)`; `MetricsStrip.start(…, mode = StripMode.SIDE)` renders `SidePanelView`s sized `canvasW × stripPx` (= panel width × canvas height).

No new JVM tests (GL/Android glue); verified by build + suite + Task 5/6 emulator + bike.

- [ ] **Step 1: `AaCompositor`.**
  1. Next to `stripPx` fields add `@Volatile private var sidePx = 0` and `@Volatile private var sideLeft = false`.
  2. Add below `setStripVisible`:

```kotlin
    /** Side panel: a column of [px] canvas pixels on the left/right that Android Auto is laid out beside (0 = off). */
    fun setSidePanel(px: Int, left: Boolean) {
        handler.post {
            sidePx = px.coerceAtLeast(0)
            sideLeft = left
            if (sidePx == 0 && stripPx == 0) stripReady = false
            computeViewport()
        }
    }
```

  3. In `setStripBitmap` change the guard `stripPx <= 0` to `(stripPx <= 0 && sidePx <= 0)`.
  4. In `computeViewport` replace

```kotlin
        val mL = ScreenMargins.left.coerceIn(0, canvasW)
        val mR = ScreenMargins.right.coerceIn(0, canvasW - mL)
```

  with

```kotlin
        // The side panel is an extra margin on its side: Android Auto is laid out in the rest.
        val sideL = if (sidePx > 0 && sideLeft) sidePx else 0
        val sideR = if (sidePx > 0 && !sideLeft) sidePx else 0
        val mL = (ScreenMargins.left + sideL).coerceIn(0, canvasW)
        val mR = (ScreenMargins.right + sideR).coerceIn(0, canvasW - mL)
```

  5. In `mapCanvasToSource`, before `val y = MetricsStripLayout.touchY(…)` add:

```kotlin
        val x = SidePanelLayout.touchX(cx, canvasW, sidePx, sideLeft, isDown) ?: return null
```

  and change `val rx = cx - vpX` to `val rx = x - vpX`.
  6. In `renderTo` replace the scissor block

```kotlin
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, shift, clipW, clipH - g.reservePx)
        }
```

  with

```kotlin
        val panel = if (isBike) sidePx else 0
        val clipX = if (panel > 0 && sideLeft) panel else 0
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            // Never paint Android Auto into the side panel column (FILL can overflow).
            GLES20.glScissor(clipX, shift, clipW - panel, clipH - g.reservePx)
        }
```

  and after the existing `if (g.drawPx > 0) { … drawStrip(g.drawPx) … }` block add:

```kotlin
        if (panel > 0) {
            try {
                drawTexture(SidePanelLayout.panelX(canvasW, panel, sideLeft), 0, panel, canvasH)
            } catch (e: Exception) {
                logStripFailure("side draw", e)
            }
        }
```

  7. Refactor `drawStrip(band: Int)` to delegate to a rect version:

```kotlin
    /** Paste the latest strip texture into the band. Band stays black (from glClear) until one exists. */
    private fun drawStrip(band: Int) {
        drawTexture(0, MetricsStripLayout.glStripY(canvasH, band), canvasW, band)
    }

    /** Draw the strip/panel texture into the GL rect ([x], [y], [w], [h]) (GL origin bottom-left). */
    private fun drawTexture(x: Int, y: Int, w: Int, h: Int) {
        if (!stripReady || stripTexId == 0 || stripProgram == 0 || canvasW == 0) return
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, w, h)
        GLES20.glViewport(x, y, w, h)
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

  (Delete the old body of `drawStrip` — it is now `drawTexture`.)

- [ ] **Step 2: `VideoPipeline`.** In the bike-canvas block, after `val stripPx = MetricsStripLayout.resolvePx(stripReq, h)`:
  1. Change `if (stripReq > 0 && stripPx == 0) {` to `if (!session.side && stripReq > 0 && stripPx == 0) {`.
  2. Replace `aaCompositor?.setStrip(stripPx, overlay = session.overlay)` with:

```kotlin
            val sidePx = if (session.side) SidePanelLayout.panelPx(w, session.sidePercent) else 0
            if (session.side && sidePx == 0) log("[STRIP] canvas ${w}x$h too narrow for the side panel — off this session")
            if (sidePx > 0) {
                log("[STRIP] mode=SIDE side=${if (session.sideLeft) "LEFT" else "RIGHT"} panel=${sidePx}px aa=${w - sidePx}x$h")
            }
            val bandPx = if (session.side) 0 else stripPx
            aaCompositor?.setStrip(bandPx, overlay = session.overlay)
            aaCompositor?.setSidePanel(sidePx, session.sideLeft)
```

  3. Replace the start block `if (stripPx > 0 && comp != null) { … }` with:

```kotlin
            if (comp != null) {
                val cells = BikeProfileHolder.stripSession.cells
                if (sidePx > 0) {
                    stripGuarded(log, "start") {
                        MetricsStrip.start(context, sidePx, h, comp::setStripBitmap, log, cells, mode = StripMode.SIDE)
                    }
                } else if (bandPx > 0) {
                    stripGuarded(log, "start") {
                        MetricsStrip.start(
                            context, w, bandPx, comp::setStripBitmap, log, cells,
                            mode = session.mode, onVisible = comp::setStripVisible,
                        )
                    }
                }
            }
```

- [ ] **Step 3: `MetricsStrip`.**
  1. Add fields:

```kotlin
    @Volatile private var appCtx: Context? = null
    @Volatile private var forest = EcoForest()
    private var eco = EcoScorer()                    // main-looper (GPS) thread only
    @Volatile private var ecoPercent: Int? = null
    @Volatile private var noGps = false
    private var lastEcoSaveMs = 0L                   // main-looper (GPS) thread only
    private var lastSide: SidePanelView? = null      // metrics-strip thread only
```

  2. In `start(...)`, after `this.onVisible = onVisible` add:

```kotlin
        appCtx = app
        if (mode == StripMode.SIDE) {
            forest = EcoStore.load(app)
            eco = EcoScorer()
            ecoPercent = null
            lastEcoSaveMs = SystemClock.elapsedRealtime()
        }
        lastSide = null
```

  Change the GPS guard `if (mode == StripMode.METRICS) {` to `if (mode == StripMode.METRICS || mode == StripMode.SIDE) {` and inside it set `noGps = !src.start()` before the log line (replace `if (!src.start()) log(...)` with `noGps = !src.start(); if (noGps) log("[STRIP] no GPS (permission or GPS off) — showing --")`).
  3. In `stop()`, at the start (before `source?.stop()`):

```kotlin
        if (mode == StripMode.SIDE) appCtx?.let { EcoStore.save(it, forest) }
```

  4. In `onFix(loc)`, after the existing `update { … }` call add:

```kotlin
        if (mode == StripMode.SIDE) {
            val now = SystemClock.elapsedRealtime()
            val step = eco.onFix(if (loc.hasSpeed()) loc.speed else 0f, now)
            ecoPercent = eco.ecoPercent
            val month = EcoForest.monthKey(System.currentTimeMillis(), java.util.TimeZone.getDefault())
            val (next, done) = forest.add(step.ecoMeters, step.movingMeters, month)
            forest = next
            if (done > 0) log("[ECO] tree complete (#${next.trees})")
            if (now - lastEcoSaveMs >= 30_000L) {
                lastEcoSaveMs = now
                appCtx?.let { EcoStore.save(it, next) }
                log("[ECO] saved eco=${String.format(java.util.Locale.US, "%.1f", next.currentMeters / 1000)} km")
            }
        }
```

  5. In `refresh()`, after `val out = sink ?: return` add `if (mode == StripMode.SIDE) { refreshSide(out); return }`.
  6. Add:

```kotlin
    private fun refreshSide(out: (Bitmap) -> Unit) {
        val d = directions
        val active = DirectionsFormat.active(d, navAtElapsedMs, SystemClock.elapsedRealtime())
        // Large nominal width: the side panel stacks the parts vertically, so nothing is dropped for width.
        val dir = if (active) DirectionsFormat.view(d, units, 100_000, 40, navAtWallMs, java.util.TimeZone.getDefault(), is24h) else null
        val f = forest
        val v = SidePanelView(
            directions = dir,
            stage = f.stage,
            progress = (f.progress * 100).toInt() / 100f,
            ecoKm = (f.currentMeters / 100).toInt() / 10.0,
            ecoPercent = ecoPercent,
            treeNumber = f.trees + 1,
            noGps = noGps,
        )
        if (v == lastSide) return
        lastSide = v
        out(SidePanelRenderer.render(v, stripW, stripH))
    }
```

- [ ] **Step 4: Build + suite.** `./gradlew :app:assembleDebug $F` then `./gradlew :app:testDebugUnitTest $F` — build succeeds; only YunmoFrameTest fails.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt app/src/main/java/dev/zanderp/opencfmoto/MetricsStrip.kt
git commit -m "feat(strip): side panel beside Android Auto with directions and a live eco tree

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Setup — Side panel choice, side, size labels

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/layout/activity_setup.xml`, `app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt`

**Interfaces:**
- Consumes: `StripMode.SIDE`, `MetricsStripPrefs.sideLeft/setSideLeft` (Task 1).

- [ ] **Step 1: Strings** (append):

```xml
    <string name="pref_strip_mode_side">Side panel</string>
    <string name="setup_strip_side">Side panel position</string>
    <string name="setup_strip_side_desc">Directions and your eco tree in a column beside Android Auto (which gets the rest of the width, without its media card). Size: Narrow 25% · Medium 30% · Wide 35%. Applies next connect.</string>
    <string name="pref_side_left">Left</string>
    <string name="pref_side_right">Right</string>
    <string name="pref_strip_narrow">Narrow</string>
    <string name="pref_strip_wide">Wide</string>
```

- [ ] **Step 2: Layout.** Run from the repo root:

```bash
python - <<'EOF'
p = 'app/src/main/res/layout/activity_setup.xml'
s = open(p, encoding='utf-8').read()
anchor = '''                        android:text="@string/pref_strip_mode_directions" />
                </LinearLayout>
'''
assert s.count(anchor) == 1
side_btn = '''                        android:text="@string/pref_strip_mode_directions" />
                </LinearLayout>

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/strip_mode_side"
                    style="@style/Widget.OpenCfMoto.Segment"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="@string/pref_strip_mode_side" />

                <LinearLayout
                    android:id="@+id/strip_side_group"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:text="@string/setup_strip_side"
                        android:textColor="@color/text_primary"
                        android:textSize="15sp"
                        android:textStyle="bold" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="2dp"
                        android:layout_marginBottom="6dp"
                        android:text="@string/setup_strip_side_desc"
                        android:textColor="@color/text_secondary"
                        android:textSize="13sp" />

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:baselineAligned="false"
                        android:orientation="horizontal">

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/strip_side_left"
                            style="@style/Widget.OpenCfMoto.Segment"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_marginEnd="4dp"
                            android:layout_weight="1"
                            android:text="@string/pref_side_left" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/strip_side_right"
                            style="@style/Widget.OpenCfMoto.Segment"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="4dp"
                            android:layout_weight="1"
                            android:text="@string/pref_side_right" />
                    </LinearLayout>
                </LinearLayout>
'''
s = s.replace(anchor, side_btn, 1)
open(p, 'w', encoding='utf-8').write(s)
print('ok')
EOF
```

Expected: `ok`.

- [ ] **Step 3: Activity.** In `SetupActivity.kt`:
  1. Next to the other strip listeners add:

```kotlin
        findViewById<MaterialButton>(R.id.strip_mode_side).setOnClickListener { setStripMode(StripMode.SIDE) }
        findViewById<MaterialButton>(R.id.strip_side_left).setOnClickListener { setSideLeft(true) }
        findViewById<MaterialButton>(R.id.strip_side_right).setOnClickListener { setSideLeft(false) }
```

  2. In `setStripMode(m)`: change the Medium-default condition to `if (m != StripMode.METRICS && MetricsStripPrefs.height(this) == StripHeight.OFF)`, and the toast label to `when (m) { StripMode.DIRECTIONS -> R.string.pref_strip_mode_directions; StripMode.SIDE -> R.string.pref_strip_mode_side; else -> R.string.pref_strip_mode_metrics }`.
  3. Add:

```kotlin
    private fun setSideLeft(left: Boolean) {
        MetricsStripPrefs.setSideLeft(this, left)
        refreshOptions()
    }
```

  4. In `refreshOptions()`:
     - Replace `val directionsMode = stripMode == StripMode.DIRECTIONS` with `val directionsMode = stripMode != StripMode.METRICS` (turn info locked for Directions and Side; cells hidden for both).
     - Replace the mode `highlight(stripMode, …)` call with:

```kotlin
        highlight(stripMode,
            R.id.strip_mode_metrics to StripMode.METRICS,
            R.id.strip_mode_directions to StripMode.DIRECTIONS,
            R.id.strip_mode_side to StripMode.SIDE)
        val sideMode = stripMode == StripMode.SIDE
        findViewById<View>(R.id.strip_side_group).visibility = if (sideMode) View.VISIBLE else View.GONE
        highlight(MetricsStripPrefs.sideLeft(this),
            R.id.strip_side_left to true,
            R.id.strip_side_right to false)
        // In Side panel mode the size row means the panel width.
        findViewById<MaterialButton>(R.id.strip_small).setText(if (sideMode) R.string.pref_strip_narrow else R.string.pref_strip_small)
        findViewById<MaterialButton>(R.id.strip_large).setText(if (sideMode) R.string.pref_strip_wide else R.string.pref_strip_large)
```

- [ ] **Step 4: Build + emulator.** `./gradlew :app:assembleRelease -Pabi=x86_64 $F`; install on AVD `ocm_test`; Settings → Screen & display: tap **Side panel** → size row reads Off / Narrow / Medium / Wide (Medium selected if it was Off), "Side panel position" row appears with Right selected, cells hidden, Turn info On and disabled; tap **Left** → Left highlighted; tap **Metrics** → side group hidden, labels back to Small / Large. Screenshot; `adb logcat -d -b crash | grep -c opencfmoto` → `0`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_setup.xml app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt
git commit -m "feat(strip): Setup option for the side panel (position and width)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Forest screen

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/forest/ForestScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt`, `app/src/main/java/dev/zanderp/opencfmoto/ui/tabs/MeTab.kt`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `EcoStore.load`, `EcoForest`, `TreeDrawing.draw/stageName` (Tasks 2–3); design system (`OcmScreen`, `OcmCard`, `OcmType`, `Ocm`).
- Produces: `@Composable fun ForestScreen()`; Me tab gets `onForest: () -> Unit`.

- [ ] **Step 1: Strings** (append):

```xml
    <string name="ui_me_forest">Forest</string>
    <string name="ui_me_forest_desc">Trees you grew by riding smoothly</string>
    <string name="forest_title">My forest</string>
    <string name="forest_trees">Trees grown</string>
    <string name="forest_growing">Growing now</string>
    <string name="forest_growing_value">%1$s · %2$.1f / 20 km</string>
    <string name="forest_all_time">Eco km (all time)</string>
    <string name="forest_month">Eco this month</string>
    <string name="forest_note">Trees grow from smooth throttle, smooth braking, steady speed and riding at 30–70 km/h, while connected with the side panel on. Rough riding pauses growth — it never takes a tree away.</string>
```

- [ ] **Step 2: `ForestScreen.kt`.**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.forest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.EcoStore
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TreeDrawing
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import java.util.Locale
import kotlin.math.ceil

private const val COLUMNS = 6

@Composable
fun ForestScreen() {
    val ctx = LocalContext.current
    val f = remember { EcoStore.load(ctx) }
    val c = Ocm.colors
    val rows = maxOf(1, ceil((f.trees + 1) / COLUMNS.toDouble()).toInt())
    OcmScreen {
        Text(stringResource(R.string.forest_title), style = OcmType.screenTitle, color = c.text)
        Canvas(
            Modifier.fillMaxWidth().height((rows * 64 + 16).dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF16242A), Color(0xFF10301F)))),
        ) {
            val cell = size.width / COLUMNS
            val rowH = (size.height - 16.dp.toPx()) / rows
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                for (i in 0..f.trees) {
                    val col = i % COLUMNS
                    val row = i / COLUMNS
                    val cx = cell * col + cell / 2
                    val base = 8.dp.toPx() + rowH * (row + 1)
                    val grown = i < f.trees
                    val scale = if (grown) 0.8f + ((i * 37) % 7) / 20f else 0.9f   // varied, stable per tree
                    TreeDrawing.draw(nc, if (grown) 5 else f.stage, cx, base, minOf(cell, rowH) * scale, shade = i)
                }
            }
        }
        OcmCard {
            StatRow(stringResource(R.string.forest_trees), f.trees.toString())
            StatRow(
                stringResource(R.string.forest_growing),
                stringResource(R.string.forest_growing_value, TreeDrawing.stageName(f.stage), f.currentMeters / 1000),
            )
            StatRow(stringResource(R.string.forest_all_time), String.format(Locale.US, "%.0f km", f.allTimeMeters / 1000))
            StatRow(stringResource(R.string.forest_month), f.monthEcoPercent?.let { "$it%" } ?: "—")
        }
        OcmCard { Text(stringResource(R.string.forest_note), style = OcmType.body, color = c.text2) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val c = Ocm.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OcmType.body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = OcmType.title, color = c.text)
    }
}
```

- [ ] **Step 3: Me row + route.**
  - `MeTab.kt`: add parameter `onForest: () -> Unit` (after `onSettings`) and, in the first `OcmCard`, after the Settings row:

```kotlin
            SettingRow(stringResource(R.string.ui_me_forest), stringResource(R.string.ui_me_forest_desc), Icons.Rounded.Park, onForest)
```

   with `import androidx.compose.material.icons.rounded.Park`.
  - `AppShell.kt`: pass `onForest = { nav.navigate("forest") }` in the `MeTab(...)` call; add a route `composable("forest") { dev.zanderp.opencfmoto.ui.forest.ForestScreen() }`; make the Me tab selected for it: `val selected = route == t.route || (t.route == "me" && (route == "logs" || route == "forest"))`.

- [ ] **Step 4: Build + emulator.** `./gradlew :app:assembleDebug -Pabi=x86_64 $F`; install; seed a forest (Git Bash, `export MSYS_NO_PATHCONV=1`):

```bash
ADB=/d/Users/user/AppData/Local/Android/platform-tools/adb.exe
cat > /f/opencfmoto-gradle/out/opencfmoto_eco.xml <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="trees" value="11" />
    <float name="current_m" value="14400.0" />
    <float name="all_time_m" value="234400.0" />
    <string name="month">2026-09</string>
    <float name="month_eco_m" value="84000.0" />
    <float name="month_moving_m" value="100000.0" />
</map>
X
$ADB shell am force-stop dev.zanderp.opencfmoto
$ADB push "F:/opencfmoto-gradle/out/opencfmoto_eco.xml" /data/local/tmp/eco.xml
$ADB shell "run-as dev.zanderp.opencfmoto sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/eco.xml shared_prefs/opencfmoto_eco.xml'"
$ADB shell am start -n dev.zanderp.opencfmoto/.MainActivity
```

Open Me → Forest. Expected (screenshots, light and dark): 11 full trees + one "Young tree" in a 6-column forest; rows: Trees grown 11 · Growing now Young tree · 14.4 / 20 km · Eco km 234 km · Eco this month 84%. `adb logcat -d -b crash | grep -c opencfmoto` → `0`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/forest/ForestScreen.kt app/src/main/java/dev/zanderp/opencfmoto/ui/shell/AppShell.kt app/src/main/java/dev/zanderp/opencfmoto/ui/tabs/MeTab.kt app/src/main/res/values/strings.xml
git commit -m "feat(eco): Forest screen with your grown trees

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Docs and bike checklist

**Files:**
- Modify: `docs/02-CURRENT-APP.md`, `README.md`

- [ ] **Step 1: Docs.** Append to `docs/02-CURRENT-APP.md`:

```markdown

## Side panel + eco tree + forest

Spec: `docs/superpowers/specs/2026-09-30-side-panel-eco-forest-design.md`.

- Setup → Strip shows **Side panel** → `StripSnapshot.side` (Right/Left, 25/30/35 % from the size row); AA margins
  computed for `panelW − sidePx`; `AaCompositor.setSidePanel` adds the panel to the left/right margin and draws the
  panel texture in its column; `SidePanelLayout.touchX` keeps taps on the panel out of Android Auto.
- `MetricsStrip` SIDE mode: GPS → `EcoScorer` (eco = |a| < 1.5 m/s², swing < 10 km/h over 10 s, 30–70 km/h) →
  `EcoForest` (20 eco km per tree, 5 stages) → `EcoStore` (every 30 s + on stop); renders `SidePanelRenderer`
  (directions while a route is active + `TreeDrawing`).
- App: Me ▸ Forest (`ui/forest/ForestScreen`).
- Logs: `[STRIP] mode=SIDE side=… panel=…px aa=…`, `[ECO] tree complete (#N)`, `[ECO] saved eco=… km`.
```

In `README.md`, after the Ride behaviour feature row add:

```markdown
| 🌳 **Side panel & eco forest** | Optional column beside Android Auto (left or right) with turn-by-turn directions and a tree that grows while you ride smoothly — completed trees fill your Forest (Me ▸ Forest). |
```

Commit: `git add docs/02-CURRENT-APP.md README.md && git commit -m "docs: side panel and eco forest"` (with trailer).

- [ ] **Step 2: Bike checklist (owner, 150SC).** Setup → Metrics strip Medium, Strip shows **Side panel**, Right; Connect:
  1. Android Auto on the left ~70% (map + bar, no media card); black panel column on the right with the tree.
  2. Start a Google Maps route → directions appear at the top of the panel, tree + eco meter below.
  3. Ride smoothly at 30–70 km/h → Eco % high, "x / 20 eco km" rises; rough riding → growth pauses.
  4. Tap the panel → nothing happens in Android Auto; tap the map → works.
  5. Stop, reconnect later → the tree keeps its progress. Me ▸ Forest shows it; after 20 eco km a tree is added.
  6. Share Logs: `[STRIP] mode=SIDE …`, `[ECO] saved …`.
