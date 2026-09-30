# Ride Behaviour — Design

**Date:** 2026-09-30 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (works for every bike)

## 1. Why

The rider wants to see how they ride: top / slowest / average speed, and **on the map** where they went
fastest, where slowest, where they stopped, where they braked hard (emergency) and where they accelerated
hard — per ride and per month, like CFMOTO RIDE's "view driving behavior" screen.

## 2. What the rider gets

Built now on the existing Trips screens (the Compose redesign's Part 4 later restyles them around this).
Everything is computed from the GPS points every trip already saves (1 per second: lat, lon, time, speed), so
**past rides get it immediately** and nothing new is recorded (no extra battery).

### 2.1 Ride detail (Trips → a ride; today's `TripMapActivity`)

**Map (OpenStreetMap, as today):**
- Route line **coloured by speed**: each segment green (< 30 km/h), yellow (30–60), orange (60–90), red (≥ 90).
  In imperial units the same bands in mph are 20 / 40 / 55.
- Markers (tap → a label with time and speed):
  - **▲ Top speed** — the point of the maximum speed.
  - **▼ Slowest moving** — the lowest speed while moving (≥ 3 km/h), outside stops.
  - **P Stop** — one per stop, label shows duration (e.g. "Stop · 2 min 10 s · 08:21").
  - **! Hard brake** — one per hard-brake event, label shows the speed drop (e.g. "Hard brake · 54 → 38 km/h").
  - **» Hard acceleration** — one per event (e.g. "Hard acceleration · 12 → 24 km/h").
  - Start / End markers stay.
- Legend under the map.

**Stats (under the map):** max km/h · average km/h (whole ride incl. stops) · moving average · slowest moving ·
stops (count + total time) · hard brakes / hard accelerations · distance · riding time (and total time) ·
**behaviour score** (0–100).

### 2.2 Monthly summary (Trips screen, top card "Riding behaviour")

- Month picker `‹ Sep 2026 ›` (months with rides only; defaults to the current/latest).
- **Radar** with five axes and the month score in the middle: Distance, Riding time, Top speed, Smooth braking,
  Smooth acceleration.
- Totals: rides, distance, riding time, top speed, average moving speed, hard brakes, hard accelerations, stops.
- Tapping a ride below still opens its detail.

## 3. Detection rules (pure, on the saved points)

Speeds come from the saved `speedMs`. Consecutive points with a time gap > 5 s are treated as a break (no
acceleration is computed across the gap).

| Behaviour | Rule |
|---|---|
| **Moving** | speed ≥ 3 km/h |
| **Stop** | speed < 3 km/h continuously for ≥ 20 s; the marker is at the first point of the stop |
| **Hard brake** | deceleration ≥ **3.3 m/s²** (≈ 12 km/h lost per second, ≈ 0.35 g) between consecutive points, starting from ≥ 15 km/h. Consecutive qualifying points merge into one event (marker at the first point, drop = start speed → lowest speed of the run) |
| **Hard acceleration** | acceleration ≥ **2.8 m/s²** (≈ 10 km/h gained per second) between consecutive points; merged the same way |
| **Implausible (GPS glitch)** | \|acceleration\| > **8 m/s²** (beyond any motorcycle) → that step is ignored for all rules and for max speed; a point whose speed differs from both neighbours by > 30 km/h is ignored for max/min speed |
| **Top speed** | max plausible speed (and its point) |
| **Slowest moving** | min speed among moving points outside stops (and its point); none if the ride never moved |
| **Average** | distance / (end − start); **moving average** = distance / moving time (the existing `Trip.avgKmh`) |

**Amended after review (real recorder data, 2026-09-30):** step time below 1 s counts as 1 s (fixes are requested ≤ 1/s;
shorter gaps are callback jitter); a braking step that ends below 5 km/h only counts inside a run that is already
hard (phones clamp crawling speed to 0, so `30 → 20 → 0` is an ordinary stop); a step up from the recorder's 0 first
fix is not a hard acceleration (cold GPS start); the higher end of any step with |a| > 8 m/s² is excluded from
top/slowest (catches multi-sample glitches); "slowest moving" ignores points within 5 s of a sub-3 km/h point; the
score divides by `max(km, 10)` so rides under 10 km are judged per 10 km; files with non-finite numbers are skipped.

**Behaviour score (per ride and per month):** `events = hardBrakes + hardAccels`; rides shorter than 1 km get
no score ("—"). `score = clamp(100 − 15 × events ÷ (max(km, 10) ÷ 10), 0, 100)`, rounded. So 1 event per 10 km → 85;
no events → 100.

**Radar axes (0–1):** Distance = month km ÷ 500 · Riding time = month hours ÷ 20 · Top speed = top km/h ÷ 120 ·
Smooth braking = 1 − min(1, hard brakes per 100 km ÷ 10) · Smooth acceleration = same with hard accels
(all clamped to 0–1).

## 4. Components

- **`RideAnalysis`** (pure, new) — `analyze(trip: Trip): RideBehaviour` with `maxSpeedMs` + point, `slowestMovingMs`
  + point, `avgMs`, `movingAvgMs`, `stops: List<StopEvent(point, durationMs)>`,
  `hardBrakes/hardAccels: List<SpeedEvent(point, fromMs, toMs)>`, `score: Int?`, `segments` (speed band per
  segment for the coloured line). Unit-tested with synthetic tracks.
- **`MonthlyBehaviour`** (pure, new) — groups trips by month (phone time zone), sums totals, score and radar
  values; lists months that have rides.
- **`TripMapActivity`** — draws coloured segments, behaviour markers, legend and the stats block.
- **`TripsListActivity`** — adds the "Riding behaviour" card (radar drawn by a small custom View) with the month
  picker above the ride list.
- Analysis runs off the main thread when a screen opens (a ride has at most a few thousand points).

## 5. Error handling

- Trips with < 2 points → stats show what's known, no markers, score "—".
- A corrupt/unreadable trip file is skipped in the monthly summary (existing `TripStore` behaviour).
- No map tiles (offline) → markers and route still draw on the blank map (as today).

## 6. Testing

**Unit (JVM):** stop detection (19 s vs 20 s, marker position), hard brake threshold (3.2 vs 3.3 m/s², below
15 km/h ignored, merged run → one event with correct from/to), hard acceleration threshold, glitch rejection
(a single 120 km/h spike ignored for max and events), time gaps > 5 s break acceleration, slowest-moving excludes
stops, averages, score formula (no events 100, 1 per 10 km 85, < 1 km null, clamp at 0), month grouping across a
month boundary in the phone's time zone, radar values clamped.

**Emulator:** open a sample ride (a JSON trip fixture copied into the app's trips folder with adb) → coloured
route, markers with labels, stats; Trips screen card with radar and month switching; light and dark.

**Bike (owner):** after a real ride, the markers sit where the rider remembers stopping / braking.

## 7. Non-goals

No new recording (no accelerometer, no extra GPS), no leaderboards or sharing, no speed-limit comparison, no
change to how trips are recorded or exported (GPX).
