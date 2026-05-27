package be.appmire.gpsinfo.ui.waypoints

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.Waypoint
import be.appmire.gpsinfo.data.model.WaypointMedia
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Bottom sheet for capturing a waypoint at the current GPS fix.
 *
 * Three modes:
 *  - Text note: save with optional free-form text and no media.
 *  - Voice note: tap to record, tap again to stop. AAC-LC in M4A.
 *  - Photo: hands off to the system camera intent and stores the JPEG.
 *
 * Without a current fix the capture buttons collapse to a "waiting"
 * panel — position is sampled at the moment the user commits the
 * waypoint, not when the sheet opens, so a slow fix that lands mid-
 * sheet still works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointCaptureSheet(
    vm: DashboardViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by vm.state.collectAsStateWithLifecycle()
    val loc = state.gnss.location

    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val voiceState = remember { VoiceRecordingHolder() }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(voiceState.isRecording) {
        if (voiceState.isRecording) {
            while (voiceState.isRecording) {
                elapsedMs = SystemClock.elapsedRealtime() - voiceState.startedAt
                delay(250)
            }
        } else {
            elapsedMs = 0L
        }
    }
    DisposableEffect(Unit) {
        onDispose { voiceState.cancel() }
    }

    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val f = pendingPhotoFile
        pendingPhotoFile = null
        if (success && f != null && f.length() > 0L && loc != null) {
            vm.addWaypoint(
                Waypoint(
                    id = UUID.randomUUID().toString(),
                    timeMillis = System.currentTimeMillis(),
                    latDeg = loc.latitude,
                    lonDeg = loc.longitude,
                    eleMeters = if (loc.hasAltitude()) loc.altitude else null,
                    note = note.trim(),
                    media = WaypointMedia.Photo(fileName = f.name),
                ),
            )
            onDismiss()
        } else {
            // The user backed out or capture failed — clean up the
            // empty placeholder so we don't leak it.
            f?.delete()
        }
    }

    var audioGranted by remember {
        mutableStateOf(audioPermissionGranted(context))
    }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }

    ModalBottomSheet(
        onDismissRequest = {
            voiceState.cancel()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.waypoint_capture_title),
                style = MaterialTheme.typography.titleLarge,
            )

            if (loc == null) {
                NoFixHint()
                return@Column
            }

            FixSummary(latDeg = loc.latitude, lonDeg = loc.longitude)

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.waypoint_capture_note_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        vm.addWaypoint(
                            Waypoint(
                                id = UUID.randomUUID().toString(),
                                timeMillis = System.currentTimeMillis(),
                                latDeg = loc.latitude,
                                lonDeg = loc.longitude,
                                eleMeters = if (loc.hasAltitude()) loc.altitude else null,
                                note = note.trim(),
                                media = WaypointMedia.None,
                            ),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.waypoint_capture_save_note))
                }
                FilledTonalButton(
                    onClick = {
                        val mediaDir = vm.waypointMediaDir
                        val file = File(mediaDir, photoFileName())
                        file.parentFile?.mkdirs()
                        file.createNewFile()
                        pendingPhotoFile = file
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        cameraLauncher.launch(uri)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Camera, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.waypoint_capture_take_photo))
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = if (voiceState.isRecording)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            if (!audioGranted) {
                                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@Button
                            }
                            if (voiceState.isRecording) {
                                val finished = voiceState.stop() ?: return@Button
                                vm.addWaypoint(
                                    Waypoint(
                                        id = UUID.randomUUID().toString(),
                                        timeMillis = System.currentTimeMillis(),
                                        latDeg = loc.latitude,
                                        lonDeg = loc.longitude,
                                        eleMeters = if (loc.hasAltitude()) loc.altitude else null,
                                        note = note.trim(),
                                        media = WaypointMedia.Voice(
                                            fileName = finished.first.name,
                                            durationMs = finished.second,
                                        ),
                                    ),
                                )
                                onDismiss()
                            } else {
                                scope.launch {
                                    voiceState.start(
                                        context = context,
                                        file = File(vm.waypointMediaDir, voiceFileName()),
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (voiceState.isRecording)
                                Icons.Outlined.Stop else Icons.Outlined.Mic,
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (voiceState.isRecording)
                                stringResource(R.string.waypoint_capture_stop_voice)
                            else stringResource(R.string.waypoint_capture_record_voice),
                        )
                    }
                    if (voiceState.isRecording) {
                        Text(
                            text = formatDuration(elapsedMs),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else if (!audioGranted) {
                        Text(
                            text = stringResource(R.string.waypoint_capture_voice_perm_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Tiny holder around [MediaRecorder]. Lives outside Compose state
 * because the framework can't snapshot a recorder.
 */
private class VoiceRecordingHolder {
    var isRecording: Boolean = false
        private set
    var startedAt: Long = 0L
        private set
    private var recorder: MediaRecorder? = null
    private var targetFile: File? = null

    fun start(context: Context, file: File) {
        if (isRecording) return
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // 64 kbps mono ≈ 8 KB/s — small enough that a five-minute
            // ramble fits comfortably in backups, clear enough for
            // voice intelligibility.
            r.setAudioEncodingBitRate(64_000)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22_050)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            targetFile = file
            startedAt = SystemClock.elapsedRealtime()
            isRecording = true
        } catch (_: Throwable) {
            r.runCatching { release() }
            recorder = null
            targetFile = null
            isRecording = false
        }
    }

    fun stop(): Pair<File, Long>? {
        if (!isRecording) return null
        val r = recorder ?: return null
        val f = targetFile ?: return null
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        return try {
            r.stop()
            r.release()
            recorder = null
            targetFile = null
            isRecording = false
            f to durationMs
        } catch (_: Throwable) {
            r.runCatching { release() }
            recorder = null
            targetFile = null
            isRecording = false
            // Partial M4A is still playable — keep it. The user gets
            // whatever fragment made it to disk.
            f to durationMs
        }
    }

    fun cancel() {
        if (!isRecording) return
        val r = recorder
        val f = targetFile
        recorder = null
        targetFile = null
        isRecording = false
        r?.runCatching { stop() }
        r?.runCatching { release() }
        f?.delete()
    }
}

private fun audioPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun photoFileName(): String = "photo_${timestampSlug()}.jpg"

private fun voiceFileName(): String = "voice_${timestampSlug()}.m4a"

private fun timestampSlug(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private fun formatDuration(ms: Long): String {
    val secs = (ms / 1000L).coerceAtLeast(0L)
    val m = secs / 60L
    val s = secs % 60L
    return "%d:%02d".format(Locale.ROOT, m, s)
}

@Composable
private fun NoFixHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.LocationOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.waypoint_capture_needs_fix),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FixSummary(latDeg: Double, lonDeg: Double) {
    Text(
        text = stringResource(
            R.string.waypoint_capture_fix_summary,
            "%.6f".format(Locale.US, latDeg),
            "%.6f".format(Locale.US, lonDeg),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
}
