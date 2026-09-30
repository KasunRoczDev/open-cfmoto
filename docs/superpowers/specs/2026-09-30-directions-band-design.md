# Directions Band — Design

**Date:** 2026-09-30 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (works for every bike)
**Builds on:** `2026-09-30-rider-metrics-strip-design.md` (metrics strip + experimental turn info).

## 1. Why

The 150SC dash already shows speed (and the rider doesn't need altitude/heading) in its own native area, so
the metrics strip's speed / altitude / heading cells are wasted space. What the rider actually needs in that
black band is **turn-by-turn directions**. And when no route is active the band should not take space at all.

## 2. What the rider gets

Setup → Metrics strip gains a **mode**:

| Mode | Band content | When the band is shown | Android Auto layout |
|---|---|---|---|
| **Metrics** (today, default) | speed / altitude / heading (+ turn if Turn info on) | always | laid out **above** the band (unchanged) |
| **Directions** (new) | next turn · then · lanes · ETA + km left | **only while a route is active** | **full screen**; the band slides **over** the bottom of the picture while navigating |

In Directions mode, left → right (mockup: `.superpowers/brainstorm/…/directions-band.html`):

1. **Next turn** (always, while navigating) — large arrow (reuses the strip's arrow drawing: slight/normal/sharp/
   U-turn/roundabout with exit number/merge/fork/ramps/destination), distance to the turn (e.g. `350 m`,
   `2.1 km`, or `0.2 mi` / `600 ft` per Map units), road name under it (ellipsized).
2. **Then** (optional) — `then ↰`: the turn after, shown when Maps sends it **and** the next turn is ≤ 1 km away.
3. **Lanes** (optional) — one arrow per lane (straight, slight/normal/sharp left/right, U-turn); lanes Maps
   marks to use are bright white, the others dim grey. Shown only when Maps sends lanes for the next step.
4. **ETA + km left** (optional, right-aligned) — `ETA 18:42` over `12.4 km` (remaining distance to the
   destination). Shown when Maps sends destination info.

When the band is too narrow for everything it drops, in order: **lanes → then → road name**. Next turn distance
and ETA are the last to go (ETA is dropped before the turn if even that doesn't fit).

**No route → no band.** Android Auto (with its app dock) is fully visible. The band appears on the first turn
message and disappears when the route ends (status inactive, cluster stop, AA session end), or when no
navigation message has arrived for **60 s** (stale route).

**Touch while the band is shown** (touch dashes): a tap that starts on the band does nothing; a drag that ends
on it is clamped to the last row above it (same rule as the metrics strip). With the band hidden, the full
screen — including the dock — takes touches normally.

**Turn info:** Directions mode needs Android Auto's navigation data, so selecting Directions **turns Turn info
on automatically** (and the Turn info row shows "On (required for Directions)"). Metrics mode keeps the
existing optional Turn info switch. The height choice (Small / Medium / Large, default Medium for Directions)
applies to both modes.

## 3. Data from Android Auto

Android Auto sends two message families on the navigation-status channel (already parsed by
`aa/NavTurnTracker`); which one a phone sends is only known from a bike log (`[NAV] first <TYPE>` lines).

| Band part | Legacy family (`NEXTTURNDETAILS` / `NEXTTURNDISTANCEANDTIME`) | Newer family (`NAVIGATION_STATE` / `NAVIGATION_CURRENT_POSITION`) |
|---|---|---|
| Next turn arrow + road | `NextTurnDetail.next_turn/side/road/turn_number` ✔ | `steps[0].maneuver`, `steps[0].road` ✔ |
| Distance to turn | `NextTurnDistanceEvent.distance_meters` ✔ | `current_position.step_distance.distance.meters` ✔ |
| Then | — | `steps[1].maneuver` |
| Lanes | — | `steps[0].lanes[].lane_directions[] {shape, is_highlighted}` |
| ETA | — | `destination_distances[0].estimated_time_at_arrival` (string from Maps), else now + `time_to_arrival_seconds` |
| km left | — | `destination_distances[0].distance.meters` |

With the legacy family the band shows only the next turn (+ distance). Nothing is invented: a part without data
is simply not drawn.

## 4. Components

- **`DirectionsState`** (pure, new) — `turn: NextTurn?`, `then: NextTurn?`, `lanes: List<Lane>`
  (`Lane(shapes: List<LaneShape>, highlighted: Boolean)`), `etaText: String?`, `remainingM: Int?`,
  `updatedAtMs: Long`. `active` = `turn != null` and not stale (60 s).
- **`NavTurnTracker`** (extend) — also fills `then`, `lanes`, ETA/remaining from the newer messages; produces a
  `DirectionsState`. Still pure; still throws on malformed payloads (caller logs once per type).
- **`DirectionsFormat`** (pure, new) — ETA text (Maps string verbatim, else `HH:mm` from seconds in the phone's
  clock format), remaining distance and turn distance per `MapUnits`, the "then" rule (≤ 1 km), and which parts
  fit a given band width (drop order above). Unit-tested.
- **`DirectionsRenderer`** (new, Canvas) — draws a `DirectionsState` into the band bitmap (black background,
  white text, arrows shared with `MetricsStripRenderer`).
- **`MetricsStrip`** (extend) — in Directions mode renders `DirectionsState`, tells the compositor whether the
  band is **visible** (active route) and re-checks staleness on its 1 s tick.
- **`StripMode { METRICS, DIRECTIONS }`** in `MetricsStripPrefs` (per bike); `StripSnapshot` gains `mode`;
  Directions forces `turnInfo = true`.
- **AA layout:** `VideoPrefs.aaMarginsFor` subtracts the band only in **Metrics** mode; in Directions mode AA
  gets the full panel.
- **`AaCompositor`** — `setStrip(px, overlay: Boolean)` and `setStripVisible(Boolean)`. Overlay mode: AA
  viewport = full canvas (no band subtracted); the band texture is drawn over the bottom `px` rows only while
  visible. Touch mapping (`MetricsStripLayout.touchY`) takes the visible flag: hidden band → no exclusion.
- **Setup UI** (existing View screen; restyled in Part 3): "Strip shows: **Metrics · Directions**" segmented
  row above the cell choices; in Directions the Speed/Altitude/Heading row is hidden and the Turn info row is
  shown as on-and-locked.

## 5. Error handling

- A render or GL failure never affects Android Auto (existing strip guarantees; band stays black/absent).
- Parse failures are logged once per message type (existing).
- Missing fields → the part is omitted; an empty `DirectionsState.turn` → band hidden.
- Stale route (60 s without a navigation message) → band hidden, logged once (`[NAV] route stale — band hidden`).
- Metrics mode behaviour, output and the AA handshake are unchanged when Directions is not selected.

## 6. Logging

`[STRIP] mode=DIRECTIONS overlay band=<px>px`, `[STRIP] band shown` / `[STRIP] band hidden (<reason>)`,
`[NAV] first <TYPE> len=…` (existing), `[NAV] parts: then=<y/n> lanes=<n> eta=<y/n>` logged when the set of
available parts changes (not per update).

## 7. Testing

**Unit (JVM):** `NavTurnTracker` — `then`/lanes/ETA/remaining from `NAVIGATION_STATE` + `CURRENT_POSITION`,
legacy family yields turn-only state, stop/inactive clears; `DirectionsFormat` — ETA string preferred over
seconds, seconds → `HH:mm`, metric/imperial distances, "then" only ≤ 1 km, drop order at narrow widths;
`DirectionsState.active` staleness at 60 s; `MetricsStripLayout.touchY` with band hidden = no exclusion;
`StripSnapshot` — Directions forces turn info, AA margins not reduced in Directions.

**Emulator:** Setup mode row, Directions hides cells and locks Turn info.

**Bike (owner, 150SC):** start a Google Maps route → band appears over the dock with arrow + distance; which
extra parts appear depends on what Maps sends (share `[NAV] parts:` log); end the route → band disappears and
the dock is usable; taps on the band do nothing while navigating.

## 8. Non-goals

No change to Metrics mode, to the AA handshake beyond the existing Turn info switch, or to the phone-side Dash
view (it mirrors whatever the dash shows). No voice or vibration cues. Compose redesign Part 2 resumes after
this.
