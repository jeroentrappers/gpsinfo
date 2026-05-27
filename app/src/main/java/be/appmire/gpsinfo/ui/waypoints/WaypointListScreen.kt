package be.appmire.gpsinfo.ui.waypoints

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.Waypoint
import be.appmire.gpsinfo.data.model.WaypointMedia
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captured-waypoint list. Newest first. Each row shows position,
 * timestamp, note, and (for media waypoints) an inline preview + a
 * share action. Photo thumbnails decode lazily; voice notes play
 * via a single shared [MediaPlayer] so only one ever plays at a time.
 *
 * The FAB opens the capture sheet so a user can drop a new waypoint
 * without leaving this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointListScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()

    var showCapture by remember { mutableStateOf(false) }
    val player = remember { SharedAudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.waypoint_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCapture = true }) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = stringResource(R.string.waypoint_capture_title))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val sorted = remember(waypoints) { waypoints.sortedByDescending { it.timeMillis } }
        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.waypoint_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.id }) { w ->
                    WaypointRow(
                        waypoint = w,
                        mediaFile = { name -> vm.waypointMediaFile(name) },
                        player = player,
                        onShare = { share(context, w, vm) },
                        onDelete = { vm.deleteWaypoint(w.id) },
                    )
                }
            }
        }
    }

    if (showCapture) {
        WaypointCaptureSheet(vm = vm, onDismiss = { showCapture = false })
    }
}

@Composable
private fun WaypointRow(
    waypoint: Waypoint,
    mediaFile: (String) -> java.io.File,
    player: SharedAudioPlayer,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatStamp(waypoint.timeMillis),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "%.6f, %.6f".format(
                            Locale.US, waypoint.latDeg, waypoint.lonDeg,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.waypoint_share))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.waypoint_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (waypoint.note.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(waypoint.note, style = MaterialTheme.typography.bodyMedium)
            }
            when (val m = waypoint.media) {
                WaypointMedia.None -> Unit
                is WaypointMedia.Photo -> {
                    Spacer(Modifier.height(8.dp))
                    PhotoThumb(file = mediaFile(m.fileName))
                }
                is WaypointMedia.Voice -> {
                    Spacer(Modifier.height(8.dp))
                    VoiceRow(
                        file = mediaFile(m.fileName),
                        durationMs = m.durationMs,
                        player = player,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(file: java.io.File) {
    // Defer decode to a remember block so scrolling doesn't decode the
    // full JPEG. inSampleSize=4 yields a ~1 MP thumbnail — plenty for
    // a row-sized preview and 16× lighter to keep in memory.
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        if (!file.exists()) return@remember null
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text(
            text = stringResource(R.string.waypoint_photo_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun VoiceRow(
    file: java.io.File,
    durationMs: Long,
    player: SharedAudioPlayer,
) {
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(file) {
        onDispose { if (playing) { player.stop(); playing = false } }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = {
                if (playing) {
                    player.stop(); playing = false
                } else {
                    player.play(file) { playing = false }
                    playing = true
                }
            },
        ) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.waypoint_voice_stop else R.string.waypoint_voice_play,
                ),
            )
        }
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private class SharedAudioPlayer {
    private var player: MediaPlayer? = null
    fun play(file: java.io.File, onComplete: () -> Unit) {
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                player?.runCatching { release() }
                player = null
                onComplete()
            }
            p.prepare()
            p.start()
            player = p
        } catch (_: Throwable) {
            p.runCatching { release() }
            player = null
            onComplete()
        }
    }
    fun stop() {
        player?.runCatching { stop() }
        player?.runCatching { release() }
        player = null
    }
    fun release() = stop()
}

private fun share(
    context: android.content.Context,
    waypoint: Waypoint,
    vm: DashboardViewModel,
) {
    val geo = "geo:%.6f,%.6f?q=%.6f,%.6f".format(
        Locale.US,
        waypoint.latDeg, waypoint.lonDeg,
        waypoint.latDeg, waypoint.lonDeg,
    )
    val body = buildString {
        append(geo)
        if (waypoint.note.isNotEmpty()) {
            append("\n\n"); append(waypoint.note)
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        when (val m = waypoint.media) {
            WaypointMedia.None -> {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            }
            is WaypointMedia.Photo -> {
                type = "image/jpeg"
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    vm.waypointMediaFile(m.fileName),
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            is WaypointMedia.Voice -> {
                type = "audio/mp4"
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    vm.waypointMediaFile(m.fileName),
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.waypoint_share_chooser_title)),
    )
}

private fun formatStamp(ms: Long): String =
    SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val secs = (ms / 1000L).coerceAtLeast(0L)
    val m = secs / 60L
    val s = secs % 60L
    return "%d:%02d".format(Locale.ROOT, m, s)
}
