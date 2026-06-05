#!/usr/bin/env python3
"""Replay a GPX track into a running Android emulator as NMEA sentences.

Why NMEA and not `geo fix`: the emulator's `geo fix` injects position
only — Location.hasSpeed()/hasBearing() stay false, so speed-adaptive
zoom, the heading-up map rotation and the rally delta never engage.
$GPRMC sentences carry ground speed (knots) and course, which the
emulator's GNSS HAL passes through to LocationManager fixes.

Usage:
    scripts/replay-gpx.py route.gpx [--serial emulator-5556] [--rate 1.0]
                                    [--speed-kmh 50]

  --rate N        playback time multiplier (2 = twice as fast)
  --speed-kmh V   override speed when the GPX has no timestamps
                  (points are then assumed equidistant at V km/h)

Stop with Ctrl-C. Loops the track until stopped.
"""

import argparse
import math
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

GPX_NS = "{http://www.topografix.com/GPX/1/1}"
GPX10_NS = "{http://www.topografix.com/GPX/1/0}"


def parse_gpx(path):
    """Returns [(lat, lon, ele, epoch_or_None), ...] from all trksegs."""
    root = ET.parse(path).getroot()
    ns = GPX_NS if root.tag.startswith(GPX_NS[:-4]) else GPX10_NS
    points = []
    for trkpt in root.iter(f"{ns}trkpt"):
        lat = float(trkpt.attrib["lat"])
        lon = float(trkpt.attrib["lon"])
        ele_el = trkpt.find(f"{ns}ele")
        ele = float(ele_el.text) if ele_el is not None else 0.0
        time_el = trkpt.find(f"{ns}time")
        epoch = None
        if time_el is not None:
            txt = time_el.text.strip().replace("Z", "+00:00")
            epoch = datetime.fromisoformat(txt).timestamp()
        points.append((lat, lon, ele, epoch))
    return points


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def bearing_deg(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def nmea_lat(lat):
    hemi = "N" if lat >= 0 else "S"
    lat = abs(lat)
    deg = int(lat)
    return f"{deg:02d}{(lat - deg) * 60:07.4f}", hemi


def nmea_lon(lon):
    hemi = "E" if lon >= 0 else "W"
    lon = abs(lon)
    deg = int(lon)
    return f"{deg:03d}{(lon - deg) * 60:07.4f}", hemi


def checksum(body):
    c = 0
    for ch in body:
        c ^= ord(ch)
    return f"{c:02X}"


def rmc(lat, lon, speed_kmh, course):
    now = datetime.now(timezone.utc)
    la, la_h = nmea_lat(lat)
    lo, lo_h = nmea_lon(lon)
    knots = speed_kmh / 1.852
    body = (
        f"GPRMC,{now:%H%M%S}.00,A,{la},{la_h},{lo},{lo_h},"
        f"{knots:.1f},{course:.1f},{now:%d%m%y},,,A"
    )
    return f"${body}*{checksum(body)}"


def gga(lat, lon, ele):
    now = datetime.now(timezone.utc)
    la, la_h = nmea_lat(lat)
    lo, lo_h = nmea_lon(lon)
    body = (
        f"GPGGA,{now:%H%M%S}.00,{la},{la_h},{lo},{lo_h},"
        f"1,08,1.0,{ele:.1f},M,0.0,M,,"
    )
    return f"${body}*{checksum(body)}"


def send_nmea(serial, sentence):
    subprocess.run(
        ["adb", "-s", serial, "emu", "geo", "nmea", sentence],
        check=False,
        capture_output=True,
    )


def send_fix(serial, lat, lon, ele):
    # geo fix takes LONGITUDE first. Modern emulator images reject
    # `geo nmea` outright, so this is the default path; the app derives
    # speed/bearing from successive fixes (CarMapRenderer
    # withDerivedMotion) since `geo fix` carries position only.
    subprocess.run(
        ["adb", "-s", serial, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", f"{ele:.1f}"],
        check=False,
        capture_output=True,
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("gpx")
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--rate", type=float, default=1.0)
    ap.add_argument("--speed-kmh", type=float, default=50.0)
    ap.add_argument(
        "--method",
        choices=["fix", "nmea"],
        default="fix",
        help="fix = geo fix (works everywhere, position only); "
        "nmea = $GPRMC with speed/course (older images only)",
    )
    args = ap.parse_args()

    points = parse_gpx(args.gpx)
    if len(points) < 2:
        sys.exit("GPX has fewer than 2 track points")
    has_times = all(p[3] is not None for p in points)
    print(f"{len(points)} points · timestamps: {'yes' if has_times else 'no'} "
          f"· rate ×{args.rate} · device {args.serial}")
    print("Ctrl-C to stop (loops forever)")

    lap = 0
    while True:
        lap += 1
        for i in range(1, len(points)):
            lat0, lon0, _, t0 = points[i - 1]
            lat1, lon1, ele1, t1 = points[i]
            dist = haversine_m(lat0, lon0, lat1, lon1)
            if has_times and t1 > t0:
                dt = (t1 - t0) / args.rate
                speed_kmh = dist / (t1 - t0) * 3.6
            else:
                speed_kmh = args.speed_kmh
                dt = (dist / (speed_kmh / 3.6)) / args.rate if speed_kmh > 0 else 1.0
            course = bearing_deg(lat0, lon0, lat1, lon1)
            if args.method == "nmea":
                send_nmea(args.serial, gga(lat1, lon1, ele1))
                send_nmea(args.serial, rmc(lat1, lon1, speed_kmh, course))
            else:
                send_fix(args.serial, lat1, lon1, ele1)
            print(f"\rlap {lap}  pt {i}/{len(points) - 1}  "
                  f"{speed_kmh:5.1f} km/h  {course:5.1f}°", end="")
            time.sleep(max(dt, 0.2))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nstopped")
