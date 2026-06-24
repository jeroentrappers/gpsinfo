# Navigation engine v2 — Valhalla migration

Status: **planned 2026-06-25** (greenlit). Supersedes the parked Valhalla
note in `improvements.md`.

## Goal

Replace the BRouter offline router with **Valhalla**, to get:
- **Route profiles / alternatives** the user picks from — *fastest*,
  *shortest*, *most economic* (Valhalla `costing` + `alternates`).
- **Lane guidance** on the turn card (Valhalla maneuver `lanes`).
- **Faster (re)routing** (C++ engine; native alternates).
- Foundation for richer guidance later.

## Why this is phased (the hard parts)

Two pieces are **infrastructure, not app code**, and gate everything:

1. **Valhalla native lib for Android.** Valhalla is C++; there is no
   official Android AAR. We must cross-compile `libvalhalla` (+ protobuf;
   modern Valhalla has dropped most of the boost dependency) for `arm64-v8a`
   (and `x86_64` for the emulator) via NDK + CMake, and write a **thin JNI
   wrapper**. Cleanest JNI surface = Valhalla's **Actor API**: one call
   `route(requestJson): responseJson`. The app builds the request JSON
   (costing, alternates, units, language) and parses the response (legs,
   maneuvers incl. `lanes`, encoded `shape` polyline).
2. **Valhalla tiles.** Valhalla uses its own routing tiles
   (`valhalla_build_tiles` from an OSM PBF). Generate for the coverage area
   (Benelux first, then Europe), package as a tar, host (reuse the existing
   tile pipeline / storage box). On device: download + route from the tile
   dir/tar. Replaces the rd5 download path (`RoutingDataRepository`,
   `Rd5Tiles`).

**Decisions needed before phase 3:**
- How to produce the Android Valhalla lib — build from source in CI, or a
  vetted community build? Which ABIs?
- Where/how to generate + host Valhalla tiles (Benelux now), and the
  on-device download/extract format (tar vs dir).
- Coverage/size budget (Benelux ≈ small; Europe = large) + update cadence.

## Phases

### Phase 1 — engine-agnostic app architecture (no native dep; ships now)
Deliver the *UX* on the existing BRouter engine so it's testable
immediately and Valhalla slots in behind an interface later.
- `Router` interface: `suspend fun route(from, to, profile): List<RouteOption>`
  (returns 1..N alternatives). `RouteProfile { FASTEST, SHORTEST, ECONOMIC }`.
- `OfflineRoute` gains optional **lane data** per `TurnHint`
  (`lanes: List<Lane>?`, null on BRouter).
- `BRouterRouter` implements `Router` (maps profiles → BRouter profiles:
  `car-vario`/`car-fast`, `car-shortest`, an eco `.brf`).
- **Route-choice UI**: a `RoutePreviewNavigationTemplate` screen listing the
  options (distance + ETA + profile label); picking one starts navigation.
  Reachable from "Where to?" after a destination is chosen.
- `NavigationController.navigateTo` takes a chosen `RouteOption`.
- `CarManeuvers` feeds `Step.Builder.addLane(...)` / `setLanesImage(...)`
  when lane data is present (no-op on BRouter → ready for Valhalla).

### Phase 2 — reroute speed on BRouter (interim)
Keep the BRouter engine warm; bound the re-route search; reuse decoded
tiles. Mitigates #10 until Valhalla lands.

### Phase 3 — Valhalla native lib + JNI (spike first)
**Spike:** prove `libvalhalla` builds for `arm64-v8a` and routes offline
over a Benelux tile set on a device/emulator, via the `route(json):json`
JNI call. Then: Gradle externalNativeBuild (CMake) or a prebuilt AAR,
`ValhallaRouter : Router` parsing the Actor response (shape, maneuvers,
lanes, alternates). Profiles → costing (`auto`, `auto` + `shortest:true`,
an eco cost config).

### Phase 4 — Valhalla tiles pipeline
`valhalla_build_tiles` for Benelux → host → device download/extract
(replacing the rd5 path). Progressive coverage like the existing map-tile
pipeline.

### Phase 5 — cut over + retire BRouter
Flip the default `Router` to Valhalla once tiles + lib are stable; keep
BRouter behind a flag for a release, then remove. Lane guidance goes live
(it rides the existing `CarManeuvers` plumbing from Phase 1).

## Notes
- Phases 1–2 are pure Kotlin, ship on the current engine, and directly
  deliver the **profile-choice UX** + **lane plumbing** now.
- Phases 3–4 are the native + tiles infrastructure (the real lift).
- Keep `Router` the single seam so the cutover is a one-line default swap.
