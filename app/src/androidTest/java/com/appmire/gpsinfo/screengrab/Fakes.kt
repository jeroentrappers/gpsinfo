package com.appmire.gpsinfo.screengrab

import android.location.Location
import android.os.SystemClock
import com.appmire.gpsinfo.data.LocationDataSource
import com.appmire.gpsinfo.data.SensorDataSource
import com.appmire.gpsinfo.data.SettingsDataSource
import com.appmire.gpsinfo.data.ThemeOverride
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.data.model.Constellation
import com.appmire.gpsinfo.data.model.FixStatus
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.MagneticAccuracy
import com.appmire.gpsinfo.data.model.SatelliteInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canned data sources for the Screengrab UI test. Emits a stable, "shop
 * window" snapshot of Antwerp on a sunny noon — enough to fill every
 * card on every screen without needing a real GPS fix.
 */
class FakeLocationDataSource : LocationDataSource {
    override fun hasFineLocationPermission() = true
    override fun isLocationEnabled() = true

    override fun snapshots(): Flow<GnssSnapshot> =
        MutableStateFlow(snapshot).asStateFlow()

    companion object {
        private val location = Location("fake").apply {
            latitude = 51.1302028
            longitude = 4.3777386
            altitude = 45.0
            accuracy = 4f
            speed = 87f / 3.6f         // 87 km/h
            bearing = 58f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        private val satellites = listOf(
            sat(Constellation.GPS, 1, 40, 60, 42f, true),
            sat(Constellation.GPS, 10, 120, 35, 38f, true),
            sat(Constellation.GPS, 32, 250, 20, 28f, true),
            sat(Constellation.GPS, 14, 305, 12, 18f, false),
            sat(Constellation.GALILEO, 7, 80, 45, 36f, true),
            sat(Constellation.GALILEO, 11, 200, 25, 22f, true),
            sat(Constellation.GLONASS, 22, 170, 15, 24f, false),
            sat(Constellation.GLONASS, 4, 70, 65, 40f, true),
            sat(Constellation.BEIDOU, 22, 310, 30, 26f, true),
            sat(Constellation.BEIDOU, 7, 25, 50, 32f, true),
            sat(Constellation.QZSS, 5, 110, 55, 32f, true),
            sat(Constellation.SBAS, 33, 180, 20, 20f, false),
        )
        val snapshot = GnssSnapshot(
            location = location,
            fix = FixStatus.THREE_D,
            satellites = satellites,
            firstFixMillis = System.currentTimeMillis() - 12_000L,
            lastUpdateElapsedRealtime = SystemClock.elapsedRealtime(),
        )

        private fun sat(c: Constellation, svid: Int, az: Int, el: Int, cn0: Float, used: Boolean) =
            SatelliteInfo(
                svid = svid,
                constellation = c,
                azimuthDeg = az.toFloat(),
                elevationDeg = el.toFloat(),
                cn0DbHz = cn0,
                usedInFix = used,
                hasEphemeris = true,
                hasAlmanac = true,
                carrierFrequencyHz = 1575_420_000f,
            )
    }
}

class FakeSensorDataSource : SensorDataSource {
    override fun readings(currentLocation: () -> Location?): Flow<CompassReading> =
        MutableStateFlow(reading).asStateFlow()

    companion object {
        val reading = CompassReading(
            magneticHeadingDeg = 58f,
            continuousMagneticHeadingDeg = 58f,
            trueHeadingDeg = 60.71f,
            pitchDeg = 4f,
            rollDeg = -2f,
            declinationDeg = 2.71f,
            inclinationDeg = 65.4f,
            fieldStrengthNanoTesla = 49_000f,
            accuracy = MagneticAccuracy.HIGH,
        )
    }
}

class FakeSettingsDataSource : SettingsDataSource {
    private val _max = MutableStateFlow(180f)
    private val _theme = MutableStateFlow(ThemeOverride.Dark)
    private val _satSort = MutableStateFlow<String?>(null)
    private val _coord = MutableStateFlow<String?>(null)
    private val _units = MutableStateFlow(UnitSystem.Metric)
    // Pre-marked as seen — we don't want the onboarding dialog popping
    // over the screenshots.
    private val _onboarding = MutableStateFlow(true)

    override val maxSpeedKmh: Flow<Float> = _max
    override val themeOverride: Flow<ThemeOverride> = _theme
    override val satelliteSort: Flow<String?> = _satSort
    override val coordinateFormat: Flow<String?> = _coord
    override val unitSystem: Flow<UnitSystem> = _units
    override val onboardingSeen: Flow<Boolean> = _onboarding

    override suspend fun setMaxSpeedKmh(value: Float) { _max.value = value }
    override suspend fun setThemeOverride(value: ThemeOverride) { _theme.value = value }
    override suspend fun setSatelliteSort(value: String) { _satSort.value = value }
    override suspend fun setCoordinateFormat(value: String) { _coord.value = value }
    override suspend fun setUnitSystem(value: UnitSystem) { _units.value = value }
    override suspend fun setOnboardingSeen(value: Boolean) { _onboarding.value = value }
}
