package be.appmire.gpsinfo.ui.charging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.charging.EvVehicleProfile
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.math.roundToInt

private data class PlugChoice(val ocpi: String, val labelRes: Int)

private val PLUGS = listOf(
    PlugChoice("", R.string.ev_plug_any),
    PlugChoice("IEC_62196_T2_COMBO", R.string.ev_plug_ccs),
    PlugChoice("IEC_62196_T2", R.string.ev_plug_type2),
    PlugChoice("CHADEMO", R.string.ev_plug_chademo),
)

/**
 * EV vehicle profile + current charge. Feeds the live-priced charger queries
 * and the charging-stop planner: the battery/consumption physics plus the
 * starting SoC. The manual SoC is used only when live OBD SoC isn't available
 * (OBD, when connected, always wins).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvProfileScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val saved by vm.evVehicleProfile.collectAsStateWithLifecycle()
    val savedSoc by vm.evManualSoc.collectAsStateWithLifecycle()
    val cluster by vm.clusterData.collectAsStateWithLifecycle()
    val liveSoc = cluster.socPct?.roundToInt()

    // Local editable state, seeded once from the saved values.
    var usable by remember(saved) { mutableStateOf(trimNum(saved.usableKwh)) }
    var consumption by remember(saved) { mutableStateOf(trimNum(saved.consumptionKwh100)) }
    var maxDc by remember(saved) { mutableStateOf(trimNum(saved.maxDcKw)) }
    var arrival by remember(saved) { mutableStateOf(saved.minArrivalSocPercent.toFloat()) }
    var chargeCap by remember(saved) { mutableStateOf(saved.chargeToSocPercent.toFloat()) }
    var plug by remember(saved) { mutableStateOf(saved.plugType) }
    var manualSoc by remember(savedSoc) { mutableStateOf(savedSoc.toFloat()) }

    fun persist() {
        vm.setEvVehicleProfile(
            EvVehicleProfile(
                usableKwh = usable.toDoubleOrNull() ?: saved.usableKwh,
                consumptionKwh100 = consumption.toDoubleOrNull() ?: saved.consumptionKwh100,
                minArrivalSocPercent = arrival.roundToInt(),
                chargeToSocPercent = chargeCap.roundToInt(),
                plugType = plug,
                maxDcKw = maxDc.toDoubleOrNull() ?: saved.maxDcKw,
            ),
        )
        vm.setEvManualSoc(manualSoc.roundToInt())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ev_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = { persist(); onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { persist(); onBack() }) {
                        Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.ev_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.ev_current_charge)) {
                if (liveSoc != null) {
                    Text(
                        stringResource(R.string.ev_soc_live_fmt, liveSoc),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(stringResource(R.string.ev_soc_manual, manualSoc.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(value = manualSoc, onValueChange = { manualSoc = it }, valueRange = 0f..100f, steps = 99)
                Text(
                    stringResource(R.string.ev_soc_live_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.ev_battery_section)) {
                NumberField(usable, { usable = it }, R.string.ev_battery_kwh)
                NumberField(consumption, { consumption = it }, R.string.ev_consumption)
                NumberField(maxDc, { maxDc = it }, R.string.ev_max_dc)
            }

            Section(stringResource(R.string.ev_charging_section)) {
                Text(stringResource(R.string.ev_arrival_buffer, arrival.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(value = arrival, onValueChange = { arrival = it }, valueRange = 0f..30f, steps = 29)
                Text(stringResource(R.string.ev_charge_cap, chargeCap.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(value = chargeCap, onValueChange = { chargeCap = it }, valueRange = 50f..100f, steps = 49)
                Text(stringResource(R.string.ev_plug), style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PLUGS.forEach { p ->
                        FilterChip(
                            selected = plug == p.ocpi,
                            onClick = { plug = p.ocpi },
                            label = { Text(stringResource(p.labelRes)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun NumberField(value: String, onValue: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Drop a trailing ".0" so whole numbers show cleanly in the field. */
private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
