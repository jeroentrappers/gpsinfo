# GPSinfo

A bespoke, modern Android-native dashboard that surfaces the rawest GNSS,
sensor and astronomical data the device can give: per-satellite SNR, fix
status, position, speed, heading, magnetic declination, day/night world
view, sunrise/sunset and more — all on a single integrated screen.

## Highlights

- **All-in-one dashboard** replacing the classic 6-tab swipe model.
- **Raw GNSS via the platform** — `GnssStatus.Callback` for per-satellite
  PRN/azimuth/elevation/Cn0/used-in-fix, `GnssMeasurementsEvent.Callback`
  pipeline registered for raw pseudorange/carrier-phase (future surface).
- **Tilt-corrected magnetic heading** from `TYPE_ROTATION_VECTOR` with
  `GeomagneticField` for true-north declination + inclination.
- **Solar Position Algorithm** (pure Kotlin) for sunrise/sunset/solar noon
  and a day/night terminator on the world map.
- **Light / dark mode** (system-following, manual toggle in the top bar).
- **Share location** + **Open in Maps** via standard `geo:` intents.
- 100% offline. No Google Play Services. No tile servers. No JNI needed —
  Android already exposes the rawest public GNSS surface in Kotlin.

## Building

Prerequisites:

- Android SDK with platform 34 installed (`~/Android/Sdk` by default).
- JDK 17.
- Gradle 8.7 (the wrapper will fetch this automatically).

```sh
./gradlew assembleDebug
```

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

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
| **Movement** | Speed (km/h), magnetic heading + cardinal, altitude. |
| **Sky view** | Radial polar plot of every satellite in azimuth/elevation, coloured by constellation; SNR bar per PRN; AVG-SNR colour bar. |
| **Compass** | Animated rose with current magnetic heading, declination °E/°W (WMM), inclination, magnetic accuracy chip, reciprocal heading. |
| **World** | Equirectangular world map drawn on `Canvas`, with day/night terminator computed from the subsolar point, sun glyph at solar noon longitude, and a "you are here" marker. |
| **Time & Sun** | UTC + Local clock and date, sunrise/solar-noon/sunset, day-length, sun azimuth/elevation, day-phase. |

## Project layout

```
app/src/main/java/com/appmire/gpsinfo/
├── MainActivity.kt                  # permission flow, theme switch
├── data/
│   ├── LocationRepository.kt        # LocationManager + GnssStatus + GnssMeasurements
│   ├── SensorRepository.kt          # ROTATION_VECTOR + MAGNETIC_FIELD + WMM
│   ├── sun/SunPositionCalculator.kt # NOAA SPA (simplified, pure Kotlin)
│   └── model/                       # GnssSnapshot, SatelliteInfo, FixStatus, CompassReading, SunInfo
├── ui/
│   ├── theme/                       # Material 3 + cyan accent, mono numeric font
│   ├── viewmodel/DashboardViewModel.kt  # combines all flows into UiState
│   ├── dashboard/DashboardScreen.kt # single integrated scrollable dashboard
│   └── components/                  # one file per card, all Canvas-drawn
└── util/
    ├── CoordinateFormatter.kt       # DMS ↔ decimal
    ├── IntentHelpers.kt             # share / open-in-maps / open-settings
    └── Cardinal.kt                  # 16-wind compass buckets
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

## Why no JNI?

Android's public GNSS API (`android.location.GnssStatus`,
`GnssMeasurementsEvent`, `GnssNavigationMessage`) already surfaces every
satellite-level field the HAL exposes — per-PRN Cn0DbHz, azimuth,
elevation, used-in-fix, ephemeris/almanac availability, and raw
pseudorange/carrier-phase. There is no public JNI path that reveals more.
Going lower would require root and HAL-level access, which is out of
scope here.

## Out of scope (v1)

- Background tracking / track recording.
- Raw pseudorange UI (callback is registered, values are not yet shown).
- Custom magnetometer calibration UI.
- Real tile-based maps.
- Translations beyond English.
