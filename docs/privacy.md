# GPSinfo — Privacy Policy

_Last updated: see git log of this file._

GPSinfo is a Belgian Android app published by **Appmire CommV** (BE 0719.812.728).
It surfaces live GNSS, sensor and astronomical data on a single dashboard.

## Short version

- Everything runs **on your device**. Nothing is sent anywhere.
- We collect, store, transmit and share **no personal data**.
- The app has **no network access** — the `android.permission.INTERNET` permission is not declared in the manifest, so the Android system refuses any network call the app code could ever attempt.

## Permissions we request

| Permission | Why |
|---|---|
| `android.permission.ACCESS_FINE_LOCATION` | To read GNSS fixes (latitude, longitude, altitude, satellite list, signal strength) directly from your device's GPS chip. Used for every metric the app displays. |
| `android.permission.ACCESS_COARSE_LOCATION` | Granted by Android automatically alongside `ACCESS_FINE_LOCATION` on Android 10+. We do not request it separately. |

We do **not** request:

- `INTERNET` — there is no network code.
- `ACCESS_BACKGROUND_LOCATION` — the app never reads your location when it is not in the foreground.
- `FOREGROUND_SERVICE` — no background service.
- `READ_*` / `WRITE_EXTERNAL_STORAGE` — we don't touch your photos, downloads or media.
- `RECORD_AUDIO`, `CAMERA`, `READ_CONTACTS`, … — these are simply not needed.

## What happens with your location

When you grant `ACCESS_FINE_LOCATION`:

1. The Android `LocationManager` API reads the GPS chip on your device.
2. The reading is delivered to GPSinfo by the operating system.
3. GPSinfo computes a few derived values (fix type, magnetic declination, sun position, day length) **in process memory**.
4. The reading is **displayed on screen**.
5. When the app closes, the reading is **forgotten**.

The only persisted state on disk is your preferences (UI theme, preferred units, the auto-adapted speed-dial ceiling). These are stored in a private DataStore file inside the app's sandbox at:

```
/data/data/com.appmire.gpsinfo/files/datastore/gpsinfo_prefs.preferences_pb
```

Even this small file is **excluded from Android's "App data" cloud backup and device transfer** unless you explicitly opt in via the system Backup settings — see [`backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml) and [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml) in the source tree.

## Sharing your location with other apps

When you tap **Share location** or **Open in Maps**, GPSinfo hands your current coordinates to an Android Intent of your choosing (your messaging app, your map app, etc.). At that point the data leaves GPSinfo and lands in whichever app you picked. **The behaviour of that other app is governed by its own privacy policy, not ours.**

## Analytics, ads, crash reporters, tracking SDKs

None. The app contains:

- No Firebase
- No Google Analytics
- No Crashlytics / Sentry / Bugsnag
- No advertising SDK
- No social-media SDK
- No third-party tracker of any kind

You can verify this by inspecting the dependency tree:

```sh
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

## Children

The app does not collect data of any kind. It is therefore safe for all ages with respect to data collection.

## Changes

If we ever start collecting any data, this file will change and a copy of the previous version will remain in git history at `docs/privacy.md`. The "Last updated" date at the top reflects the most recent edit.

## Contact

Questions, requests, or anything else: **privacy@appmire.be** or the postal address on https://appmire.be.
