# GPSinfo — Privacy Policy

_Last updated: 2026-05-19._

GPSinfo is a Belgian Android app published by **Appmire CommV** (BE 0719.812.728).
It surfaces live GNSS, sensor and astronomical data on a single dashboard, with
optional GPS trail recording and an offline-capable trail map.

## Short version

- Everything runs **on your device**. We never see your data.
- We collect, store, transmit and share **no personal data of our own**.
- We do not use any analytics, advertising, crash-reporting, social-media or
  tracker SDK.
- The only network calls the app ever makes are anonymous map-tile fetches from
  **OpenStreetMap.org**, and only when you actively open or pan a trail map.
  Those requests carry your IP address to OpenStreetMap and nothing else — see
  ["Map tiles"](#map-tiles) below.
- We do not depend on Google Play Services and do not contact any Google
  server.

## Permissions we request

| Permission | When | Why |
|---|---|---|
| `ACCESS_FINE_LOCATION` | Always required | Read GNSS fixes (latitude, longitude, altitude, per-satellite signal strength) directly from your device's GPS chip. Every metric the dashboard shows derives from this. |
| `ACCESS_COARSE_LOCATION` | Granted alongside fine | Required by the Android OS together with fine location on Android 10+. We do not request it separately. |
| `ACCESS_BACKGROUND_LOCATION` | Optional, prompted at recording start | Keep recording a GPS trail when the phone is locked or you switch to another app. **Only used while a trail recording is in progress.** Never used to read your location passively. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Granted at install | Permits a notification-backed service that keeps GNSS streaming while a trail is being recorded. The service exists only between Start and Stop on the record button. |
| `POST_NOTIFICATIONS` | Optional, prompted at recording start | Show the ongoing "Recording trail" notification. Recording works without it; you just won't see the notification on Android 13+. |
| `INTERNET` | Always required | Fetch OpenStreetMap tiles for the trail map. The rest of the app does not use the network. |
| `ACCESS_NETWORK_STATE` | Required by the tile library | Lets the tile cache decide whether to attempt a download or fall back to cached tiles. |
| `WRITE_EXTERNAL_STORAGE` (Android ≤9 only) | Legacy | Required by the OpenStreetMap-tile library to write its on-disk cache on Android 9 and below. Capped at `maxSdkVersion="28"`; never declared on Android 10+. |

We do **not** request:

- `READ_*` / `WRITE_*_STORAGE` on Android 10+
- `RECORD_AUDIO`, `CAMERA`, `READ_CONTACTS`, `READ_CALL_LOG`, `READ_SMS`, …
- Any of the AD_ID / advertising / tracking permissions.
- Any system-private permission requiring signature protection.

## What happens with your location

While the app is open:

1. The Android `LocationManager` API reads the GPS chip on your device.
2. Each reading is delivered to GPSinfo by the operating system.
3. GPSinfo computes derived values (fix type, magnetic declination, sun
   position, day length, compass heading) **in process memory**.
4. The reading is **displayed on screen**.
5. When the screen closes, the in-memory reading is **forgotten**.

While a trail recording is active:

1. As above, plus each accepted fix is appended to an **in-memory list** of
   trackpoints belonging to the active recording.
2. When you stop the recording and provide a name, the trackpoints are
   written to disk as a single **GPX 1.1 file** inside the app's private
   storage (see below).
3. The file is never transmitted anywhere by the app. You can share, export,
   or delete it from inside the app at any time.

The single exception to step 5 (above) is the on-disk location cache the
Android system itself maintains for `getLastKnownLocation()`. That cache is
owned by the OS, not by GPSinfo, and is documented under your phone's privacy
settings.

## Files we store on your device

| File | Location | Purpose |
|---|---|---|
| User preferences | `/data/data/com.appmire.gpsinfo/files/datastore/gpsinfo_prefs.preferences_pb` | UI theme, units, the auto-adapted speed-dial ceiling, "onboarding seen" flag. |
| Recorded trails | `/data/data/com.appmire.gpsinfo/files/trails/<id>.gpx` | One GPX file per recording, in the standard GPX 1.1 format. |
| Map tile cache | `/data/data/com.appmire.gpsinfo/files/osmdroid/tiles/` | OpenStreetMap tiles you have viewed, kept so the trail map works offline. Cleared whenever you uninstall the app. |

All three live in the app's private sandbox. No other app on the device can
read them. They are **excluded from Android's "App data" cloud backup and
device transfer** unless you explicitly opt in via the system Backup
settings — see [`backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml)
and [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml)
in the source tree.

## Sharing your data with other apps

You — and only you — control sharing. The app exposes three explicit hand-off
actions:

- **Share location** (dashboard) → hands your current coordinates to an
  Android `ACTION_SEND` intent of your choosing (messaging app, email, etc.).
- **Open in maps** (dashboard) → opens your current coordinates in the map app
  you choose from the system chooser.
- **Share trail** (trail map) → hands the recorded GPX file to an
  `ACTION_SEND` intent of your choosing. The receiving app gets read-only
  access to the file for the duration of that single intent — no broader
  filesystem access is granted.
- **Save GPX file** (trail map) → writes a copy of the trail to a location
  you pick via the Android system file picker (Storage Access Framework).
  GPSinfo can only write to the URI you pick; it cannot enumerate your
  storage.
- **Import GPX** (trails list) → reads a GPX file you pick via the system
  file picker and stores a copy in app-private storage.

After any of these actions, the data lives in whichever app or location you
sent it to, and is governed by that app's privacy policy, not ours.

## Map tiles

The trail map renders [OpenStreetMap](https://www.openstreetmap.org) raster
tiles fetched anonymously over HTTPS from `tile.openstreetmap.org` via the
[osmdroid](https://github.com/osmdroid/osmdroid) library. Each request carries:

- The URL of the requested tile (zoom + tile coordinates — equivalent to "a
  map square at some location and zoom level"; does not identify *you*).
- Your IP address (visible to the OpenStreetMap server, as for any HTTPS
  request to any site).
- A `User-Agent` header set to the package name `com.appmire.gpsinfo`.

We do not see these requests. They go directly from your device to the
OpenStreetMap Foundation's servers. Their privacy policy is at
<https://wiki.osmfoundation.org/wiki/Privacy_Policy>.

Once a tile has been fetched it is cached on your device, so revisiting the
same area generates no further network traffic.

If you do not want any network traffic at all, do not open the trail map.
The rest of the app — dashboard, satellite list, compass, speed gauge, trail
recording — has no network code paths.

## Analytics, ads, crash reporters, tracking SDKs

None. The app contains:

- No Firebase / Google Analytics / Google Mobile Ads
- No Crashlytics / Sentry / Bugsnag / Datadog
- No advertising SDK of any kind
- No Facebook SDK or other social-media SDK
- No third-party tracker, attribution SDK, or A/B-testing SDK

You can verify this by inspecting the release dependency tree:

```sh
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

## Children

The app does not collect personal data of any kind. It is therefore safe for
all ages with respect to data collection. Location services should be used
under adult supervision.

## Changes

If we ever start collecting any data, this file will change and the previous
version will remain in git history at `docs/privacy.md`. The "Last updated"
date at the top reflects the most recent edit.

## Contact

Questions, requests, or anything else:

- **privacy@appmire.be**
- Or the postal address on https://appmire.be
