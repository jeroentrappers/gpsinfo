# GPSinfo traffic service

Live traffic for navigation. Polls the national DATEX II feeds, normalizes
every event into a WGS84 `Incident`, serves them to the apps over **SSE
(push)** + a **JSON snapshot (poll)**, and feeds **native Valhalla live
traffic** so routes and ETAs avoid jams and closures.

**Live in production** at `https://traffic.appmire.be` (TLS + `?key=` gate,
same key as tiles/routing), on `appmire-hetz1` beside the Valhalla router.

## Architecture

```
 DATEX II NAP feeds ──poll──> traffic service ──/edgespeeds──> traffic-writer ──> traffic.tar (mmap)
 (verkeerscentrum, …)         (Go)            (edge→speed)     (sidecar)          ▲
                               │  /traffic (snapshot, bbox)                       │ reads live speeds
                               │  /events  (SSE push)                          Valhalla  ──> app routes/ETAs
                               └────────────> apps (car + phone): markers, route colour, alternatives
```

- **traffic service** (`main.go`, `datex.go`, `lambert72.go`) — fetch +
  normalize + serve. Reprojects Belgian Lambert-72 (EPSG:31370) geometry to
  WGS84 in pure Go.
- **edge pipeline** (`valhalla.go`) — resolves each incident's geometry to
  Valhalla directed-edge ids via `/trace_attributes` and assigns a target
  live speed per category. Served at `/edgespeeds`.
- **traffic-writer** (`writer/`) — sidecar that pokes those speeds into
  Valhalla's memory-mapped `traffic.tar` (phase 2, below).

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /traffic?bbox=minLon,minLat,maxLon,maxLat` | JSON snapshot (poll / initial load), bbox-filtered |
| `GET /events` | Server-Sent Events — one `update` per source per refresh |
| `GET /edgespeeds` | edge→speed list for the traffic.tar writer (phase 2) |
| `GET /healthz` | per-source incident count, `publicationTime`, fetch age, errors |

Real-time delivery to clients is **notify-then-fetch-bbox**: the SSE stream
carries only tiny `update` notifications; on each, the client re-pulls
`/traffic?bbox=<route corridor>` so it downloads just its area, only when
something changed. Polling `/traffic` is the fallback when SSE can't connect.

## Run locally

```sh
go run . -once          # fetch every source once, print a summary + freshness
go run . -addr :8791    # run the server
# edge resolution (phase 2) is enabled by pointing at a Valhalla instance:
TRAFFIC_VALHALLA_URL=http://127.0.0.1:8002 go run . -addr :8791
```

## Sources (`main.go`)

| ID | Feed | State |
|---|---|---|
| `be-flanders` | verkeerscentrum.be DATEX II v3 (Lambert-72) | ✅ live |
| `nl-ndw-srti` | NDW `veiligheidsgerelateerde_berichten_srti` DATEX II v3 (gzip) — safety/incidents | ✅ live |
| `nl-ndw-closures` | NDW `tijdelijke_verkeersmaatregelen_afsluitingen` DATEX II v3 (gzip) — closures/rerouting | ✅ live |
| `fr-dir` | Bison Futé `Evenementiel-DIR/grt/RRN/content.xml` DATEX II — non-concessioned national network | ✅ live |
| `de-autobahn` | Autobahn GmbH REST (JSON) via `autobahn.go` — warning/roadworks/closure | ✅ live |
| `lu-cita` | CITA `cita.lu/info_trafic/datex/situationrecord36` DATEX II v3.6 (data.public.lu, CC0) | ✅ live |
| `be-wallonia` | `ws.sofico-trademex.be` DATEX II (SPW/SOFICO) | URL wired; **contract basic-auth** — set `TRAFFIC_BE_WALLONIA_USER/PASS` (via secrets.yml) and it auto-enables (`loadCreds`). Creds obtained from the dataset owner on transportdata.be. |
| `be-brussels` | Brussels Mobility | **no open incident feed** — data.mobility.brussels is counts/measurement, not DATEX situations |

DATEX II Situation feeds parse with `parseDATEX`, which now walks the document
and matches `situation` / `publicationTime` by **local name at any depth** —
so it handles the different NAP wrappers (Flanders exposes `situation` near the
root, NDW nests it under `messageContainer > payload`, France under
`payloadPublication`) namespace-agnostically. Germany's Autobahn API is JSON,
handled by the dedicated fetcher in `autobahn.go` (dispatched from `fetch()`),
emitting the same `Incident` model. Per-source basic-auth comes from env
`TRAFFIC_<ID>_USER` / `_PASS` (`-`→`_`, upper-cased).

> Licences: BE verkeerscentrum open; NL NDW open data; FR Etalab Open Licence
> 2.0; DE Autobahn GmbH (dl-de/by-2-0). Attribution terms vary — surface in the
> app's data-attribution screen.
>
> NDW's `planningsfeed_wegwerkzaamheden_en_evenementen` (roadworks) is
> intentionally **not** ingested: it's a ~165 MB national planning dump of
> mostly future works, not live-drive relevant.

> Reuse note: align `parseDATEX` with the charging project's DATEX II parser
> — that side is the AFIR `EnergyInfrastructure` profile, this is the
> `Situation` profile; shared HTTP-poll plumbing, different bindings.

## Phase 2 — native Valhalla live traffic

Valhalla reads a memory-mapped **`traffic.tar`** of fixed-size per-edge
`TrafficSpeed` records (`mjolnir.traffic_extract`); at request time it prefers
live speed. End to end:

1. **Overlay enabled** — the gis-ops image env `traffic_tar_name: "traffic.tar"`
   wires `mjolnir.traffic_extract` but does **not** create the file. The
   edge-aligned skeleton is built with:
   ```sh
   docker exec -u root gpsinfo-valhalla \
     valhalla_build_extract -c /custom_files/valhalla.json -t -O
   ```
   (`-t/--with-traffic`; run as root to overwrite the extract). The valhalla
   role does this on first run, primes it once, then restarts Valhalla —
   **Valhalla only adopts live traffic that is present when it maps the file**,
   so the order (build skeleton → write speeds → restart) matters.

2. **Writer sidecar** (`writer/`) — `updater.cc` is compiled **inside the
   exact gis-ops image** so the `TrafficSpeed` / `TrafficTileHeader` layout
   matches the running router byte-for-byte (header-only against
   `traffictile.h`; no libvalhalla link). It walks the traffic.tar by hand and
   pokes per-edge speeds; `run.sh` loops `GET /edgespeeds` → `updater` every
   `INTERVAL` (45 s). Runtime base is `ubuntu:24.04` to match the build glibc.

   Traffic-extract format (Valhalla 3.5.x): a TAR whose first member
   `index.bin` is an array of `tile_index_entry { uint64 offset; uint32
   tile_id; uint32 size }`. Each tile's data is at `tar_base + offset` as a
   32-byte `TrafficTileHeader` + `directed_edge_count` × 8-byte `TrafficSpeed`.
   A directed edge's GraphId value (= `/trace_attributes` `edge.id`) is
   `tile_id | (index << 25)`.

   Speed mapping (`targetSpeedFor`): closure/accident → 0 (closed),
   congestion → 15 km/h, roadworks → 40 km/h. Encoded as km/h ÷ 2 with
   `breakpoint1 = 255` (full edge); edges not in `/edgespeeds` are cleared.

3. **App opt-in** — the app requests `date_time: { type: 0 }` (current) on
   all Valhalla route + alternates calls, so routes / ETAs / en-route forks
   are traffic-aware. The en-route fork ranking uses Valhalla's live durations
   directly (no client-side congestion penalty — would double-count).

**Verified:** a route across a live closure detours
(4.5 km/330 s free-flow → 5.2 km/365 s current); the writer sets ~2000
edges/cycle; non-zero records on disk match the requested set.

## Deploy

```sh
cd deploy/ansible
# DNS: traffic.appmire.be → the box (already set).
ansible-playbook site.yml --tags traffic  -e ansible_host=appmire-hetz1   # Go service (host net, loopback :8793)
ansible-playbook site.yml --tags valhalla -e ansible_host=appmire-hetz1   # Valhalla + traffic.tar skeleton + writer sidecar
ansible-playbook site.yml --tags nginx    -e ansible_host=appmire-hetz1   # vhost + TLS
```

> The `-e ansible_host=appmire-hetz1` override is required: the inventory
> points at `~/.ssh/id_ed25519`, but the working key is reached via the
> `appmire-hetz1` SSH-config Host alias (`id_ed25519_oldbox`).

Verify:

```sh
curl "https://traffic.appmire.be/healthz?key=<TILES_API_KEY>"
curl "https://traffic.appmire.be/traffic?key=<KEY>&bbox=4.3,50.8,4.5,50.95"
docker logs --tail 3 gpsinfo-traffic-writer   # on the box
```

## Operational notes / follow-ups

- **Skeleton vs tile rebuilds:** if the routing tiles are rebuilt (coverage
  change → different edge counts), delete `custom_files/traffic.tar` so the
  role regenerates the skeleton with the new counts.
- **Congestion granularity:** congestion is a flat 15 km/h; could scale by
  severity / jam length later (the feed carries an `abnormalTrafficType`).
- **Client-side heuristic:** retired. Fork trade-offs now come straight from
  Valhalla's live-traffic-aware durations; the app no longer scores incidents
  against routes itself (that would double-count what the engine already does).
- **Coverage:** BE Flanders + NL (NDW SRTI + closures) + FR (non-concessioned
  national network) + DE (Autobahn network) live. Still to wire: BE Wallonia
  (SOFICO creds), BE Brussels + LU (URLs), and FR's concessioned motorways
  (separate concessionaire feeds — Vinci/APRR/Sanef). Mostly motorway +
  national roads, not urban streets.
- **Edge-resolution warm-up:** adding a large feed (Germany) resolves incidents
  to Valhalla edges gradually — `maxResolvePerCycle` (valhalla.go) caps new
  `/trace_attributes` calls per poll so a first load can't burst thousands at
  the loopback router; the cache drains the backlog over the next few cycles.
