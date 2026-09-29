# Rider Metrics Strip — Design

**Date:** 2026-09-30 · **Status:** Draft for review · **Area:** Android Auto → bike video path

## 1. Goal

While Android Auto (AA) is projected on the bike dash, show a strip of live rider metrics in a
**reserved band at the bottom of the dash**, below the AA map:

`[next turn arrow + distance] | [speed] | [altitude] | [heading]`

Delivered in two phases:

- **Phase 1** — strip with **speed, altitude, heading** from the phone's GPS. No change to the AA
  handshake.
- **Phase 2** — **next-turn** cell fed by AA's navigation-status (instrument cluster) messages,
  behind an experimental switch.

### Non-goals

- **Bike telemetry (RPM, fuel, gear, engine temp).** Not reachable without a T-Box — see
  `docs/RE-VEHICLE-TELEMETRY.md`. All values come from the phone or from AA.
- Strip in the in-app **Dash view** (phone preview). Bike dash only; may be added later.
- Strip in **mirror mode** or the built-in **Map/GPX** mode (which has its own dash widgets).
- Turn images (PNG) from AA; lane guidance; ETA/destination distance. (Can follow later.)
- Changing the strip on/off mid-session. It applies on the next Connect.

### Success criteria

1. With the strip on, AA is drawn only above the band, **not distorted and not cropped** (when the
   panel size is known), and the band shows the metrics.
2. With the strip off (default), video output and the AA handshake are unchanged from today.
3. Metric values update within ~1 s of a GPS fix and never stall AA video.
4. Phase 2: during a Google Maps route, the turn cell shows the correct arrow and distance; the
   handshake with turn info off is byte-for-byte unchanged.
5. A single shared log from one bike ride is enough to diagnose placement and nav data
   (`[STRIP]`, `[NAV]` lines).

## 2. User-facing behaviour

### Settings (Setup → Display, per bike via `BikeScope`)

| Setting | Values | Default |
|---|---|---|
| Metrics strip | On / Off | Off |
| Strip height | Small 48 px · Medium 64 px · Large 80 px (dash pixels) | Medium |
| Turn info (experimental) — phase 2 | On / Off; only enabled when the strip is on | Off |

The height is clamped to at most **25% of the canvas height**. Changes take effect on the **next
Connect**. The settings row says so.

### Layout

Four equal-width cells across the strip (landscape 800×384 → 200 px each):

```
+--------------------+--------------------+--------------------+--------------------+
|  ↱  350 m          |      72            |      412 m         |      NE            |
|  Main St           |      km/h          |      ALT           |      45°           |
+--------------------+--------------------+--------------------+--------------------+
```

- White values on black, grey labels. Value text ≈ 55% of strip height, label ≈ 22%.
- **No turn available** (phase 1, turn info off, or no active route): three cells. Speed takes the
  width of two cells, and its value text is ≈ 65% of strip height.
- Portrait dashes use the same cell order at the same strip height. Cells are just wider or narrower.

### Values

| Cell | Source | Rules | Unavailable |
|---|---|---|---|
| Speed | `Location.speed` (GPS provider) | Rounded to integer km/h or mph | `--` if last fix is older than 5 s or has no speed |
| Altitude | `Location.mslAltitudeMeters` when available (API 34+), else `Location.altitude` | Rounded to nearest 5 m / 10 ft | `--` if stale or none |
| Heading | `Location.bearing` | Updated only while speed > 5 km/h. Otherwise holds the last value. Shown as 8-point compass (N, NE, …) + degrees | `--` until the first moving fix |
| Next turn (phase 2) | AA navigation messages (§5) | Arrow by maneuver type/side (+ roundabout exit number), distance, road name (ellipsized) | Cell hidden |

**Units** follow the existing `MapPrefs.units()` (`METRIC`/`IMPERIAL`):
- Speed: km/h or mph.
- Altitude: m or ft.
- Turn distance, metric: `< 1000 m` → `350 m` (rounded to 10 m), `≥ 1000 m` → `1.2 km`.
- Turn distance, imperial: `< 0.1 mi` → `500 ft` (rounded to 50 ft), otherwise `0.3 mi`.

Compass is **not** used for heading, because a phone in a pocket next to an engine gives unreliable
readings.

## 3. Architecture

```
LocationManager (GPS, 1 s) ─▶ MetricsSource ─┐
                                             ├─▶ RiderMetrics (snapshot) ─▶ MetricsStripRenderer ─▶ Bitmap
AA nav channel ─▶ NavTurnParser ─────────────┘        (phase 2)                  (only on change)
                                                                                     │
AA decoder ─▶ AaCompositor ── draws AA into area above band ◀── pastes strip texture ┘
                    │
                    ▼
            VideoPipeline encoder ─▶ EasyConnProber ─▶ dash
```

### New units

| Unit (file) | Purpose | Interface (sketch) | Depends on |
|---|---|---|---|
| `MetricsStripPrefs.kt` | Per-bike settings | `enabled(ctx)`, `height(ctx): StripHeight`, `turnInfo(ctx)`, setters | `BikeScope` |
| `RiderMetrics.kt` | Immutable snapshot + formatting | `data class RiderMetrics(speedKmh: Float?, altitudeM: Double?, headingDeg: Float?, fixAgeMs: Long, turn: NextTurn?)`. `fun display(units, nowMs): StripValues` (strings). `StripValues` is a data class, so `==` detects change | none (pure) |
| `NextTurn.kt` | Turn model | `data class NextTurn(kind: TurnKind, side: TurnSide, exitNumber: Int?, distanceM: Int?, road: String?)` | none (pure) |
| `MetricsSource.kt` | Owns a GPS listener while projecting, publishes `RiderMetrics` | `start(ctx)`, `stop()`, `setTurn(NextTurn?)`, `@Volatile latest: RiderMetrics`, `onChange: (() -> Unit)?` | `LocationManager` |
| `MetricsStripLayout.kt` | Pure geometry | `fun stripRect(canvasW, canvasH, stripPx): Rect` (top-origin), `fun cells(stripW, stripH, hasTurn): List<Cell>`, `fun glY(topY, h, canvasH): Int` | none (pure) |
| `MetricsStripRenderer.kt` | Draws `StripValues` into an ARGB `Bitmap` with `Canvas`/`Paint`, incl. turn arrows as `Path`s | `fun render(values: StripValues, w, h): Bitmap` (reuses one bitmap) | `MetricsStripLayout` |
| `aa/NavTurnParser.kt` (phase 2) | Turns AA nav protobufs into `NextTurn` / status | `fun onMessage(type: Int, payload: ByteArray): NavUpdate?` | `aa/proto/NavigationStatus` |

`MetricsSource` registers **its own** `LocationManager.GPS_PROVIDER` listener (1000 ms, 0 m).
`TripRecorder` only runs when *Log trips automatically* is on, so we can't depend on it. Android
shares the GPS hardware between listeners, so this costs nothing extra.

### Changes to existing code

| File | Change |
|---|---|
| `AaCompositor.kt` | (a) New field `stripPx` (0 = off). `computeViewport()` insets the bottom by `stripPx` in addition to `ScreenMargins.bottom`. (b) Second GL program: plain `sampler2D` quad for the strip texture. (c) `setStripBitmap(bmp)` posts to the GL thread and uploads with `GLUtils.texSubImage2D` (`texImage2D` on first use or size change). (d) `renderTo(windowSurface…)` draws the strip quad into the band after the AA quad. It isn't drawn for `previewSurface`. |
| `VideoPipeline.kt` | `configureBikeCanvas()` passes the resolved strip height into the compositor. It starts and stops `MetricsSource` with the AA compositor mode. |
| `VideoPrefs.aaMarginsFor()` | When the strip is on, the panel height used for match-aspect is `panelH − stripPx`. AUTO and MANUAL modes subtract it. OFF mode also computes margins from `(panelW, panelH − stripPx)` when a panel size is known, because the strip needs the correct shape. Logged as `[match-aspect] … strip −N px`. |
| `ServiceDiscoveryResponse.kt` (phase 2) | If `turnInfo` is on: add `Service{ id = Channel.ID_NAV, navigationStatusService = { minimumIntervalMs = 1000, type = ImageCodesOnly } }`. Otherwise unchanged. |
| `aa/AapControl.kt` (phase 2) | Route `Channel.ID_NAV` messages (types 32769–32775) to `NavTurnParser`, then `MetricsSource.setTurn(...)`. |
| `SetupActivity.kt` + `activity_setup.xml` + `strings.xml` | Settings rows (§2). New strings in `values/strings.xml`. Translations follow the normal community flow. |

### Threading

- The GPS callback runs on the main looper. It builds a new `RiderMetrics` and calls `onChange`.
- The renderer runs on a single-thread executor (`metrics-strip`). It computes `StripValues` and
  returns if they equal the previous values. Otherwise it renders the bitmap and calls
  `compositor.setStripBitmap()`.
- A 1 s ticker on the same executor re-evaluates staleness, so values fall back to `--` without a
  new fix.
- The GL upload and draw happen only on the existing `aa-compositor` thread.
- AA nav messages arrive on the AA transport thread. `NavTurnParser` is pure. `setTurn` swaps a
  `@Volatile` field and triggers `onChange`.

### Coordinate systems (verify on bike)

`computeViewport()` works in top-origin canvas pixels, but `glViewport` is bottom-origin. The
strip's rectangle is computed in top-origin terms by `MetricsStripLayout` and converted with
`glY()` in exactly one place. On first draw the compositor logs:

`[STRIP] on canvas=800x384 strip=64px rect(top-origin)=0,320,800,64 gl=0,0 aaRect=…`

If the first bike test shows the band at the top, the fix is confined to `glY()`.

## 4. Error handling

- Any exception in `MetricsSource`, the renderer, or the strip GL path is caught and logged once per
  cause as `[STRIP] … failed: …`. The band stays black, and AA video continues. The strip must never
  throw into `drawFrame()`.
- No location permission or GPS off: `MetricsSource` logs `[STRIP] no GPS — showing --`. Cells show
  `--`.
- **Unknown panel size** (first connect of an unknown dash): no match-aspect margins are possible.
  The compositor still reserves the band, and AA is fit/cropped into the smaller area per
  `ScreenFit`. It's logged. The next connect uses the learned `DashMemory` size.
- Strip height clamped to ≤ 25% of canvas height and ≥ 32 px. If the canvas is too small (< 128 px
  tall), the strip is disabled for the session and logged.
- Phase 2: unparseable nav messages are logged (first occurrence per type) and ignored. Nav
  `INACTIVE`/`UNAVAILABLE` or the AA session ending clears the turn. `REROUTING` keeps the
  last turn.

## 5. Phase 2 — AA navigation messages

Both message families are handled because different AA/Maps versions send different ones:

| Msg type | Proto | Used fields → `NextTurn` |
|---|---|---|
| 32771 `INSTRUMENT_CLUSTER_NAVIGATION_STATUS` | `NavigationClusterStatus` | `status`: ACTIVE shows the cell. INACTIVE/UNAVAILABLE clears it. REROUTING keeps the last turn |
| 32772 `NEXTTURNDETAILS` | `NextTurnDetail` | `next_turn` (NextEvent) + `side` → kind/side. `turn_number` → exit number. `road` |
| 32773 `NEXTTURNDISTANCEANDTIME` | `NextTurnDistanceEvent` | `distance_meters` |
| 32774 `INSTRUMENT_CLUSTER_NAVIGATION_STATE` | `NavigationState` | `steps[0].maneuver.type` (NavigationType) → kind/side. `roundabout_exit_number`. `steps[0].road.name` |
| 32775 `INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION` | `NavigationCurrentPosition` | `step_distance.distance.meters` |
| 32769 / 32770 `START` / `STOP` | — | log only; STOP clears the turn |

Distance comes from **meters**. The app formats it (§2) instead of using AA's display strings, so
the units follow the rider's setting.

**`TurnKind`** (arrow set): `STRAIGHT, SLIGHT, NORMAL, SHARP, U_TURN, ROUNDABOUT, MERGE, FORK,
RAMP_ON, RAMP_OFF, DEPART, DESTINATION, UNKNOWN`. **`TurnSide`**: `LEFT, RIGHT, NONE`. Mapping
tables from `NextEvent`/`Side` and `NavigationType` live in `NavTurnParser`, and each enum value is
unit-tested. `UNKNOWN` draws a plain straight arrow with the distance.

Log: `[NAV]` for the first message of each type (summary, no raw bytes) and one line per turn
change: `[NAV] turn → NORMAL RIGHT 350 m "Main St"`.

## 6. Testing

### Unit tests (`app/src/test`, JUnit 4, existing style)

- `MetricsStripLayoutTest`: 800×384, 800×480, 800×944 portrait; each height; clamp rules; 3 vs 4
  cells; `glY()` conversion.
- `RiderMetricsTest`: metric and imperial speed/altitude/turn distance formatting, rounding, stale →
  `--`, heading → 8-point compass and hold-when-slow, `StripValues` equality.
- `StripAaMarginsTest`: `aaMarginsFor` / `AaMargins.forAspect` with panel height reduced by the
  strip (AUTO, MANUAL, OFF + known panel, unknown panel).
- `NavTurnParserTest` (phase 2): builds each proto message, checks the `NextTurn`/status output,
  covers every `NavigationType` and `NextEvent`×`Side` mapping, and malformed payload → null.

`MetricsStripRenderer` and the GL path are verified on the device (logs and a visual check). There
are no instrumented tests.

### Bike tests (owner runs, shares log)

**Phase 1**
1. Strip off → Connect. Output and log identical to before (no `[STRIP]` lines).
2. Strip on (Medium) → Connect. Band at the bottom, AA not cropped or distorted, `[STRIP]`
   placement line matches.
3. Ride: speed matches the bike speedometer (±2 km/h), heading changes on turns, altitude plausible.
4. Airplane-mode/GPS off: cells show `--`, AA unaffected.
5. Repeat step 2 on a portrait dash (1000 MT-X) if available.

**Phase 2**
1. Turn info off → handshake unchanged (no `NAVIGATION_DIRECTIONS` channel open in the log).
2. Turn info on + Google Maps route. `[NAV]` lines appear, and the arrow/distance matches the Maps
   UI. End the route and the cell disappears.
3. Same with Waze.
4. If AA drops right after service discovery with turn info on, record the AA version and turn it
   off.

### Build prerequisite

This checkout has no `local.properties`. Building and running unit tests
(`gradlew testDebugUnitTest`) needs the Android SDK path configured, or a build from Android
Studio.

## 7. Rollout

- Phase 1 ships with the strip **off by default**. Release notes point to Setup → Display.
- Phase 2 ships with turn info **off by default**, labelled experimental. It is promoted after
  confirmations on Discord across several bikes and AA versions.
- `docs/02-CURRENT-APP.md` and `README.md` feature table updated in the same change.
