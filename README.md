# GPSinfo

A bespoke, modern Android-native dashboard that surfaces the rawest GNSS,
sensor and astronomical data the device can give: per-satellite SNR, fix
status, position, speed, heading, magnetic declination, day/night world
view, sunrise/sunset, plus GPS trail recording with offline-capable map.

## Highlights

### Dashboard + raw GNSS

- **All-in-one dashboard** — fix quality, sky view, position, movement,
  compass, world map, time & sun, all on one scrollable screen. The
  classic 6-tab swipe layout is gone.
- **Raw GNSS via the platform** — `GnssStatus.Callback` for per-satellite
  PRN/azimuth/elevation/Cn0/used-in-fix. No Play Services, no Fused
  Location, no JNI.
- **Five coordinate formats** — DMS, Decimal, Plus Code, Maidenhead, MGRS.
  Tap-to-cycle on the position card; long-press copies the active format.
- **Tilt-corrected magnetic heading** from `TYPE_ROTATION_VECTOR` with
  `GeomagneticField` for true-north declination + inclination.
- **Custom magnetometer calibration screen** — live accuracy chip,
  orientation-coverage bar, three 2D scatter projections of the magnetic
  field cloud, hard-iron offset estimate, and a "move away from metal"
  warning when the field magnitude leaves the Earth-field band.
- **Solar Position Algorithm** (pure Kotlin) for sunrise/sunset/solar
  noon and a day/night terminator on the world map.

### Trails

- **Trail recording** — foreground-service backed, survives screen-off
  and app-backgrounding. GPX 1.1 export, RDP simplification with three
  presets, optional GPX import via SAF.
- **Trail map** — osmdroid + OpenStreetMap tiles for the trail-detail
  view only. The rest of the app stays offline; the world overview on
  the dashboard uses a custom Canvas + Natural Earth coastline.
- **GPX 1.1 with extensions** — Garmin `gpxtpx:speed/course/hr` for
  speed, course and per-point heart rate; private `gpsinfo:vacc` for
  vertical accuracy; private `gpsinfo:target_pace_s_per_km` for the
  trail-level and per-segment pace targets that drive scoring.

### Navigation + guided runs

- **Track-back navigation** — start from any trail's overflow menu;
  picks the nearest trail point to your current fix and walks you
  backwards through the route.
- **Bearing-to-waypoint** — tap a point on the map or paste a coord;
  live relative-bearing arrow + distance + ETA on the dashboard's
  navigation card.
- **BLE heart-rate monitor** — pair a standard BLE strap (Polar,
  Wahoo, Garmin chest belts); BPM card on the dashboard, zone colouring
  from a fully configurable HR-zones screen. Per-point HR is captured
  during recording and written to GPX via the Garmin extension.
- **Sports Dashboard** — full-screen "guided run" view: elapsed +
  distance header, live-vs-avg pace / speed / cadence / stride
  (gait derived from step counter + GNSS), pace-deviation gauge,
  HR-zone gauge with the configured zones, intensity profile of the
  next ~1.5 km of trail coloured by grade, ETA to next major climb.
- **Pace targets** — set an overall target pace or per-segment
  targets along the route (10-waypoint editor on the trail map's
  overflow menu). The Sports Dashboard and audible cues follow the
  effective per-segment target as you advance.
- **Audible coaching cues** — on-device TextToSpeech announces "Too
  fast — ease off" / "On target" / zone transitions. Uses
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` audio attributes so a music
  app in earbuds ducks for the cue, then resumes. Off by default,
  toggle in Settings.
- **Performance scoring** — completed trails with a target pace get a
  0–100 score badge on the trail map. Pace adherence + HR-zone
  adherence (50/50 when HR samples are present, pace-only without).

### Platform & polish

- **No Play Services**, **no telemetry**, **no API keys**, **no
  account ever**.
- **Light / dark mode**, system-following with a manual toggle.
- **Per-app language picker** — 12 locales (`en`, `nl`, `de`, `fr`,
  `es`, `it`, `pt-BR`, `ru`, `ja`, `cs`, `tr`, `pl`), each native-
  reviewed.
- **Share location** + **Open in Maps** via standard `geo:` intents.
- Source-available. Donations via Liberapay or GitHub Sponsors — see
  the About screen. The only network call the app makes is anonymous
  OSM tile fetching on the trail map screen — see `docs/privacy.md`.

## Build matrix

This is the toolchain the project is currently built and shipped with.
The combination is deliberately on the leading edge — bump deliberately,
never on autopilot. Cross-reference with `gradle/libs.versions.toml`.

| Component | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin or JetBrains) | Pinned via `gradle/gradle-daemon-jvm.properties` |
| Gradle | 9.5.1 | Wrapper-managed |
| AGP | 9.2.1 | `android.suppressUnsupportedCompileSdk=37,37.0` opts in to compileSdk 37 ahead of AGP's tested matrix |
| Kotlin | 2.1.20 | Compose Compiler plugin matches Kotlin version |
| Compose BOM | 2026.05.00 | All Compose artifacts resolve through this |
| compileSdk / targetSdk | 37 | See note above |
| minSdk | 24 | |
| osmdroid | 6.1.20 | Trail map only |
| baselineprofile plugin | 1.5.0-alpha06 | Alpha; produces an ART profile bundled at install |

## Building

Prerequisites:

- Android SDK (the Android Gradle Plugin will download platform 37 on
  first build). `local.properties` points at it.
- JDK 21. The Gradle daemon will auto-provision it via foojay if you
  let it; otherwise install Temurin 21 yourself.

```sh
./gradlew assembleDebug
```

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Checks run in CI

```sh
./gradlew testDebugUnitTest   # pure-JVM math + util tests
./gradlew lintDebug           # Android Lint; treats MissingTranslation as error
./gradlew assembleDebug       # full compile + R8 (release variant)
```

CI is in `.github/workflows/ci.yml` and runs all three on every push
to `main` and every PR.

## Installing on a device

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.appmire.gpsinfo/.MainActivity
```

Grant **Precise location** when prompted. Take the device outdoors for a
real GNSS lock — most emulators only stub a single Location and do not
fire the satellite-level callbacks.

## Reading the dashboard

| Section | What it shows |
|---|---|
| **Status bar** | Fix type (NO / 2D / 3D), horizontal accuracy. |
| **Navigation** (when a target is set) | Relative-bearing arrow, distance, bearing, ETA, and a pace-deviation row with the active per-segment target when navigating a route. Tap the goal area to set / edit the target pace. |
| **Heart rate** (when paired + connected) | BPM colour-coded by the configured zones, zone badge, time-in-zone strip, disconnect icon. |
| **Position** | Lat/Lon in the active format — tap to cycle DMS → Decimal → Plus Code → Maidenhead → MGRS. Altitude, H/V accuracy. Share + Open-in-Maps actions. Long-press copies the active format. |
| **Movement** | Speed (km/h, mph or kn) with live pace sub-line, magnetic heading + cardinal, altitude. Tap for full-screen retro analog speed gauge with HUD mirror mode. |
| **Sky view** | Radial polar plot of every satellite in azimuth/elevation, coloured by constellation; SNR bar per PRN. Tap for satellite list + NMEA-style readout. |
| **Compass** | Animated rose with current magnetic heading, declination °E/°W (WMM), inclination, magnetic accuracy chip, reciprocal heading. Tap for gimballed binnacle detail. |
| **World** | Equirectangular world map drawn on `Canvas`, with day/night terminator computed from the subsolar point, sun glyph at solar noon longitude, and a "you are here" marker. |
| **Time & Sun** | UTC + Local clock and date, sunrise/solar-noon/sunset, day-length, sun azimuth/elevation, day-phase. |

Above the cards, two banners surface actionable state when relevant:

- **Location off** — when the system Location toggle is disabled. Taps
  open the Location settings.
- **Compass calibration** — when the rotation-vector accuracy is LOW or
  UNRELIABLE. Prompts the figure-of-eight motion.

The bottom-right FAB starts/stops a **trail recording**. Top-bar icons
let you save the current fix as a one-point waypoint, open the trails
list, start a **bearing-to-waypoint** flow, jump to the **Sports view**
(only while recording), toggle the theme, and open **Settings & About**.

## Project layout

```
app/src/main/java/com/appmire/gpsinfo/
├── MainActivity.kt                  # nav graph + permission flow + theme
├── data/
│   ├── DataSources.kt               # interfaces every repo implements
│   ├── LocationRepository.kt        # LocationManager + GnssStatus
│   ├── SensorRepository.kt          # ROTATION_VECTOR + MAGNETIC_FIELD + WMM
│   ├── SettingsRepository.kt        # DataStore-backed prefs
│   ├── TestDataSourceOverride.kt    # test hook (production no-op)
│   ├── TrailRecorder.kt             # in-memory point capture + throttle + rolling gait
│   ├── TrailRecordingController.kt  # process-wide singleton
│   ├── TrailRecordingService.kt     # foreground service (location + steps + HR)
│   ├── TrailRepository.kt           # file-backed GPX persistence
│   ├── HeartRateRepository.kt       # BLE GATT, Heart Rate Service (0x180D)
│   ├── AudibleCueManager.kt         # on-device TTS with audio-focus ducking
│   ├── gpx/
│   │   ├── GpxIo.kt                 # GPX 1.1 reader + writer (incl. hr, target_pace)
│   │   └── TrailSimplifier.kt       # Ramer-Douglas-Peucker, 3D-aware
│   ├── calibration/
│   │   └── CalibrationEstimator.kt  # hard-iron offset + coverage bins
│   ├── sun/SunPositionCalculator.kt # NOAA SPA (pure Kotlin)
│   └── model/                       # immutable domain types
├── ui/
│   ├── theme/                       # Material 3 + cyan accent + mono font
│   ├── viewmodel/DashboardViewModel.kt
│   ├── dashboard/                   # main screen + permission screen + helpers
│   ├── components/                  # one Card per file, all Canvas-drawn
│   ├── about/                       # About + Settings sections + donations
│   ├── calibration/                 # magnetometer calibration screen + VM
│   ├── compass/                     # full-screen compass detail
│   ├── satellite/                   # satellite list + NMEA readout
│   ├── speed/                       # retro analog speed gauge
│   ├── trails/                      # list, detail map, charts, dialogs, pace-targets editor
│   ├── navigation/                  # destination picker + pace-target dialog
│   ├── heartrate/                   # HR pairing + zones editor
│   ├── sports/                      # Sports Dashboard + gauges + intensity profile
│   ├── nmea/                        # NMEA-style raw GNSS readout
│   └── onboarding/                  # first-run tour dialog
└── util/                            # coord fmt (5 systems), units, intents, naming,
                                     # bearing/distance math, route projection, scoring
baselineprofile/                     # AGP baseline-profile producer module
```

## Verification checklist

When testing on a real device outdoors:

1. Fix status transitions `NO_FIX → 2D → 3D` as satellites lock in.
2. Sky view dots match the SNR bars; PRN labels visible; used satellites
   filled, in-view-only ones outlined.
3. Compass rose rotates smoothly; declination becomes non-zero once a
   location is known.
4. World map cursor sits at your real position; the night curve sweeps
   across over a few minutes.
5. Sunrise/sunset times agree with an external source to within ≤ 1 minute.
6. Toggle the top-bar theme button — the dashboard flips between dark
   and light immediately.
7. Tap **Share** — chooser appears with coords + `geo:` URI.
8. Tap **Open in Maps** — your maps app opens at your current fix.
9. Start a **trail recording**, walk for a minute, hit Stop, save it.
   Open it from the Trails list — the polyline matches your walk and
   the elevation chart looks right.

## Tooling

- **Fastlane** drives unit tests, signed-AAB builds, Play Console
  uploads and screenshot capture. See `fastlane/Fastfile`.
- `build-production.sh.example` is the canonical local-publish pipeline.
  Copy to `build-production.sh` (gitignored), fill in secrets, run it.
- Release signing reads from `keystore.properties` (gitignored) or
  `FASTLANE_GPSINFO_*` env vars — see `keystore.properties.example`.
- Screenshots: `bundle exec fastlane screenshots` with a running emulator.
  Uses fake data sources injected via `TestDataSourceOverride`; the test
  drives the UI by `testTagsAsResourceId` so it survives locale flips.
- Baseline profile: `./gradlew :app:generateBaselineProfile` with a
  connected device or emulator (API 28+).

## Donations

The app is free, source-available, and contains no IAP. If you'd like
to support continued development:

- [Liberapay](https://liberapay.com/jeroentrappers) — recurring,
  FOSS-friendly, lowest fees.
- [GitHub Sponsors](https://github.com/sponsors/jeroentrappers) — one-
  shot or monthly, matches the source-available framing.
- [PayPal](https://paypal.me/jeroentrappers) — one-shot.

Deliberately no Play Billing — keeps the "no Play Services" stance
intact. None of the donation links gate any feature; the app works
identically without donating.

## Licence

GPSinfo is **dual-licensed**:

- **Open-source path** — [GNU AGPL-3.0](LICENSE). You may use, study,
  modify and redistribute the code freely, subject to the AGPL's
  copyleft terms (any derivative work, including any network service
  that exposes the software's functionality to users, must itself be
  AGPL-licensed and provide source).
- **Commercial path** — for closed-source embedding, white-label forks,
  proprietary SaaS, or anything else the AGPL doesn't fit. See
  [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md) for what triggers it
  and how to acquire one.

Sole copyright holder is Appmire CommV (BE 0719.812.728). PRs are
welcome under the AGPL; submitting one means you accept the
contributor licensing terms outlined in `COMMERCIAL-LICENSE.md`.

## Why no JNI?

Android's public GNSS API (`android.location.GnssStatus`,
`GnssMeasurementsEvent`, `GnssNavigationMessage`) already surfaces every
satellite-level field the HAL exposes — per-PRN Cn0DbHz, azimuth,
elevation, used-in-fix, ephemeris/almanac availability, and raw
pseudorange/carrier-phase. There is no public JNI path that reveals more.
Going lower would require root and HAL-level access, which is out of
scope here.

## Out of scope (v1)

- Raw pseudorange UI (the field is in the GnssStatus stream but not
  currently surfaced on a card).
- Full ellipsoid (soft-iron) calibration fit — the dedicated calibration
  screen estimates hard-iron offset only. Android's `TYPE_ROTATION_VECTOR`
  fuses gyro/accelerometer/magnetometer with its own ongoing calibration,
  so writing our estimate back into the sensor pipeline isn't useful;
  the screen is informational, the OS does the actual correction as the
  user moves the phone.
- Full-tile online maps anywhere except the dedicated trail-detail
  screen.
