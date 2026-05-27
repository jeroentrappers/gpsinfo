# GPSinfo — Privacy Policy

_Last updated: 2026-05-21._

GPSinfo is a Belgian Android app published by **Appmire CommV** (BE 0719.812.728).
It surfaces live GNSS, sensor and astronomical data on a single dashboard, with
optional GPS trail recording, an offline-capable trail map, Bluetooth heart-
rate monitor support, and a guided-run Sports view with pace coaching.

## Short version

- Everything runs **on your device**. We never see your data.
- We collect, store, transmit and share **no personal data of our own**.
- We do not use any analytics, advertising, crash-reporting, social-media or
  tracker SDK.
- The only network calls the app ever makes are anonymous map-tile fetches from
  **OpenStreetMap.org**, and only when you actively open or pan a trail map.
  Those requests carry your IP address to OpenStreetMap and nothing else — see
  ["Map tiles"](#map-tiles) below.
- Bluetooth heart-rate data and on-device text-to-speech for coaching cues
  stay on the device — there is no cloud sync and no audio leaves the phone.
- We do not depend on Google Play Services and do not contact any Google
  server. Donations are external links (Liberapay, GitHub Sponsors, PayPal)
  opened in your browser — no in-app purchase, no Play Billing.

## Permissions we request

| Permission | When | Why |
|---|---|---|
| `ACCESS_FINE_LOCATION` | Always required | Read GNSS fixes (latitude, longitude, altitude, per-satellite signal strength) directly from your device's GPS chip. Every metric the dashboard shows derives from this. |
| `ACCESS_COARSE_LOCATION` | Granted alongside fine | Required by the Android OS together with fine location on Android 10+. We do not request it separately. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Granted at install | Permits a notification-backed service that keeps GNSS streaming while a trail is being recorded. The service exists only between Start and Stop on the record button. Because the service is `foregroundServiceType="location"`, Android grants it foreground-app status for location-access purposes — so we do **not** request `ACCESS_BACKGROUND_LOCATION` separately. |
| `POST_NOTIFICATIONS` | Optional, prompted at recording start | Show the ongoing "Recording trail" notification. Recording works without it; you just won't see the notification on Android 13+. |
| `ACTIVITY_RECOGNITION` | Optional, prompted at recording start | Read step counts from the device's pedometer sensor (Android 10+) so the recording can capture steps and derive cadence + stride. Recording works without it; you just won't see step / cadence data. |
| `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT` (Android 12+) | Optional, prompted when opening the heart-rate pairing screen | Discover and connect to a Bluetooth Low Energy heart-rate monitor that advertises the standard Heart Rate Service (0x180D). The `neverForLocation` flag tells Android we do not derive location from advertisements. |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` (Android ≤11 only) | Legacy | Pre-Android-12 BLE permission model. Capped at `maxSdkVersion="30"`; never declared on newer Android. |
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

## Heart-rate monitor

When you pair a Bluetooth heart-rate monitor on the pairing screen,
GPSinfo opens a **direct GATT connection** from your phone to the
device using the Bluetooth-SIG standard Heart Rate Service (UUID
`0x180D`) and Heart Rate Measurement characteristic (`0x2A37`). No
vendor SDKs, no cloud bridge, no API key. The device's MAC address
and friendly name are stored in app preferences (see below) so the
app can auto-reconnect on next launch. BPM samples are kept in process
memory and, while a trail recording is active, attached to the
trackpoints written to GPX (Garmin `gpxtpx:hr` extension). The
samples never leave the device.

You can tap the disconnect icon on the heart-rate card to drop the
live link without forgetting the device, or "Forget" on the pairing
screen to clear the stored MAC entirely.

## Audible coaching cues

When you enable "Audible cues" in Settings, the Sports view announces
pace deviation and zone changes via the **device's on-board Text-to-
Speech engine**. The cues are generated locally — no audio is uploaded
or downloaded for them. The TTS engine renders with the
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` audio attribute, which lets the
operating system duck any music app you have running rather than
interrupting it.

The feature is off by default.

## Files we store on your device

| File | Location | Purpose |
|---|---|---|
| User preferences | `/data/data/be.appmire.gpsinfo/files/datastore/gpsinfo_prefs.preferences_pb` | UI theme, units, the auto-adapted speed-dial ceiling, "onboarding seen" flag, paired HR monitor's MAC address + friendly name, HR-zone configuration, audible-cues on/off. |
| Recorded trails | `/data/data/be.appmire.gpsinfo/files/trails/<id>.gpx` | One GPX 1.1 file per recording. May include per-point heart rate (`gpxtpx:hr`) and a trail-level or per-segment target pace (`gpsinfo:target_pace_s_per_km`) when those were set. |
| Map tile cache | `/data/data/be.appmire.gpsinfo/files/cache/osmdroid/tiles/` | OpenStreetMap tiles you have viewed, kept so the trail map works offline. Cleared whenever you uninstall the app. |

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
- A `User-Agent` header set to the package name `be.appmire.gpsinfo`.

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

## Donations

The app contains **no in-app purchase** and uses **no Play Billing**.
The About screen offers three external donation links — Liberapay,
GitHub Sponsors, and PayPal. Tapping any of them opens the link in
your default browser; from that point on, the donation is governed
by that platform's terms and privacy policy, not ours. We never see
who donates from inside the app.

No donation gates any feature — the app behaves identically whether
you donate or not.

## Contact

Questions, requests, or anything else:

- **privacy@appmire.be**
- Or the postal address on https://appmire.be
