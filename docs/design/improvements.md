# Improvements backlog

Status: **living document**, started 2026-06-23.

Post-MVP enhancements that are deliberately *not* being done now, with
enough context to pick them up later without re-deriving the analysis.
Each entry records the idea, why it's parked, and what unblocks it.

---

## Lane-aware turn guidance (Valhalla)

Status: **parked — revisit after Play Store approval.**

### What

Show lane guidance ("get in the left two lanes") on the Android Auto
turn card and the phone navigation UI. The androidx.car.app `Step` API
already renders lanes (`Step.Builder.addLane` / `setLanesImage`) the
moment we can feed it a lanes array — the gap is the data, not the
template.

### Why it's parked

- **Not required for approval.** The car rejection is about responding
  to navigation intents (fixed) and keeping the navigation surface
  map-only (done). Lanes are polish, not a blocker — so they wait until
  the app is approved and stable.
- **Our offline router can't produce them.** We route with **BRouter**
  (pure-Java, offline, `rd5` segment tiles). `rd5` tiles are optimized
  for routing cost and don't carry the OSM `turn:lanes` tags, so there's
  nothing to surface.

### The data source (free / open)

OpenStreetMap's [`turn:lanes`](https://wiki.openstreetmap.org/wiki/Key:turn:lanes)
tagging (+ `turn:lanes:forward/backward`, `lanes`, …) is the open
lane-aware dataset. Caveat: **coverage is uneven** — good in
Belgium/Netherlands/Germany and well-mapped motorways, sparse on minor
roads. Lane guidance is only ever as good as the local tagging.

### Engine options (evaluated 2026-06-23)

| Engine | Lanes from `turn:lanes`? | Offline on Android? | License |
|---|---|---|---|
| **Valhalla** | ✅ `turn_lanes` → per-maneuver `lanes` array | ✅ tiled, built for it; C++ via **NDK** | MIT |
| OSRM | ✅ `intersections[].lanes` | ❌ server-side in practice | BSD |
| GraphHopper | ❌ not implemented ([#1131](https://github.com/graphhopper/graphhopper/issues/1131), open since 2017) | ✅ pure-Java | Apache 2.0 |
| BRouter (current) | ❌ `rd5` drops lane tags | ✅ pure-Java | MIT |

**Conclusion: Valhalla is the only realistic offline-on-device path to
real lanes.** GraphHopper is an easy Java swap but doesn't give lanes;
OSRM isn't viable offline; rolling our own lane parser on top of BRouter
re-implements what Valhalla already does.

### Integration cost / risks

- **C++/NDK** dependency (vs today's pure-Java BRouter) — bigger APK,
  native build, ABI splits to manage.
- **Different tile format** than `rd5` — new download/cache pipeline
  (`RoutingDataRepository`, `Rd5Tiles`, `OfflineRouter` all assume
  BRouter), and the corridor map-tile caching stays as-is.
- Decide whether Valhalla **replaces** BRouter or runs **alongside** it
  (e.g. BRouter for hiking/cycling profiles, Valhalla for car) — two
  engines doubles tile storage.
- Lane data is **patchy**; the UI must degrade gracefully where tags are
  missing (most of the time there'll simply be no lanes array).

### What unblocks it

App approved on the Play Store, car navigation stable in the field, and
appetite for a native-routing migration. Spike: prototype Valhalla
offline tiles over Benelux, confirm the maneuver `lanes` array reaches a
car `Step`, then scope the engine swap-vs-coexist decision.

Refs: [Valhalla turn-by-turn API](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/),
[Valhalla offline on Android (#4704)](https://github.com/valhalla/valhalla/issues/4704).
