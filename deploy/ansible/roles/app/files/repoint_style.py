#!/usr/bin/env python3
"""Derive a self-hosted style from an OpenFreeMap style by repointing its
external URLs at our server (carrying the ?key= gate). The low-zoom raster
source (ne2_shaded) is left on OpenFreeMap — tiny, cosmetic, and keyless.

  repoint_style.py <ofm_style_url> <base_url> <key> <out_file>
"""
import json
import sys
import urllib.request

ofm_url, base, key, out = sys.argv[1], sys.argv[2].rstrip("/"), sys.argv[3], sys.argv[4]
q = f"?key={key}" if key else ""

with urllib.request.urlopen(ofm_url, timeout=30) as r:
    style = json.load(r)

for name, src in style.get("sources", {}).items():
    if src.get("type") == "vector":
        style["sources"][name] = {
            "type": "vector",
            "url": f"pmtiles://{base}/planet.pmtiles{q}",
        }

style["glyphs"] = f"{base}/fonts/{{fontstack}}/{{range}}.pbf{q}"
style["sprite"] = f"{base}/sprites/ofm_f384/ofm{q}"

with open(out, "w") as f:
    json.dump(style, f, separators=(",", ":"))
print(f"wrote {out}")
