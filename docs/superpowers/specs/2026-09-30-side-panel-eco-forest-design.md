# Side Panel + Eco Tree + Forest — Design

**Date:** 2026-09-30 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (works for every bike)
**Builds on:** `2026-09-30-directions-band-design.md` (navigation data, arrows) and the metrics strip.

## 1. Why

The rider wants directions **beside** Android Auto instead of under it — where Android Auto would put its media
card — showing only navigation details (the 150SC dash already shows speed). That same panel should reward smooth,
economical riding the way a Nissan Leaf does: **a tree grows while you ride eco, finished trees are collected into
a forest** in the app.

## 2. What the rider gets

### 2.1 Side panel (dash)

- Setup → Metrics strip → **Strip shows: Metrics · Directions · Side panel**.
  - **Side panel** options: **Right / Left** (default Right) and **Narrow 25% / Medium 30% / Wide 35%** of the dash
    width (default Medium). The existing Small/Medium/Large row is reused as Narrow/Medium/Wide in this mode;
    "Off" still turns the whole strip off.
- Android Auto is laid out for the remaining width (e.g. 70%), so it shows its map and bar **without a media card**.
  The panel is **always visible while connected** (it is never drawn over Android Auto).
- **While a route is active** (same data as the Directions band): next-turn arrow · distance · road; "then ↰"
  (when ≤ 1 km); lane arrows (highlighted lanes bright); `ETA 18:42 · 12.4 km`. Below: a small tree, "Eco 86% ·
  tree 2/5" and an eco meter.
- **No route:** the whole panel is the eco tree — the tree drawing at its current stage, "Young tree · stage 4 of 5",
  a progress bar, **Eco NN%** for this ride, and "14.4 / 20 eco km".
- Taps on the panel never reach Android Auto; taps on the map work as usual.
- Selecting Side panel turns Turn info on automatically (like Directions).

### 2.2 Eco tree (live while connected)

Fed by the phone's GPS (1 fix/s) while the panel is showing. A second **while moving** (≥ 3 km/h) is **eco** when
all hold:

| Rule | Threshold |
|---|---|
| Smooth acceleration | acceleration < **1.5 m/s²** |
| Smooth braking | deceleration < **1.5 m/s²** |
| Steady speed | max − min speed over the last **10 s** < **10 km/h** |
| Eco speed band | **30–70 km/h** |

Step time below 1 s counts as 1 s and gaps > 5 s reset the steadiness window (same realism rules as ride behaviour).

- **Eco distance** = distance covered during eco seconds (speed × time). **Eco %** (this ride) = eco distance ÷ moving
  distance, shown once ≥ 200 m moved (else "—").
- **A tree completes at 20 eco km**, through 5 stages: seed (0–4 km), sprout (4–8), sapling (8–12), young (12–16),
  full (16–20). On reaching 20 km the tree is added to the forest and a new seed starts (extra km carry over).
- Rough riding only **pauses** growth — a tree never shrinks or dies.
- Progress (current tree's eco km, trees grown, all-time eco km, per-month eco and moving distance) is saved on the
  phone, **shared across bikes**, at least every 30 s while riding and when the session stops.

### 2.3 Forest (app)

- **Me ▸ Forest** (new Compose screen in the redesign style).
- A forest drawing: one tree per completed tree (drawn, varied sizes/shades), plus the growing one at its stage.
- Rows: Trees grown · Growing now (stage, eco km / 20) · Eco km all time · Eco % this month.
- A short note explaining what makes a tree grow. The trees are a game: **no CO₂ or fuel claims**.

## 3. Components

| Unit | Kind | Responsibility |
|---|---|---|
| `SidePanelLayout` | pure | panel width from percent + canvas width (min 120 px, max 45%), side, AA panel shape `panelW − sidePx`, the canvas x-range of the panel, `isInPanel(x)` |
| `EcoScorer` | pure | per-fix eco decision (rules above), eco/moving distance for the ride, steadiness window |
| `EcoForest` | pure | `add(ecoMeters)` → new state + trees completed; stage 1–5; progress within tree |
| `EcoStore` | Android | SharedPreferences load/save of `EcoForest` + monthly eco/moving metres |
| `SidePanelRenderer` | Canvas | draws the panel (directions block when active; tree block always), procedural tree per stage (trunk + canopy circles; no emoji) |
| `MetricsStrip` | modify | SIDE mode: GPS on, `EcoScorer` per fix, `EcoForest` updates, periodic save, renders the side panel on change/tick |
| `AaCompositor` | modify | `setSidePanel(px, left)`: AA viewport computed with the panel added to the left/right margin; panel texture drawn in its column; FILL never paints over the panel (scissor) |
| `VideoPrefs.aaMarginsFor` | modify | SIDE: AA target = `(panelW − sidePx) × panelH` |
| `StripSnapshot` / `MetricsStripPrefs` | modify | `StripMode.SIDE`, `sideLeft`, `sidePercent` (25/30/35), frozen at Connect |
| `ForestScreen` | Compose | Me ▸ Forest |
| Setup UI | modify | third mode segment, Left/Right row, size row relabelled in SIDE |

## 4. Error handling

- A render/GL failure never affects Android Auto (existing strip guarantees; the panel column stays black).
- No GPS (permission/GPS off) → panel shows directions (if any) and the tree with "No GPS"; nothing grows.
- Canvas too narrow (panel would be < 120 px or the AA area < 200 px) → side panel off this session, logged.
- A corrupt/missing EcoStore → starts from an empty forest (logged once).

## 5. Logging

`[STRIP] mode=SIDE side=RIGHT panel=<px>px aa=<w>x<h>`, `[ECO] tree complete (#N)`, `[ECO] saved eco=<km>`.

## 6. Testing

**Unit (JVM):** `SidePanelLayout` (percent → px, min/max clamps, left/right x-range, AA width, too-narrow → 0);
`EcoScorer` (each rule's threshold on both sides, steadiness window 10 s, jitter floor, gap reset, eco %
threshold 200 m); `EcoForest` (stages at 4/8/12/16 km, completion at 20 km with carry-over, multiple trees in one
add, never negative); `StripSnapshot` SIDE (turn info forced, AA reserve horizontal, not vertical).

**Emulator:** Setup mode/side/size rows; Forest screen light/dark with a seeded EcoStore.

**Bike (owner):** Side panel right/medium — Android Auto map without media card, panel shows directions while
navigating and the tree otherwise; ride smoothly at 30–70 km/h → eco % high and tree progress rises; Forest shows
a completed tree after 20 eco km.

## 7. Non-goals

No real-world tree planting or sharing, no CO₂/fuel estimates, no eco scoring of past trips (live only), no change
to the bottom Metrics/Directions modes.
