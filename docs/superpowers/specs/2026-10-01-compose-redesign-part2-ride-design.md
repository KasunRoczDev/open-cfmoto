# Compose Redesign — Part 2: Ride — Design

**Date:** 2026-10-01 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (works for every bike)
**Builds on:** `2026-09-30-compose-redesign-part1-foundation-design.md` (design system, 4-tab shell, `ConnectionController`).

## 1. Goal

Give the Ride tab its "My moto" hero and rebuild the four Ride screens in the Part 1 style, as shown in the
accepted mockups (`part2-screens.html`):

1. **Ride hero:** big faded model name behind the bike, bike photo, last ride + this month, five tiles.
2. **Dash view** (`HudViewActivity`), **Controls** (`ControlsActivity`), **Button mapping**
   (`ButtonMappingActivity`) and **QR scan** (`QrScanActivity`) redrawn in Compose.
3. A new optional **Model** field per saved bike (set in Garage).

**Approach A (agreed):** every screen keeps its Activity class, manifest entry, intents, extras and result
contract. Only `setContentView(R.layout.…)` becomes `setContent { OcmTheme { … } }`. Views that must stay
Views (the AA video `SurfaceView`, the CameraX `PreviewView`, the GPX preview presentation) are hosted with
`AndroidView`.

### Non-goals

No change to the bike protocol, Android Auto receiver, video pipeline, touch mapping maths, `ButtonMap` /
`ButtonMode` / `RemotePad` / `MediaButtonBridge` logic, QR parsing, or the connect flow. Garage, Setup,
Screen margins, Custom resolution and About are rewritten in Part 3 (Part 2 only adds "Set model" to the
existing Garage screen). The map drawn **on the dash** is not touched.

### Success criteria

1. The hero shows the model (or the bike name when no model is set), the Garage photo (or a neutral drawing),
   and last ride / this month figures that match Saved rides.
2. Every control on the four screens does what the old one did: the same `AaInput` key codes and scroll
   deltas, the same log lines, the same `ButtonMap`/`SavedPlaces`/`MapPrefs` writes, the same `RESULT_QR`.
3. Dash view touch still lands in the right place on the dash (same `mapToSource` path), full screen and the
   GPX preview mode still work, Back behaves as today (Controls: Back leaves pad full screen first).
4. Every screen renders in light and dark and survives rotation; tap targets ≥ 56 dp, pad keys ≥ 64 dp.
5. New unit tests pass; the suite stays green apart from the known `YunmoFrameTest.parseOkDimension_xCape1200Payload`.

## 2. Ride hero

### 2.1 Layout (top → bottom)

| Part | Content |
|---|---|
| Header | "My moto" · `StatusChip` · bike name (small, `text2`) |
| Hero | Model text in `heroModel` (72 sp ExtraBold, 15 % opacity, auto-shrinks to fit one line) behind the bike image. Image = the Garage photo (cropped to fit, rounded) or a neutral scooter drawing (vector, `text2` tint). No CFMoto artwork. |
| Primary | `PrimaryButton` — unchanged Part 1 behaviour and labels (Connect to <bike> / Scan your bike / Stop) |
| Detail | status detail line (unchanged) |
| Ride card | `OcmCard` with two columns: **Last ride** — `<day> · <km> km · <duration>`; **This month** — `<km> km`. Tap → Saved rides (`TripsListActivity`). Hidden when there are no saved rides. |
| Tiles | Row 1: **Dash view** · **Controls** · **Parked here**. Row 2: **Mirror** · **Scan new bike**. Enablement for Dash view/Controls/Mirror/Scan unchanged from Part 1. |

### 2.2 Data

- **Model:** `SavedBike` gains `val model: String? = null`; stored in the existing JSON list; entries without
  the key load as `null` (no migration step). `BikeMemory.setModel(ctx, raw, model)` trims and stores blank
  as `null`. `RideUiState` gains `model: String?` and `photoPath: String?` from `BikeMemory.selected`.
- **Hero text** = `model ?: bikeName ?: ""` (pure, tested).
- **Ride summary** = pure `RideSummary.of(trips, nowMs, zone)`:
  - `last`: the trip with the latest `end` → `LastRide(dayLabel, km, durationText)` where `dayLabel` is
    `TODAY`, `YESTERDAY`, or a weekday / short date for older rides (resolved to strings in the UI);
    km rounded to 0.1.
  - `monthKm`: sum of `distanceKm` for trips whose `start` is in the current calendar month in `zone`,
    rounded to whole km.
  - `null` when the list is empty.
  Loaded with `TripStore.list` on `Dispatchers.IO` by the Ride view model on start and on every `ON_RESUME`
  (a ride just saved shows when you come back).

### 2.3 Parked here tile

- State from `MapPlaces.parked(ctx)`: `SET` (pin exists) or `NONE`.
- `SET` → label "Parked here", tap = phone navigation to the pin (the same phone-side route the Map hub's
  "Go to parked" uses — the helper is moved to a shared place, not duplicated).
- `NONE` → label "Park here", tap = save the current location as the pin (`MapPlaces.setParked`, same
  `MapPlace("Parked", …, "parking", …)` shape as the Map hub) with a toast; no GPS fix → toast "Need a GPS fix"
  (existing string) and nothing saved. Location permission is requested through the existing path.
- Clearing the pin stays in the Map hub.

### 2.4 Garage "Set model"

The existing Garage bike action sheet gets **Set model** (next to Rename): a text dialog prefilled with the
current model, hint "e.g. 150 SC". Garage's card shows the model under the name when set.

## 3. Shared pieces (`ui/ride/`)

- **`AaKeys`** — one helper holding the key/scroll/mic logic that `HudViewActivity` and `ControlsActivity`
  each have today (`key(code)`, `scroll(delta)`, `ensureMicPermission()`), moved verbatim (same log lines).
- **`AaPad`** composable — ⟲ Prev · ▲ · Next ⟳ / ◀ OK ▶ / ▼, then Back · Home · Voice. Parameters:
  `onKey(code)`, `onScroll(delta)`, `onVoice()`, `big: Boolean` (full screen = larger keys). Keys ≥ 64 dp,
  accent-tinted icons on `surface2`.
- **`SegmentedChoice`** (Part 1 component) for map theme and on/off rows.

## 4. Dash view (`HudViewActivity`)

- **Top bar:** ✕ close · title "Dash view" · 🗺 map hub · ◐ theme (cycles as today) · ⛶ full screen.
- **Video:** the existing `SurfaceView` in `AndroidView`, same `SurfaceHolder.Callback`, same touch
  forwarding (`onSurfaceTouch` → `mapToSource` → `forward`). Aspect and letterboxing unchanged.
- **Pad:** `AaPad` below the video (hidden by the existing controls toggle).
- **Full screen:** hides top bar, pad and system bars; **Back** exits (today's rule and toast). The mockup's
  "tap the video to bring it back" is dropped: a tap on the video is a touch on the dash and must stay one.
- **GPX preview mode:** the existing inflated `presentation_gpx` view is hosted in `AndroidView`; the same
  code decides when to show it and hides the bars as today.
- `dispatchKeyEvent`, `onNewIntent`, `onResume`/`onPause`/`onDestroy`, `onConfigurationChanged`, the Back
  callback and `startGpxPreview(ctx)` keep their behaviour. UI flags that the old code set with
  `visibility` become Compose state held by the Activity (`mutableStateOf`).

## 5. Controls (`ControlsActivity`)

Cards, top → bottom:

1. **Navigate to…** — text field + Go (`NavLauncher.navigate`, same empty-field toast). Below it, chips for
   each non-empty `SavedPlaces` slot (🏠 first slot, 💼 second, ★ others) → navigate to that slot's query.
2. **Map theme** — `SegmentedChoice` Auto / Day / Night (`setMapTheme`, unchanged).
3. **On-screen pad** — title row with ⛶ Full screen; the touch-dash warning (same rule) when it applies;
   `AaPad`. Full screen hides the other cards and grows the pad; Back leaves full screen first (unchanged).
4. **Volume** — slider 0..max with value (`MediaButtonBridge.setVolume`, synced on resume as today).
5. **Handlebar buttons** — switch "Buttons control Android Auto" (`ButtonMode.set` + bridge + log + mic, as
   today) and a row "`<bt status>` · N buttons mapped ›" → `ButtonMappingActivity`.

"N buttons mapped" = pure count of gestures whose action is not "None", from `ButtonMap`.

## 6. Button mapping (`ButtonMappingActivity`)

Cards, top → bottom:

1. **Bluetooth** — status line (`BluetoothHelper.status(...).shortLine()`, tap opens BT settings as today),
   presence line, **Teach my buttons** (existing teach flow).
2. **What each press does** — one row per `ButtonGesture`: label + hint, current action on the right; tap →
   action picker dialog (Compose `AlertDialog` with the same choices as `pickAction`).
3. **Saved places** — row "Home · Work · Place 3 ›" (names of the slots) → a full-screen Compose dialog
   with name + address fields per slot; Save writes `SavedPlaces.set` (same as `savePlaces`).
4. **Helmet / remote pad** — `SegmentedChoice` On / Off + **Teach remote** (existing flows, including the
   wait-for-key dialog, now Compose).
5. **Dash preset** — row showing the active preset (or None) → preset picker; Clear with confirm.
6. **Overlay permission** — card shown only under the existing condition; button opens the permission screen.
7. **Reset all** — enabled only when not all default; confirm dialog.

`dispatchKeyEvent` stays in the Activity (teach flows capture keys there). All dialogs become state rendered
by the screen (same pattern as Part 1's `DialogHost`).

## 7. QR scan (`QrScanActivity`)

- ✕ close · title "Scan your dash" · hint "Point at the pairing QR your dash shows".
- CameraX `PreviewView` in `AndroidView` (bound to the Activity lifecycle as today) with a rounded viewfinder
  frame drawn over it; zoom chips 1× / 2× / 3× (`setZoom`, unchanged clamp).
- **Scan a photo of the QR** (photo picker → `decodePhoto`) and **Enter Wi-Fi name & password** (the existing
  manual dialog, now Compose; same raw string built).
- Camera permission denied → the existing toast, plus an inline message with the two alternatives still usable.
- Camera bind failure → the hint text shows the error as today.
- Result contract unchanged: `RESULT_OK` + `RESULT_QR`.

## 8. Clean-up

Delete `activity_hud_view.xml`, `activity_controls.xml`, `activity_button_mapping.xml`,
`activity_qr_scan.xml`, `row_button_mapping.xml`, `row_saved_place.xml` and any drawables/strings used only
by them. `presentation_gpx.xml` stays (dash + preview). Strings the new screens reuse keep their names.

## 9. Error handling

- `TripStore.list` or photo decode failure → the ride card / photo is simply not shown (logged once), the
  hero still renders.
- Everything moved keeps its existing try/catch and log messages; no new silent catches.
- Activity recreation: screen state lives in the Activity's Compose state or in the existing prefs, so rotation
  keeps pad full screen, theme and dialogs (dialogs via `rememberSaveable` where they hold user input).

## 10. Testing

**Unit (JVM):**
- `RideSummaryTest`: empty → null; latest-by-`end` wins; today / yesterday / older labels across midnight in
  a fixed zone; month sum includes only this month's starts; rounding.
- `HeroTextTest` (in `RideUiStateTest`): model wins, blank model → bike name, neither → "".
- `ParkedTileTest`: pin → `SET`, none → `NONE`.
- `ButtonMapSummaryTest`: count of non-None gestures.
- `BikeMemoryModelTest`: JSON without `model` loads as null; set/trim/blank→null; rename keeps model.

**Emulator:** Ride hero with and without model/photo/trips; Park here → Parked here; each of the four
screens in light and dark; rotation with a dialog open; Controls pad full screen + Back; QR permission denied
path; Garage Set model round trip. Screenshots attached to the report.

**Bike (owner):** Dash view touch and pad on the dash; handlebar teach + mapping; QR pairing; Controls
navigate chip → route on the dash.

## 11. Risks

| Risk | Mitigation |
|---|---|
| `SurfaceView` inside Compose flickers or loses the surface on recomposition | Create it once in the Activity and pass it to `AndroidView` (factory returns the same instance); surface callbacks unchanged; check on the emulator with an AA session and on the bike |
| Moved key/teach code changes behaviour | Code moved verbatim; same log lines; manual check of every button against the old build |
| CameraX preview in `AndroidView` | `PreviewView` keeps the same binding code; only its parent changes |
| Build memory on the dev PC | Same flags as Part 1 (`--max-workers=2`, in-process Kotlin, Gradle home on F:) |
