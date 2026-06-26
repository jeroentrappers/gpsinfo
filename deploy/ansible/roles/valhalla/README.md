# Valhalla routing service (valhalla.appmire.be)

Server-side [Valhalla](https://github.com/valhalla/valhalla) on the shared
appmire-hetz1 box, deployed like the tile server. The app calls it **online**
for route profiles (fastest / shortest / economic), alternatives, lane
guidance and fast (re)routing; **BRouter stays the offline fallback**. See
`docs/design/nav-engine-v2.md`.

No native build — the official `gis-ops/docker-valhalla` image builds its
routing tiles from the configured OSM extracts (`valhalla_pbf_urls` in
`group_vars/all.yml`) on first run, caches them under
`/opt/gpsinfo-valhalla/custom_files`, and serves the HTTP API on loopback
`:8002`. The nginx role fronts it with TLS + the `?key=` gate at
`https://valhalla.appmire.be`.

## Deploy

```
cd deploy/ansible
# DNS: point valhalla.appmire.be at appmire-hetz1 first.
ansible-playbook site.yml --tags valhalla   # container + tile build (first run = minutes for Benelux)
ansible-playbook site.yml --tags nginx      # vhost + certbot TLS
```

First deploy: the tile build can outlast the readiness wait — the container
keeps building; re-run `--tags valhalla` to confirm `/status` is 200.

## Test

```
# health
curl 'https://valhalla.appmire.be/status?key=YOURKEY'
# a route (fastest = costing auto)
curl 'https://valhalla.appmire.be/route?key=YOURKEY' -d '{
  "locations":[{"lat":51.21,"lon":3.22},{"lat":51.13,"lon":4.39}],
  "costing":"auto","alternates":2,
  "directions_options":{"units":"kilometers"}
}'
```

## Coverage

`valhalla_pbf_urls` defaults to Benelux (Belgium + Netherlands + Luxembourg).
Add `europe-latest.osm.pbf` for wider coverage — much larger build + RAM; do
it deliberately. Changing the list and re-running with `force_rebuild: True`
(env in the compose) rebuilds the tiles.

## Profiles → costing

The app maps `RouteProfile` to Valhalla costing:
- **FASTEST** → `auto`
- **SHORTEST** → `auto` with `shortest: true`
- **ECONOMIC** → `auto` with an eco-leaning cost config (use_highways/ferry
  penalties), TBD when the client lands.
