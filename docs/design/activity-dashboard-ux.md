# Activity-based dashboard — UX spec

Status: **proposal / for sign-off** · Direction: **Hybrid A+B** (persona-seeded Activity Hub, per-activity Simple/Pro) · Author: design exploration, 2026-06-09

---

## 1. Why this exists

GPSinfo started as a one-screen GPS dashboard and has grown to cover at least five unrelated jobs — **navigate, record/train, rally, orient/find, and raw-GPS instrumentation** — plus an Android Auto projection of several of them. Today they're surfaced as a flat set of dashboard cards plus a long list of screens reachable from a single dashboard. Two problems follow:

- **Discovery** — users don't learn what the app can do or *why* they'd open a given screen (a sailor never finds the sun/compass tools; a runner never finds NMEA).
- **Density** — every user carries the cognitive cost of all ~24 screens, even the 90% they'll never use.

The 8 personas already in the app (`DashboardProfile`) are the latent fix: they're effectively answers to *"what do you use this for."* Today they only re-order dashboard cards. This spec promotes that idea to the top of the app.

### Goals
- A clear, stable top-level model of *what the app is for*.
- Each capability has a labelled front door that states **why** and **how**.
- Users see only what their activity needs; depth is opt-in.
- New features slot into the structure instead of lengthening a flat menu.
- Reuse the existing personas / profiles / card system as the backbone.

### Non-goals
- Removing any current capability (power users keep an "everything" path).
- Rewriting the rendering of individual screens (Pro ≈ what exists today).
- Re-theming; accents/night-mode stay as-is.

---

## 2. Information architecture: five activities

Every current screen lands in exactly one **home activity**; a few appear as shortcuts in a second. The set is intentionally small and stable.

| Activity | One-liner (the "why") | Contains |
|---|---|---|
| 🧭 **Drive & Navigate** | "Get me there." | Navigation (BRouter offline), live vector map, Places (Home/Work/saved/recent), address search (Photon), speed gauge, G-meter, Android Auto surface |
| 🏃 **Track & Train** | "Record what I did and pace me." | Trail recording, Sports view, pace targets, ghost runner, HR/power BLE sensors, GPX/FIT export, trail list & map |
| 🏁 **Rally / Regularity** | "Hold an exact average over a stage." | Rally mode, wheel-sensor pairing, speed tables, ±recal nudges, AA delta HUD |
| 🔭 **Explore & Orient** | "Where am I, where's that, mark this." | Compass + calibration, waypoints, coordinate formats, share position, world map, sun/moon & time, geocache flow |
| 📡 **GPS Lab** | "Show me the raw signal." | Satellites / sky view, NMEA stream, fix status, accuracy, constellations |

Plus a retained escape hatch:

| ⚙️ **Custom / Everything** | "I want it all on one screen." | The current editable dashboard (`DashboardProfileEditor`) — nothing is taken away. |

---

## 3. Persona → activity & defaults map

Personas become *bundles*: they pin a primary activity (and sometimes secondary), set the dashboard profile + accent + a default detail level. First-run multi-select unions these.

| Persona | Accent | Primary activity | Also pins | Default level |
|---|---|---|---|---|
| Default / Driver | orange | Drive & Navigate | Explore | Simple |
| Runner | red | Track & Train | — | Simple |
| Cyclist | blue | Track & Train | Drive & Navigate | Pro |
| Hiker | green | Explore & Orient | Track & Train | Simple |
| Sailor | navy | Explore & Orient | GPS Lab | Pro |
| Motorcyclist | amber | Drive & Navigate | Rally | Pro |
| Geocacher | purple | Explore & Orient | — | Simple |
| Ham / SOTA | teal | GPS Lab | Explore & Orient | Pro |
| *(new)* Motorsport | red/black | Rally | Drive & Navigate | Pro |
| "Just GPS stuff" / Custom | orange | Custom / Everything | all | Pro |

Open decision: add the **Motorsport** persona (Rally has none today) or let any persona pin Rally.

---

## 4. Simple vs Pro — per activity

Detail level is **per-activity**, defaulted by persona, toggled in the activity's app bar, and persisted per activity. (A user is often Simple for Navigate but Pro for GPS Lab — a global switch forces a false choice.)

- **Pro ≈ what exists today**, so the only net-new design per activity is the **Simple** layout.

| Activity | Simple (essentials + one-line "how") | Pro (full instrument set — mostly exists today) |
|---|---|---|
| 🧭 Drive & Navigate | Search box + map + Home/Work/recent; one-tap "Go". Speed read-out. | + speed gauge dial, G-meter, route options, tilt/3D map modes, breadcrumb, pan, AA controls |
| 🏃 Track & Train | Big **Start/Stop**; live distance · time · pace. | + Sports view, pace targets, ghost runner, HR/power pairing & zones, lap markers, stride calibration, GPX/FIT export |
| 🏁 Rally / Regularity | Target avg + tap-to-start; ± delta HUD. | + speed tables, wheel-sensor pairing, multi-wheel, ±10 m recal, OCR/OBD (future) |
| 🔭 Explore & Orient | Compass + current position + "mark here" + "share". | + coordinate-format picker, waypoint manager, world/sun-moon, declination, calibration, geocache tools |
| 📡 GPS Lab | Fix status + sats-in-use + accuracy, plain language. | + sky plot, per-sat CN0 bars, NMEA stream, constellation filters |

---

## 5. Screen-by-screen IA (current routes → activity)

| Route (today) | Home activity | Notes |
|---|---|---|
| `LiveMap`, `NavPicker` (search), `Speed`, `GForce` | Drive & Navigate | speed/G also shortcut from Track |
| Android Auto (`TripDashboardScreen`) | Drive & Navigate | own front door; see §8 |
| `Trails`, `TrailMap`, `Sports`, `PaceTargets`, `Ghost`, `HrPair`, `HrZones`, `CpPair`, `StrideCalibration` | Track & Train | |
| `Rally`, `WheelPair` | Rally / Regularity | |
| `Compass`, `Calibration`, `Waypoints`, `SharePosition` | Explore & Orient | + world/sun-moon cards |
| `Satellites`, `Nmea` | GPS Lab | |
| `DashboardProfileEditor` | Custom / Everything | the retained "all-in-one" |
| `About` | global (settings/overflow) | not an activity |

Cards (`DashboardSection`: Status, Position, Speed, Sky, Compass, World, TimeSun, GForce) become the building blocks of each activity's **Pro** layout — the profile system already does this; we just scope each profile to an activity.

---

## 6. First-run flow (Proposal B seed)

```
┌─────────── Welcome ───────────┐      ┌────── Pick what fits (1+) ──────┐
│  GPSinfo                       │      │  ☑ Cyclist     ☐ Sailor         │
│  A precise GPS toolkit for     │  →   │  ☐ Runner      ☑ Motorcyclist   │
│  driving, tracking, exploring  │      │  ☐ Hiker       ☐ Geocacher      │
│  and tinkering.                │      │  ☐ Ham/SOTA    ☐ Just GPS stuff │
│              [Get started]     │      │                   [Continue]    │
└────────────────────────────────┘      └─────────────────────────────────┘
```

Outcome of Continue:
1. Union the selected personas' pinned activities → those tiles float to the top of the Hub.
2. Set dashboard profile + accent from the *primary* persona (first selected); offer to switch later.
3. Set each pinned activity's default detail level.
4. Land on the **Hub** (not a deep screen) so the user sees the full map of capabilities once.

Skippable → defaults to Driver (Drive & Navigate, Simple). Re-runnable from Settings.

---

## 7. Home: the personalized Activity Hub (Proposal A)

```
┌──────────────── GPSinfo ──────────────┐
│  What do you want to do?          ⚙︎  │
│  ── Your activities ──                 │
│ ┌────────────┐ ┌────────────┐          │
│ │ 🏃 Track & │ │ 🧭 Drive & │  ← pinned│
│ │   Train  ★ │ │ Navigate ★ │   (persona)│
│ └────────────┘ └────────────┘          │
│  ── More ──                            │
│ ┌────────────┐ ┌────────────┐ ┌──────┐ │
│ │🔭 Explore  │ │📡 GPS Lab  │ │🏁Rally│ │
│ └────────────┘ └────────────┘ └──────┘ │
│ ┌────────────┐                         │
│ │⚙️ Custom    │  Resume: Track & Train ▸│
│ └────────────┘                         │
└────────────────────────────────────────┘
```

Behaviours:
- Each tile: icon + title + one-line "what." Long-press / `ⓘ` flips to **why + how** (2–3 sentences) — this copy doubles as onboarding and as Play-listing screenshots.
- **Resume last activity**: a prominent shortcut + a setting "open to: Hub / last activity" so regulars aren't slowed by the hub.
- Pinned activities (from persona) shown first; the rest under "More" — everything stays discoverable.
- Drag-reorder / pin-unpin to personalize beyond the persona seed.

---

## 8. Navigation model

- **Stack**: Hub is the root. Entering an activity pushes the activity's home screen; deeper screens push onto that. A persistent "home/hub" affordance (app-bar logo or back-to-hub) returns to the Hub.
- **Resume**: on cold start, honour the "open to" setting (Hub vs last activity).
- **Deep links** (geo:, navigation intents, share targets) route straight into the relevant activity, bypassing the Hub.
- **Android Auto** (§ below) is independent.

### Android Auto
The car surface is its own front door and should stay **navigation-centric** (Drive & Navigate) regardless of the phone's active activity — drivers want the map, not the GPS Lab. Track recording and Rally remain reachable from the car action strips as today. Open decision: optionally mirror the phone's *detail level* (Simple/Pro) for the car gauges.

---

## 9. Detail-level mechanics

- Stored per activity (e.g. `detailLevel.<activity>` in `SettingsRepository`), seeded at first-run from persona.
- One toggle in each activity's app bar ("Simple ⇄ Pro").
- Simple layouts are new, curated, fixed. Pro layouts reuse the existing dashboard/profile/editor machinery, scoped per activity.
- "Custom / Everything" is always Pro (it *is* the full editor).

---

## 10. Migration from today

- The current single dashboard becomes the **Custom / Everything** activity — existing users lose nothing; their saved profile/accent carry over.
- First run for *existing* users: a one-time card on the old dashboard — "Try the new activity view" → opens the Hub; opting out keeps today's behaviour as the default home.
- No data migration: profiles, trails, waypoints, sensors all unaffected.

---

## 11. Implementation mapping (light — for estimation)

| Spec concept | Reuses / touches |
|---|---|
| Activities | new `Activity` enum + a registry mapping → existing `Routes` |
| Persona seed | existing `DashboardProfile` (id, accent, cards) + new `pinnedActivities` + `defaultDetail` fields |
| Hub home | new Compose screen; new root in `MainActivity` NavHost |
| First-run picker | new screen; persists to `SettingsRepository` (extends existing onboarding-seen flag) |
| Per-activity Pro layout | existing dashboard + `DashboardProfileEditor`, scoped per activity |
| Per-activity Simple layout | net-new, ~5 small composables |
| Detail level | new `SettingsRepository` keys |
| Car behaviour | unchanged (TripDashboardScreen stays navigation-first) |

Net-new design surface ≈ the Hub, the first-run picker, and **one Simple layout per activity** (5). Everything else is re-pointing existing screens.

---

## 12. Suggested rollout

- **Phase 1** — Activity enum + registry; Hub home behind a setting; route existing screens into activities (no Simple layouts yet → activities open at today's Pro views). Ships the IA with near-zero risk.
- **Phase 2** — First-run persona picker → seeds pins + accent + detail default.
- **Phase 3** — Per-activity Simple layouts + the Simple/Pro toggle.
- **Phase 4** — Polish: why/how copy (localized in all 12 locales), drag-reorder/pin, resume-to-last, "new activity view" nudge for existing users.

---

## 13. Open decisions (need your call)

1. **Motorsport persona** — add it, or let any persona pin Rally?
2. **Home replacement vs. opt-in** — does the Hub replace the dashboard as the launch screen, or is it opt-in initially (safer for existing users)?
3. **Car detail level** — mirror the phone's Simple/Pro on the AA gauges, or keep the car fixed?
4. **"Explore & Orient" breadth** — it's the widest bucket (compass + waypoints + world/sun + geocache). Split into two if it feels overloaded in testing?
5. **Activity naming/icons** — the labels above are placeholders; worth a quick naming pass before localization.

---

## 14. Success signals

- Fewer users stuck on the default dashboard never opening other features (discovery).
- First-run → first useful action in ≤ 2 taps (time-to-value).
- Simple is the resting state for most users; Pro adoption concentrated in the geek personas (right-sized density).
