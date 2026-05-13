package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.model.SunInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun TimeSunCard(nowMillis: Long, sun: SunInfo?) {
    val utcDate = remember("utcD") { SimpleDateFormat("yy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") } }
    val utcTime = remember("utcT") { SimpleDateFormat("HH:mm:ss", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") } }
    val locDate = remember("locD") { SimpleDateFormat("yy-MM-dd", Locale.getDefault()) }
    val locTime = remember("locT") { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeOnly = remember("locTm") { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    SectionCard(title = stringResource(R.string.section_time)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(modifier = Modifier.weight(1f)) {
                    Text("UTC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(utcTime.format(Date(nowMillis)), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text(utcDate.format(Date(nowMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Tile(modifier = Modifier.weight(1f)) {
                    Text("Local", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(locTime.format(Date(nowMillis)), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text(locDate.format(Date(nowMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(modifier = Modifier.weight(1f)) {
                    Text("Sunrise", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        sun?.sunriseEpochMillis?.let { timeOnly.format(Date(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Tile(modifier = Modifier.weight(1f)) {
                    Text("Solar noon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        sun?.solarNoonEpochMillis?.let { timeOnly.format(Date(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Tile(modifier = Modifier.weight(1f)) {
                    Text("Sunset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        sun?.sunsetEpochMillis?.let { timeOnly.format(Date(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricMini("Day length", sun?.dayLengthMillis?.let { formatDuration(it) } ?: "—")
                MetricMini("Sun elev", sun?.let { "%.1f°".format(it.sunElevationDeg) } ?: "—")
                MetricMini("Sun az", sun?.let { "%03d°".format(it.sunAzimuthDeg.toInt()) } ?: "—")
                MetricMini("Phase", sun?.let { if (it.isDaytime) "Day" else "Night" } ?: "—")
            }
        }
    }
}

@Composable
private fun MetricMini(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Tile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.CenterStart) {
            Column { content() }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return "%dh %02dm".format(h, m)
}
