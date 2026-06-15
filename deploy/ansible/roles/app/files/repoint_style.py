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

# OpenFreeMap 403s the default Python-urllib UA — present a normal one.
req = urllib.request.Request(ofm_url, headers={"User-Agent": "Mozilla/5.0 (gpsinfo-tiles)"})
with urllib.request.urlopen(req, timeout=30) as r:
    style = json.load(r)

for name, src in style.get("sources", {}).items():
    if src.get("type") == "vector":
        style["sources"][name] = {
            "type": "vector",
            "url": f"pmtiles://{base}/planet.pmtiles{q}",
        }

style["glyphs"] = f"{base}/fonts/{{fontstack}}/{{range}}.pbf{q}"
style["sprite"] = f"{base}/sprites/ofm_f384/ofm{q}"


# OpenFreeMap's dark style is near-black (bg rgb(12,12,12)) — too dark to read.
# Bake in the readable "Waze-style" dark palette (same colours/targeting the
# app's tuneDarkStyle used) so every client renders the nicer dark, not just
# the phone. Flat colours (matches what tuneDarkStyle set).
NIGHT = {
    "bg": "#28313D", "land": "#28313D", "green": "#2E3C34", "water": "#1C2836",
    "building": "#333D4B", "road": "#5E6E84", "casing": "#3C4858",
    "text": "#DCE3ED", "halo": "#1A2230",
}


def _is_road(i):
    return (i.startswith("highway") or i.startswith("road") or "transportation" in i
            or i.startswith("bridge") or i.startswith("tunnel") or i.startswith("street"))


def recolor_dark(style):
    for layer in style.get("layers", []):
        i = layer.get("id", "").lower()
        t = layer.get("type")
        paint = layer.setdefault("paint", {})
        if t == "background":
            paint["background-color"] = NIGHT["bg"]
            paint.pop("background-pattern", None)
        elif t == "symbol":
            if "text-field" in layer.get("layout", {}):
                paint["text-color"] = NIGHT["text"]
                paint["text-halo-color"] = NIGHT["halo"]
                paint["text-halo-width"] = 1.2
        elif t == "line":
            if _is_road(i):
                casing = "casing" in i or "outline" in i
                paint["line-color"] = NIGHT["casing"] if casing else NIGHT["road"]
        elif t == "fill":
            if "water" in i:
                c = NIGHT["water"]
            elif any(w in i for w in ("park", "wood", "forest", "grass", "golf",
                                      "cemetery", "vegetation", "scrub")):
                c = NIGHT["green"]
            elif "building" in i:
                c = NIGHT["building"]
            else:
                c = NIGHT["land"]
            paint["fill-color"] = c


if out.endswith("dark.json"):
    recolor_dark(style)

with open(out, "w") as f:
    json.dump(style, f, separators=(",", ":"))
print(f"wrote {out}")
