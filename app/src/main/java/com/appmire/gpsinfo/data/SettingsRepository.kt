package com.appmire.gpsinfo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gpsinfo_prefs")

/**
 * Single source of truth for user preferences. Survives process death.
 *
 * Backed by Jetpack DataStore — preferences file lives at
 * `<filesDir>/datastore/gpsinfo_prefs.preferences_pb`, which is the only
 * file allow-listed in our backup rules. Everything else (caches, fixes,
 * tracks) is excluded from cloud backup / device transfer by default.
 */
class SettingsRepository(private val context: Context) : SettingsDataSource {

    private object Keys {
        val MaxSpeedKmh = floatPreferencesKey("max_speed_kmh")
        val ForceDark = stringPreferencesKey("force_dark")           // "system" | "light" | "dark"
        val SatelliteSort = stringPreferencesKey("sat_sort")         // SatSortMode name
        val CoordinateFormat = stringPreferencesKey("coord_format")  // CoordinateFormat name
        val UnitSystem = stringPreferencesKey("unit_system")         // metric | imperial | nautical
        val OnboardingSeen = booleanPreferencesKey("onboarding_seen")
    }

    override val maxSpeedKmh: Flow<Float> = context.dataStore.data
        .map { it[Keys.MaxSpeedKmh] ?: DEFAULT_MAX_SPEED_KMH }

    override val themeOverride: Flow<ThemeOverride> = context.dataStore.data
        .map { ThemeOverride.fromString(it[Keys.ForceDark]) }

    override val satelliteSort: Flow<String?> = context.dataStore.data
        .map { it[Keys.SatelliteSort] }

    override val coordinateFormat: Flow<String?> = context.dataStore.data
        .map { it[Keys.CoordinateFormat] }

    override val unitSystem: Flow<UnitSystem> = context.dataStore.data
        .map { UnitSystem.fromString(it[Keys.UnitSystem]) }

    override val onboardingSeen: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.OnboardingSeen] ?: false }

    override suspend fun setMaxSpeedKmh(value: Float) {
        context.dataStore.edit { it[Keys.MaxSpeedKmh] = value }
    }

    override suspend fun setThemeOverride(value: ThemeOverride) {
        context.dataStore.edit { it[Keys.ForceDark] = value.value }
    }

    override suspend fun setSatelliteSort(value: String) {
        context.dataStore.edit { it[Keys.SatelliteSort] = value }
    }

    override suspend fun setCoordinateFormat(value: String) {
        context.dataStore.edit { it[Keys.CoordinateFormat] = value }
    }

    override suspend fun setUnitSystem(value: UnitSystem) {
        context.dataStore.edit { it[Keys.UnitSystem] = value.key }
    }

    override suspend fun setOnboardingSeen(value: Boolean) {
        context.dataStore.edit { it[Keys.OnboardingSeen] = value }
    }

    companion object {
        const val DEFAULT_MAX_SPEED_KMH = 180f
    }
}

enum class ThemeOverride(val value: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromString(s: String?): ThemeOverride = entries.firstOrNull { it.value == s } ?: System
    }
}
