package be.appmire.gpsinfo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.appmire.gpsinfo.data.model.HrZoneConfig
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
        // BLE heart-rate monitor — MAC address of the paired device; null
        // when nothing is paired. Friendly name is stored alongside so
        // the Settings screen can render "Polar H10" without re-querying
        // the GATT cache after a cold start.
        val HrDeviceMac = stringPreferencesKey("hr_device_mac")
        val HrDeviceName = stringPreferencesKey("hr_device_name")
        // BLE Cycling Power meter — same shape as HR but for the
        // Cycling Power Service (0x1818). Friendly name kept
        // alongside so the Settings row can render "Stages LR" etc.
        // without re-querying the GATT cache.
        val CpDeviceMac = stringPreferencesKey("cp_device_mac")
        val CpDeviceName = stringPreferencesKey("cp_device_name")
        // BLE wheel-speed sensors (CSC service 0x1816) — bike speed
        // sensors on car wheel hubs acting as rally wheel probes.
        // Multiple sensors supported (two on one axle measure the
        // vehicle centreline); each entry is "MAC|FriendlyName".
        val WheelDevices = stringSetPreferencesKey("wheel_devices")
        // Legacy single-sensor keys, read once as a migration source.
        val WheelDeviceMac = stringPreferencesKey("wheel_device_mac")
        val WheelDeviceName = stringPreferencesKey("wheel_device_name")
        // HR zone config — max HR plus four zone boundaries (fractions
        // of max). Defaults defined on HrZoneConfig itself.
        val HrMaxBpm = intPreferencesKey("hr_max_bpm")
        val HrZ2Frac = floatPreferencesKey("hr_z2_frac")
        val HrZ3Frac = floatPreferencesKey("hr_z3_frac")
        val HrZ4Frac = floatPreferencesKey("hr_z4_frac")
        val HrZ5Frac = floatPreferencesKey("hr_z5_frac")
        /** Whether audible cues (TTS) fire on pace / HR threshold crosses
         *  while the Sports Dashboard is visible. Off by default — voice
         *  in earbuds is opt-in. */
        val AudibleCuesEnabled = booleanPreferencesKey("audible_cues_enabled")
        /** Whether tactile (vibration) cues fire on the same threshold
         *  crosses as the audible ones. Off by default. */
        val VibrationCuesEnabled = booleanPreferencesKey("vibration_cues_enabled")
        /** Whether the first-time Sports-view tutorial has been
         *  dismissed by the user. */
        val SportsTutorialSeen = booleanPreferencesKey("sports_tutorial_seen")
        /** Dashboard density preset — "standard" (current default) or
         *  "glanceable" (larger spacing and roomier cards). */
        val DashboardDensity = stringPreferencesKey("dashboard_density")
        /** Epoch millis the user last reset the trip computer. Trails
         *  recorded at or after this instant contribute to the visible
         *  trip totals; older trails stay around for personal-records
         *  but don't count toward "since I last reset". 0 = never reset
         *  (everything counts). */
        val TripResetMillis = androidx.datastore.preferences.core.longPreferencesKey("trip_reset_millis")
        /** Calibrated personal stride length in metres. Null when the
         *  user has not yet completed the calibration walk. */
        val PersonalStrideMeters = floatPreferencesKey("personal_stride_m")
        /** Diagnostic: log raw NMEA sentences from the GPS chip while
         *  a recording is active. File lives under cacheDir/nmea/ and
         *  is wiped on uninstall. Off by default — niche feature for
         *  power users debugging GPS chip behaviour. */
        val NmeaLoggingEnabled = booleanPreferencesKey("nmea_logging_enabled")
        /** Diagnostic: rebroadcast raw NMEA sentences over Bluetooth
         *  Serial Port Profile (RFCOMM) while a recording is active.
         *  Lets a paired chart-plotter or desktop tool consume the
         *  phone's GNSS as if it were a serial GPS puck. Off by
         *  default; requires Bluetooth + fine-location permission. */
        val NmeaBtBridgeEnabled = booleanPreferencesKey("nmea_bt_bridge_enabled")
        /** When true, the dashboard altitude readout is fed through an
         *  IIR low-pass filter to suppress the ±5-10 m per-sample
         *  jitter that's normal on consumer GNSS. Off by default
         *  because hikers who care about exact summit moments don't
         *  want the lag. */
        val AltitudeSmoothEnabled = booleanPreferencesKey("altitude_smooth_enabled")
        /** Ghost-runner (virtual partner) selection on the Runner
         *  dashboard. Mode is "none" | "pace" | "goal" | "trail"; the
         *  remaining keys hold that mode's parameters. */
        val GhostMode = stringPreferencesKey("ghost_mode")
        val GhostPaceSecPerKm = floatPreferencesKey("ghost_pace_sec_per_km")
        val GhostGoalSeconds = intPreferencesKey("ghost_goal_seconds")
        val GhostGoalMeters = intPreferencesKey("ghost_goal_meters")
        val GhostTrailId = stringPreferencesKey("ghost_trail_id")
        val GhostTrailName = stringPreferencesKey("ghost_trail_name")
        /** Id of the active dashboard profile — one of the built-in
         *  presets in [be.appmire.gpsinfo.data.model.DashboardProfile.builtIns],
         *  the special "custom" sentinel, or "default" when nothing has
         *  been chosen. */
        val DashboardProfileId = stringPreferencesKey("dashboard_profile_id")
        /** Serialised card list for the user-customised dashboard
         *  profile. Comma-separated [be.appmire.gpsinfo.data.model.DashboardSection]
         *  enum names. Null when the user hasn't built one yet — the
         *  "Custom" entry falls back to the Default ordering until then. */
        val CustomProfileCards = stringPreferencesKey("custom_profile_cards")
        /** Accent colour for the Custom profile, packed as an ARGB
         *  int. Null falls back to the Default profile's orange. */
        val CustomProfileAccent = intPreferencesKey("custom_profile_accent")
        /** Chrome style for the Custom profile —
         *  [be.appmire.gpsinfo.data.model.ChromeStyle] key. */
        val CustomProfileChrome = stringPreferencesKey("custom_profile_chrome")
        /** Number of cold starts so far — used to time the Play Store
         *  rating nudge (and nothing else). Incremented once per process
         *  launch, never reset. */
        val AppLaunchCount = intPreferencesKey("app_launch_count")
        /** Set once the user taps "Rate" or "Don't ask again" on the
         *  rating nudge. Permanent — the prompt never returns. */
        val RateNudgeDismissed = booleanPreferencesKey("rate_nudge_dismissed")
        /** Launch count below which the rating nudge stays hidden after a
         *  "Not now". Lets the prompt come back later without nagging. */
        val RateNudgeSnoozeUntilLaunch = intPreferencesKey("rate_nudge_snooze_until_launch")
        /** Epoch millis of the last GitHub-releases update check. Debounces
         *  the probe to ~once a day. */
        val UpdateCheckLastMillis = longPreferencesKey("update_check_last_millis")
        /** Newest release version name seen on the last successful check.
         *  Cached so the banner shows without re-fetching. Null = unknown. */
        val UpdateLatestVersion = stringPreferencesKey("update_latest_version")
        /** Version the user dismissed the update banner for. When the
         *  latest release is newer than this, the banner returns. */
        val UpdateDismissedVersion = stringPreferencesKey("update_dismissed_version")
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

    /** Persisted MAC of the paired BLE heart-rate monitor, or null. */
    val hrDeviceMac: Flow<String?> = context.dataStore.data.map { it[Keys.HrDeviceMac] }
    /** Friendly name of the paired BLE heart-rate monitor, or null. */
    val hrDeviceName: Flow<String?> = context.dataStore.data.map { it[Keys.HrDeviceName] }

    /** Paired BLE cycling power meter — same persistence pattern as HR. */
    val cpDeviceMac: Flow<String?> = context.dataStore.data.map { it[Keys.CpDeviceMac] }
    val cpDeviceName: Flow<String?> = context.dataStore.data.map { it[Keys.CpDeviceName] }

    /** Paired BLE wheel-speed sensors (rally wheel probes), MAC →
     *  friendly name. Falls back to the legacy single-sensor keys so
     *  a probe paired before multi-sensor support keeps working. */
    val wheelDevices: Flow<Map<String, String?>> = context.dataStore.data.map { prefs ->
        val set = prefs[Keys.WheelDevices]
        if (set != null) {
            set.associate { entry ->
                val sep = entry.indexOf('|')
                if (sep < 0) entry to null
                else entry.take(sep) to entry.drop(sep + 1).ifBlank { null }
            }
        } else {
            val legacyMac = prefs[Keys.WheelDeviceMac]
            if (legacyMac != null) mapOf(legacyMac to prefs[Keys.WheelDeviceName])
            else emptyMap()
        }
    }

    /** Active heart-rate zone configuration. Reads back the default
     *  [HrZoneConfig] when nothing has been persisted yet. */
    val hrZoneConfig: Flow<HrZoneConfig> = context.dataStore.data.map { prefs ->
        val default = HrZoneConfig()
        HrZoneConfig(
            maxBpm = prefs[Keys.HrMaxBpm] ?: default.maxBpm,
            z2Fraction = prefs[Keys.HrZ2Frac] ?: default.z2Fraction,
            z3Fraction = prefs[Keys.HrZ3Frac] ?: default.z3Fraction,
            z4Fraction = prefs[Keys.HrZ4Frac] ?: default.z4Fraction,
            z5Fraction = prefs[Keys.HrZ5Frac] ?: default.z5Fraction,
        )
    }

    val audibleCuesEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AudibleCuesEnabled] ?: false }

    suspend fun setAudibleCuesEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.AudibleCuesEnabled] = value }
    }

    val vibrationCuesEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.VibrationCuesEnabled] ?: false }

    suspend fun setVibrationCuesEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.VibrationCuesEnabled] = value }
    }

    val sportsTutorialSeen: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SportsTutorialSeen] ?: false }

    suspend fun setSportsTutorialSeen(value: Boolean) {
        context.dataStore.edit { it[Keys.SportsTutorialSeen] = value }
    }

    val dashboardDensity: Flow<DashboardDensity> = context.dataStore.data
        .map { DashboardDensity.fromString(it[Keys.DashboardDensity]) }

    suspend fun setDashboardDensity(value: DashboardDensity) {
        context.dataStore.edit { it[Keys.DashboardDensity] = value.key }
    }

    val tripResetMillis: Flow<Long> = context.dataStore.data
        .map { it[Keys.TripResetMillis] ?: 0L }

    suspend fun setTripResetMillis(value: Long) {
        context.dataStore.edit { it[Keys.TripResetMillis] = value }
    }

    val personalStrideMeters: Flow<Float?> = context.dataStore.data
        .map { it[Keys.PersonalStrideMeters] }

    suspend fun setPersonalStrideMeters(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.PersonalStrideMeters)
            else prefs[Keys.PersonalStrideMeters] = value
        }
    }

    val nmeaLoggingEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.NmeaLoggingEnabled] ?: false }

    suspend fun setNmeaLoggingEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.NmeaLoggingEnabled] = value }
    }

    val nmeaBtBridgeEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.NmeaBtBridgeEnabled] ?: false }

    suspend fun setNmeaBtBridgeEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.NmeaBtBridgeEnabled] = value }
    }

    val altitudeSmoothEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AltitudeSmoothEnabled] ?: false }

    suspend fun setAltitudeSmoothEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.AltitudeSmoothEnabled] = value }
    }

    /** Active ghost-runner selection, reconstructed into a
     *  [be.appmire.gpsinfo.data.model.GhostReference] (null = off). */
    val ghostReference: Flow<be.appmire.gpsinfo.data.model.GhostReference?> =
        context.dataStore.data.map { prefs ->
            when (prefs[Keys.GhostMode]) {
                "pace" -> prefs[Keys.GhostPaceSecPerKm]?.let {
                    be.appmire.gpsinfo.data.model.GhostReference.TargetPace(it)
                }
                "goal" -> {
                    val secs = prefs[Keys.GhostGoalSeconds]
                    val metres = prefs[Keys.GhostGoalMeters]
                    if (secs != null && metres != null) {
                        be.appmire.gpsinfo.data.model.GhostReference.Goal(secs.toLong(), metres.toDouble())
                    } else null
                }
                "trail" -> prefs[Keys.GhostTrailId]?.let { id ->
                    be.appmire.gpsinfo.data.model.GhostReference.PastRun(
                        trailId = id,
                        trailName = prefs[Keys.GhostTrailName] ?: id,
                    )
                }
                else -> null
            }
        }

    suspend fun setGhostReference(ref: be.appmire.gpsinfo.data.model.GhostReference?) {
        context.dataStore.edit { prefs ->
            when (ref) {
                null -> prefs[Keys.GhostMode] = "none"
                is be.appmire.gpsinfo.data.model.GhostReference.TargetPace -> {
                    prefs[Keys.GhostMode] = "pace"
                    prefs[Keys.GhostPaceSecPerKm] = ref.secondsPerKm
                }
                is be.appmire.gpsinfo.data.model.GhostReference.Goal -> {
                    prefs[Keys.GhostMode] = "goal"
                    prefs[Keys.GhostGoalSeconds] = ref.totalSeconds.toInt()
                    prefs[Keys.GhostGoalMeters] = ref.totalMeters.toInt()
                }
                is be.appmire.gpsinfo.data.model.GhostReference.PastRun -> {
                    prefs[Keys.GhostMode] = "trail"
                    prefs[Keys.GhostTrailId] = ref.trailId
                    prefs[Keys.GhostTrailName] = ref.trailName
                }
            }
        }
    }

    val dashboardProfileId: Flow<String> = context.dataStore.data
        .map { it[Keys.DashboardProfileId] ?: "default" }

    suspend fun setDashboardProfileId(value: String) {
        context.dataStore.edit { it[Keys.DashboardProfileId] = value }
    }

    /** Raw serialised list — comma-joined section names. The model
     *  layer is responsible for decoding into [DashboardSection]s. */
    val customProfileCardsRaw: Flow<String?> = context.dataStore.data
        .map { it[Keys.CustomProfileCards] }

    suspend fun setCustomProfileCardsRaw(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.CustomProfileCards)
            else prefs[Keys.CustomProfileCards] = value
        }
    }

    val customProfileAccent: Flow<Int?> = context.dataStore.data
        .map { it[Keys.CustomProfileAccent] }

    suspend fun setCustomProfileAccent(value: Int?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.CustomProfileAccent)
            else prefs[Keys.CustomProfileAccent] = value
        }
    }

    val customProfileChrome: Flow<be.appmire.gpsinfo.data.model.ChromeStyle> =
        context.dataStore.data
            .map { be.appmire.gpsinfo.data.model.ChromeStyle.fromString(it[Keys.CustomProfileChrome]) }

    suspend fun setCustomProfileChrome(value: be.appmire.gpsinfo.data.model.ChromeStyle) {
        context.dataStore.edit { it[Keys.CustomProfileChrome] = value.key }
    }

    // ---------- Play Store rating nudge ----------

    val appLaunchCount: Flow<Int> = context.dataStore.data
        .map { it[Keys.AppLaunchCount] ?: 0 }

    /** Bump the cold-start counter by one and return the new total. */
    suspend fun incrementAppLaunchCount(): Int {
        var updated = 0
        context.dataStore.edit { prefs ->
            updated = (prefs[Keys.AppLaunchCount] ?: 0) + 1
            prefs[Keys.AppLaunchCount] = updated
        }
        return updated
    }

    val rateNudgeDismissed: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.RateNudgeDismissed] ?: false }

    suspend fun setRateNudgeDismissed(value: Boolean) {
        context.dataStore.edit { it[Keys.RateNudgeDismissed] = value }
    }

    val rateNudgeSnoozeUntilLaunch: Flow<Int> = context.dataStore.data
        .map { it[Keys.RateNudgeSnoozeUntilLaunch] ?: 0 }

    suspend fun setRateNudgeSnoozeUntilLaunch(value: Int) {
        context.dataStore.edit { it[Keys.RateNudgeSnoozeUntilLaunch] = value }
    }

    // ---------- Update nudge (GitHub Releases) ----------

    val updateCheckLastMillis: Flow<Long> = context.dataStore.data
        .map { it[Keys.UpdateCheckLastMillis] ?: 0L }

    suspend fun setUpdateCheckLastMillis(value: Long) {
        context.dataStore.edit { it[Keys.UpdateCheckLastMillis] = value }
    }

    val updateLatestVersion: Flow<String?> = context.dataStore.data
        .map { it[Keys.UpdateLatestVersion] }

    suspend fun setUpdateLatestVersion(value: String) {
        context.dataStore.edit { it[Keys.UpdateLatestVersion] = value }
    }

    val updateDismissedVersion: Flow<String?> = context.dataStore.data
        .map { it[Keys.UpdateDismissedVersion] }

    suspend fun setUpdateDismissedVersion(value: String) {
        context.dataStore.edit { it[Keys.UpdateDismissedVersion] = value }
    }

    suspend fun setHrZoneConfig(cfg: HrZoneConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HrMaxBpm] = cfg.maxBpm
            prefs[Keys.HrZ2Frac] = cfg.z2Fraction
            prefs[Keys.HrZ3Frac] = cfg.z3Fraction
            prefs[Keys.HrZ4Frac] = cfg.z4Fraction
            prefs[Keys.HrZ5Frac] = cfg.z5Fraction
        }
    }

    suspend fun setHrDevice(macAddress: String?, friendlyName: String?) {
        context.dataStore.edit { prefs ->
            if (macAddress == null) {
                prefs.remove(Keys.HrDeviceMac)
                prefs.remove(Keys.HrDeviceName)
            } else {
                prefs[Keys.HrDeviceMac] = macAddress
                if (friendlyName != null) prefs[Keys.HrDeviceName] = friendlyName
                else prefs.remove(Keys.HrDeviceName)
            }
        }
    }

    suspend fun setCpDevice(macAddress: String?, friendlyName: String?) {
        context.dataStore.edit { prefs ->
            if (macAddress == null) {
                prefs.remove(Keys.CpDeviceMac)
                prefs.remove(Keys.CpDeviceName)
            } else {
                prefs[Keys.CpDeviceMac] = macAddress
                if (friendlyName != null) prefs[Keys.CpDeviceName] = friendlyName
                else prefs.remove(Keys.CpDeviceName)
            }
        }
    }

    suspend fun addWheelDevice(macAddress: String, friendlyName: String?) {
        context.dataStore.edit { prefs ->
            val current = currentWheelSet(prefs)
            prefs[Keys.WheelDevices] =
                current.filterNot { it.startsWith("$macAddress|") || it == macAddress }
                    .toSet() + "$macAddress|${friendlyName.orEmpty()}"
            // Migration complete — the legacy keys are now stale.
            prefs.remove(Keys.WheelDeviceMac)
            prefs.remove(Keys.WheelDeviceName)
        }
    }

    suspend fun removeWheelDevice(macAddress: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WheelDevices] = currentWheelSet(prefs)
                .filterNot { it.startsWith("$macAddress|") || it == macAddress }
                .toSet()
            prefs.remove(Keys.WheelDeviceMac)
            prefs.remove(Keys.WheelDeviceName)
        }
    }

    /** The stored entry set, folding the legacy single-sensor keys in
     *  when the set was never written. */
    private fun currentWheelSet(prefs: Preferences): Set<String> {
        prefs[Keys.WheelDevices]?.let { return it }
        val legacyMac = prefs[Keys.WheelDeviceMac] ?: return emptySet()
        return setOf("$legacyMac|${prefs[Keys.WheelDeviceName].orEmpty()}")
    }

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

/**
 * Dashboard layout density. "Standard" is the historical default
 * (10 dp inter-card spacing, modest padding). "Glanceable" trades
 * vertical real estate for legibility — bigger spacing, more padding
 * — for users who keep the phone mounted on a bike, bar or boat
 * helm and read at arm's length.
 */
enum class DashboardDensity(val key: String) {
    Standard("standard"),
    Glanceable("glanceable");

    companion object {
        fun fromString(s: String?): DashboardDensity = entries.firstOrNull { it.key == s } ?: Standard
    }
}
