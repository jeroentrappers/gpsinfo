package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.data.model.FixStatus
import com.appmire.gpsinfo.ui.theme.SignalGreen
import com.appmire.gpsinfo.ui.theme.SignalOrange
import com.appmire.gpsinfo.ui.theme.SignalRed
import com.appmire.gpsinfo.ui.theme.SignalYellow
import com.appmire.gpsinfo.util.UnitConverter
import com.appmire.gpsinfo.util.lengthUnitLabel

@Composable
fun StatusBar(
    fix: FixStatus,
    accuracyMeters: Float?,
    satellitesInView: Int,
    satellitesInUse: Int,
    averageSnr: Float,
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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FixDot(fix)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(fix.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            val dash = stringResource(R.string.placeholder_dash)
            val lengthLabel = lengthUnitLabel(unitSystem)
            val accuracyDisplay = accuracyMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
            Divider()
            MetricMini(
                stringResource(R.string.metric_accuracy),
                if (accuracyDisplay != null) "±${accuracyDisplay.toInt()}$lengthLabel" else dash,
            )
            Divider()
            MetricMini(stringResource(R.string.metric_sats), "$satellitesInUse / $satellitesInView")
            Divider()
            MetricMini(
                stringResource(R.string.metric_snr),
                if (averageSnr > 0f) "%.1f".format(averageSnr) else dash,
                accent = snrColor(averageSnr)
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
private fun MetricMini(
    label: String,
    value: String,
    accent: Color? = null
) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = accent ?: MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .height(20.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

fun snrColor(snr: Float): Color = when {
    snr <= 0f -> Color.Gray
    snr < 20f -> SignalRed
    snr < 30f -> SignalOrange
    snr < 40f -> SignalYellow
    else -> SignalGreen
}
