#!/usr/bin/env bash
# Derive our self-hosted styles from OpenFreeMap's open styles by repointing
# the three external URLs (vector source → our pmtiles, glyphs + sprite →
# our host). Output goes to ./styles/{liberty,dark}.json ready to serve.
#
# Usage:
#   ./repoint-style.sh https://tiles.appmire.be SHARED_KEY
#
# Requires: curl, jq.
set -euo pipefail

BASE="${1:?usage: repoint-style.sh <base-url> <key>}"   # e.g. https://tiles.appmire.be
KEY="${2:?usage: repoint-style.sh <base-url> <key>}"
BASE="${BASE%/}"
Q="?key=${KEY}"

mkdir -p styles
for name in liberty dark; do
  echo "→ ${name}"
  curl -fsSL "https://tiles.openfreemap.org/styles/${name}" \
  | jq \
      --arg src "pmtiles://${BASE}/planet.pmtiles${Q}" \
      --arg glyphs "${BASE}/fonts/{fontstack}/{range}.pbf${Q}" \
      --arg sprite "${BASE}/sprites/sprite${Q}" \
      '
      # Repoint the OpenMapTiles vector source to our PMTiles file. The
      # source key is "openmaptiles" in OpenFreeMap styles; rewrite whichever
      # vector source carries a url/tiles so this survives renames.
      (.sources |= with_entries(
         if (.value.type == "vector")
         then .value = {type:"vector", url:$src}
         else . end))
      | .glyphs = $glyphs
      | .sprite = $sprite
      ' > "styles/${name}.json"
done

echo
echo "Wrote styles/liberty.json and styles/dark.json."
echo "Deploy them to /srv/tiles/styles/ alongside planet.pmtiles, /fonts, /sprites."
