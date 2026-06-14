#!/usr/bin/env bash
# Mirror glyph PBFs for the given font stacks from an OpenFreeMap-style server
# into DEST/<stack>/<range>.pbf. Idempotent (skips existing). 404 ranges are
# skipped silently — font stacks are sparse across the Unicode range space.
#
#   mirror-fonts.sh <base_url> <dest_dir> "Noto Sans Regular" "Noto Sans Bold" ...
set -euo pipefail
BASE="${1%/}"; DEST="$2"; shift 2

enc() { python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1"; }

fetched=0
for stack in "$@"; do
  mkdir -p "$DEST/$stack"
  estack="$(enc "$stack")"
  for r0 in $(seq 0 256 65535); do
    rng="${r0}-$((r0 + 255))"
    out="$DEST/$stack/$rng.pbf"
    [ -s "$out" ] && continue
    if curl -fsS --max-time 30 -o "$out.tmp" "$BASE/fonts/$estack/$rng.pbf" 2>/dev/null; then
      mv "$out.tmp" "$out"; fetched=$((fetched + 1))
    else
      rm -f "$out.tmp"
    fi
  done
done
echo "mirrored $fetched glyph range(s) into $DEST"
