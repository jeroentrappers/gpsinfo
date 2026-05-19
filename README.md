# GPSinfo

A bespoke, modern Android-native dashboard that surfaces the rawest GNSS,
sensor and astronomical data the device can give: per-satellite SNR, fix
status, position, speed, heading, magnetic declination, day/night world
view, sunrise/sunset, plus GPS trail recording with offline-capable map.

## Highlights

- **All-in-one dashboard** — fix quality, sky view, position, movement,
  compass, world map, time & sun, all on one scrollable screen. The
  classic 6-tab swipe layout is gone.
- **Raw GNSS via the platform** — `GnssStatus.Callback` for per-satellite
  PRN/azimuth/elevation/Cn0/used-in-fix. No Play Services, no Fused
  Location, no JNI.
- **Trail recording** — foreground-service backed, survives screen-off
  and app-backgrounding. GPX 1.1 export (with Garmin TrackPointExtension
  for speed/course). RDP simplification with three presets. Optional
  GPX import via SAF.
- **Trail map** — osmdroid + OpenStreetMap tiles for the trail-detail
  view only. The rest of the app stays offline; the world overview on
  the dashboard still uses a custom Canvas + Natural Earth coastline.
- **Tilt-corrected magnetic heading** from `TYPE_ROTATION_VECTOR` with
  `GeomagneticField` for true-north declination + inclination.
- **Solar Position Algorithm** (pure Kotlin) for sunrise/sunset/solar
  noon and a day/night terminator on the world map.
- **Light / dark mode** (system-following, manual toggle in the top bar).
- **Per-app language picker**: 11 translated locales.
- **Share location** + **Open in Maps** via standard `geo:` intents.
- No analytics, ads, or crash reporting. No Play Services dependency.
  The only network call is anonymous OSM tile fetching, and only on the
  trail map screen — see `docs/privacy.md`.

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
| **Status bar** | Fix type (NO / 2D / 3D), horizontal accuracy, sats in-use ▸ in-view, average SNR. |
| **Position** | Lat/Lon (DMS or decimal — toggle button), altitude, H/V accuracy. Share + Open-in-Maps actions. |
| **Movement** | Speed (km/h, mph or kn), magnetic heading + cardinal, altitude. Tap for full-screen retro analog speed gauge with HUD mirror mode. |
| **Sky view** | Radial polar plot of every satellite in azimuth/elevation, coloured by constellation; SNR bar per PRN; AVG-SNR colour bar. Tap for satellite list + NMEA-style readout. |
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
list, and toggle the theme.

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
│   ├── TrailRecorder.kt             # in-memory point capture + throttle
│   ├── TrailRecordingController.kt  # process-wide singleton
│   ├── TrailRecordingService.kt     # foreground service (location)
│   ├── TrailRepository.kt           # file-backed GPX persistence
│   ├── gpx/
│   │   ├── GpxIo.kt                 # GPX 1.1 reader + writer
│   │   └── TrailSimplifier.kt       # Ramer-Douglas-Peucker, 3D-aware
│   ├── sun/SunPositionCalculator.kt # NOAA SPA (pure Kotlin)
│   └── model/                       # immutable domain types
├── ui/
│   ├── theme/                       # Material 3 + cyan accent + mono font
│   ├── viewmodel/DashboardViewModel.kt
│   ├── dashboard/                   # main screen + permission screen + helpers
│   ├── components/                  # one Card per file, all Canvas-drawn
│   ├── about/                       # About + Settings sections
│   ├── compass/                     # full-screen compass detail
│   ├── satellite/                   # satellite list + NMEA readout
│   ├── speed/                       # retro analog speed gauge
│   ├── trails/                      # list, detail map, charts, dialogs
│   ├── nmea/                        # NMEA-style raw GNSS readout
│   └── onboarding/                  # first-run tour dialog
└── util/                            # coordinate fmt, units, intents, naming
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
- Custom magnetometer calibration UI (we surface a "calibrate" banner
  when the sensor reports LOW accuracy and let the OS handle the rest).
- Full-tile online maps anywhere except the dedicated trail-detail
  screen.
