package be.appmire.gpsinfo.ui.charging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.charging.SavedTrip
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * Saved charging-stop trips: activate one to (re)plan it live against current
 * SoC + prices and navigate, or plan a new trip via the destination picker.
 * The dedicated ("B") entry point; the ad-hoc ("A") one lives in the
 * destination picker's "Plan with charging" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onPlanNew: () -> Unit,
    onOpenTrip: (SavedTrip) -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadTrips() }
    val trips by vm.savedTrips.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trips_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            FilledTonalButton(
                onClick = onPlanNew,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("  " + stringResource(R.string.trips_new))
            }
            if (trips.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        stringResource(R.string.trips_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trips) { trip ->
                        TripRow(trip, onOpen = { onOpenTrip(trip) }, onDelete = { vm.deleteTrip(trip.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(trip: SavedTrip, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(trip.name, fontWeight = FontWeight.SemiBold)
                if (trip.destName.isNotBlank() && trip.destName != trip.name) {
                    Text(
                        trip.destName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.trips_delete))
            }
        }
    }
}
