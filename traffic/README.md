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
| `be-flanders` | verkeerscentrum.be DATEX II v3 | ✅ live |
| `be-wallonia` | transportdata.be walloon-road-traffic-events (SOFICO) | needs basic-auth (contract) — set `TRAFFIC_BE_WALLONIA_USER/PASS` + URL, then `Enabled` |
| `be-brussels` | Brussels Mobility | URL TODO |
| `nl-ndw-incidents` | NDW DATEX II (gzip) | URL TODO (old `opendata.ndw.nu` host 404s; parser + gzip ready) |
| `lu-cita` | data.public.lu CITA DATEX II v3.6 (CC0) | URL TODO |
| `fr-dir` | transport.data.gouv.fr non-toll national network | URL TODO |
| `de-autobahn` | Autobahn GmbH REST (JSON, not DATEX II) | needs a separate fetcher |

Most are DATEX II Situation feeds that parse with `parseDATEX` — fill in the
exact resource URL (+ any creds/key) and set `Enabled: true`. Germany's
Autobahn API is JSON and needs a small separate fetcher emitting the same
`Incident` model. Per-source basic-auth comes from env
`TRAFFIC_<ID>_USER` / `_PASS` (`-`→`_`, upper-cased).

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
- **Coverage:** Flanders only until Wallonia (SOFICO creds) / Brussels / the
  neighbours are wired. Mostly motorway + national roads, not urban streets.
