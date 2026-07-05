package be.appmire.gpsinfo.ui.charging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.charging.ChargingPlan
import be.appmire.gpsinfo.data.charging.ChargingStop
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The charging-stop plan for a chosen destination: runs [DashboardViewModel.computeChargingPlan]
 * against the live position, effective SoC (OBD or manual) and the EV profile,
 * then lists the stops with their LIVE price, power, arrival→target SoC, energy
 * and charge time. "Start" navigates through the stops; "Save" stores the trip.
 * Shared by the ad-hoc (destination picker) and saved-trip entry points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingPlanScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val target by vm.planTarget.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var plan by remember { mutableStateOf<ChargingPlan?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showSave by remember { mutableStateOf(false) }

    LaunchedEffectPlan(target?.destLat, target?.destLon) {
        val t = target ?: return@LaunchedEffectPlan
        loading = true
        plan = vm.computeChargingPlan(t)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target?.destName ?: stringResource(R.string.plan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (target != null) {
                        IconButton(onClick = { showSave = true }) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = stringResource(R.string.plan_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                target == null -> Centered(stringResource(R.string.plan_no_destination))
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> PlanBody(plan!!, onStart = {
                    val t = target ?: return@PlanBody
                    val stops = plan!!.stops.map { doubleArrayOf(it.charger.lat, it.charger.lon) } +
                        doubleArrayOf(t.destLat, t.destLon)
                    NavigationController.navigateVia(context, stops, destName = t.destName)
                    onStartNavigation()
                })
            }
        }
    }

    if (showSave) {
        val t = target
        var name by remember { mutableStateOf(t?.destName ?: "") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.plan_save_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plan_save_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (t != null) vm.saveTrip(name, t)
                    showSave = false
                }) { Text(stringResource(R.string.ev_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PlanBody(plan: ChargingPlan, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SummaryCard(plan)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(plan.stops) { StopRow(it) }
        }
        Button(
            onClick = onStart,
            enabled = plan.feasible,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(Icons.Outlined.Navigation, contentDescription = null)
            Text("  " + stringResource(R.string.plan_start))
        }
    }
}

@Composable
private fun SummaryCard(plan: ChargingPlan) {
    val text = when {
        !plan.feasible -> plan.message ?: stringResource(R.string.plan_infeasible)
        plan.reachableWithoutCharging -> stringResource(R.string.plan_no_stops)
        else -> {
            val stops = stringResource(R.string.plan_stops_count, plan.stops.size)
            val mins = stringResource(R.string.plan_charge_time, plan.totalChargeMinutes.roundToInt())
            val cost = plan.totalCostEur?.let {
                "  ·  " + stringResource(R.string.plan_total_cost, String.format(Locale.getDefault(), "%.2f", it))
            } ?: ""
            "$stops  ·  $mins$cost"
        }
    }
    Surface(
        Modifier.fillMaxWidth().padding(12.dp),
        color = if (plan.feasible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (plan.feasible) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StopRow(stop: ChargingStop) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stop.charger.name.ifBlank { stop.charger.source ?: "Charger" }, fontWeight = FontWeight.SemiBold)
            val powerLine = buildString {
                append("${stop.charger.powerKw.roundToInt()} kW")
                if (stop.charger.currentType.isNotBlank()) append(" ${stop.charger.currentType}")
                if (stop.charger.offRouteM > 0) {
                    append("  ·  ")
                    append(stringResource(R.string.plan_offroute, String.format(Locale.getDefault(), "%.1f", stop.charger.offRouteM / 1000.0)))
                }
            }
            Text(powerLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val detail = stringResource(
                R.string.plan_stop_detail,
                stop.arrivalSocPercent.roundToInt(),
                stop.targetSocPercent.roundToInt(),
                String.format(Locale.getDefault(), "%.1f", stop.kwhAdded),
                stop.chargeMinutes.roundToInt(),
            )
            val cost = stop.costEur?.let { "  ·  €" + String.format(Locale.getDefault(), "%.2f", it) } ?: ""
            Text(detail + cost, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** LaunchedEffect keyed on the destination so the plan recomputes when it changes. */
@Composable
private fun LaunchedEffectPlan(lat: Double?, lon: Double?, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(lat, lon) { block() }
}
