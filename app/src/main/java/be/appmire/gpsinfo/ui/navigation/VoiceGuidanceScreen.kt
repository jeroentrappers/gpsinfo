package be.appmire.gpsinfo.ui.navigation

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.about.AppLanguages
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale

/**
 * Voice-guidance settings: master on/off, concise vs detailed verbosity,
 * the spoken-instruction language, a live "Test voice" button, and help
 * to install / activate Android's Text-to-Speech (the engine behind the
 * spoken directions). Self-contained — reads/writes [DashboardViewModel];
 * owns a throwaway [TextToSpeech] purely for the test.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VoiceGuidanceScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by vm.voiceGuidanceEnabled.collectAsStateWithLifecycle()
    val verbose by vm.voiceVerbose.collectAsStateWithLifecycle()
    val languageTag by vm.voiceLanguageTag.collectAsStateWithLifecycle()

    // A throwaway TTS engine for the Test button; torn down with the screen.
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context.applicationContext, null)
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    var showLangDialog by remember { mutableStateOf(false) }

    val currentLangLabel = AppLanguages.all
        .firstOrNull { AppLanguages.matches(it.tag, languageTag) }
        ?.let { if (it.tag == null) stringResource(R.string.settings_voice_language_default) else it.endonym }
        ?: stringResource(R.string.settings_voice_language_default)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_voice_guidance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToggleRow(
                R.string.settings_voice_enabled,
                R.string.settings_voice_enabled_body,
                enabled, vm::setVoiceGuidanceEnabled,
            )
            ToggleRow(
                R.string.settings_voice_detailed,
                R.string.settings_voice_detailed_body,
                verbose, vm::setVoiceVerbose,
            )

            // Instruction language.
            LinkRow(
                title = stringResource(R.string.settings_voice_language),
                value = currentLangLabel,
            ) { showLangDialog = true }

            // Test voice.
            Button(
                onClick = {
                    val engine = tts ?: return@Button
                    runCatching {
                        engine.language = languageTag?.takeIf { it.isNotBlank() }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: context.resources.configuration.locales[0]
                    }
                    engine.speak(
                        context.getString(R.string.settings_voice_test_phrase),
                        TextToSpeech.QUEUE_FLUSH, null, "voice-test",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_voice_test))
            }

            // Install / activate TTS help.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_voice_install_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.settings_voice_install_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { openTtsSettings(context) }) {
                        Text(stringResource(R.string.settings_voice_open_tts))
                    }
                }
            }
        }
    }

    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            confirmButton = {
                TextButton(onClick = { showLangDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
            title = { Text(stringResource(R.string.settings_voice_language)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AppLanguages.all.forEach { lang ->
                        val label = if (lang.tag == null) {
                            stringResource(R.string.settings_voice_language_default)
                        } else {
                            lang.endonym
                        }
                        val selected = AppLanguages.matches(lang.tag, languageTag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setVoiceLanguageTag(lang.tag)
                                    showLangDialog = false
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

@Composable
private fun ToggleRow(titleRes: Int, bodyRes: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!value) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openTtsSettings(context: android.content.Context) {
    // Android's Text-to-speech output settings. The action is stable across
    // versions but not a public constant; fall back to general settings.
    val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(tts) }.isFailure) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
