package be.appmire.gpsinfo.ui.preview

import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import be.appmire.gpsinfo.data.LocationDataSource
import be.appmire.gpsinfo.data.SensorDataSource
import be.appmire.gpsinfo.data.SettingsDataSource
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.data.TrailDataSource
import be.appmire.gpsinfo.data.TrailSummary
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.Constellation
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.MagnetometerSample
import be.appmire.gpsinfo.data.model.SatelliteInfo
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake data sources + a one-call helper that materialises a
 * [DashboardViewModel] for Compose previews. Lives under `app/src/debug`
 * so it never reaches a release build — release R8 wouldn't even see
 * these classes to strip them.
 *
 * The fakes parallel the ones the screengrab test uses, but with one
 * difference: those live in `androidTest/` and aren't visible from
 * `@Preview` rendering. Keeping a debug-set copy here is the trade-off.
 */

internal object PreviewFakes {

    val antwerpLocation: Location = Location("preview").apply {
        latitude = 51.1302028
        longitude = 4.3777386
        altitude = 45.0
        accuracy = 4f
        speed = 87f / 3.6f
        bearing = 58f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    val satellites: List<SatelliteInfo> = listOf(
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

    val snapshot: GnssSnapshot = GnssSnapshot(
        location = antwerpLocation,
        fix = FixStatus.THREE_D,
        satellites = satellites,
        firstFixMillis = System.currentTimeMillis() - 12_000L,
        lastUpdateElapsedRealtime = SystemClock.elapsedRealtime(),
    )

    val compass: CompassReading = CompassReading(
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

    val sampleTrail: Trail = Trail(
        id = "preview-trail",
        name = "Antwerp loop",
        points = (0 until 30).map { i ->
            TrailPoint(
                timeMillis = 1_716_120_000_000L + i * 1000L,
                latDeg = 51.22 + i * 0.0001,
                lonDeg = 4.40 + i * 0.00015,
                eleMeters = 5.0 + i * 0.3,
                speedMps = 1.4f,
                hAccuracyM = 4f,
                satellitesInFix = 11,
            )
        },
    )

    /**
     * 64 magnetometer samples on a 45 µT sphere with a small +5 µT
     * hard-iron offset on X — what a slightly miscalibrated phone in
     * a normal environment looks like. Used by the calibration preview.
     */
    val magnetometerCloud: List<MagnetometerSample> = run {
        val radius = 45f
        val offsetX = 5f
        val n = 64
        (0 until n).map { i ->
            // Fibonacci lattice, deterministic.
            val golden = PI * (3.0 - kotlin.math.sqrt(5.0))
            val y01 = 1.0 - (i.toDouble() / (n - 1).toDouble()) * 2.0
            val r = kotlin.math.sqrt(1.0 - y01 * y01)
            val theta = golden * i
            MagnetometerSample(
                xMicroTesla = offsetX + (radius * r * cos(theta)).toFloat(),
                yMicroTesla = (radius * y01).toFloat(),
                zMicroTesla = (radius * r * sin(theta)).toFloat(),
                timeNanos = i * 1_000_000L,
                accuracy = MagneticAccuracy.HIGH,
            )
        }
    }

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

internal class FakeLocationDataSource : LocationDataSource {
    override fun hasFineLocationPermission() = true
    override fun isLocationEnabled() = true
    override fun snapshots(): Flow<GnssSnapshot> =
        MutableStateFlow(PreviewFakes.snapshot).asStateFlow()
}

internal class FakeSensorDataSource : SensorDataSource {
    override fun readings(currentLocation: () -> Location?): Flow<CompassReading> =
        MutableStateFlow(PreviewFakes.compass).asStateFlow()

    override fun magnetometerStream(): Flow<MagnetometerSample> {
        // Replay the canned cloud once and stay open. The calibration
        // VM treats this as a stream of new samples; the bounded buffer
        // dedupe behaviour means we don't need to keep emitting forever
        // to drive a representative preview.
        val flow = MutableStateFlow(PreviewFakes.magnetometerCloud.last())
        return flow.asStateFlow()
    }

    override fun gForceStream(
        currentBearingDeg: () -> Float?,
    ): Flow<be.appmire.gpsinfo.data.model.GForceSample> =
        MutableStateFlow(be.appmire.gpsinfo.data.model.GForceSample(0.18f, -0.32f, 0.05f))
            .asStateFlow()
}

internal class FakeSettingsDataSource : SettingsDataSource {
    private val _max = MutableStateFlow(180f)
    private val _theme = MutableStateFlow(ThemeOverride.Dark)
    private val _sort = MutableStateFlow<String?>(null)
    private val _coord = MutableStateFlow<String?>(null)
    private val _units = MutableStateFlow(UnitSystem.Metric)
    // Pre-seen so the onboarding dialog doesn't pop over screen previews.
    private val _onboarding = MutableStateFlow(true)

    override val maxSpeedKmh: Flow<Float> = _max
    override val themeOverride: Flow<ThemeOverride> = _theme
    override val satelliteSort: Flow<String?> = _sort
    override val coordinateFormat: Flow<String?> = _coord
    override val unitSystem: Flow<UnitSystem> = _units
    override val onboardingSeen: Flow<Boolean> = _onboarding

    override suspend fun setMaxSpeedKmh(value: Float) { _max.value = value }
    override suspend fun setThemeOverride(value: ThemeOverride) { _theme.value = value }
    override suspend fun setSatelliteSort(value: String) { _sort.value = value }
    override suspend fun setCoordinateFormat(value: String) { _coord.value = value }
    override suspend fun setUnitSystem(value: UnitSystem) { _units.value = value }
    override suspend fun setOnboardingSeen(value: Boolean) { _onboarding.value = value }
}

internal class FakeTrailDataSource(
    private val seed: List<Trail> = listOf(PreviewFakes.sampleTrail),
) : TrailDataSource {
    private val summaries = MutableStateFlow(seed.map { it.toSummary() })
    override val trails: Flow<List<TrailSummary>> = summaries.asStateFlow()
    override suspend fun load(id: String): Trail? = seed.firstOrNull { it.id == id }
    override suspend fun save(
        name: String,
        points: List<TrailPoint>,
        targetPaceSecondsPerKm: Float?,
        laps: List<be.appmire.gpsinfo.data.model.LapMarker>,
    ): String = "preview-saved"
    override suspend fun delete(id: String) = Unit
    override suspend fun rename(id: String, newName: String): Boolean = true
    override suspend fun updatePoints(id: String, newPoints: List<TrailPoint>): Boolean = true
    override suspend fun setTags(id: String, newTags: List<String>): Boolean = true
    override fun gpxFile(id: String): java.io.File? = null
    override suspend fun fitFile(id: String): java.io.File? = null
    override suspend fun importGpx(input: java.io.InputStream, suggestedName: String): String? = null
    override suspend fun simplify(id: String, epsilonMeters: Double, replace: Boolean): String? = id

    private fun Trail.toSummary() = TrailSummary(
        id = id,
        name = name,
        startTimeMillis = startTimeMillis,
        endTimeMillis = endTimeMillis,
        pointCount = points.size,
        distanceMeters = distanceMeters,
        durationMillis = durationMillis,
        avgSpeedKmh = avgSpeedKmh,
        ascentMeters = ascentMeters,
    )
}

/**
 * Build a fully-wired [DashboardViewModel] for a Compose `@Preview`.
 * Pulls the host Application from [LocalContext]; in Studio's preview
 * pane this is the IDE-provided inspectable Application.
 */
@Composable
internal fun rememberPreviewVm(): DashboardViewModel {
    val app = LocalContext.current.applicationContext as Application
    return remember {
        DashboardViewModel(
            app = app,
            locationRepo = FakeLocationDataSource(),
            sensorRepo = FakeSensorDataSource(),
            settings = FakeSettingsDataSource(),
            trailRepo = FakeTrailDataSource(),
        )
    }
}
