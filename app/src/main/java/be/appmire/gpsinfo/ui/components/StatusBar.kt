package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.lengthUnitLabel

@Composable
fun StatusBar(
    fix: FixStatus,
    accuracyMeters: Float?,
    unitSystem: UnitSystem = UnitSystem.Metric,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FixDot(fix)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(fix.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val dash = stringResource(R.string.placeholder_dash)
            val lengthLabel = lengthUnitLabel(unitSystem)
            val accuracyDisplay = accuracyMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
            MetricMini(
                stringResource(R.string.metric_accuracy),
                if (accuracyDisplay != null) "±${accuracyDisplay.toInt()}$lengthLabel" else dash,
            )
        }
    }
}

@Composable
private fun FixDot(fix: FixStatus) {
    val c = when (fix) {
        FixStatus.NO_FIX -> SignalRed
        FixStatus.TWO_D -> SignalOrange
        FixStatus.THREE_D -> SignalGreen
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(c)
    )
}

@Composable
private fun MetricMini(label: String, value: String) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

fun snrColor(snr: Float): Color = when {
    snr <= 0f -> Color.Gray
    snr < 20f -> SignalRed
    snr < 30f -> SignalOrange
    snr < 40f -> SignalYellow
    else -> SignalGreen
}
