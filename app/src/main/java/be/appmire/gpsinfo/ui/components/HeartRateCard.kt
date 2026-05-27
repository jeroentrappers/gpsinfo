package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.HeartRateState
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow

/**
 * Dashboard card showing the live BPM from a paired BLE heart-rate
 * monitor, colour-coded by the active [HrZoneConfig].
 */
@Composable
fun HeartRateCard(
    hrState: HeartRateState,
    zoneConfig: HrZoneConfig,
    onDisconnect: () -> Unit = {},
) {
    SectionCard(
        title = stringResource(R.string.hr_card_title),
        trailing = {
            // Tap-to-disconnect lives on the card itself so the user
            // doesn't have to go into Settings → HR pairing to stop the
            // live monitor. The persisted MAC stays — next app start
            // tries to reconnect.
            if (hrState is HeartRateState.Connected) {
                FilledTonalIconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Outlined.LinkOff,
                        contentDescription = stringResource(R.string.hr_card_disconnect),
                    )
                }
            }
        },
    ) {
        when (hrState) {
            HeartRateState.Idle -> StatusText(stringResource(R.string.hr_card_no_paired))
            HeartRateState.Scanning -> StatusText(stringResource(R.string.hr_pair_scanning))
            is HeartRateState.Connecting ->
                StatusText(stringResource(R.string.hr_status_connecting, hrState.deviceMac))
            is HeartRateState.Disconnected ->
                StatusText(stringResource(
                    R.string.hr_status_disconnected,
                    hrState.deviceName ?: hrState.deviceMac,
                ))
            is HeartRateState.Connected -> ConnectedBody(hrState, zoneConfig)
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectedBody(hrState: HeartRateState.Connected, cfg: HrZoneConfig) {
    val bpm = hrState.lastBpm
    if (bpm == null) {
        StatusText(stringResource(R.string.hr_card_waiting))
        return
    }
    val zone = cfg.zoneFor(bpm)
    val zoneColor = zoneColor(zone)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "$bpm",
                    style = MaterialTheme.typography.displayLarge,
                    color = zoneColor,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.hr_card_zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Z$zone",
                    style = MaterialTheme.typography.displaySmall,
                    color = zoneColor,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = hrState.deviceName ?: hrState.deviceMac,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ZoneStripe(cfg = cfg)
    }
}

/** Horizontal five-segment stripe sized by the zone fractions. Gives a
 *  small "where do my zones sit on the scale" reference under the BPM. */
@Composable
private fun ZoneStripe(cfg: HrZoneConfig) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        Segment(cfg.z2Fraction, zoneColor(1))
        Segment(cfg.z3Fraction - cfg.z2Fraction, zoneColor(2))
        Segment(cfg.z4Fraction - cfg.z3Fraction, zoneColor(3))
        Segment(cfg.z5Fraction - cfg.z4Fraction, zoneColor(4))
        Segment(1f - cfg.z5Fraction, zoneColor(5))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(weight: Float, color: Color) {
    if (weight <= 0f) return
    Box(
        modifier = Modifier
            .weight(weight.coerceAtLeast(0.001f))
            .height(8.dp)
            .background(color),
    )
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> SignalGreen.copy(alpha = 0.55f)
    2 -> SignalGreen
    3 -> SignalYellow
    4 -> SignalOrange
    else -> SignalRed
}
