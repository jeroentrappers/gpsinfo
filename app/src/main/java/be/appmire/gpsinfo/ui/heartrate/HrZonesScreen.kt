package be.appmire.gpsinfo.ui.heartrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * Editor for the global HR zone profile. Max-HR is a single integer
 * input; the four zone boundaries are percentage sliders (60 % .. 95 %)
 * that the user nudges to match their physiology. Changes commit on
 * every interaction — DataStore writes are coalesced so this doesn't
 * thrash IO even with continuous slider drags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrZonesScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val cfg by vm.hrZoneConfig.collectAsStateWithLifecycle()
    var maxBpmText by remember(cfg.maxBpm) { mutableStateOf(cfg.maxBpm.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hr_zones_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.hr_zones_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = maxBpmText,
                onValueChange = { entry ->
                    maxBpmText = entry.filter { it.isDigit() }.take(3)
                    val parsed = maxBpmText.toIntOrNull()
                    if (parsed != null && parsed in MIN_REASONABLE_MAX_BPM..MAX_REASONABLE_MAX_BPM) {
                        vm.setHrZoneConfig(cfg.copy(maxBpm = parsed))
                    }
                },
                label = { Text(stringResource(R.string.hr_zones_max_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Zone preview row — coloured bands sized to the current
            // thresholds so the user sees what they're editing at a
            // glance.
            ZonePreviewBar(cfg = cfg)

            ZoneSlider(
                label = stringResource(R.string.hr_zones_z2_threshold),
                fraction = cfg.z2Fraction,
                onChange = { vm.setHrZoneConfig(cfg.copy(z2Fraction = it)) },
                color = SignalGreen,
                cfg = cfg,
            )
            ZoneSlider(
                label = stringResource(R.string.hr_zones_z3_threshold),
                fraction = cfg.z3Fraction,
                onChange = { vm.setHrZoneConfig(cfg.copy(z3Fraction = it)) },
                color = SignalYellow,
                cfg = cfg,
            )
            ZoneSlider(
                label = stringResource(R.string.hr_zones_z4_threshold),
                fraction = cfg.z4Fraction,
                onChange = { vm.setHrZoneConfig(cfg.copy(z4Fraction = it)) },
                color = SignalOrange,
                cfg = cfg,
            )
            ZoneSlider(
                label = stringResource(R.string.hr_zones_z5_threshold),
                fraction = cfg.z5Fraction,
                onChange = { vm.setHrZoneConfig(cfg.copy(z5Fraction = it)) },
                color = SignalRed,
                cfg = cfg,
            )
        }
    }
}

@Composable
private fun ZoneSlider(
    label: String,
    fraction: Float,
    onChange: (Float) -> Unit,
    color: Color,
    cfg: HrZoneConfig,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "%d%% · %d BPM".format((fraction * 100).toInt(), (cfg.maxBpm * fraction).toInt()),
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = fraction,
            onValueChange = { onChange(it) },
            valueRange = 0.40f..0.99f,
            steps = 58, // 0.01 increments across the range
        )
    }
}

@Composable
private fun ZonePreviewBar(cfg: HrZoneConfig) {
    val z1 = cfg.z2Fraction
    val z2 = cfg.z3Fraction - cfg.z2Fraction
    val z3 = cfg.z4Fraction - cfg.z3Fraction
    val z4 = cfg.z5Fraction - cfg.z4Fraction
    val z5 = 1f - cfg.z5Fraction
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Band(label = "Z1", color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f), weight = z1)
            Band(label = "Z2", color = SignalGreen, weight = z2)
            Band(label = "Z3", color = SignalYellow, weight = z3)
            Band(label = "Z4", color = SignalOrange, weight = z4)
            Band(label = "Z5", color = SignalRed, weight = z5)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Band(
    label: String,
    color: Color,
    weight: Float,
) {
    if (weight <= 0f) return
    Box(
        modifier = Modifier
            .weight(weight.coerceAtLeast(0.01f))
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Black)
    }
    Spacer(Modifier.size(4.dp))
}

private const val MIN_REASONABLE_MAX_BPM = 120
private const val MAX_REASONABLE_MAX_BPM = 220
