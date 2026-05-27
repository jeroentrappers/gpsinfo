package be.appmire.gpsinfo.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale

/**
 * Personal stride-length calibration. The runner walks a known
 * distance while the step counter sensor (Sensor.TYPE_STEP_COUNTER)
 * accumulates. Derived stride = distance ÷ (totalSteps − baseline).
 *
 * Persisted to DataStore via [DashboardViewModel.setPersonalStrideMeters];
 * downstream features (indoor mode, sanity-checked step-derived distance)
 * read it off the same flow.
 *
 * Why a separate screen and not embedded in a recording: stride
 * calibration wants a known-good distance and a controlled walk, not a
 * casual run with noisy GPS at the start. Surface it on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrideCalibrationScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val savedStride by vm.personalStrideMeters.collectAsStateWithLifecycle()
    val stepFlow = remember { vm.stepCounterFlow() }
    val totalSteps by stepFlow.collectAsState(initial = 0L)

    var distanceText by remember { mutableStateOf("100") }
    var baselineSteps by remember { mutableStateOf<Long?>(null) }
    var live by remember { mutableStateOf(false) }

    // Capture the baseline as soon as the user taps Start. The flow
    // ticks at sensor rate (1-3 Hz when walking) so the baseline is
    // always a recent sample.
    LaunchedEffect(live) {
        if (live && baselineSteps == null) {
            baselineSteps = totalSteps
        }
    }

    val distance = distanceText.toFloatOrNull()
    val stepDelta = baselineSteps?.let { (totalSteps - it).coerceAtLeast(0L) } ?: 0L
    val derivedStride: Float? =
        if (distance != null && distance > 0 && stepDelta > 0) (distance / stepDelta) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stride_calibration_title)) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.stride_calibration_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (savedStride != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.stride_calibration_saved_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "%.2f m".format(Locale.ROOT, savedStride),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = distanceText,
                onValueChange = { distanceText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stride_calibration_distance_label)) },
                suffix = { Text("m") },
                singleLine = true,
                enabled = !live,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
            )

            if (live) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.stride_calibration_walking),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stepDelta.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = stringResource(R.string.stride_calibration_steps),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = derivedStride?.let { "%.2f m / step".format(Locale.ROOT, it) }
                                ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!live) {
                Button(
                    onClick = {
                        baselineSteps = null
                        live = true
                    },
                    enabled = distance != null && distance > 0f,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stride_calibration_start))
                }
            } else {
                Button(
                    onClick = {
                        live = false
                        derivedStride?.let { vm.setPersonalStrideMeters(it) }
                    },
                    enabled = derivedStride != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stride_calibration_save))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        live = false
                        baselineSteps = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }

            if (savedStride != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { vm.setPersonalStrideMeters(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stride_calibration_clear))
                }
            }
        }
    }
}
