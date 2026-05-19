package com.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.util.TrailNaming

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
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.trail_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.trail_discard))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
