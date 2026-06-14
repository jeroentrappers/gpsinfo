# Self-hosted vector tiles (PMTiles) for GPSinfo

`tiles.appmire.be` on the shared **appmire-hetz1** box. MapLibre Native reads
the `.pmtiles` file directly over HTTP Range, so the host **nginx** serves the
static assets directly and proxies only `/extract` to a small container:

```
/opt/gpsinfo-tiles/
  data/           # served by nginx (root)
    planet.pmtiles            # map data (OpenMapTiles schema), built by Planetiler
    styles/{liberty,dark}.json  # OpenFreeMap styles, repointed at our host (+?key=)
    fonts/<stack>/<range>.pbf   # glyphs, mirrored from OpenFreeMap
    sprites/ofm_f384/ofm*       # sprite sheet, mirrored
  cache/          # /extract region-cut cache
  extract/        # the Go extractor (built into a container)
  docker-compose.yml
```

The `ne2_shaded` low-zoom raster relief is left pointing at OpenFreeMap
(tiny, cosmetic, keyless). Access is gated by a `?key=` query param checked in
nginx (coarse — the key ships in the app; abuse is bounded by `limit_req`).

## Deploy (Ansible — `deploy/ansible/`, like the other appmire workloads)

```bash
cd deploy/ansible
ansible-playbook site.yml --tags app      # dirs, styles, fonts, sprites, extract container
ansible-playbook site.yml --tags nginx    # vhost + certbot TLS for tiles.appmire.be
ansible-playbook site.yml --tags build    # launch the throttled planet build (detached, hours)
```

- The key lives in the gitignored `deploy/ansible/secrets.yml` (this repo is
  public) and in the app's `keystore.properties`.
- The planet build is throttled (`docker --cpus` + low blk-IO + mmap storage +
  capped heap) and detached as a `systemd-run` unit, with a disk-headroom guard
  so it can't fill `/` under the co-hosted live sites. Watch:
  `journalctl -u gpsinfo-planet-build -f`.
- Everything except `planet.pmtiles` comes up immediately, so the endpoint is
  live (styles/fonts/sprites/extract) while the planet bakes.

## Point the app at it

In `keystore.properties` (gitignored), then rebuild:

```properties
tilesBaseUrl=https://tiles.appmire.be
tilesApiKey=<the key from secrets.yml>
```

## Offline (Phase 2)

The app downloads regional `.pmtiles` from `/extract` (`extract/main.go`) and
renders them from a `file://` source. The download/store half is in
`OfflineMapRepository`; the render wiring + bundled offline glyphs/sprites are
still TODO — see the project notes.
