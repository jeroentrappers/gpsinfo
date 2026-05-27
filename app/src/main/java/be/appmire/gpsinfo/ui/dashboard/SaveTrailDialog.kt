package be.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.util.TrailNaming

/**
 * Stop-recording dialog. Three actions live in the confirmButton slot as
 * a single Row (M3 only supports one confirm + one dismiss slot, and
 * stacking them in dismissButton hides the destructive intent of
 * "Discard"). Discard is error-tinted; Cancel is the soft middle path;
 * Save is the recommended action, enabled when the name is non-blank.
 */
@Composable
internal fun SaveTrailDialog(
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
) {
    val default = remember { TrailNaming.defaultTrailName(System.currentTimeMillis()) }
    var name by remember { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.trail_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.trail_save_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.trail_discard))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = { onSave(name.trim()) },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.trail_save))
                }
            }
        },
    )
}
