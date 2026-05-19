package com.appmire.gpsinfo.data

import android.location.Location
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.data.model.GnssSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Contract for whatever feeds GNSS snapshots into the ViewModel. The real
 * implementation is [LocationRepository]; tests can plug in a fake that
 * pushes canned snapshots.
 */
interface LocationDataSource {
    fun hasFineLocationPermission(): Boolean
    fun isLocationEnabled(): Boolean
    fun snapshots(): Flow<GnssSnapshot>
}

/**
 * Contract for whatever feeds compass readings. The real implementation is
 * [SensorRepository]; tests can fake the rotation-vector stream.
 *
 * The `currentLocation` lambda is how the data source learns about the
 * latest fix so it can compute the magnetic declination — keeping it as
 * a callback avoids the data layer depending directly on the location
 * source's flow.
 */
interface SensorDataSource {
    fun readings(currentLocation: () -> Location?): Flow<CompassReading>
}

/**
 * Contract for user preferences. The real implementation is
 * [SettingsRepository] (DataStore-backed); tests can use a pure
 * in-memory fake.
 */
interface SettingsDataSource {
    val maxSpeedKmh: Flow<Float>
    val themeOverride: Flow<ThemeOverride>
    val satelliteSort: Flow<String?>
    val coordinateFormat: Flow<String?>
    val unitSystem: Flow<UnitSystem>
    /** Whether the first-run onboarding tour has been shown. */
    val onboardingSeen: Flow<Boolean>

    suspend fun setMaxSpeedKmh(value: Float)
    suspend fun setThemeOverride(value: ThemeOverride)
    suspend fun setSatelliteSort(value: String)
    suspend fun setCoordinateFormat(value: String)
    suspend fun setUnitSystem(value: UnitSystem)
    suspend fun setOnboardingSeen(value: Boolean)
}
