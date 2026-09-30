# Directions Band Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Directions" metrics-strip mode that shows turn-by-turn directions (next turn, then, lanes, ETA + km left) in a band that slides over the bottom of Android Auto only while a route is active.

**Architecture:** Pure pieces (strip geometry, directions data, formatting/fit plan) carry the logic and are unit-tested; `NavTurnTracker` additionally produces a `Directions` value from Android Auto's navigation messages; `MetricsStrip` renders it with a new Canvas `DirectionsRenderer` and tells `AaCompositor` when the band is visible; the compositor draws the band as an overlay (AA keeps the full canvas) instead of reserving space.

**Tech Stack:** Kotlin, Android Canvas + GLES20 (existing compositor), protobuf-lite (existing AA protos), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-directions-band-design.md`

## Global Constraints

- Package `dev.zanderp.opencfmoto` (tracker in `dev.zanderp.opencfmoto.aa`). New files start with the 3-line SPDX/AGPL header (copy from `BikeScope.kt`).
- Metrics mode must behave exactly as today (AA laid out above the band, band always drawn, same touch rule).
- Directions mode: AA gets the full panel (no AA margin reduction for the band); band drawn only while a route is active; no route → band hidden and full-screen touches allowed.
- Stale route: no navigation message for **60 000 ms** → band hidden. "Then" shown only when the next turn is **≤ 1000 m** away. Drop order when the band is too narrow: **lanes → then → road name → ETA**.
- Selecting Directions forces Turn info on; if the strip height is Off it becomes **Medium (64 px)**.
- A strip/render/GL failure must never affect Android Auto (existing guarantees).
- Pure units (`StripGeometry`, `Directions*`, `NavTurnTracker`) make no LogBus/Android calls.
- Build/test from repo root in Git Bash: `export JAVA_HOME="/d/Program Files/Java/jdk-21" GRADLE_USER_HOME="F:/opencfmoto-gradle/home" TMP="F:\\opencfmoto-gradle\\tmp" TEMP="F:\\opencfmoto-gradle\\tmp" GRADLE_OPTS="-Djava.io.tmpdir=F:/opencfmoto-gradle/tmp"; F="--console=plain --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process"` then `./gradlew <task> $F`.
- "Suite green" = only the known pre-existing failure `YunmoFrameTest.parseOkDimension_xCape1200Payload` (exactly 1).
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Band hidden, rider taps AA's dock at the bottom** → the tap must reach Android Auto (no exclusion). Pinned by `StripGeometryTest.hiddenOverlayExcludesNothing` (Task 1).
2. **Metrics-mode riders after this change** → identical AA margins, band and touch rule. Pinned by `StripGeometryTest.metricsModeUnchanged` + `StripModeTest.metricsReservesBandForAa` (Task 1).
3. **Phone only sends the legacy NEXTTURN\* messages** → band still shows next turn + distance, no then/lanes/ETA. Pinned by `NavTurnTrackerTest.legacyMessagesGiveTurnOnlyDirections` (Task 2).
4. **Maps stops sending (tunnel, app killed) without an explicit stop** → band hides after 60 s. Pinned by `DirectionsFormatTest.staleAfterSixtySeconds` (Task 3).
5. **Android Auto session drops mid-route** → band hides (no frozen arrow). Pinned by Task 5 wiring (`MetricsStrip.clearNavigation()` on transport quit) + owner bike check.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `MetricsStripPrefs.kt` | Modify | `StripMode`, mode pref, `StripSnapshot.mode/overlay/aaReservePx` |
| `MetricsStripLayout.kt` | Modify | `StripGeometry` (reserve / draw / touch px) |
| `VideoPrefs.kt` | Modify | AA margins use `aaReservePx` |
| `Directions.kt` | Create | `Lane`, `Directions`, `DirectionsView`, `DirectionsPlan`, `DirectionsFormat` |
| `aa/NavTurnTracker.kt` | Modify | produce `Directions` (then, lanes, ETA, km left) |
| `MetricsStripRenderer.kt` | Modify | share `drawArrow` / `fit` (internal) |
| `DirectionsRenderer.kt` | Create | Canvas drawing of a `DirectionsView` |
| `MetricsStrip.kt` | Modify | Directions mode, staleness, band visibility callback, `clearNavigation()` |
| `AaCompositor.kt` | Modify | overlay band + `setStripVisible` |
| `VideoPipeline.kt` | Modify | pass mode/overlay/visibility |
| `aa/AapControl.kt` | Modify | feed `Directions` on every nav message, parts log |
| `aa/AaReceiver.kt` | Modify | `clearNavigation()` on transport quit |
| `SetupActivity.kt`, `res/layout/activity_setup.xml`, `res/values/strings.xml` | Modify | "Strip shows: Metrics · Directions" |
| Tests (`app/src/test/java/dev/zanderp/opencfmoto/`): `StripModeTest.kt`, `StripGeometryTest.kt`, `DirectionsFormatTest.kt`, `aa/NavTurnTrackerTest.kt` (extend) | Create/Modify | |

---

### Task 1: Strip mode, snapshot and overlay geometry

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripLayout.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt:261`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/StripModeTest.kt`, `StripGeometryTest.kt`

**Interfaces:**
- Produces: `enum class StripMode { METRICS, DIRECTIONS }`; `StripSnapshot(requestPx, turnInfo, cells, mode = StripMode.METRICS)` with `val overlay: Boolean` and `val aaReservePx: Int`; `StripSnapshot.of(height, turnInfo, cells = ALL, mode = METRICS)`; `MetricsStripPrefs.mode(ctx)/setMode(ctx, m)`; `data class StripGeometry(val reservePx: Int, val drawPx: Int, val touchPx: Int)` with `StripGeometry.of(stripPx: Int, overlay: Boolean, visible: Boolean)`.

- [ ] **Step 1: Failing tests.**

`StripModeTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StripModeTest {
    @Test
    fun metricsReservesBandForAa() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false)
        assertEquals(StripMode.METRICS, s.mode)
        assertFalse(s.overlay)
        assertEquals(64, s.aaReservePx)
    }

    @Test
    fun directionsOverlaysAndForcesTurnInfo() {
        val s = StripSnapshot.of(StripHeight.MEDIUM, turnInfo = false, mode = StripMode.DIRECTIONS)
        assertTrue(s.overlay)
        assertTrue(s.turnInfo)
        assertEquals(0, s.aaReservePx)
        assertEquals(64, s.requestPx)
    }

    @Test
    fun stripOffMeansNothingInEitherMode() {
        val s = StripSnapshot.of(StripHeight.OFF, turnInfo = true, mode = StripMode.DIRECTIONS)
        assertFalse(s.turnInfo)
        assertEquals(0, s.requestPx)
        assertEquals(0, s.aaReservePx)
    }
}
```

`StripGeometryTest.kt`:

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class StripGeometryTest {
    @Test
    fun metricsModeUnchanged() {
        // Reserved under AA, always drawn, taps on it excluded — visible flag ignored.
        assertEquals(StripGeometry(64, 64, 64), StripGeometry.of(64, overlay = false, visible = false))
        assertEquals(StripGeometry(64, 64, 64), StripGeometry.of(64, overlay = false, visible = true))
    }

    @Test
    fun visibleOverlayDrawsOverFullScreenAa() {
        assertEquals(StripGeometry(0, 64, 64), StripGeometry.of(64, overlay = true, visible = true))
    }

    @Test
    fun hiddenOverlayExcludesNothing() {
        assertEquals(StripGeometry(0, 0, 0), StripGeometry.of(64, overlay = true, visible = false))
    }

    @Test
    fun noStripNoGeometry() {
        assertEquals(StripGeometry(0, 0, 0), StripGeometry.of(0, overlay = false, visible = true))
    }
}
```

- [ ] **Step 2: Run to see them fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.StripModeTest" --tests "dev.zanderp.opencfmoto.StripGeometryTest" $F`
Expected: compile errors `Unresolved reference 'StripMode'` / `'StripGeometry'`.

- [ ] **Step 3: Snapshot + mode.** In `MetricsStripPrefs.kt`, add after `enum class StripHeight {…}`:

```kotlin

/** What the strip shows: speed/altitude/heading (band reserved under AA) or turn-by-turn directions (overlay). */
enum class StripMode { METRICS, DIRECTIONS }
```

Replace the `StripSnapshot` class with:

```kotlin
data class StripSnapshot(
    val requestPx: Int,
    val turnInfo: Boolean,
    val cells: Set<StripCell> = StripCells.ALL,
    val mode: StripMode = StripMode.METRICS,
) {
    /** Directions: the band is drawn over Android Auto (which keeps the full screen) only while navigating. */
    val overlay: Boolean get() = mode == StripMode.DIRECTIONS && requestPx > 0

    /** Dash pixels Android Auto must be laid out without (only Metrics mode reserves the band). */
    val aaReservePx: Int get() = if (overlay) 0 else requestPx

    companion object {
        val OFF = StripSnapshot(0, false)

        /** Turn info only makes sense with a visible strip; Directions requires it. */
        fun of(
            height: StripHeight,
            turnInfo: Boolean,
            cells: Set<StripCell> = StripCells.ALL,
            mode: StripMode = StripMode.METRICS,
        ): StripSnapshot = StripSnapshot(
            requestPx = height.px,
            turnInfo = (turnInfo || mode == StripMode.DIRECTIONS) && height != StripHeight.OFF,
            cells = cells,
            mode = mode,
        )
    }
}
```

In `object MetricsStripPrefs` add `private const val KEY_MODE = "metrics_strip_mode"` next to the other keys, and before `snapshot`:

```kotlin
    fun mode(ctx: Context): StripMode {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_MODE, StripMode.METRICS.name)
        return runCatching { StripMode.valueOf(name!!) }.getOrDefault(StripMode.METRICS)
    }

    fun setMode(ctx: Context, m: StripMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_MODE, m.name)
    }
```

and change `snapshot` to `StripSnapshot.of(height(ctx), turnInfo(ctx), cells(ctx), mode(ctx))`.

- [ ] **Step 4: Geometry.** In `MetricsStripLayout.kt`, after `data class StripRect(...)` add:

```kotlin

/**
 * How many dash pixels the strip takes in each role. [reservePx]: removed from Android Auto's area.
 * [drawPx]: band painted this frame. [touchPx]: bottom rows whose taps don't reach Android Auto.
 * Metrics = reserved + always drawn. Directions (overlay) = AA full screen; band only while visible.
 */
data class StripGeometry(val reservePx: Int, val drawPx: Int, val touchPx: Int) {
    companion object {
        fun of(stripPx: Int, overlay: Boolean, visible: Boolean): StripGeometry = when {
            stripPx <= 0 -> StripGeometry(0, 0, 0)
            !overlay -> StripGeometry(stripPx, stripPx, stripPx)
            visible -> StripGeometry(0, stripPx, stripPx)
            else -> StripGeometry(0, 0, 0)
        }
    }
}
```

- [ ] **Step 5: AA margins.** In `VideoPrefs.aaMarginsFor` replace `val stripReq = BikeProfileHolder.stripSession.requestPx` with:

```kotlin
        // Metrics mode lays AA out above the band; Directions overlays it, so AA keeps the full panel.
        val stripReq = BikeProfileHolder.stripSession.aaReservePx
```

- [ ] **Step 6: Run tests.** Same command as Step 2. Expected: PASS (7 tests). Then the full suite `./gradlew :app:testDebugUnitTest $F` — Expected: only the known YunmoFrameTest fails.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStripPrefs.kt app/src/main/java/dev/zanderp/opencfmoto/MetricsStripLayout.kt app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt app/src/test/java/dev/zanderp/opencfmoto/StripModeTest.kt app/src/test/java/dev/zanderp/opencfmoto/StripGeometryTest.kt
git commit -m "feat(strip): Directions mode setting and overlay geometry

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Directions data from Android Auto (tracker)

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/Directions.kt` (data types only in this task)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/aa/NavTurnTracker.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/aa/NavTurnTrackerTest.kt` (append)

**Interfaces:**
- Consumes: `NextTurn`, `TurnKind`, `TurnSide` (existing, `RiderMetrics.kt`).
- Produces: `data class Lane(val arrows: List<Pair<TurnKind, TurnSide>>, val highlighted: Boolean)`; `data class Directions(val turn: NextTurn?, val then: NextTurn? = null, val lanes: List<Lane> = emptyList(), val etaText: String? = null, val etaSeconds: Long? = null, val remainingM: Int? = null)` with `companion val NONE = Directions(null)`; `NavTurnTracker.directions: Directions`; `NavTurnTracker.Companion.handles(type: Int): Boolean`; `NavTurnTracker.Companion.fromLaneShape(shape): Pair<TurnKind, TurnSide>`.

- [ ] **Step 1: Failing tests.** Append inside `class NavTurnTrackerTest` (before its final `}`), adding imports `dev.zanderp.opencfmoto.Directions`, `dev.zanderp.opencfmoto.Lane`, `dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationLane`, `dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationDestinationDistance`:

```kotlin
    private fun step(t: NavigationManeuver.NavigationType, road: String? = null, lanes: List<NavigationLane> = emptyList()) =
        NavigationStep.newBuilder().setManeuver(NavigationManeuver.newBuilder().setType(t))
            .apply { if (road != null) setRoad(NavigationRoad.newBuilder().setName(road)) }
            .addAllLanes(lanes).build()

    private fun lane(highlighted: Boolean, vararg shapes: NavigationLane.LaneDirection.Shape) =
        NavigationLane.newBuilder().apply {
            for (s in shapes) addLaneDirections(NavigationLane.LaneDirection.newBuilder().setShape(s).setIsHighlighted(highlighted))
        }.build()

    @Test
    fun newerMessagesGiveThenLanesEtaAndRemaining() {
        val state = NavigationState.newBuilder()
            .addSteps(step(NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT, "Galle Rd", listOf(
                lane(false, NavigationLane.LaneDirection.Shape.NORMAL_LEFT),
                lane(true, NavigationLane.LaneDirection.Shape.STRAIGHT, NavigationLane.LaneDirection.Shape.SLIGHT_RIGHT),
            )))
            .addSteps(step(NavigationManeuver.NavigationType.TURN_NORMAL_LEFT, "Duplication Rd"))
            .build().toByteArray()
        val pos = NavigationCurrentPosition.newBuilder()
            .setStepDistance(NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(350)))
            .addDestinationDistances(
                NavigationDestinationDistance.newBuilder()
                    .setDistance(NavigationDistance.newBuilder().setMeters(12400))
                    .setEstimatedTimeAtArrival("18:42")
                    .setTimeToArrivalSeconds(1320),
            ).build().toByteArray()
        val t = NavTurnTracker()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state))
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))
        val d = t.directions
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 350, road = "Galle Rd"), d.turn)
        assertEquals(NextTurn(TurnKind.NORMAL, TurnSide.LEFT, road = "Duplication Rd"), d.then)
        assertEquals(
            listOf(
                Lane(listOf(TurnKind.NORMAL to TurnSide.LEFT), highlighted = false),
                Lane(listOf(TurnKind.STRAIGHT to TurnSide.NONE, TurnKind.SLIGHT to TurnSide.RIGHT), highlighted = true),
            ),
            d.lanes,
        )
        assertEquals("18:42", d.etaText)
        assertEquals(1320L, d.etaSeconds)
        assertEquals(12400, d.remainingM)
    }

    @Test
    fun legacyMessagesGiveTurnOnlyDirections() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        t.onMessage(MsgType.NEXTTURNDISTANCEANDTIME_VALUE, distance(200))
        assertEquals(Directions(NextTurn(TurnKind.NORMAL, TurnSide.LEFT, distanceM = 200, road = "Main St")), t.directions)
    }

    @Test
    fun directionsChangeAloneIsReported() {
        val state = NavigationState.newBuilder().addSteps(step(NavigationManeuver.NavigationType.STRAIGHT)).build().toByteArray()
        val t = NavTurnTracker()
        t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, state)
        val pos = NavigationCurrentPosition.newBuilder().addDestinationDistances(
            NavigationDestinationDistance.newBuilder().setTimeToArrivalSeconds(600),
        ).build().toByteArray()
        assertTrue(t.onMessage(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE, pos))   // only ETA changed
        assertEquals(600L, t.directions.etaSeconds)
    }

    @Test
    fun stopClearsDirections() {
        val t = NavTurnTracker()
        t.onMessage(MsgType.NEXTTURNDETAILS_VALUE, detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT))
        t.onMessage(MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, ByteArray(0))
        assertEquals(Directions.NONE, t.directions)
    }

    @Test
    fun laneShapesMapToArrows() {
        val m = NavTurnTracker.Companion
        for (s in NavigationLane.LaneDirection.Shape.values()) m.fromLaneShape(s)   // must not throw
        assertEquals(TurnKind.SHARP to TurnSide.RIGHT, m.fromLaneShape(NavigationLane.LaneDirection.Shape.SHARP_RIGHT))
        assertEquals(TurnKind.U_TURN to TurnSide.LEFT, m.fromLaneShape(NavigationLane.LaneDirection.Shape.U_TURN_LEFT))
        assertEquals(TurnKind.STRAIGHT to TurnSide.NONE, m.fromLaneShape(NavigationLane.LaneDirection.Shape.UNKNOWN))
    }

    @Test
    fun handlesOnlyNavTypes() {
        assertTrue(NavTurnTracker.handles(MsgType.NEXTTURNDETAILS_VALUE))
        assertTrue(NavTurnTracker.handles(MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE))
        assertFalse(NavTurnTracker.handles(1234))
    }
```

- [ ] **Step 2: Run to see them fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.aa.NavTurnTrackerTest" $F`
Expected: compile errors `Unresolved reference 'Directions'` / `'directions'`.

- [ ] **Step 3: Data types.** Create `Directions.kt`:

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** One road lane: the arrows painted on it and whether Maps says to use it. */
data class Lane(val arrows: List<Pair<TurnKind, TurnSide>>, val highlighted: Boolean)

/**
 * Everything the Directions band can show, as Android Auto last sent it. Parts Maps doesn't send stay
 * null/empty and are simply not drawn. [etaSeconds] is "seconds to arrival" at the time of the message.
 */
data class Directions(
    val turn: NextTurn?,
    val then: NextTurn? = null,
    val lanes: List<Lane> = emptyList(),
    val etaText: String? = null,
    val etaSeconds: Long? = null,
    val remainingM: Int? = null,
) {
    companion object {
        val NONE = Directions(null)
    }
}
```

- [ ] **Step 4: Tracker.** In `NavTurnTracker.kt`:

1. Add imports `dev.zanderp.opencfmoto.Directions`, `dev.zanderp.opencfmoto.Lane`, `dev.zanderp.opencfmoto.aa.proto.NavigationStatus.NavigationLane`.
2. Below `var current: NextTurn? = null  private set` add:

```kotlin

    /** Full directions for the Directions band (turn + then/lanes/ETA when the newer messages carry them). */
    var directions: Directions = Directions.NONE
        private set

    private var then: NextTurn? = null
    private var lanes: List<Lane> = emptyList()
    private var etaText: String? = null
    private var etaSeconds: Long? = null
    private var remainingM: Int? = null
```

3. In the `INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE` branch, inside `if (st.stepsCount > 0) { … }` after `road = …` add:

```kotlin
                    lanes = step.lanesList.map { l ->
                        Lane(l.laneDirectionsList.map { fromLaneShape(it.shape) }, l.laneDirectionsList.any { it.isHighlighted })
                    }
                    then = if (st.stepsCount > 1) {
                        val s2 = st.getSteps(1)
                        val (k2, sd2) = fromNavigationType(s2.maneuver.type)
                        NextTurn(k2, sd2, road = s2.road.name.takeIf { s2.hasRoad() && it.isNotBlank() })
                    } else null
```

4. In the `INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE` branch after the `distanceM` line add:

```kotlin
                if (p.destinationDistancesCount > 0) {
                    val dd = p.getDestinationDistances(0)
                    etaText = dd.estimatedTimeAtArrival.takeIf { dd.hasEstimatedTimeAtArrival() && it.isNotBlank() }
                    etaSeconds = if (dd.hasTimeToArrivalSeconds()) dd.timeToArrivalSeconds else null
                    remainingM = if (dd.hasDistance()) dd.distance.meters else null
                }
```

5. Replace the tail of `onMessage` (from `val next = kind?.let …` to the final `return true`) with:

```kotlin
        val next = kind?.let { NextTurn(it, side, exit, distanceM, road) }
        val dir = if (next == null) Directions.NONE else Directions(next, then, lanes, etaText, etaSeconds, remainingM)
        if (next == current && dir == directions && reported) return false
        current = next
        directions = dir
        reported = true
        return true
```

6. In `clear()` also reset: `then = null; lanes = emptyList(); etaText = null; etaSeconds = null; remainingM = null`.
7. In `companion object` add:

```kotlin
        private val NAV_TYPES = setOf(
            MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
            MsgType.INSTRUMENT_CLUSTER_START_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE,
            MsgType.NEXTTURNDETAILS_VALUE,
            MsgType.NEXTTURNDISTANCEANDTIME_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE,
            MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE,
        )

        /** True for the message types [onMessage] understands (used to keep the route "fresh"). */
        fun handles(type: Int): Boolean = type in NAV_TYPES

        internal fun fromLaneShape(s: NavigationLane.LaneDirection.Shape): Pair<TurnKind, TurnSide> = when (s) {
            NavigationLane.LaneDirection.Shape.SLIGHT_LEFT -> TurnKind.SLIGHT to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.SLIGHT_RIGHT -> TurnKind.SLIGHT to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.NORMAL_LEFT -> TurnKind.NORMAL to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.NORMAL_RIGHT -> TurnKind.NORMAL to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.SHARP_LEFT -> TurnKind.SHARP to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.SHARP_RIGHT -> TurnKind.SHARP to TurnSide.RIGHT
            NavigationLane.LaneDirection.Shape.U_TURN_LEFT -> TurnKind.U_TURN to TurnSide.LEFT
            NavigationLane.LaneDirection.Shape.U_TURN_RIGHT -> TurnKind.U_TURN to TurnSide.RIGHT
            else -> TurnKind.STRAIGHT to TurnSide.NONE
        }
```

- [ ] **Step 5: Run tests.** Same command as Step 2. Expected: PASS (all NavTurnTracker tests, including the 6 new).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/Directions.kt app/src/main/java/dev/zanderp/opencfmoto/aa/NavTurnTracker.kt app/src/test/java/dev/zanderp/opencfmoto/aa/NavTurnTrackerTest.kt
git commit -m "feat(nav): parse then/lanes/ETA/remaining into Directions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Directions formatting and fit plan

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/Directions.kt` (append)
- Test: `app/src/test/java/dev/zanderp/opencfmoto/DirectionsFormatTest.kt`

**Interfaces:**
- Consumes: `Directions`, `Lane`, `NextTurn`, `MapUnits`, `MetricsFormat.turnDistance(meters, imperial)` (existing).
- Produces:
  - `data class DirectionsPlan(val road: Boolean, val then: Boolean, val lanes: Boolean, val eta: Boolean)`
  - `data class DirectionsView(val turn: NextTurn, val distance: String, val road: String?, val then: NextTurn?, val lanes: List<Lane>, val eta: String?, val remaining: String?)`
  - `object DirectionsFormat` with `const val STALE_MS = 60_000L`, `const val THEN_MAX_M = 1000`, `fun active(d: Directions?, lastMsgElapsedMs: Long, nowElapsedMs: Long): Boolean`, `fun etaText(mapsText: String?, etaSeconds: Long?, receivedAtWallMs: Long, zone: java.util.TimeZone, is24h: Boolean): String?`, `fun plan(bandW: Int, bandH: Int, hasRoad: Boolean, hasThen: Boolean, laneCount: Int, hasEta: Boolean): DirectionsPlan`, `fun view(d: Directions, units: MapUnits, bandW: Int, bandH: Int, receivedAtWallMs: Long, zone: java.util.TimeZone, is24h: Boolean): DirectionsView?`

- [ ] **Step 1: Failing test.**

```kotlin
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DirectionsFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val turn = NextTurn(TurnKind.NORMAL, TurnSide.RIGHT, distanceM = 350, road = "Galle Rd")
    private val lanes = listOf(Lane(listOf(TurnKind.STRAIGHT to TurnSide.NONE), true))

    @Test
    fun staleAfterSixtySeconds() {
        val d = Directions(turn)
        assertTrue(DirectionsFormat.active(d, lastMsgElapsedMs = 1_000, nowElapsedMs = 61_000))
        assertFalse(DirectionsFormat.active(d, lastMsgElapsedMs = 1_000, nowElapsedMs = 61_001))
        assertFalse(DirectionsFormat.active(Directions.NONE, 1_000, 1_000))
        assertFalse(DirectionsFormat.active(null, 1_000, 1_000))
    }

    @Test
    fun etaPrefersMapsText() {
        assertEquals("ETA 18:42", DirectionsFormat.etaText("18:42", 1320, 0, utc, true))
    }

    @Test
    fun etaFromSecondsInClockFormat() {
        // 1970-01-01 00:00 UTC + 18h42m
        val secs = 18L * 3600 + 42 * 60
        assertEquals("ETA 18:42", DirectionsFormat.etaText(null, secs, 0, utc, is24h = true))
        assertEquals("ETA 6:42 PM", DirectionsFormat.etaText(null, secs, 0, utc, is24h = false))
        assertNull(DirectionsFormat.etaText(null, null, 0, utc, true))
    }

    @Test
    fun everythingFitsOnAWideBand() {
        assertEquals(DirectionsPlan(road = true, then = true, lanes = true, eta = true),
            DirectionsFormat.plan(640, 64, hasRoad = true, hasThen = true, laneCount = 3, hasEta = true))
    }

    @Test
    fun dropOrderLanesThenRoadThenEta() {
        // Widths at h=64: turn 166, road +52 (218), then 90, 3 lanes 115, eta 154.
        assertEquals(DirectionsPlan(true, true, false, true), DirectionsFormat.plan(480, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(true, false, false, true), DirectionsFormat.plan(380, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(false, false, false, true), DirectionsFormat.plan(330, 64, true, true, 3, true))
        assertEquals(DirectionsPlan(false, false, false, false), DirectionsFormat.plan(200, 64, true, true, 3, true))
    }

    @Test
    fun missingPartsAreNotPlanned() {
        assertEquals(DirectionsPlan(false, false, false, false), DirectionsFormat.plan(640, 64, false, false, 0, false))
    }

    @Test
    fun thenOnlyWithinOneKilometre() {
        val then = NextTurn(TurnKind.NORMAL, TurnSide.LEFT)
        val near = DirectionsFormat.view(Directions(turn.copy(distanceM = 1000), then), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        val far = DirectionsFormat.view(Directions(turn.copy(distanceM = 1001), then), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals(then, near.then)
        assertNull(far.then)
    }

    @Test
    fun viewFormatsDistancesPerUnits() {
        val d = Directions(turn, lanes = lanes, etaText = "18:42", remainingM = 12400)
        val m = DirectionsFormat.view(d, MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals("350 m", m.distance)
        assertEquals("Galle Rd", m.road)
        assertEquals("12.4 km", m.remaining)
        assertEquals("ETA 18:42", m.eta)
        assertEquals(lanes, m.lanes)
        val i = DirectionsFormat.view(d, MapUnits.IMPERIAL, 640, 64, 0, utc, true)!!
        assertEquals("0.2 mi", i.distance)
        assertEquals("7.7 mi", i.remaining)
    }

    @Test
    fun noTurnNoView() {
        assertNull(DirectionsFormat.view(Directions.NONE, MapUnits.METRIC, 640, 64, 0, utc, true))
    }

    @Test
    fun unknownDistanceShowsDashes() {
        val v = DirectionsFormat.view(Directions(turn.copy(distanceM = null)), MapUnits.METRIC, 640, 64, 0, utc, true)!!
        assertEquals(MetricsFormat.NONE, v.distance)
    }
}
```

- [ ] **Step 2: Run to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests "dev.zanderp.opencfmoto.DirectionsFormatTest" $F`
Expected: compile error `Unresolved reference 'DirectionsFormat'`.

- [ ] **Step 3: Implement** — append to `Directions.kt` (add imports `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.Locale`, `java.util.TimeZone`, `kotlin.math.roundToInt` at the top):

```kotlin

/** Which optional parts fit the band this frame. */
data class DirectionsPlan(val road: Boolean, val then: Boolean, val lanes: Boolean, val eta: Boolean)

/** Display strings for one band frame. Data-class equality = "nothing visible changed". */
data class DirectionsView(
    val turn: NextTurn,
    val distance: String,
    val road: String?,
    val then: NextTurn?,
    val lanes: List<Lane>,
    val eta: String?,
    val remaining: String?,
)

object DirectionsFormat {
    const val STALE_MS = 60_000L
    const val THEN_MAX_M = 1000

    // Cell widths as multiples of the band height (used to decide what fits; the renderer uses the same).
    internal const val TURN_W = 2.6f
    internal const val ROAD_EXTRA_W = 0.8f
    internal const val THEN_W = 1.4f
    internal const val LANE_W = 0.5f
    internal const val LANES_PAD_W = 0.3f
    internal const val ETA_W = 2.4f

    /** A route is shown while there's a next turn and Maps spoke within [STALE_MS]. */
    fun active(d: Directions?, lastMsgElapsedMs: Long, nowElapsedMs: Long): Boolean =
        d?.turn != null && nowElapsedMs - lastMsgElapsedMs <= STALE_MS

    /** Maps' own arrival text when sent, else message time + seconds in the phone's clock format. */
    fun etaText(mapsText: String?, etaSeconds: Long?, receivedAtWallMs: Long, zone: TimeZone, is24h: Boolean): String? {
        if (!mapsText.isNullOrBlank()) return "ETA $mapsText"
        val s = etaSeconds ?: return null
        val fmt = SimpleDateFormat(if (is24h) "H:mm" else "h:mm a", Locale.US).apply { timeZone = zone }
        return "ETA " + fmt.format(Date(receivedAtWallMs + s * 1000L))
    }

    /** Fit the optional parts into [bandW]; drop order lanes → then → road → ETA. */
    fun plan(bandW: Int, bandH: Int, hasRoad: Boolean, hasThen: Boolean, laneCount: Int, hasEta: Boolean): DirectionsPlan {
        val h = bandH.toFloat()
        var road = hasRoad
        var then = hasThen
        var lanes = laneCount > 0
        var eta = hasEta
        fun width(): Float = h * (TURN_W + (if (road) ROAD_EXTRA_W else 0f) + (if (then) THEN_W else 0f) +
            (if (lanes) laneCount * LANE_W + LANES_PAD_W else 0f) + (if (eta) ETA_W else 0f))
        if (width() > bandW) lanes = false
        if (width() > bandW) then = false
        if (width() > bandW) road = false
        if (width() > bandW) eta = false
        return DirectionsPlan(road, then, lanes, eta)
    }

    fun view(
        d: Directions,
        units: MapUnits,
        bandW: Int,
        bandH: Int,
        receivedAtWallMs: Long,
        zone: TimeZone,
        is24h: Boolean,
    ): DirectionsView? {
        val turn = d.turn ?: return null
        val imperial = units == MapUnits.IMPERIAL
        val thenCandidate = d.then?.takeIf { (turn.distanceM ?: Int.MAX_VALUE) <= THEN_MAX_M }
        val eta = etaText(d.etaText, d.etaSeconds, receivedAtWallMs, zone, is24h)
        val p = plan(bandW, bandH, !turn.road.isNullOrBlank(), thenCandidate != null, d.lanes.size, eta != null)
        return DirectionsView(
            turn = turn,
            distance = turn.distanceM?.let { MetricsFormat.turnDistance(it, imperial) } ?: MetricsFormat.NONE,
            road = turn.road.takeIf { p.road },
            then = thenCandidate.takeIf { p.then },
            lanes = if (p.lanes) d.lanes else emptyList(),
            eta = eta.takeIf { p.eta },
            remaining = d.remainingM?.takeIf { p.eta }?.let { MetricsFormat.turnDistance(it, imperial) },
        )
    }
}
```

- [ ] **Step 4: Run tests.** Same command. Expected: PASS (10 tests). If `dropOrderLanesThenRoadThenEta` fails on the boundary widths, the formula differs from the test comment (widths at h=64: turn 166.4, road 51.2, then 89.6, 3 lanes 115.2, eta 153.6) — fix the code, not the test.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/Directions.kt app/src/test/java/dev/zanderp/opencfmoto/DirectionsFormatTest.kt
git commit -m "feat(strip): directions formatting — ETA, distances, fit plan, staleness

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: DirectionsRenderer

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/MetricsStripRenderer.kt` (`drawArrow`, `fit` → `internal`)
- Create: `app/src/main/java/dev/zanderp/opencfmoto/DirectionsRenderer.kt`

**Interfaces:**
- Consumes: `DirectionsView`, `DirectionsFormat` width constants, `MetricsStripRenderer.drawArrow(c, t, left, top, s)`, `MetricsStripRenderer.fit(text, paint, maxW)`.
- Produces: `object DirectionsRenderer { fun render(v: DirectionsView, w: Int, h: Int): Bitmap }`.

No JVM test (Android Canvas); verified by build and the bike check.

- [ ] **Step 1: Share helpers.** In `MetricsStripRenderer.kt` change `private fun drawArrow(` → `internal fun drawArrow(` and `private fun fit(` → `internal fun fit(`.

- [ ] **Step 2: Create `DirectionsRenderer.kt`.**

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

/**
 * Draws one Directions band frame: [next turn | then | lanes | ETA]. Called only from the single
 * `metrics-strip` thread (shared Paints). New bitmap per call; the compositor recycles it.
 */
object DirectionsRenderer {
    private const val GREY = 0xFFAAAAAA.toInt()
    private const val DIM = 0xFF555555.toInt()
    private const val DIVIDER = 0xFF333333.toInt()

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val divider = Paint().apply { color = DIVIDER; strokeWidth = 2f }

    fun render(v: DirectionsView, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val hf = h.toFloat()
        var x = 0f

        // 1) Next turn: arrow + distance, road under it.
        val turnW = hf * (DirectionsFormat.TURN_W + if (v.road != null) DirectionsFormat.ROAD_EXTRA_W else 0f)
        val box = hf * 0.9f
        val pad = box * 0.12f
        MetricsStripRenderer.drawArrow(c, v.turn, x + pad, (hf - box) / 2f + pad, box - 2 * pad)
        val textX = x + box + hf * 0.1f
        val textW = turnW - box - hf * 0.15f
        big.textSize = hf * (if (v.road != null) 0.42f else 0.55f)
        val distBase = if (v.road != null) hf * 0.48f else hf * 0.68f
        c.drawText(MetricsStripRenderer.fit(v.distance, big, textW), textX, distBase, big)
        if (v.road != null) {
            small.textSize = hf * 0.24f
            c.drawText(MetricsStripRenderer.fit(v.road, small, textW), textX, hf * 0.86f, small)
        }
        x += turnW

        // 2) Then: small "then" + arrow.
        if (v.then != null) {
            c.drawLine(x, hf * 0.15f, x, hf * 0.85f, divider)
            val cellW = hf * DirectionsFormat.THEN_W
            small.textSize = hf * 0.24f
            c.drawText("then", x + hf * 0.12f, hf * 0.6f, small)
            val s = hf * 0.55f
            MetricsStripRenderer.drawArrow(c, v.then, x + cellW - s - hf * 0.1f, (hf - s) / 2f, s)
            x += cellW
        }

        // 3) Lanes: one arrow per lane (first arrow of each lane); lanes to use bright, others dim.
        if (v.lanes.isNotEmpty()) {
            c.drawLine(x, hf * 0.15f, x, hf * 0.85f, divider)
            val laneW = hf * DirectionsFormat.LANE_W
            var lx = x + hf * DirectionsFormat.LANES_PAD_W / 2f
            val s = laneW * 0.9f
            for (lane in v.lanes) {
                val (kind, side) = lane.arrows.firstOrNull() ?: (TurnKind.STRAIGHT to TurnSide.NONE)
                val bmpLane = Bitmap.createBitmap(s.toInt().coerceAtLeast(1), s.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                MetricsStripRenderer.drawArrow(Canvas(bmpLane), NextTurn(kind, side), 0f, 0f, s)
                val tint = Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        if (lane.highlighted) Color.WHITE else DIM, android.graphics.PorterDuff.Mode.SRC_IN,
                    )
                }
                c.drawBitmap(bmpLane, lx, (hf - s) / 2f, tint)
                bmpLane.recycle()
                lx += laneW
            }
            x += laneW * v.lanes.size + hf * DirectionsFormat.LANES_PAD_W
        }

        // 4) ETA + remaining, right-aligned.
        if (v.eta != null) {
            val etaW = hf * DirectionsFormat.ETA_W
            val left = (w - etaW).coerceAtLeast(x)
            c.drawLine(left, hf * 0.15f, left, hf * 0.85f, divider)
            val right = w - hf * 0.15f
            big.textSize = hf * 0.36f
            big.textAlign = Paint.Align.RIGHT
            c.drawText(MetricsStripRenderer.fit(v.eta, big, etaW * 0.9f), right, hf * 0.48f, big)
            big.textAlign = Paint.Align.LEFT
            if (v.remaining != null) {
                small.textSize = hf * 0.24f
                small.textAlign = Paint.Align.RIGHT
                c.drawText(v.remaining, right, hf * 0.86f, small)
                small.textAlign = Paint.Align.LEFT
            }
        }
        return bmp
    }
}
```

- [ ] **Step 3: Build.** Run: `./gradlew :app:assembleDebug $F` — Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStripRenderer.kt app/src/main/java/dev/zanderp/opencfmoto/DirectionsRenderer.kt
git commit -m "feat(strip): Canvas renderer for the directions band

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Wire it — MetricsStrip, compositor overlay, pipeline, nav channel

**Files:**
- Modify: `MetricsStrip.kt`, `AaCompositor.kt`, `VideoPipeline.kt`, `aa/AapControl.kt`, `aa/AaReceiver.kt` (all under `app/src/main/java/dev/zanderp/opencfmoto/`)

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `MetricsStrip.start(ctx, canvasW, stripPx, sink, log, cells = ALL, mode = StripMode.METRICS, onVisible: (Boolean) -> Unit = {})`, `MetricsStrip.setDirections(d: Directions)`, `MetricsStrip.clearNavigation()`; `AaCompositor.setStrip(px: Int, overlay: Boolean = false)`, `AaCompositor.setStripVisible(visible: Boolean)`.

No new JVM tests (Android-bound glue); the pure behaviour is pinned by Tasks 1–3. Verified by build, full suite and the bike check.

- [ ] **Step 1: `MetricsStrip`.**
  1. Add fields next to the others:

```kotlin
    @Volatile private var mode = StripMode.METRICS
    @Volatile private var onVisible: (Boolean) -> Unit = {}
    @Volatile private var directions: Directions = Directions.NONE
    @Volatile private var navAtElapsedMs = 0L
    @Volatile private var navAtWallMs = 0L
    @Volatile private var is24h = true
    private var lastView: DirectionsView? = null     // metrics-strip thread only
    private var lastVisible: Boolean? = null         // metrics-strip thread only
```

  2. Extend `start(...)` with parameters `mode: StripMode = StripMode.METRICS, onVisible: (Boolean) -> Unit = {}`; inside, after `this.cells = cells` add:

```kotlin
        this.mode = mode
        this.onVisible = onVisible
        is24h = android.text.format.DateFormat.is24HourFormat(app)
        directions = Directions.NONE
        lastView = null
        lastVisible = null
```

  Only start the GPS source in Metrics mode — wrap the three `MetricsSource` lines (`val src = …`, `source = src`, `if (!src.start()) …`) in `if (mode == StripMode.METRICS) { … }`. Change the start log to `log("[STRIP] started ${canvasW}x$stripPx units=$units mode=$mode" + if (mode == StripMode.METRICS) " cells=${StripCells.serialize(cells)}" else " overlay")`.
  3. In `stop()` also set `directions = Directions.NONE` and `onVisible = {}`.
  4. Add public functions below `setTurn`:

```kotlin
    /** Directions from Android Auto; called for every nav message (keeps the route fresh). Any thread. */
    fun setDirections(d: Directions) {
        directions = d
        navAtElapsedMs = SystemClock.elapsedRealtime()
        navAtWallMs = System.currentTimeMillis()
        val ex = synchronized(this) { executor }
        try { ex?.execute { refresh() } } catch (_: RejectedExecutionException) {}
    }

    /** AA session ended: no route any more (both modes). */
    fun clearNavigation() {
        setTurn(null)
        setDirections(Directions.NONE)
    }
```

  5. Replace the body of `refresh()`'s `try` block with:

```kotlin
            val out = sink ?: return
            if (mode == StripMode.DIRECTIONS) {
                refreshDirections(out)
                return
            }
            val v = MetricsFormat.values(metrics, units, SystemClock.elapsedRealtime())
            if (v == lastValues) return
            val bmp = MetricsStripRenderer.render(v, stripW, stripH, cells)
            lastValues = v
            out(bmp)
```

  and add:

```kotlin
    private fun refreshDirections(out: (Bitmap) -> Unit) {
        val d = directions
        val now = SystemClock.elapsedRealtime()
        val active = DirectionsFormat.active(d, navAtElapsedMs, now)
        if (active != lastVisible) {
            if (active) log("[STRIP] band shown")
            else log("[STRIP] band hidden (" + (if (d.turn != null) "route stale" else "no route") + ")")
            lastVisible = active
            if (!active) lastView = null
            onVisible(active)
        }
        if (!active) return
        val v = DirectionsFormat.view(d, units, stripW, stripH, navAtWallMs, java.util.TimeZone.getDefault(), is24h) ?: return
        if (v == lastView) return
        lastView = v
        out(DirectionsRenderer.render(v, stripW, stripH))
    }
```

- [ ] **Step 2: `AaCompositor`.**
  1. Next to `@Volatile private var stripPx = 0` add `@Volatile private var stripOverlay = false` and `@Volatile private var stripVisible = true`, and a helper `private fun stripGeometry() = StripGeometry.of(stripPx, stripOverlay, stripVisible)`.
  2. Replace `setStrip(px: Int)` with:

```kotlin
    /**
     * Band of [px] dash pixels on the bike output (0 = off). [overlay] false = Metrics (reserved under AA);
     * true = Directions (AA full screen, band drawn over it only while [setStripVisible] is true).
     * Call before [setOutput].
     */
    fun setStrip(px: Int, overlay: Boolean = false) {
        handler.post {
            stripPx = px.coerceAtLeast(0)
            stripOverlay = overlay
            stripVisible = !overlay
            if (stripPx == 0) stripReady = false
            computeViewport()
        }
    }

    /** Directions mode: show/hide the band (route active or not) and re-emit a frame. */
    fun setStripVisible(visible: Boolean) {
        handler.post {
            if (stripVisible == visible) return@post
            stripVisible = visible
            if (hasContent) drawFrame()
        }
    }
```

  3. In `computeViewport()` change `val aaCanvasH = (canvasH - stripPx).coerceAtLeast(1)` to `val aaCanvasH = (canvasH - stripGeometry().reservePx).coerceAtLeast(1)`.
  4. In `mapCanvasToSource` change `MetricsStripLayout.touchY(cy, canvasH, stripPx, isDown)` to `MetricsStripLayout.touchY(cy, canvasH, stripGeometry().touchPx, isDown)`.
  5. In `renderTo` replace the lines

```kotlin
        val band = if (isBike) stripPx else 0
        val shift = if (band > 0) MetricsStripLayout.glAaShift(band) else 0
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, shift, clipW, clipH - band)
        }
```

  with

```kotlin
        val g = if (isBike) stripGeometry() else StripGeometry(0, 0, 0)
        val shift = if (g.reservePx > 0) MetricsStripLayout.glAaShift(g.reservePx) else 0
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, shift, clipW, clipH - g.reservePx)
        }
```

  and the later `if (band > 0) { try { drawStrip(band) } … }` with `if (g.drawPx > 0) { try { drawStrip(g.drawPx) } catch (e: Exception) { logStripFailure("draw", e) } }`.
  6. In the `[STRIP] on canvas=…` log line (inside `setOutput`'s handler block) append `" overlay=$stripOverlay"` to the message.

- [ ] **Step 3: `VideoPipeline`.** In the bike-canvas configuration block replace `aaCompositor?.setStrip(stripPx)` with:

```kotlin
            val session = BikeProfileHolder.stripSession
            aaCompositor?.setStrip(stripPx, overlay = session.overlay)
```

  and the `MetricsStrip.start(...)` call inside `stripGuarded` with:

```kotlin
                stripGuarded(log, "start") {
                    MetricsStrip.start(
                        context, w, stripPx, comp::setStripBitmap, log, cells,
                        mode = session.mode, onVisible = comp::setStripVisible,
                    )
                }
```

  (keep the preceding `val cells = BikeProfileHolder.stripSession.cells`). After `if (stripReq > 0 && stripPx == 0) { … }` add `if (session.overlay && stripPx > 0) log("[STRIP] mode=DIRECTIONS overlay band=${stripPx}px")` (declare `session` before this line if needed).

- [ ] **Step 4: `AapControlNav`** (`aa/AapControl.kt`). Add a field `private var lastParts: String? = null`. After the `val changed = try { … } catch { … }` block insert:

```kotlin
        // Every understood nav message keeps the Directions band's route fresh (60 s staleness).
        if (NavTurnTracker.handles(message.type) && !failedTypes.contains(message.type)) {
            val d = tracker.directions
            dev.zanderp.opencfmoto.MetricsStrip.setDirections(d)
            val parts = "then=${if (d.then != null) "y" else "n"} lanes=${d.lanes.size} " +
                "eta=${if (d.etaText != null || d.etaSeconds != null) "y" else "n"}"
            if (d.turn != null && parts != lastParts) {
                lastParts = parts
                dev.zanderp.opencfmoto.LogBus.log("[NAV] parts: $parts")
            }
        }
```

  (`failedTypes` holds types that failed to parse at least once; a later good message of that type still refreshes on the next change — acceptable.)

- [ ] **Step 5: `AaReceiver`.** In the transport-quit handler replace `dev.zanderp.opencfmoto.MetricsStrip.setTurn(null)` with `dev.zanderp.opencfmoto.MetricsStrip.clearNavigation()`.

- [ ] **Step 6: Build + suite.** Run: `./gradlew :app:assembleDebug $F` then `./gradlew :app:testDebugUnitTest $F`. Expected: build succeeds; only the known YunmoFrameTest fails.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/MetricsStrip.kt app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt app/src/main/java/dev/zanderp/opencfmoto/aa/AaReceiver.kt
git commit -m "feat(strip): directions band drawn over Android Auto while a route is active

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Setup — "Strip shows: Metrics · Directions"

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/layout/activity_setup.xml`, `app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt`

**Interfaces:**
- Consumes: `MetricsStripPrefs.mode/setMode`, `StripMode` (Task 1).

- [ ] **Step 1: Strings** (append inside `<resources>`):

```xml
    <string name="setup_strip_mode">Strip shows</string>
    <string name="setup_strip_mode_desc">Directions: next turn, lanes and arrival time from Google Maps — only while navigating, over the bottom of Android Auto (which keeps the full screen). Metrics: speed / altitude / heading. Applies next connect.</string>
    <string name="pref_strip_mode_metrics">Metrics</string>
    <string name="pref_strip_mode_directions">Directions</string>
    <string name="setup_toast_strip_mode">Strip shows: %1$s</string>
```

- [ ] **Step 2: Layout.** Run this from the repo root (inserts the mode row after the strip size rows and wraps the existing cell-choice block in `strip_cells_group`):

```bash
python - <<'EOF'
p = 'app/src/main/res/layout/activity_setup.xml'
s = open(p, encoding='utf-8').read()
anchor = '                        android:text="@string/pref_strip_large" />\n                </LinearLayout>\n'
assert s.count(anchor) == 1
i = s.index(anchor) + len(anchor)
end_marker = 'android:id="@+id/strip_cell_heading"'
j = s.index(end_marker, i)
k = s.index('</LinearLayout>\n', s.index('<Space', j)) + len('</LinearLayout>\n')
cells = s[i:k]
mode_row = '''
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:text="@string/setup_strip_mode"
                    android:textColor="@color/text_primary"
                    android:textSize="15sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:layout_marginBottom="6dp"
                    android:text="@string/setup_strip_mode_desc"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:baselineAligned="false"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_mode_metrics"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginEnd="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_mode_metrics" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/strip_mode_directions"
                        style="@style/Widget.OpenCfMoto.Segment"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="4dp"
                        android:layout_weight="1"
                        android:text="@string/pref_strip_mode_directions" />
                </LinearLayout>

                <LinearLayout
                    android:id="@+id/strip_cells_group"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical">
'''
s = s[:i] + mode_row + cells + '                </LinearLayout>\n' + s[k:]
open(p, 'w', encoding='utf-8').write(s)
print('ok')
EOF
```

Expected: prints `ok`.

- [ ] **Step 3: Activity.** In `SetupActivity.kt`:
  1. Next to the strip click listeners add:

```kotlin
        findViewById<MaterialButton>(R.id.strip_mode_metrics).setOnClickListener { setStripMode(StripMode.METRICS) }
        findViewById<MaterialButton>(R.id.strip_mode_directions).setOnClickListener { setStripMode(StripMode.DIRECTIONS) }
```

  2. Add below `setStripHeight`:

```kotlin
    private fun setStripMode(m: StripMode) {
        MetricsStripPrefs.setMode(this, m)
        // Directions needs a visible band; start at Medium if the strip was Off.
        if (m == StripMode.DIRECTIONS && MetricsStripPrefs.height(this) == StripHeight.OFF) {
            MetricsStripPrefs.setHeight(this, StripHeight.MEDIUM)
        }
        refreshOptions()
        toast(getString(R.string.setup_toast_strip_mode, getString(
            if (m == StripMode.DIRECTIONS) R.string.pref_strip_mode_directions else R.string.pref_strip_mode_metrics,
        )))
    }
```

  3. In `refreshOptions()` replace the block from `highlight(MetricsStripPrefs.turnInfo(this),` through the end of the `for ((id, cell) in listOf(…)) { … }` loop with:

```kotlin
        val stripMode = MetricsStripPrefs.mode(this)
        val directionsMode = stripMode == StripMode.DIRECTIONS
        highlight(stripMode,
            R.id.strip_mode_metrics to StripMode.METRICS,
            R.id.strip_mode_directions to StripMode.DIRECTIONS)
        // Directions requires turn info: show it as On and locked.
        highlight(MetricsStripPrefs.turnInfo(this) || directionsMode,
            R.id.turninfo_on to true,
            R.id.turninfo_off to false)
        val stripOn = MetricsStripPrefs.height(this) != StripHeight.OFF
        findViewById<MaterialButton>(R.id.turninfo_on).isEnabled = stripOn && !directionsMode
        findViewById<MaterialButton>(R.id.turninfo_off).isEnabled = stripOn && !directionsMode
        findViewById<View>(R.id.strip_cells_group).visibility = if (directionsMode) View.GONE else View.VISIBLE
        val shownCells = MetricsStripPrefs.cells(this)
        for ((id, cell) in listOf(
            R.id.strip_cell_speed to StripCell.SPEED,
            R.id.strip_cell_altitude to StripCell.ALTITUDE,
            R.id.strip_cell_heading to StripCell.HEADING,
        )) {
            highlight(cell in shownCells, id to true)
            findViewById<MaterialButton>(id).isEnabled = stripOn
        }
```

  (Add `import android.view.View` if missing.)

- [ ] **Step 4: Build + emulator check.** `./gradlew :app:assembleRelease -Pabi=x86_64 $F`; install on the AVD `ocm_test` (`ANDROID_AVD_HOME=F:\opencfmoto-gradle\avd`); open Settings → Screen & display: tap **Directions** → strip size becomes Medium if it was Off, the Speed/Altitude/Heading block disappears, Turn info shows **On** and is greyed out; tap **Metrics** → cells return, Turn info editable. Screenshot both. `adb logcat -d -b crash | grep -c opencfmoto` → `0`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_setup.xml app/src/main/java/dev/zanderp/opencfmoto/SetupActivity.kt
git commit -m "feat(strip): Setup choice between Metrics and Directions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Docs and bike checklist

**Files:**
- Modify: `docs/02-CURRENT-APP.md`, `README.md`

- [ ] **Step 1: Docs.** Append to `docs/02-CURRENT-APP.md`:

```markdown

## Directions band (metrics strip mode)

Spec: `docs/superpowers/specs/2026-09-30-directions-band-design.md`.

- Setup → Strip shows: **Directions** (per bike) → `StripSnapshot.mode = DIRECTIONS`, `overlay = true`,
  `aaReservePx = 0` (AA keeps the full panel), Turn info forced on.
- `aa/NavTurnTracker` builds a `Directions` (turn, then, lanes, ETA, km left); `AapControlNav` feeds it to
  `MetricsStrip.setDirections` on every nav message. `DirectionsFormat` (pure) decides staleness (60 s), the
  "then" rule (≤ 1 km) and what fits; `DirectionsRenderer` draws it.
- `AaCompositor` overlay: `StripGeometry.of(px, overlay, visible)` → band drawn over AA only while
  `setStripVisible(true)`; hidden band = full-screen touches.
- Logs: `[STRIP] mode=DIRECTIONS overlay band=…`, `[STRIP] band shown` / `band hidden (no route|route stale)`,
  `[NAV] parts: then=… lanes=… eta=…`.
```

In `README.md`, in the **Metrics strip** feature row, add after "heading": `— or **Directions**: next turn, lanes and ETA from Google Maps, shown over Android Auto only while navigating`.

Commit: `git add docs/02-CURRENT-APP.md README.md && git commit -m "docs: directions band"` (with trailer).

- [ ] **Step 2: Bike checklist (owner, 150SC).** Setup → Metrics strip Medium, Strip shows **Directions**, Connect:
  1. No route → no band; Android Auto's dock visible and tappable.
  2. Start a Google Maps route → band appears over the dock: arrow + distance (+ road). Log shows `[STRIP] band shown`.
  3. Share Logs → `[NAV] first …` and `[NAV] parts: …` lines tell which extras (then / lanes / ETA) this phone sends.
  4. Tap on the band while navigating → nothing happens in Android Auto.
  5. End the route (or stop Maps) → band disappears within 60 s; `[STRIP] band hidden (…)`.
  6. Switch back to **Metrics** → old strip behaviour unchanged.
