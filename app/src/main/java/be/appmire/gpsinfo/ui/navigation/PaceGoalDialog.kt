package be.appmire.gpsinfo.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.util.paceUnitLabel

/**
 * Goal-editor dialog. Two numeric inputs — minutes and seconds — so the
 * user enters "5" + "30" rather than a single combined `Float` that
 * would need to disambiguate decimal-vs-minutes-vs-seconds at parse
 * time. Confirms via the M3 confirmButton slot; an optional Clear
 * action removes any active goal.
 */
@Composable
fun PaceGoalDialog(
    initialSecondsPerUnit: Float?,
    unitSystem: UnitSystem,
    onDismiss: () -> Unit,
    onConfirm: (Float?) -> Unit,
) {
    val initMin = ((initialSecondsPerUnit ?: 0f) / 60f).toInt()
    val initSec = ((initialSecondsPerUnit ?: 0f) - initMin * 60).toInt().coerceIn(0, 59)
    var minutesText by remember { mutableStateOf(if (initialSecondsPerUnit != null) initMin.toString() else "") }
    var secondsText by remember { mutableStateOf(if (initialSecondsPerUnit != null) "%02d".format(initSec) else "") }

    val minutes = minutesText.toIntOrNull()
    val seconds = secondsText.toIntOrNull()
    val valid = minutes != null && seconds != null && seconds in 0..59 &&
        (minutes > 0 || seconds > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_goal_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.nav_goal_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text(stringResource(R.string.nav_goal_minutes_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text(stringResource(R.string.nav_goal_seconds_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = paceUnitLabel(unitSystem),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        val total = (minutes ?: 0) * 60 + (seconds ?: 0)
                        onConfirm(total.toFloat())
                    }
                },
                enabled = valid,
            ) {
                Text(stringResource(R.string.trail_save))
            }
        },
        dismissButton = {
            Row {
                if (initialSecondsPerUnit != null) {
                    TextButton(onClick = { onConfirm(null) }) {
                        Text(stringResource(R.string.nav_clear_pace_goal))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

