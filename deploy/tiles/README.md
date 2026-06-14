# Self-hosted vector tiles (PMTiles) for GPSinfo

Replaces the dependency on `tiles.openfreemap.org` with our own server on
`appmire-hetz1`. MapLibre Native (≥ 11.7) reads a `.pmtiles` file directly
over HTTP **Range** requests, so the server is **pure static hosting** — no
tile-server process, just Caddy serving four things:

```
/srv/tiles/
  planet.pmtiles            # the map data (OpenMapTiles schema)
  styles/liberty.json       # repointed OpenFreeMap styles
  styles/dark.json
  fonts/{fontstack}/{range}.pbf   # glyphs
  sprites/sprite{,@2x}.{json,png} # sprite sheet
```

The phone live map and the car snapshotter render from these; offline
region/corridor downloads **still use OpenFreeMap for now** (MapLibre's
offline downloader can't read a `pmtiles://` source — see Phase 2 below).

---

## 1. Build the tiles (PMTiles, OpenMapTiles schema)

Use **Planetiler** — its default profile emits the OpenMapTiles schema the
Liberty/dark styles expect. Run on the box (needs Java 21+, SSD, and RAM
roughly = 1.5× the region's `.osm.pbf`; the full planet wants a big machine,
a single country is trivial).

```bash
# one country to start (fast, ~hundreds of MB):
java -Xmx8g -jar planetiler.jar --download --area=belgium --output=planet.pmtiles

# the whole planet (hours, ~100–130 GB output, lots of RAM):
java -Xmx100g -jar planetiler.jar --download --area=planet --output=planet.pmtiles
```

Output `planet.pmtiles` is a single immutable file. Refresh monthly by
rebuilding and atomically swapping it (`mv new.pmtiles planet.pmtiles`).

## 2. Glyphs + sprites (one-time asset mirror)

The styles reference fonts and a sprite sheet that also need self-hosting:

```bash
# Glyphs — prebuilt OpenMapTiles font stacks:
git clone https://github.com/openmaptiles/fonts && cd fonts
npm install && node ./generate.js           # writes ./_output/{fontstack}/{range}.pbf
cp -r _output /srv/tiles/fonts

# Sprites — lift the Liberty sprite (4 files) and serve under /sprites:
for f in sprite.json sprite.png sprite@2x.json sprite@2x.png; do
  curl -fsSL "https://tiles.openfreemap.org/sprites/ofm_f384/$f" -o "/srv/tiles/sprites/$f"
done
```

> Check the exact sprite path in the live style first:
> `curl -s https://tiles.openfreemap.org/styles/liberty | jq .sprite`

## 3. Repoint the styles

```bash
./repoint-style.sh https://tiles.appmire.be "$SHARED_KEY"
cp styles/*.json /srv/tiles/styles/
```

This rewrites the vector source to `pmtiles://…/planet.pmtiles`, and the
`glyphs` / `sprite` URLs to our host, all carrying `?key=`.

## 4. Serve it

Edit `Caddyfile` — set the hostname and `REPLACE_WITH_SHARED_KEY` — then:

```bash
xcaddy build --with github.com/mholt/caddy-ratelimit   # rate-limit module
./caddy run --config Caddyfile
```

Caddy handles TLS automatically and serves Range requests for the PMTiles.

## 5. Point the app at it

Set these in `keystore.properties` (the local secrets file) or via env, then
build — the live map cuts over; with them empty the app stays on OpenFreeMap:

```properties
tilesBaseUrl=https://tiles.appmire.be
tilesApiKey=SHARED_KEY
```

(`MapLibreStyle` builds the style URLs from these; the key rides as `?key=`.)

## 6. Verify

```bash
curl -I "https://tiles.appmire.be/styles/liberty.json?key=$SHARED_KEY"   # 200
curl -I "https://tiles.appmire.be/styles/liberty.json"                   # 403
curl -sI -H 'Range: bytes=0-99' \
     "https://tiles.appmire.be/planet.pmtiles?key=$SHARED_KEY" | grep -i 206
```

---

## Phase 2 — offline on PMTiles (not done yet)

MapLibre's `OfflineManager` **cannot** pre-download a `pmtiles://` source, so
the region + corridor downloaders (`OfflineMapRepository`,
`NavigationController`) still target OpenFreeMap via
`MapLibreStyle.OFFLINE_DOWNLOAD`. To fully drop the OpenFreeMap dependency,
offline must move to downloaded `.pmtiles` region files referenced with a
`file://` source. That needs either:

- a **server-side `pmtiles extract`** endpoint (cut an arbitrary bbox subset
  on demand — small dynamic service, no longer pure-static), or
- **pre-cut country `.pmtiles`** the user picks from (static, but no
  arbitrary corridors).

Decide which before building Phase 2.
