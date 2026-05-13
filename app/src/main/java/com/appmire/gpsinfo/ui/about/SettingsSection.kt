package com.appmire.gpsinfo.ui.about

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem

/**
 * Compact settings block: unit-system selector (3-way segmented) + a
 * language picker that opens a dialog. Language switching only takes
 * effect on Android 13+ — on older versions LocaleManager isn't
 * available and the row is hidden.
 */
@Composable
fun SettingsSection(
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.screen_settings).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.padding(top = 12.dp))

            UnitsRow(unitSystem = unitSystem, onChange = onUnitSystemChange)

            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.padding(top = 16.dp))
                LanguageRow()
            }
        }
    }
}

@Composable
private fun UnitsRow(
    unitSystem: UnitSystem,
    onChange: (UnitSystem) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_units_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 6.dp))
    val options = listOf(
        UnitSystem.Metric to R.string.unit_system_metric,
        UnitSystem.Imperial to R.string.unit_system_imperial,
        UnitSystem.Nautical to R.string.unit_system_nautical,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (value, labelRes) ->
            SegmentedButton(
                selected = unitSystem == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private data class AppLanguage(val tag: String?, val endonym: String)

private val supportedLanguages = listOf(
    AppLanguage(null, ""),
    AppLanguage("en", "English"),
    AppLanguage("cs", "Čeština"),
    AppLanguage("de", "Deutsch"),
    AppLanguage("es", "Español"),
    AppLanguage("fr", "Français"),
    AppLanguage("it", "Italiano"),
    AppLanguage("nl", "Nederlands"),
    AppLanguage("pl", "Polski"),
    AppLanguage("pt-BR", "Português (Brasil)"),
    AppLanguage("ru", "Русский"),
    AppLanguage("tr", "Türkçe"),
    AppLanguage("ja", "日本語"),
)

@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val localeManager = remember(context) {
        context.getSystemService(LocaleManager::class.java)
    } ?: return  // System service should always exist on API 33+; bail if not.
    var showDialog by remember { mutableStateOf(false) }

    val currentTag: String? = remember(localeManager) {
        val list = localeManager.applicationLocales
        if (list.isEmpty) null else list[0].toLanguageTag()
    }
    val systemDefaultLabel = stringResource(R.string.language_system)
    val currentLabel = supportedLanguages
        .firstOrNull { matches(it.tag, currentTag) }
        ?.let { if (it.tag == null) systemDefaultLabel else it.endonym }
        ?: systemDefaultLabel

    Text(
        text = stringResource(R.string.settings_language_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 6.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = currentLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
            title = { Text(stringResource(R.string.settings_language_label)) },
            text = {
                // The list is taller than a typical dialog content; wrap in a
                // scroll so all 13 options stay reachable on short screens.
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    supportedLanguages.forEach { lang ->
                        val label = if (lang.tag == null) systemDefaultLabel else lang.endonym
                        val selected = matches(lang.tag, currentTag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val list = if (lang.tag.isNullOrEmpty())
                                        LocaleList.getEmptyLocaleList()
                                    else
                                        LocaleList.forLanguageTags(lang.tag)
                                    localeManager.applicationLocales = list
                                    showDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}

/** BCP47 tags can be returned with either dash or region-script variants;
 *  match leniently so "pt-BR" in our list also matches "pt-Latn-BR" etc. */
private fun matches(option: String?, current: String?): Boolean {
    if (option == null) return current.isNullOrEmpty()
    if (current.isNullOrEmpty()) return false
    val normOption = option.lowercase().replace('_', '-')
    val normCurrent = current.lowercase().replace('_', '-')
    return normCurrent == normOption ||
        normCurrent.startsWith("$normOption-") ||
        normOption.startsWith("$normCurrent-")
}
