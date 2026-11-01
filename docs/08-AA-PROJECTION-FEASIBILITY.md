# WP-01 — Android Auto projection: architecture & feasibility note

**For:** OCM-AA-SRS-002 (Part 2: Motorcycle Android Auto Projection Experience) · **Date:** 2026-11-01
**Status:** source investigation done; hardware evidence still to collect (§7). Not yet a signed exit gate.

All paths below are under `app/src/main/java/dev/zanderp/opencfmoto/` unless noted.

---

## 1. Bottom line

1. **The SRS's starting assumption is off in a way that helps.** OpenCfMoto is not an app sitting next to
   Google's projection — it **is the Android Auto head unit**. It hosts an AAP receiver on
   `127.0.0.1:5288`, decodes Google's H.264, composites its own pixels into the frame, re-encodes, and
   streams to the dash over Carbit PXC. Every pixel the dash shows passes through our compositor.
2. So **Mode A2 (companion overlay) is not speculative — it ships**: metrics strip, directions band and
   side panel are drawn into the bike stream next to Android Auto.
3. **Mode B1 (OpenCfMoto-owned renderer) also ships, for one feature**: the Map / GPX dash is a full-frame
   Android `Presentation` rendered by us, with handlebar/touch input and a live soft-switch between it and
   Android Auto that keeps the PXC link up. A custom motorcycle "home" is a new screen on a proven
   transport, not a protocol research project.
4. **What stays Google's:** the AA launcher, Maps/Waze, media and call screens. We can't theme them. We
   *can* shape them through head-unit parameters we already send (resolution, DPI, margins, day/night,
   input type, driving status).
5. **Biggest gap is safety, not visuals:** we always report `DRIVING_STATUS = UNRESTRICTED`, so Android
   Auto's own while-moving restrictions (keyboard lockout, list limits) never apply (AA-016, NFR-07).
6. **No bike telemetry exists to show.** Without a T-Box the dash exposes no speed/fuel/RPM on BLE, PXC or
   its network (`docs/RE-VEHICLE-TELEMETRY.md`). Ride values must come from phone GPS or be labelled
   estimates.
7. **Recommendation:** Track A + a feature-flagged "B1-lite" owned dash home built on the existing
   Presentation path, stock Android Auto stays the default, **reject B2** (patched Google APK) outright.

---

## 2. Actual architecture (AA-002)

```
 Google Android Auto (Gearhead, on the phone)
        │  AAP over loopback 127.0.0.1:5288 (no VPN) — started by aa/AaSelfMode
        ▼
 AndroidAutoService (FGS) ── aa/AaReceiver → aa/AapTransport (TLS) → aa/VideoDecoder
        │                         ▲ head-unit declaration: aa/ServiceDiscoveryResponse
        │ decoded frames          │ input back to AA: aa/AaInput (touch), keycodes (handlebar)
        ▼                         │ mic: aa/AaMicrophone  · sensors: NIGHT, DRIVING_STATUS
 AaCompositor (EGL/GLES2) ◄── MetricsStrip / DirectionsRenderer / SidePanelRenderer (our pixels)
        │  letterbox / fit / stretch into the bike's runtime canvas
        ▼
 VideoPipeline (MediaCodec H.264 Baseline)  ◄── or: owned Presentation (GpxDashUi, Map/GPX)
        │                                     ◄── or: MediaProjection screen mirror
        ▼
 AaVideoBridge → EasyConnProber / PxcHandshake (Carbit PXC, ports 10920–10922, per-dash BikeProfile)
        ▼
 CFMoto / Carbit dash (CFDL16, CFDL26, …)
```

| Role | Owner | Evidence |
|---|---|---|
| AA phone side (launcher, Maps, media, calls) | Google | Gearhead, started by `aa/AaSelfMode.kt` |
| AA head unit (receiver, decoder, sensors, input, mic) | **OpenCfMoto** | `aa/AaReceiver.kt`, `aa/AapTransport.kt`, `aa/AapControl.kt`, `aa/ServiceDiscoveryResponse.kt` |
| Frame composition before the dash | **OpenCfMoto** | `AaCompositor.kt`, `VideoPipeline.kt` (compositor mode) |
| Owned full-frame dash UI | **OpenCfMoto** | `GpxDashUi.kt` + `res/layout/presentation_gpx.xml`, `DashMapEngine.kt` |
| Dash transport + per-model quirks | **OpenCfMoto** (Carbit protocol, RE'd) | `EasyConnProber.kt`, `PxcHandshake.kt`, `BikeProfile.kt` |
| Dash firmware, its own menus/status bar | Dash vendor | out of reach; the dash only shows our stream |

Video source switching without dropping the bike link: `EasyConnProber.attachOwnedGpxVideo()` /
`detachVideoSource()` ("soft-switch … without tearing down PXC sockets or rejoining Wi‑Fi").

---

## 3. What can be customised, by layer

| Layer | Customisable? | How / limits |
|---|---|---|
| AA launcher, Maps/Waze/media/call UI | **No** | Google-rendered. Don't claim it follows our theme (SRS §5.2). |
| AA layout density | Yes, indirectly | Advertised DPI — `VideoPrefs.dpiOverride`, profile defaults 160/240 |
| AA resolution / orientation | Yes | `BikeProfile.aaVideo`, `VideoPrefs.ResolutionMode` (800×480 … 1080×1920) |
| AA safe area | Yes | Advertised margins (`BikeProfileHolder.aaContentMargins`), `ScreenMarginsActivity` |
| AA day/night (Maps theme) | Yes | NIGHT sensor ← `NightPrefs` Auto/Day/Night (`aa/AapControl.kt:155`) |
| AA input mode | Yes | Touchscreen advertised only on touch dashes, else focus/D-pad (`ServiceDiscoveryResponse.kt:84`) |
| AA motion restrictions | Yes — **currently disabled** | DRIVING_STATUS sent once as UNRESTRICTED (`aa/AapControl.kt:368`) |
| Next-turn data out of Google Maps | Yes, opt-in | NavigationStatusService "Turn info" → `aa/NavTurnTracker.kt` |
| Pixels around AA (A2) | **Yes, fully** | strip / directions band / side panel via `AaCompositor` |
| Full owned screen (B1) | **Yes, fully** | Presentation → encoder → PXC (Map/GPX today) |
| Dash vendor chrome | No | firmware |

---

## 4. Product modes, re-assessed (SRS §4)

| Mode | SRS assumption | Finding |
|---|---|---|
| A1 Stock AA | Exists | Exists, default. Regression target. |
| A2 Companion overlay | "may be impossible over protected projection" | **Ships.** No protected surface — we composite before encoding. Limited only by dash pixels left for AA. |
| B1 Owned renderer | R&D, needs PoC | **Transport + input + switching proven by Map/GPX.** Missing: a home/launcher screen, media integration, and per-dash glanceability tests. |
| B2 Modified Google AA | Research | **Recommend reject.** Unnecessary given B1, needs root/patching, breaks on updates, licensing risk (NFR-03, NFR-09). |

ADR-001's proof ("B1 can render a custom screen, receive input, restore normal projection") is largely met
by Map/GPX on real dashes (README demos `docs/media/map-demo-*.gif`). Still to record formally: switch
latency AA → owned → AA and input coverage per dash (§7).

---

## 5. Requirement status (AA-001 … AA-022)

✅ met in code · 🟡 partial · ❌ gap · 🔬 needs hardware evidence

| ID | Status | Evidence / gap |
|---|---|---|
| AA-001 Session start | ✅🔬 | Existing flow; regression on target bike |
| AA-002 Roles documented | 🟡 | This note; device logs still to attach |
| AA-003 Resolution/DPI/safe bounds | ✅ | Runtime canvas from dash `REQ_CONFIG_CAPTURE` → `configureBikeCanvas`; Fit/Fill/Stretch; margins; DPI override |
| AA-004 Stock fallback without reinstall | ✅ | Setup → **Restore working projection** (`ProjectionReset.kt`): video, fit, resolution, DPI, aspect, margins, profile, strip, touch overrides, lock-while-moving → defaults for the selected bike. Keeps Wi‑Fi transport, buttons, ride data |
| AA-005 Owned dark theme | 🟡 | Owned dash surfaces use their own fixed colours (`dash_*` in `values/colors.xml`, renderers); not yet the §5.2 token set |
| AA-006 Daylight / high-contrast | 🟡 | Day/Night/Auto exists for AA + map; no high-contrast preset for owned chrome |
| AA-007 Owned home/launcher | ❌ | Not built; transport ready (B1) |
| AA-008 Nav entry | ✅ (A1) / 🟡 (B1) | Stock Maps/Waze in A1. In owned UI: own OSM nav, or Google next-turn data only — Google's map can't be embedded |
| AA-009 Media controls | ❌ (owned) | A1 uses AA's media UI. Owned UI needs `MediaSessionManager`, which requires **notification-listener access — a new permission** (decision §8) |
| AA-010 Calls / assistant | ✅🔬 | Mic routed (SCO helmet preferred) `aa/AaMicrophone.kt`; AA audio sink advertised but PCM discarded — sound goes phone → helmet |
| AA-011 Telemetry | ✅ (honest) | No bike data exists (RE doc). Phone-GPS values have 5 s staleness (`RiderMetrics`); fuel/range are estimates and must be labelled so |
| AA-012 Input | ✅🔬 | Touch + multi-touch `aa/AaInput.kt`; handlebar via AVRCP `MediaButtonBridge.kt`; HID `RemotePad.kt`; `ButtonMap`; ghost-touch tests (`GhostTouchTest`) |
| AA-013 State screens | 🟡 | Phases incl. RECONNECTING / WAITING_FOR_BIKE / ERROR on the phone; dash can only show states while we're streaming |
| AA-014 Reconnect | 🟡🔬 | Reconnect phases exist; no documented recovery target — measure first (NFR-02) |
| AA-015 Density / font / theme prefs | ✅ | Per-bike prefs (`BikeScope`) persist; "font scale" for AA = DPI |
| AA-016 Limit interaction in motion | 🟡🔬 | Setup → **Lock Android Auto while moving** (off by default): GPS speed → `aa/DrivingStatusPolicy.kt` (≥10 km/h moving; parked after 5 s < 4 km/h) → DRIVING_STATUS 26 (no keyboard / config, short messages; video + voice stay). Needs H5 before defaulting on. Owned UI not gated yet |
| AA-017 Phone preview | ✅ | `HudViewActivity` (Dash view): live AA when connected, map preview otherwise |
| AA-018 AA compatibility | ✅🔬 | Regression matrix needed |
| AA-019 Custom renderer PoC | 🟡🔬 | Render + input + switch proven by Map/GPX; audio is phone-side (TTS → helmet) |
| AA-020 Rollback flag | 🟡 | Restore working projection covers today's switches; any owned home must add its flag to `ProjectionReset` |
| AA-021 No sensitive data in logs | ✅ | `LogRedactor` masks passwords/serials and cuts coordinates to 2 decimals (~1 km); `[LAP]` line no longer logs a position |
| AA-022 Compatibility matrix | 🟡 | `docs/SUPPORTED-BIKES.md` lists models; lacks firmware / phone / Android / AA-version columns |

---

## 6. Recommended plan

**Track A (commit now, P0 first)** — items 1–3 done on `feature/aa-track-a`; 4–5 open

1. **Real driving status (AA-016).** Report RESTRICTED/UNRESTRICTED from phone GPS speed (with
   hysteresis) instead of a constant, so Android Auto's own lockouts work. Ship behind a setting until a
   ride test confirms AA behaves (keyboard and long lists lock while moving; nothing breaks when stopped).
2. **"Restore working projection" (AA-004/020).** One action that resets video, strip, profile override and
   experimental flags for the selected bike to shipped defaults.
3. **Log hygiene (AA-021).** Redact coordinates in `LogRedactor`; fix the `[LAP] armed at` line.
4. **Dash token set (AA-005/006).** One `DashColors` source for strip, side panel, directions band and Map/GPX
   chrome using §5.2 values, plus a Daylight high-contrast preset. Google screens untouched.
5. **Matrix + baselines (AA-022, NFR-02).** Add firmware / phone / Android / AA columns; log reconnect time
   and AA ↔ owned switch time so thresholds come from measurements.

**Track B1-lite (feature-flagged, after Track A)**

6. **Owned "Ride home" on the dash.** A Presentation like `GpxDashUi`: clock, connection state, GPS speed,
   range *estimate*, Google next-turn (if Turn info on), shortcuts to **Android Auto** (default) and
   **Map / GPX**. Handlebar-first focus order, no text entry. Off by default; any failure → soft-switch
   back to AA.
7. **Now-playing card** — only if notification-listener access is approved (§8).

**Not doing:** B2; re-theming Google's screens; inventing telemetry.

---

## 7. Hardware evidence still needed (exit gate)

| # | Test | Captures |
|---|---|---|
| H1 | Baseline connect on target bike | model/year, dash firmware, canvas size, phone, Android, AA version |
| H2 | AA → Map/GPX → AA soft-switch ×10 | switch latency, any black frames / PXC drop |
| H3 | Wi-Fi drop & recover ×5 | time to video, prompts shown |
| H4 | Handlebar + touch matrix (AA and owned UI) | each key → action, ghost touches |
| H5 | Driving-status prototype ride | AA lockouts engage moving / release stopped |
| H6 | Day / night readability | strip + owned chrome in sun and dark |

Use `am instrument` or manual runs on the phone — **not** `connectedAndroidTest`, which uninstalls the app
and wipes ride data.

---

## 8. Open decisions (SRS §11)

| # | Question | What the code already answers |
|---|---|---|
| 1 | Bike model / year / firmware / resolution | Need yours — profiles exist for CFDL16 (5" ~800×386) and CFDL26 (portrait / 800MT touch) |
| 2 | Phone, Android, AA version, rooted? | Need yours — production path needs no root |
| 3 | Replace AA launcher, or owned home + one-tap to stock AA? | Full replacement of Google's launcher isn't possible; owned home + switch is feasible today |
| 4 | Nav inside owned UI: full map, next-turn only, or launch AA? | Full **OSM** map exists (Map/GPX); with Google Maps only next-turn data is available |
| 5 | Which bike data is exposed? | **None** without T-Box; phone GPS speed/alt/heading + fuel/range estimates |
| 6 | Allow modified Google components? | Recommend **no** |
| new | Allow notification-listener access for a media card? | New sensitive permission; needed only for AA-009 in owned UI |
| new | Driving status from GPS on by default? | Recommend on after H5 passes |
