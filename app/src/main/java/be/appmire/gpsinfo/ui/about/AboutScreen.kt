package be.appmire.gpsinfo.ui.about

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.DashboardDensity
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.IntentHelpers

private const val APPMIRE_URL = "https://appmire.be"
private const val PAYPAL_URL = "https://paypal.me/jeroentrappers"
private const val GITHUB_URL = "https://github.com/jeroentrappers/gpsinfo"
// Donation platforms aligned with the no-Play-Services / no-API-key
// stance. Liberapay = recurring micro-donations, FOSS-friendly. GitHub
// Sponsors = fits the source-available framing and the existing GitHub
// link. PayPal stays for the one-shot crowd.
private const val LIBERAPAY_URL = "https://liberapay.com/jeroentrappers"
private const val GITHUB_SPONSORS_URL = "https://github.com/sponsors/jeroentrappers"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onOpenHrPair: () -> Unit = {},
    onOpenHrZones: () -> Unit = {},
    onOpenCpPair: () -> Unit = {},
    onOpenSharePosition: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenStrideCalibration: () -> Unit = {},
    onOpenDashboardEditor: () -> Unit = {},
    onOpenObdLab: () -> Unit = {},
    onOpenVoiceGuidance: () -> Unit = {},
    onNmeaLoggingChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val onShowTour = {
            vm.resetOnboarding()
            onBack()
        }
        val audibleCuesEnabled by vm.audibleCuesEnabled.collectAsStateWithLifecycle()
        val vibrationCuesEnabled by vm.vibrationCuesEnabled.collectAsStateWithLifecycle()
        val dashboardDensity by vm.dashboardDensity.collectAsStateWithLifecycle()
        val nmeaLoggingEnabled by vm.nmeaLoggingEnabled.collectAsStateWithLifecycle()
        val nmeaBtBridgeEnabled by vm.nmeaBtBridgeEnabled.collectAsStateWithLifecycle()
        val altitudeSmoothEnabled by vm.altitudeSmoothEnabled.collectAsStateWithLifecycle()
        val carOverlaySpeed by vm.carOverlaySpeed.collectAsStateWithLifecycle()
        val carOverlaySpeedLimit by vm.carOverlaySpeedLimit.collectAsStateWithLifecycle()
        val carOverlayCluster by vm.carOverlayCluster.collectAsStateWithLifecycle()
        val carOverlayCompass by vm.carOverlayCompass.collectAsStateWithLifecycle()
        val carOverlayRecordingStrip by vm.carOverlayRecordingStrip.collectAsStateWithLifecycle()
        val carOverlayRallyPanel by vm.carOverlayRallyPanel.collectAsStateWithLifecycle()
        val carOverlay = CarOverlayControls(
            speed = carOverlaySpeed, onSpeed = vm::setCarOverlaySpeed,
            speedLimit = carOverlaySpeedLimit, onSpeedLimit = vm::setCarOverlaySpeedLimit,
            cluster = carOverlayCluster, onCluster = vm::setCarOverlayCluster,
            compass = carOverlayCompass, onCompass = vm::setCarOverlayCompass,
            recordingStrip = carOverlayRecordingStrip, onRecordingStrip = vm::setCarOverlayRecordingStrip,
            rallyPanel = carOverlayRallyPanel, onRallyPanel = vm::setCarOverlayRallyPanel,
        )
        if (isLandscape) {
            AboutLandscape(
                padding = padding,
                unitSystem = state.unitSystem,
                onUnitSystemChange = vm::setUnitSystem,
                onShowTour = onShowTour,
                onOpenHrPair = onOpenHrPair,
                onOpenHrZones = onOpenHrZones,
                onOpenCpPair = onOpenCpPair,
                onOpenSharePosition = onOpenSharePosition,
                onOpenWaypoints = onOpenWaypoints,
                onOpenStrideCalibration = onOpenStrideCalibration,
                onOpenDashboardEditor = onOpenDashboardEditor,
                onOpenObdLab = onOpenObdLab,
                onOpenVoiceGuidance = onOpenVoiceGuidance,
                audibleCuesEnabled = audibleCuesEnabled,
                onAudibleCuesEnabledChange = vm::setAudibleCuesEnabled,
                vibrationCuesEnabled = vibrationCuesEnabled,
                onVibrationCuesEnabledChange = vm::setVibrationCuesEnabled,
                dashboardDensity = dashboardDensity,
                onDashboardDensityChange = vm::setDashboardDensity,
                nmeaLoggingEnabled = nmeaLoggingEnabled,
                onNmeaLoggingEnabledChange = vm::setNmeaLoggingEnabled,
                nmeaBtBridgeEnabled = nmeaBtBridgeEnabled,
                onNmeaBtBridgeEnabledChange = vm::setNmeaBtBridgeEnabled,
                altitudeSmoothEnabled = altitudeSmoothEnabled,
                onAltitudeSmoothEnabledChange = vm::setAltitudeSmoothEnabled,
                carOverlay = carOverlay,
                onOpenSite = { IntentHelpers.openUrl(context, APPMIRE_URL) },
                onOpenPaypal = { IntentHelpers.openUrl(context, PAYPAL_URL) },
                onOpenLiberapay = { IntentHelpers.openUrl(context, LIBERAPAY_URL) },
                onOpenGithubSponsors = { IntentHelpers.openUrl(context, GITHUB_SPONSORS_URL) },
                onOpenGithub = { IntentHelpers.openUrl(context, GITHUB_URL) },
                onRateApp = { IntentHelpers.openPlayStoreListing(context) },
            )
        } else {
            AboutPortrait(
                padding = padding,
                unitSystem = state.unitSystem,
                onUnitSystemChange = vm::setUnitSystem,
                onShowTour = onShowTour,
                onOpenHrPair = onOpenHrPair,
                onOpenHrZones = onOpenHrZones,
                onOpenCpPair = onOpenCpPair,
                onOpenSharePosition = onOpenSharePosition,
                onOpenWaypoints = onOpenWaypoints,
                onOpenStrideCalibration = onOpenStrideCalibration,
                onOpenDashboardEditor = onOpenDashboardEditor,
                onOpenObdLab = onOpenObdLab,
                onOpenVoiceGuidance = onOpenVoiceGuidance,
                audibleCuesEnabled = audibleCuesEnabled,
                onAudibleCuesEnabledChange = vm::setAudibleCuesEnabled,
                vibrationCuesEnabled = vibrationCuesEnabled,
                onVibrationCuesEnabledChange = vm::setVibrationCuesEnabled,
                dashboardDensity = dashboardDensity,
                onDashboardDensityChange = vm::setDashboardDensity,
                nmeaLoggingEnabled = nmeaLoggingEnabled,
                onNmeaLoggingEnabledChange = vm::setNmeaLoggingEnabled,
                nmeaBtBridgeEnabled = nmeaBtBridgeEnabled,
                onNmeaBtBridgeEnabledChange = vm::setNmeaBtBridgeEnabled,
                altitudeSmoothEnabled = altitudeSmoothEnabled,
                onAltitudeSmoothEnabledChange = vm::setAltitudeSmoothEnabled,
                carOverlay = carOverlay,
                onOpenSite = { IntentHelpers.openUrl(context, APPMIRE_URL) },
                onOpenPaypal = { IntentHelpers.openUrl(context, PAYPAL_URL) },
                onOpenLiberapay = { IntentHelpers.openUrl(context, LIBERAPAY_URL) },
                onOpenGithubSponsors = { IntentHelpers.openUrl(context, GITHUB_SPONSORS_URL) },
                onOpenGithub = { IntentHelpers.openUrl(context, GITHUB_URL) },
                onRateApp = { IntentHelpers.openPlayStoreListing(context) },
            )
        }
    }
}

/** Bundles the six "Android Auto" overlay toggles + their setters so the
 *  About layout composables thread one param instead of twelve. */
private class CarOverlayControls(
    val speed: Boolean, val onSpeed: (Boolean) -> Unit,
    val speedLimit: Boolean, val onSpeedLimit: (Boolean) -> Unit,
    val cluster: Boolean, val onCluster: (Boolean) -> Unit,
    val compass: Boolean, val onCompass: (Boolean) -> Unit,
    val recordingStrip: Boolean, val onRecordingStrip: (Boolean) -> Unit,
    val rallyPanel: Boolean, val onRallyPanel: (Boolean) -> Unit,
)

@Composable
private fun AboutPortrait(
    padding: androidx.compose.foundation.layout.PaddingValues,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onShowTour: () -> Unit,
    onOpenHrPair: () -> Unit,
    onOpenHrZones: () -> Unit,
    onOpenCpPair: () -> Unit,
    onOpenSharePosition: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenStrideCalibration: () -> Unit,
    onOpenDashboardEditor: () -> Unit,
    onOpenObdLab: () -> Unit,
    onOpenVoiceGuidance: () -> Unit,
    audibleCuesEnabled: Boolean,
    onAudibleCuesEnabledChange: (Boolean) -> Unit,
    vibrationCuesEnabled: Boolean,
    onVibrationCuesEnabledChange: (Boolean) -> Unit,
    dashboardDensity: DashboardDensity,
    onDashboardDensityChange: (DashboardDensity) -> Unit,
    nmeaLoggingEnabled: Boolean,
    onNmeaLoggingEnabledChange: (Boolean) -> Unit,
    nmeaBtBridgeEnabled: Boolean,
    onNmeaBtBridgeEnabledChange: (Boolean) -> Unit,
    altitudeSmoothEnabled: Boolean,
    onAltitudeSmoothEnabledChange: (Boolean) -> Unit,
    carOverlay: CarOverlayControls,
    onOpenSite: () -> Unit,
    onOpenPaypal: () -> Unit,
    onOpenLiberapay: () -> Unit,
    onOpenGithubSponsors: () -> Unit,
    onOpenGithub: () -> Unit,
    onRateApp: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(12.dp))
        LogoCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenSite)
        Spacer(Modifier.height(24.dp))
        DonationBlock(
            onOpenPaypal = onOpenPaypal,
            onOpenLiberapay = onOpenLiberapay,
            onOpenGithubSponsors = onOpenGithubSponsors,
        )
        Spacer(Modifier.height(12.dp))
        GitHubButton(onClick = onOpenGithub)
        Spacer(Modifier.height(12.dp))
        RateButton(onClick = onRateApp)
        Spacer(Modifier.height(16.dp))
        SettingsSection(
            unitSystem = unitSystem,
            onUnitSystemChange = onUnitSystemChange,
            onShowTour = onShowTour,
            onOpenHrPair = onOpenHrPair,
            onOpenHrZones = onOpenHrZones,
            onOpenCpPair = onOpenCpPair,
            onOpenSharePosition = onOpenSharePosition,
            onOpenWaypoints = onOpenWaypoints,
            onOpenStrideCalibration = onOpenStrideCalibration,
            onOpenDashboardEditor = onOpenDashboardEditor,
            onOpenObdLab = onOpenObdLab,
            onOpenVoiceGuidance = onOpenVoiceGuidance,
            audibleCuesEnabled = audibleCuesEnabled,
            onAudibleCuesEnabledChange = onAudibleCuesEnabledChange,
            vibrationCuesEnabled = vibrationCuesEnabled,
            onVibrationCuesEnabledChange = onVibrationCuesEnabledChange,
            dashboardDensity = dashboardDensity,
            onDashboardDensityChange = onDashboardDensityChange,
            nmeaLoggingEnabled = nmeaLoggingEnabled,
            onNmeaLoggingEnabledChange = onNmeaLoggingEnabledChange,
            nmeaBtBridgeEnabled = nmeaBtBridgeEnabled,
            onNmeaBtBridgeEnabledChange = onNmeaBtBridgeEnabledChange,
            altitudeSmoothEnabled = altitudeSmoothEnabled,
            onAltitudeSmoothEnabledChange = onAltitudeSmoothEnabledChange,
            carOverlaySpeed = carOverlay.speed,
            onCarOverlaySpeedChange = carOverlay.onSpeed,
            carOverlaySpeedLimit = carOverlay.speedLimit,
            onCarOverlaySpeedLimitChange = carOverlay.onSpeedLimit,
            carOverlayCluster = carOverlay.cluster,
            onCarOverlayClusterChange = carOverlay.onCluster,
            carOverlayCompass = carOverlay.compass,
            onCarOverlayCompassChange = carOverlay.onCompass,
            carOverlayRecordingStrip = carOverlay.recordingStrip,
            onCarOverlayRecordingStripChange = carOverlay.onRecordingStrip,
            carOverlayRallyPanel = carOverlay.rallyPanel,
            onCarOverlayRallyPanelChange = carOverlay.onRallyPanel,
        )
        Spacer(Modifier.height(16.dp))
        LicenseCard()
        Spacer(Modifier.height(16.dp))
        BuildBadge()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AboutLandscape(
    padding: androidx.compose.foundation.layout.PaddingValues,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onShowTour: () -> Unit,
    onOpenHrPair: () -> Unit,
    onOpenHrZones: () -> Unit,
    onOpenCpPair: () -> Unit,
    onOpenSharePosition: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenStrideCalibration: () -> Unit,
    onOpenDashboardEditor: () -> Unit,
    onOpenObdLab: () -> Unit,
    onOpenVoiceGuidance: () -> Unit,
    audibleCuesEnabled: Boolean,
    onAudibleCuesEnabledChange: (Boolean) -> Unit,
    vibrationCuesEnabled: Boolean,
    onVibrationCuesEnabledChange: (Boolean) -> Unit,
    dashboardDensity: DashboardDensity,
    onDashboardDensityChange: (DashboardDensity) -> Unit,
    nmeaLoggingEnabled: Boolean,
    onNmeaLoggingEnabledChange: (Boolean) -> Unit,
    nmeaBtBridgeEnabled: Boolean,
    onNmeaBtBridgeEnabledChange: (Boolean) -> Unit,
    altitudeSmoothEnabled: Boolean,
    onAltitudeSmoothEnabledChange: (Boolean) -> Unit,
    carOverlay: CarOverlayControls,
    onOpenSite: () -> Unit,
    onOpenPaypal: () -> Unit,
    onOpenLiberapay: () -> Unit,
    onOpenGithubSponsors: () -> Unit,
    onOpenGithub: () -> Unit,
    onRateApp: () -> Unit,
) {
    // Two-column: logo on the left (height-constrained so it doesn't crop
    // the buttons on the right), donation copy and links on the right.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .wrapContentHeight(Alignment.CenterVertically),
            onClick = onOpenSite,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            DonationBlock(
            onOpenPaypal = onOpenPaypal,
            onOpenLiberapay = onOpenLiberapay,
            onOpenGithubSponsors = onOpenGithubSponsors,
        )
            Spacer(Modifier.height(12.dp))
            GitHubButton(onClick = onOpenGithub)
            Spacer(Modifier.height(12.dp))
            RateButton(onClick = onRateApp)
            Spacer(Modifier.height(16.dp))
            SettingsSection(
            unitSystem = unitSystem,
            onUnitSystemChange = onUnitSystemChange,
            onShowTour = onShowTour,
            onOpenHrPair = onOpenHrPair,
            onOpenHrZones = onOpenHrZones,
            onOpenCpPair = onOpenCpPair,
            onOpenSharePosition = onOpenSharePosition,
            onOpenWaypoints = onOpenWaypoints,
            onOpenStrideCalibration = onOpenStrideCalibration,
            onOpenDashboardEditor = onOpenDashboardEditor,
            onOpenObdLab = onOpenObdLab,
            onOpenVoiceGuidance = onOpenVoiceGuidance,
            audibleCuesEnabled = audibleCuesEnabled,
            onAudibleCuesEnabledChange = onAudibleCuesEnabledChange,
            vibrationCuesEnabled = vibrationCuesEnabled,
            onVibrationCuesEnabledChange = onVibrationCuesEnabledChange,
            dashboardDensity = dashboardDensity,
            onDashboardDensityChange = onDashboardDensityChange,
            nmeaLoggingEnabled = nmeaLoggingEnabled,
            onNmeaLoggingEnabledChange = onNmeaLoggingEnabledChange,
            nmeaBtBridgeEnabled = nmeaBtBridgeEnabled,
            onNmeaBtBridgeEnabledChange = onNmeaBtBridgeEnabledChange,
            altitudeSmoothEnabled = altitudeSmoothEnabled,
            onAltitudeSmoothEnabledChange = onAltitudeSmoothEnabledChange,
            carOverlaySpeed = carOverlay.speed,
            onCarOverlaySpeedChange = carOverlay.onSpeed,
            carOverlaySpeedLimit = carOverlay.speedLimit,
            onCarOverlaySpeedLimitChange = carOverlay.onSpeedLimit,
            carOverlayCluster = carOverlay.cluster,
            onCarOverlayClusterChange = carOverlay.onCluster,
            carOverlayCompass = carOverlay.compass,
            onCarOverlayCompassChange = carOverlay.onCompass,
            carOverlayRecordingStrip = carOverlay.recordingStrip,
            onCarOverlayRecordingStripChange = carOverlay.onRecordingStrip,
            carOverlayRallyPanel = carOverlay.rallyPanel,
            onCarOverlayRallyPanelChange = carOverlay.onRallyPanel,
        )
            Spacer(Modifier.height(16.dp))
            LicenseCard()
            Spacer(Modifier.height(16.dp))
            BuildBadge()
        }
    }
}

@Composable
private fun LogoCard(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.appmire_logo),
                contentDescription = stringResource(R.string.about_brand_alt),
                contentScale = ContentScale.Fit,
                // Aspect-ratio anchored to the source 1000×800 = 5:4 keeps the
                // logo from stretching when the available width grows in
                // landscape. fillMaxWidth lets it scale up to whatever the
                // column gives us.
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1000f / 800f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = APPMIRE_URL.removePrefix("https://"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DonationBlock(
    onOpenPaypal: () -> Unit,
    onOpenLiberapay: () -> Unit,
    onOpenGithubSponsors: () -> Unit,
) {
    Text(
        text = stringResource(R.string.about_tagline),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    // Three donation buttons — Liberapay first (FOSS-friendly recurring
    // micro-donations), GitHub Sponsors second (matches the source-
    // available framing), PayPal third (one-shot fallback). No Play
    // Billing — keeps the no-Play-Services stance intact.
    DonationButton(
        url = LIBERAPAY_URL,
        contentDescription = stringResource(R.string.about_liberapay_label),
        onClick = onOpenLiberapay,
    )
    Spacer(Modifier.height(8.dp))
    DonationButton(
        url = GITHUB_SPONSORS_URL,
        contentDescription = stringResource(R.string.about_github_sponsors_label),
        onClick = onOpenGithubSponsors,
    )
    Spacer(Modifier.height(8.dp))
    DonationButton(
        url = PAYPAL_URL,
        contentDescription = stringResource(R.string.about_paypal_label),
        onClick = onOpenPaypal,
    )
}

@Composable
private fun DonationButton(url: String, contentDescription: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = url.removePrefix("https://"),
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RateButton(onClick: () -> Unit) {
    val label = stringResource(R.string.about_rate_label)
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Icon(
            Icons.Outlined.Star,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label)
    }
}

@Composable
private fun GitHubButton(onClick: () -> Unit) {
    // The screen-reader announcement otherwise reads the URL character-
    // by-character. Override with the human label.
    val label = stringResource(R.string.about_github_label)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_github),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = GITHUB_URL.removePrefix("https://"),
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LicenseCard() {
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.about_license_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_license_body, year),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            // Map data attribution (the live map hides the on-map credit).
            Text(
                text = stringResource(R.string.about_map_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BuildBadge() {
    val context = LocalContext.current
    val version = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }
    Text(
        text = stringResource(R.string.build_badge, version),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}
