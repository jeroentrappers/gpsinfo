# Navigation / Information Architecture (v2)

Status: **agreed 2026-06-12**, supersedes `activity-dashboard-ux.md`.

## Why

The app grew three competing organizing metaphors stacked on a legacy
drawer: **Persona** (who you are) → **Activity** (what you're doing) →
**Simple/Pro** (how dense) → **Dashboard profile** (which cards), plus a
**Hub** launcher and a separate **drawer** that both reach the same
destinations under different labels. Result: every feature has 2–3
entry points, "Pro" means a different screen per activity
(`routeForActivity` is irregular), activities opened from the Hub are
dead-ends, and a forced persona pick gates first run. The *features* are
a strength; the *framing* is the problem.

## Principle

**One primary navigation model.** A flat, predictable bottom navigation
bar over the app's real jobs-to-be-done. Personalization is a
lightweight preference, never a navigation layer.

## The four pillars (bottom navigation)

```
 Dashboard  │   Map   │  Record  │  Tools          ⚙︎ (settings, top bar)
```

| Tab | Is | Absorbs (old) |
|---|---|---|
| **Dashboard** | Live GPS readout cards (today's "Custom/Everything"). A profile chip at top swaps layouts (Default/Runner/Cyclist/Motorsport). | Custom/Everything; dashboard profiles |
| **Map** | Live map + navigate + "where am I / mark this". | Drive&Navigate + Explore&Orient |
| **Record** | Trails/tracks, ghost runner, pace targets, sports. | Track&Train |
| **Tools** | Specialist/diagnostic list: GPS Lab (satellites/NMEA/compass/calibration), Rally, OBD Lab, sensor pairing, waypoints. | GPS Lab, Rally, OBD Lab, pairings |

Every existing screen stays reachable; each tab is a stable home (no
dead-ends); exactly one path to each feature.

## Onboarding (replaces the persona picker)

First run only: **Welcome → Language → Units → Theme → Dashboard**, each
step **pre-filled with a smart default** so it's mostly "Continue", with
"change anytime in Settings".

- **Language** — Android 13+ only (platform `LocaleManager`, as today);
  on Android 12 and older the step is skipped and the app follows the
  system language (already localized to it).
- **Units** — default from locale (`UnitSystem.defaultFor`).
- **Theme** — default "Follow system" (`ThemeOverride.System`).

No persona, no activity, no profile choice during onboarding. Lands on
the **Dashboard** tab.

## Retired

- Persona system + `PersonaPickerScreen` (10 personas) — as navigation.
- The **Hub** (`ActivityHubScreen`) and the `Activity`/`routeForActivity`
  Simple/Pro routing.
- The four `*Simple` screens (Track/Explore/GpsLab/Rally) — replaced by
  one adaptive screen per pillar + the existing density setting.
- The Dashboard **drawer** — its items become the tabs + Settings.

Kept: dashboard **profiles** (as a chip on the Dashboard tab), the
density setting, and every feature screen.

## Phasing

1. ✅ **Onboarding** — new Language/Units/Theme flow; first run → Dashboard.
2. ✅ **Bottom-nav shell** — `Scaffold` + `NavigationBar` with the 4 tabs
   wired to existing screens (Dashboard, Map=LiveMap, Record=Trails,
   Tools=new list).
3. ✅ **Fold + retire** — dashboard-profile is now a top-bar **chip**
   (with an overflow menu for New waypoint / Save location / Settings);
   the hamburger **drawer** is gone; Map (LiveMap) drops its back arrow
   as a top-level tab. Deleted: `ActivityHubScreen` (Hub) + `Routes.Hub`,
   `PersonaPickerScreen`, the four `*Simple` screens + routes, the
   per-screen Simple/Pro toggles (`onShowSimple` on Rally/Satellite/
   Compass), `routeForActivity`, and `DashboardViewModel.completeOnboarding`.

   **Follow-up cleanup (2026-06-14):** deleted the redundant **Sports
   Dashboard** (whole `ui/sports/` package + `RouteProjection` + its test
   + the `sports_*` strings + the sports-tutorial setting) — the
   Runner/Cyclist/Motorsport dashboard *profiles* cover it. Retired the
   leftover **persona machinery**: `Persona.kt` (`Personas`/`PersonaOption`,
   zero callers) and the dead `pinnedActivities` / `lastActivity` /
   `activityIntroSeen` VM + repository members and their DataStore keys,
   plus the `persona_*` picker/display strings.

   Kept (load-bearing): the `Activity` enum + `detailLevels` (Map/Live
   detail toggle), `PersonaLayouts.kt` (renders the bespoke per-profile
   dashboard faces), and `DashboardProfile` (independent of the deleted
   persona system — uses literal display names).
