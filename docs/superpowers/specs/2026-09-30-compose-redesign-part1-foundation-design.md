# Compose Redesign — Part 1: Foundation — Design

**Date:** 2026-09-30 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (works for every bike)

## 0. The whole redesign (context)

The rider asked for a new app design **inspired by the official CFMOTO RIDE app** ("My moto" hero with a big
model name behind the bike, rounded quick tiles, bottom tabs, "Historical Track" by month) and a **full
rewrite of all 14 screens in Jetpack Compose**. Agreed decisions:

| Decision | Choice |
|---|---|
| Colour mode | **Follow the phone** (light + dark, Android DayNight) |
| Accent | **Teal** (light `#0B8C9B`, dark `#27C1D3`); brand orange stays only in the app icon |
| Navigation | **4 bottom tabs: 🏍 Ride · 🗺 Map · 📈 Trips · ⚙ Me** |
| Settings | The single long Setup page becomes **6 short groups** under Me → Settings |
| Tech | **Jetpack Compose, all 14 screens**, delivered in 5 parts; old screens stay in use until replaced |
| Style boundary | Copy the *style* (layout patterns, hierarchy, feel). **Never** CFMoto's logo, name, product photos or icons. App stays "OpenCfMoto — not affiliated". Bike image = the rider's own Garage photo (or a neutral drawing). |
| Bike data | No fuel/odometer/location from the bike (needs a paid T‑Box — `docs/RE-VEHICLE-TELEMETRY.md`). Show what we have: model, photo, km ridden with the app, trips, parked spot. |

**Parts:** 1 Foundation (this spec) → 2 Ride (home hero, Dash view, Controls, Button mapping, QR scan) →
3 Me (Settings groups, Garage, Screen margins, Custom resolution, About) → 4 Trips (by month, ride map,
trip computer) → 5 Map (Map/GPX hub phone UI; the map drawn **on the dash** is not rewritten).

## 1. Part 1 goal

Give the app its new skeleton so Parts 2–5 only build screens:

1. Compose + a **light/dark design system** (tokens, type, components).
2. **`MainActivity` becomes the Compose host** with the 4-tab shell.
3. The connect flow moves out of `MainActivity` into a tested **`ConnectionController`** — logic
   unchanged, so riding behaviour and logs stay the same.
4. A first Compose **Ride** tab (Connect/Stop + status + tiles), simple Map/Trips/Me tabs that open the
   existing screens, a Compose **Logs** screen, and all of `MainActivity`'s dialogs in Compose.
5. Existing View screens adopt the same light/dark colours immediately.

### Non-goals (later parts)

The Ride hero (model name + photo, km ridden, Parked here) → Part 2. Rewriting Setup/Garage/etc. → Part 3.
Trips by month → Part 4. Map hub → Part 5. Riding stats / crash detection → the separate new-features work.
No change to the bike protocol, Android Auto receiver, video pipeline or metrics strip.

### Success criteria

1. Tapping **Connect** produces the **same `[state]` / connect log sequence** as today's build on the same
   bike, and Android Auto reaches the dash.
2. Stop, auto-connect on launch, park/resume (incl. the "Bike reconnected — tap to resume" notification →
   `EXTRA_RESUME`), the Bluetooth trigger (`EXTRA_BT_TRIGGER`), `EXTRA_START_GPX`/`EXTRA_GPX_TO_BIKE`,
   handlebar/RemotePad key handling, commute navigation and the rival auto-retry all work unchanged.
3. Every tab renders correctly in **light and dark**, survives rotation, and Back behaves (Ride is the
   start tab; Back from another tab → Ride; Back on Ride → leaves the app).
4. Every tap target ≥ **56 dp**; the Connect/Stop button is **64 dp** high.
5. Unit tests cover the connect-flow ordering and the Ride UI state mapping; the existing suite stays green
   (apart from the known, unrelated `YunmoFrameTest.parseOkDimension_xCape1200Payload`).

## 2. Architecture

```
MainActivity  (AppCompatActivity + setContent; same class name → every existing intent keeps working)
 ├─ dispatchKeyEvent → RemotePad.consume(...)             (unchanged)
 ├─ onNewIntent / onResume → ConnectionController.onIntent / onResume   (EXTRA_RESUME, EXTRA_BT_TRIGGER,
 │                                                          EXTRA_START_GPX, EXTRA_GPX_TO_BIKE)
 ├─ ActivityResult launchers (QR scan, MediaProjection consent, permissions) → controller callbacks
 └─ AppShell (Compose)
      ├─ NavigationBar: Ride · Map · Trips · Me          (Navigation-Compose; start = Ride)
      ├─ RideScreen   ← RideViewModel ← ConnectionController.state
      ├─ MapTab / TripsTab / MeTab  (Part 1: cards/rows that start the existing Activities)
      ├─ LogsScreen   ← LogBus flow
      └─ DialogHost   ← ConnectionController.dialog (one pending dialog)

ConnectionController  (process-scoped object; owns the ~25 connect-flow functions moved from MainActivity)
 ├─ state:  StateFlow<ConnectionUi>            (from ConnectionState + BikeMemory)
 ├─ dialog: StateFlow<AppDialog?>              (rival, VPN, Nearby+BT, Wi-Fi off, update, crash recovery)
 ├─ attach(port: UiPort) / detach()            (activity lifecycle)
 └─ engine: ConnectionEngine                   (thin interface over the existing statics)

ConnectionEngine (interface) ── RealConnectionEngine → AndroidAutoService, BikeWifi, BikeWifiP2p,
                                PhoneHotspotAssist, BleWakeUp, EasyConnProber paths, WifiGate, RivalClient,
                                CommuteNav, BikeMemory, BikeProfiles … (all existing, untouched)
```

### 2.1 What moves where

| From `MainActivity` | To |
|---|---|
| `applyProfile`, `tearDownForModeSwitch`, `startAaFlow`, `aaAlreadyLiveOrTerminal`, `ensureNearbyBtForAaConnect`, `maybeResumeFromParked`, `maybeStartFromBtTrigger`, `maybeAutoConnect`, `logAutoConnectSkipOnce`, `reconnectSavedBike`, `maybeRetryAfterRivalStop`, `joinWifi`, `joinPhoneHotspot`, `startPhoneHotspotScan`, `joinWifiP2p`, `runBleWakeUpThenProber`, `ensureLocationPermission`, `stopEverything`, `onMapPressed`, `onMirrorPressed`, `startMirrorLink`, `beginGpxProjection`, `startAaScan`, `navigateToTyped`, `sendDestinationToAndroidAuto`, `lastKnownLatLon` | `ConnectionController` (same code, same log lines, same order) |
| `promptCloseRival`, `promptVpnKillSwitch`, `showNearbyBtForAaDialog`, `maybeShowCrashRecovery`, `showUpdateDialog`, WifiGate's "Wi-Fi is off" prompt | `AppDialog` sealed class, rendered by `DialogHost` (Compose `AlertDialog`); the button actions call controller methods |
| `renderStatus`, `updateButtonStates`, `refreshBikeLabel`, `bikeLabelText` | `RideViewModel` → `RideUiState` (pure mapping) |
| log panel, `shareLog` | `LogsScreen` (Me → Logs) |
| `maybeCheckUpdate`, `checkUpdateManual`, `reportProblem`, `shareProblemReport` | `ConnectionController` helpers used by Me tab rows (logic unchanged) |
| Tips card, footer links | Removed from home; About/Update/Report/Donate live in Me |

`MainActivity` keeps: `onCreate` (setContent, launchers, attach), `onResume`/`onPause`/`onNewIntent`/`onDestroy`
forwarding, `dispatchKeyEvent`, and the `EXTRA_*` constants (unchanged names/values).

### 2.2 `UiPort` (what the controller needs from the screen)

```kotlin
interface UiPort {
    fun requestPermissions(perms: Array<String>, onResult: (granted: Boolean) -> Unit)
    fun launchQrScan()                       // result → controller.onQrScanned(qr)
    fun requestMirrorConsent()               // result → controller.onMirrorConsent(resultCode, data)
    fun startActivity(intent: Intent): Boolean
    fun toast(text: String)
    val context: Context
}
```

Dialogs are **state**, not port calls: `controller.dialog` holds at most one pending `AppDialog`; if no screen
is attached it waits and shows on the next attach (the latest wins).

### 2.3 State

- `ConnectionState` keeps its existing `listener` (other code uses it) and **adds**
  `val flow: StateFlow<Pair<Phase, String>>`, updated in `set(...)`.
- `ConnectionUi(phase, detail, bikeName: String?, hasSavedBike: Boolean)` combines that flow with
  `BikeMemory`.
- `RideUiState` is a pure function of `ConnectionUi`: primary button (`Connect to <bike>` / `Scan your bike`
  / `Stop`), chip (`Ready` idle-grey · busy-amber · `Streaming`/`Mirroring` live-green · `Error` red), detail
  text, which tiles are enabled (Dash view/Controls need a live AA session; Mirror/Scan need not-busy).

### 2.4 Dependencies (version catalog)

Compose BOM, `ui`, `material3`, `ui-tooling-preview` (+ `ui-tooling` debug), `activity-compose`,
`navigation-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `material-icons-extended`,
`kotlinx-coroutines-android`, and the **Kotlin Compose compiler plugin** (`org.jetbrains.kotlin.plugin.compose`)
at the Kotlin version AGP 9.2.1 uses. `buildFeatures { compose = true }`. Exact versions are pinned in the
plan after a build check. Release R8 already on (unused icons are stripped).

## 3. Design system

### 3.1 Colour tokens (Compose `ColorScheme` + `values/colors.xml` & `values-night/colors.xml` for View screens)

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#F4F7FA` (top `#E6EDF2`) | `#0F1419` (top `#1C2430`) | screen background (soft vertical gradient) |
| `surface` | `#FFFFFF` | `#182029` | cards, tiles, nav bar |
| `surface2` | `#EEF2F5` | `#212B36` | secondary fills, segmented off |
| `accent` | `#0B8C9B` | `#27C1D3` | primary buttons, selected tab/segment, icons |
| `onAccent` | `#FFFFFF` | `#06262B` | text on accent |
| `text` | `#14191F` | `#EEF2F6` | primary text |
| `text2` | `#5B6773` | `#9AA7B4` | secondary text, labels |
| `outline` | `#DCE3E9` | `#2A3440` | borders, dividers |
| `live` | `#1FA66A` | `#34D399` | Streaming / Mirroring |
| `busy` | `#C98A00` | `#FBBF24` | Joining / Starting |
| `error` | `#D93A3A` | `#F87171` | Error, Stop button |

The existing XML colour names (`bg`, `surface`, `surface_high`, `text_primary`, `text_secondary`, …) are
kept and given light/dark values so old screens follow; `Theme.OpenCfMoto` moves to
`Theme.Material3.DayNight.NoActionBar`. `brand_orange` stays defined (icon, legacy highlights) until the
screens using it are rewritten.

### 3.2 Type (Roboto / system)

| Style | Size / weight | Use |
|---|---|---|
| `heroModel` | 72 sp / ExtraBold, −2 sp tracking, 15 % opacity | big model name (Part 2) |
| `screenTitle` | 24 sp / SemiBold | "My moto", screen titles |
| `title` | 17 sp / SemiBold | buttons, row titles |
| `body` | 15 sp / Regular | descriptions |
| `label` | 12 sp / Medium, UPPERCASE, +1.4 sp | section headers |
| `stat` | 28 sp / Bold | numbers (km, speed) |

### 3.3 Components (`ui/design/`)

`OcmTheme` · `PrimaryButton` (64 dp, radius 18, accent; `danger` variant = error colour for Stop) ·
`StatusChip(kind)` · `ActionTile(icon, label, enabled)` (min 88 dp tall, radius 18) · `OcmCard` (radius 20,
outline) · `SectionLabel` · `SettingRow(title, subtitle, trailing)` (min 64 dp) · `SegmentedChoice` ·
`OcmNavigationBar`. All tap targets ≥ 56 dp; icons from Material Symbols (rounded).

## 4. Part 1 screens

| Tab | Content |
|---|---|
| **Ride** (start) | Title "My moto"; bike name + `StatusChip`; `PrimaryButton` (Connect to <bike> / Scan your bike / Stop — `danger` while busy or live); status detail line; tiles **Dash view** (→ `HudViewActivity`), **Controls** (→ `ControlsActivity`), **Mirror** (→ controller mirror flow), **Scan new bike** (→ QR scan). First run (Setup never seen) → opens `SetupActivity` as today. |
| **Map** | `OcmCard` "Map & GPX — free ride, search, GPX on the dash" → `GpxActivity`; a **Search a place** field + Go → `navigateToTyped` (Google Maps / AA), moved from the old home. |
| **Trips** | Cards "Saved rides" → `TripsListActivity`, "Trip computer" → `TripActivity`. |
| **Me** | Rows: Garage → `GarageActivity`; **Settings** → `SetupActivity`; **Logs** → `LogsScreen`; Report a problem; Check for update; About → `AboutActivity`; Donate (Ko‑fi link). |
| **Logs** (from Me) | Live `LogBus` text (monospace, auto-scroll, secrets redacted as today), **Share**, **Clear**. |

## 5. Error handling

- The controller is process-scoped; activity recreation (rotation, returning from background) re-attaches a
  new `UiPort`; connection work is never tied to the screen's lifetime.
- Permission/consent/scan results arriving after recreation are delivered via the new port's launchers to the
  same controller callback (the flow resumes where it paused — e.g. location granted → continue `startAaFlow`).
- A `UiPort` call while detached: `toast` is dropped (logged); `startActivity`/permission requests are deferred
  to the next attach; dialogs are held as state.
- All moved code keeps its existing try/catch and log messages; no new silent catches.

## 6. Testing

**Unit (JVM):**
- `ConnectionControllerTest` with `FakeConnectionEngine` + `FakeUiPort`:
  Connect ordering (Wi-Fi gate → Nearby/BT → commute armed → profile → AA start → join), Wi-Fi off → `WifiOff`
  dialog and no AA start, parked resume → commute **not** armed, rival error detail → `Rival` dialog once,
  App-settings then resume → one retry, auto-connect only once, Stop tears down (AA service stopped, mirror
  cleared), dialog raised while detached is shown on attach.
- `RideUiStateTest`: every `Phase` → button label/variant, chip kind, tile enablement; no saved bike → "Scan
  your bike".
- `ConnectionState` flow emits on `set`.

**Emulator:** each tab in light and dark (system theme toggle), rotation on Ride with a dialog open, Back
behaviour, Logs share intent, first-run → Setup. Screenshots attached to the PR.

**Bike (owner):** Connect → AA on dash, compare Share Logs with a pre-redesign run (same `[state]` lines);
Stop; park/resume via notification; Bluetooth trigger; commute nav; rival dialog + auto-retry.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Moving the connect flow changes behaviour | Code moved verbatim; ordering unit tests written **before** the move against the current behaviour; log-sequence comparison on the bike |
| Build memory on the dev PC (C: full, native OOM seen) | Keep `--max-workers=2`, in-process Kotlin compiler, Gradle home on F:; add Compose incrementally |
| AGP 9 built-in Kotlin + Compose plugin version mismatch | Plan step 1 is a build-only spike that pins the matching plugin version before any UI code |
| APK size (+Compose, icons) | R8 + resource shrinking already on for release; measure before/after |
