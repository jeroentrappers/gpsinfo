# GPSinfo traffic service (prototype)

Live traffic aggregator. Polls the national DATEX II traffic feeds,
normalizes every event into a WGS84 `Incident`, and serves it to clients
over **SSE (push)** + a **JSON snapshot (poll)**. It sits *beside* the
Valhalla routing service — clients keep routing via Valhalla and use this
layer to warn, colour the route, and trigger **reroute-due-to-traffic**.

## Status

Working prototype, verified live against **Belgium / Flanders**
(`verkeerscentrum.be` DATEX II v3):

- 370–375 incidents per pull, **`publicationTime` ~45 s old** (1-min feed).
- Categories: `laneClosure` (~297), `roadworks` (~42), `congestion` (~31),
  `accident`, `rerouting`.
- Belgian **Lambert 72 (EPSG:31370) geometry reprojected to WGS84** in pure
  Go — verified (sample lands at `4.4325, 50.9155`, near Nivelles).

## Run

```sh
go run . -once          # fetch every source once, print a summary + freshness
go run . -addr :8791    # run the server
```

Endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /traffic?bbox=minLon,minLat,maxLon,maxLat` | JSON snapshot (poll / initial load), bbox-filtered |
| `GET /events` | Server-Sent Events — one `update` per source per refresh |
| `GET /healthz` | per-source incident count, `publicationTime`, fetch age, errors |

## Sources (`main.go`)

| ID | Feed | State |
|---|---|---|
| `be-flanders` | verkeerscentrum.be DATEX II v3 | ✅ live |
| `nl-ndw-incidents` | NDW DATEX II (gzip) | URL TODO (old host 404s; parser + gzip ready) |
| `be-wallonia` | transportdata.be walloon-road-traffic-events (CC0) | URL TODO |
| `be-brussels` | Brussels Mobility | URL TODO |
| `lu-cita` | data.public.lu CITA DATEX II v3.6 (CC0) | URL TODO |
| `fr-dir` | transport.data.gouv.fr non-toll national network | URL TODO |
| `de-autobahn` | Autobahn GmbH REST (JSON, not DATEX II) | needs a separate fetcher |

Most are DATEX II Situation feeds and parse with the existing
`parseDATEX`; only the exact resource URL (and any `?key=`) has to be
filled in, then `Enabled: true`. Germany's Autobahn API is JSON — a small
separate fetcher emitting the same `Incident` model.

> Reuse note: align `parseDATEX` with the **charging project's DATEX II
> parser** — the charging side is the AFIR `EnergyInfrastructure` profile;
> this is the `Situation` profile, so they share the HTTP-poll plumbing but
> need different bindings.

## How it integrates with routing (Valhalla)

Two phases:

1. **Now (client-side avoid):** the app subscribes to `/events`, pulls
   `/traffic?bbox=` around the route, and when a blocking incident
   (`accident` / `laneClosure` / heavy `congestion`) intersects the path
   ahead, requests a Valhalla reroute with `exclude_polygons` around it.
   Cheap, no Valhalla changes.
2. **Later (native traffic-aware costing):** translate `congestion` into
   per-edge live speeds and write Valhalla **live-traffic tiles**
   (`traffic.tar`), so Valhalla's own costing avoids jams and ETAs reflect
   them. Harder (event→edge map-matching) but the proper solution.

## Deploy (planned)

New Ansible role `traffic` mirroring `roles/valhalla`: this `Dockerfile` on
loopback `:80xx`, an nginx vhost `traffic.appmire.be` (TLS + `?key=` gate +
rate limit + CORS, copy `valhalla.conf.j2`). Build/serve is light (one
~1 MB XML/min, in-memory), so no resource caps needed.
