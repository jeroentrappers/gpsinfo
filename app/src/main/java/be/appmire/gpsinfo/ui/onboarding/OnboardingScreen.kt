package be.appmire.gpsinfo.ui.onboarding

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.ui.about.AppLanguages
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * First-run onboarding: capture the three preferences that shape the
 * whole app — Language (Android 13+ only), Units and Theme — each
 * pre-filled with a smart default so it's mostly "Get started". Replaces
 * the old persona picker; on finish the app lands on the Dashboard.
 *
 * Theme/units are written live via the view model as they're picked;
 * language uses the platform LocaleManager (which recreates the activity
 * to apply — onboarding simply re-renders in the chosen language).
 */
@Composable
fun OnboardingScreen(vm: DashboardViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboarding_prefs_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // ── Language (Android 13+ only) ──
        if (Build.VERSION.SDK_INT >= 33) {
            LanguageSection()
            Spacer(Modifier.height(24.dp))
        }

        // ── Units ──
        SectionLabel(stringResource(R.string.pref_units))
        val unitOptions = listOf(
            UnitSystem.Metric to R.string.unit_system_metric,
            UnitSystem.Imperial to R.string.unit_system_imperial,
            UnitSystem.Nautical to R.string.unit_system_nautical,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            unitOptions.forEachIndexed { i, (value, labelRes) ->
                SegmentedButton(
                    selected = value == state.unitSystem,
                    onClick = { vm.setUnitSystem(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = unitOptions.size),
                ) { Text(stringResource(labelRes)) }
            }
        }
        Spacer(Modifier.height(24.dp))

        // ── Theme ──
        SectionLabel(stringResource(R.string.pref_theme))
        val themeOptions = listOf(
            ThemeOverride.System to R.string.theme_system,
            ThemeOverride.Light to R.string.theme_light,
            ThemeOverride.Dark to R.string.theme_dark,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            themeOptions.forEachIndexed { i, (value, labelRes) ->
                SegmentedButton(
                    selected = value == state.themeOverride,
                    onClick = { vm.setThemeOverride(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = themeOptions.size),
                ) { Text(stringResource(labelRes)) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
        ?: return
    var currentTag by remember {
        val list = localeManager.applicationLocales
        mutableStateOf(if (list.isEmpty) null else list[0].toLanguageTag())
    }
    val systemLabel = stringResource(R.string.language_system)

    SectionLabel(stringResource(R.string.pref_language))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AppLanguages.all.forEach { lang ->
                val selected = AppLanguages.matches(lang.tag, currentTag)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = {
                                currentTag = lang.tag
                                localeManager.applicationLocales =
                                    if (lang.tag == null) {
                                        LocaleList.getEmptyLocaleList()
                                    } else {
                                        LocaleList.forLanguageTags(lang.tag)
                                    }
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        text = if (lang.tag == null) systemLabel else lang.endonym,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
