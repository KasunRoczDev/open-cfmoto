# Maintenance Tracker — Design

**Date:** 2026-10-02 · **Status:** Draft for review · **Target bike:** CFMoto 150SC (per-model schedule data, extensible)

## 1. Why

The 150SC owner's manual has a Break-In chart (serviced once around 1000 km) and a Periodic chart (recurring
engine/brake/electrical/wheel/suspension/steering/frame/cooling/transmission/other-parts items, due at a km
*or* calendar interval, whichever comes first). Nothing in OpenCfMoto tracks this today. The rider wants the app
to estimate where they stand against the schedule and nudge them before something is overdue.

The app has no access to the bike's real odometer — on the 150SC it's drawn natively on the dash, below the AA
picture, outside anything we capture. So the estimate is built from a one-time **baseline** (typed in from the
dash) plus the distance of every ride OpenCfMoto has recorded since that baseline was set. It's an estimate, not
a reading, and the design treats it that way (baseline re-settable anytime; no claim of precision).

## 2. What the rider gets

- **Me ▸ Maintenance** (new Compose screen): estimated odometer, a "Set odometer" action, and a list of every
  schedule item grouped by category (Engine, Brake, Electrical, Wheels, Suspension, Steering, Frame, Cooling,
  Transmission, Other), each showing a status pill — **OK**, **Due soon**, or **Overdue** — and how it's due
  ("in 400 km", "in 12 days", "600 km overdue"). Overdue items sort to the top.
- Tapping an item asks "Mark as serviced?" and, on confirm, records today's date and the current estimated km as
  that item's new baseline for computing its *next* due point.
- A badge on the **Maintenance** row in the Me tab shows the due/overdue count at a glance.
- A push notification fires the first time an item becomes due or overdue (not daily — see §6), taking the rider
  to the Maintenance screen.
- If the active bike has no model set, or its model has no schedule data yet, the screen explains that instead
  of showing a badge, and links to Garage ▸ Set model.

## 3. Data model

### 3.1 Schedule (static, per model)

```kotlin
data class MaintenanceItem(
    val id: String,              // stable key, e.g. "engine_oil"
    val category: String,        // "Engine", "Brake", "Electrical", "Wheels", "Suspension",
                                  // "Steering", "Frame", "Cooling", "Transmission", "Other"
    val name: String,            // "Oil", "Brake pads", ...
    val action: String,          // "Replace", "Inspect", "Clean", "Lubricate", ...
    val kmInterval: Int?,        // null = no km-based interval
    val monthInterval: Int?,     // null = no calendar-based interval
    val oneTime: Boolean,        // true only for break-in-exclusive items (no periodic-chart recurrence)
)

object MaintenanceSchedule {
    fun forModel(model: String?): List<MaintenanceItem>   // empty list when unknown/unset
}
```

Due point for an item, given `lastServicedKm`/`lastServicedAtMs` (null before first service — see §3.2):
- `dueKm = (lastServicedKm ?: 0.0) + kmInterval` (skip if `kmInterval == null`)
- `dueAtMs = (lastServicedAtMs ?: baselineSetAtMs) + monthInterval` (skip if `monthInterval == null`)
- Item is due when the estimated odometer ≥ `dueKm` **or** now ≥ `dueAtMs` (whichever first, matching the manual).
- `oneTime` items drop off the list permanently once serviced once (no next due point computed).

150SC's full item list (transcribed from the manual, §9 appendix) seeds `MaintenanceSchedule.forModel("150SC")`.
Markers in the manual (▲ halve the interval under severe duty, ■ dealer should perform the repair) are **not**
modeled — no severe-duty detection exists in the app; this is a straight interval tracker.

### 3.2 Tracking state (per bike, persisted)

`MaintenanceStore` — same flat-JSON-file pattern as `TripStore`/`EcoStore`, one file per bike id
(`BikeScope.idFor(raw)`, since each Garage bike has independent ride history):

```kotlin
data class MaintenanceState(
    val baselineKm: Double,
    val baselineSetAtMs: Long,
    val items: Map<String, ItemState>,   // keyed by MaintenanceItem.id, absent = never serviced
)

data class ItemState(
    val lastServicedKm: Double,
    val lastServicedAtMs: Long,
    val notified: Boolean,   // true once a push notification has fired for the current due cycle
)
```

No `MaintenanceState` yet (new bike, or feature used for the first time) → the Maintenance screen opens straight
into "Set your current odometer" before showing anything else.

### 3.3 Estimated odometer

```
estimateKm(ctx, bike) =
    state.baselineKm + sum(trip.distanceMeters for trip in TripStore.list(ctx)
                            if trip belongs to bike and trip.endTime > state.baselineSetAtMs) / 1000
```

Re-setting the baseline (e.g. after a dealer visit, to re-sync with the dash) updates `baselineKm`/`baselineSetAtMs`
only — per-item service history is untouched, so due points computed from `lastServicedKm`/`lastServicedAtMs`
don't shift.

## 4. Components

| Unit | Kind | Responsibility |
|---|---|---|
| `MaintenanceItem`, `MaintenanceSchedule` | pure | static per-model item list, due-point math |
| `MaintenanceState`, `ItemState` | pure | tracking state shape |
| `MaintenanceStore` | Android | per-bike JSON load/save (pattern: `TripStore`) |
| `MaintenanceEstimate` | pure | `estimateKm` from `MaintenanceState` + trip list; due/overdue/OK classification given "now" + estimate |
| `MaintenanceScreen` | Compose | Me ▸ Maintenance: odometer header, grouped item list, mark-serviced dialog |
| `MeTab` | modify | "Maintenance" row with due/overdue badge |
| `MaintenanceCheckWorker` | Android (WorkManager) | daily: recompute due state for the active bike, notify on newly-due items, set `notified` |
| New `androidx.work:work-runtime-ktx` dependency | build | app has no background-scheduling library today |

## 5. UI flow

1. Me tab shows "Maintenance" with a badge (count of due + overdue) when the active bike has schedule data and a
   baseline; no badge (just the row) when everything's OK; "Set up" affordance when no baseline yet.
2. Maintenance screen: header card (estimated odometer, "Set odometer"), then items grouped by category, overdue
   first within each group, then due-soon, then OK (OK items collapsed/secondary — exact visual detail left to
   implementation to match the Compose redesign's existing card language).
3. Tap item → confirm dialog → `MaintenanceStore` updated → list re-evaluated in place.

## 6. Notifications

- New low-importance `NotificationChannel` (pattern: `ProjectionService`'s mirror channel).
- `MaintenanceCheckWorker` is a `PeriodicWorkRequest` (~24 h, `ExistingPeriodicWorkPolicy.KEEP`), scheduled once at
  app start when at least one bike has a baseline set.
- Each run: for the active bike, recompute every item's due state; for each item that's due/overdue and
  `notified == false`, post one notification (tapping it opens the Maintenance screen) and set `notified = true`.
  An item that's serviced (clearing its due state) resets `notified` for its next cycle automatically, since
  marking it serviced rewrites its `ItemState`.
- `POST_NOTIFICATIONS` (Android 13+) requested the first time the Maintenance screen is opened, not at app start.

## 7. Error handling

- Unreadable/corrupt `MaintenanceStore` file → treated as "no state yet" (prompt for baseline), logged once —
  same convention as `EcoStore`/`TripStore`.
- Unknown or empty model string → `MaintenanceSchedule.forModel` returns an empty list; screen shows the
  "no schedule for this bike" explainer instead of an empty list.
- Worker failure (e.g. no bike selected, store unreadable) → logs and returns `Result.success()` so WorkManager
  doesn't retry-storm; next scheduled run tries again naturally.

## 8. Testing

**Unit (JVM):** due-point math (km-only, calendar-only, both set, whichever-first, never-serviced defaults to
baseline time); `estimateKm` (baseline + summed trips after baseline time, trips before baseline excluded, no
trips → baseline only); `oneTime` items drop after first service; worker dedup (`notified` flag prevents repeat
notifications across runs, resets after service).

**Emulator:** Me ▸ Maintenance with a seeded `MaintenanceStore` (mix of OK/due-soon/overdue items), mark-serviced
flow, "no schedule"/"no baseline" empty states, badge count on the Me tab.

**Bike (owner):** not required for correctness (no bike-specific rendering), but a short real-world check that a
ride's distance moves the estimate as expected is worthwhile once implemented.

## 9. Appendix — 150SC schedule (from the owner's manual, pp. 106–114)

**Break-in (1000 km / whichever first), one-time (`oneTime = true` unless noted as already in the periodic chart):**

| Category | Item | Action |
|---|---|---|
| Engine | Oil | Replace *(recurs periodically — not one-time)* |
| Engine | Main reducer gearbox oil | Replace *(recurs periodically — not one-time)* |
| Engine | Coarse oil filter | Clean (one-time; no periodic-chart recurrence) |
| Engine | Idle | Inspect *(recurs periodically — not one-time)* |
| Engine | Coolant | Inspect *(recurs periodically — not one-time)* |
| Engine | Throttle system | Inspect *(recurs periodically — not one-time)* |
| Electrical | Functions of electrical parts | Inspect *(recurs periodically — not one-time)* |
| Electrical | Battery | Inspect *(recurs periodically — not one-time)* |
| Electrical | Fuses or circuit breakers | Inspect *(recurs periodically — not one-time)* |
| Brake | Brake discs | Inspect *(recurs periodically — not one-time)* |
| Brake | Brake pads | Inspect *(recurs periodically — not one-time)* |
| Brake | Brake fluid level | Inspect *(recurs periodically — not one-time)* |
| Brake | Brake hoses | Inspect for damage/sealing (one-time; periodic chart has its own 5000 km/12M entry — treat as the same recurring item) |
| Brake | Brake lever | Inspect free play (one-time; periodic chart covers "front and rear brake lever") |
| Other | Fault control memory | Read with PDA *(recurs periodically — not one-time)* |
| Other | Movable parts | Lubricate, inspect flexibility *(recurs periodically — not one-time)* |
| Other | Bolts and nuts | Inspect firmness *(recurs periodically — not one-time)* |
| Other | Cables | Inspect for damage/bending/setting *(recurs periodically — not one-time)* |

In practice every break-in item has a matching periodic-chart entry except **Coarse oil filter** — that one is
genuinely one-time at 1000 km. Implementation should use the break-in chart only to seed the *first* due point
(1000 km) for items that also recur, and mark only the coarse oil filter as `oneTime = true`.

**Periodic (recurring, km or calendar, whichever first):**

| Category | Item | Km interval | Month interval | Action |
|---|---|---|---|---|
| Engine | Oil | 3000 | 3 | Replace |
| Engine | Oil filter | 3000 | 3 | Replace |
| Engine | Main reducer gearbox oil | 3000 | 3 | Inspect and repair/replace if necessary |
| Engine | Idle | 5000 | — | Inspect and adjust if necessary |
| Engine | Coolant | 35000 | 24 | Replace |
| Engine | Throttle system | 5000 | — | Inspect and adjust if necessary |
| Engine | Throttle body | 5000 | — | Clean |
| Engine | Air filter elements | 5000 | 12 | Clean |
| Engine | Spark plug | 10000 | — | Replace |
| Engine | Valve clearance | 40000 | — | Inspect and adjust if necessary |
| Electrical | Functions of electrical parts | 10000 | 12 | Inspect and repair/replace if necessary |
| Electrical | Battery | 5000 | 6 | Inspect and recharge if necessary |
| Electrical | Fuses or circuit breakers | 5000 | 6 | Inspect and replace if necessary |
| Electrical | Wires | 10000 | 12 | Inspect for damage/bending |
| Wheels | Tire condition | 5000 | 12 | Inspect and repair/replace if necessary |
| Wheels | Tire pressure | 5000 | 12 | Inspect and replenish if necessary |
| Wheels | Wheel bearings | 5000 | — | Inspect and repair/replace if necessary |
| Brake | Front and rear braking systems | 5000 | 12 | Inspect and repair/replace if necessary |
| Brake | Brake discs | 5000 | 12 | Inspect and repair/replace if necessary |
| Brake | Brake pads | 5000 | 12 | Inspect and repair/replace if necessary |
| Brake | Brake fluid level | 5000 | 12 | Inspect and replenish if necessary |
| Brake | Brake hoses | 5000 | 12 | Inspect for damage/sealing |
| Brake | Front and rear brake lever | 5000 | 12 | Inspect free play |
| Brake | Brake fluid | — | 24 | Replace |
| Suspension | Suspension system | 5000 | — | Inspect and repair/replace if necessary |
| Suspension | Front and rear shock absorbers | 10000 | 12 | Inspect for oil leakage |
| Frame | Frame | 30000 | — | Inspect and repair/replace if necessary |
| Steering | Steering bearings | 10000 | 12 | Inspect and repair/replace if necessary |
| Cooling | Coolant level | 10000 | 12 | Inspect and replenish if necessary |
| Cooling | Radiator fan function | 10000 | 12 | Inspect and repair/replace if necessary |
| Cooling | Cooling hoses | 10000 | 12 | Inspect and replace if necessary |
| Transmission | Driving belt | 10000 | 12 | Inspect and adjust if necessary |
| Transmission | Weight roller set | 10000 | 12 | Inspect and replace if necessary |
| Transmission | Drive pulley | 10000 | 12 | Inspect and replace if necessary |
| Transmission | Driven pulley | 10000 | 12 | Inspect and replace if necessary |
| Other | Fault control memory | 10000 | 12 | Read with PDA |
| Other | Movable parts | 10000 | 12 | Lubricate and inspect flexibility |
| Other | Bolts and nuts | 10000 | 12 | Inspect firmness |
| Other | Cables | 5000 | 12 | Inspect for damage/bending/setting |
| Other | Pipes, ducts, hoses and sleeves | 10000 | 12 | Inspect for cracks/sealing/correct setting |
| Other | Windshield | 25000 | — | Inspect for shaking; replace bracket if clearance > 5 mm |

## 10. Non-goals

No severe-duty interval halving (▲ marker), no dealer-vs-DIY distinction (■ marker), no service-cost tracking, no
parts-ordering integration, no support for bikes other than the 150SC in this iteration (schedule list is simply
empty for every other model until someone transcribes their manual).
