package be.appmire.gpsinfo.ui.share

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.QrEncoder
import java.util.Locale
import androidx.compose.foundation.Image

/**
 * Share-my-position screen. Renders the latest GNSS fix as a `geo:`
 * URI inside a QR code so a nearby device can scan and open a maps
 * intent. Also offers a system-share button for the same payload.
 *
 * The `geo:` scheme is the universal "open a coordinate" URI on
 * Android — Google Maps, OsmAnd, Organic Maps, magic-earth, all honour
 * it. We include a `?q=` so apps that prefer a search-style intent
 * still drop the pin at the right spot, and a 6-decimal precision
 * (~11 cm) so we don't leak more than the GPS actually knows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePositionScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val loc = state.gnss.location

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_position_title)) },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loc == null) {
                Text(
                    text = stringResource(R.string.share_position_waiting_fix),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val geoUri = remember(loc.latitude, loc.longitude) {
                buildGeoUri(loc.latitude, loc.longitude)
            }
            val bitmap = remember(geoUri) { QrEncoder.encode(geoUri) }

            Text(
                text = stringResource(R.string.share_position_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // White-on-black background regardless of theme — QR
            // scanners want high contrast and a known polarity. We
            // pin the inner background to white so dark-mode users
            // still get the standard black-on-white code.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier.padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            painter = BitmapPainter(
                                image = bitmap.asImageBitmap(),
                                // FilterQuality.None keeps the QR
                                // crisp at any scale — bilinear
                                // smoothing on a 1-bit code blurs the
                                // module edges and hurts scan-rate.
                                filterQuality = FilterQuality.None,
                            ),
                            contentDescription = stringResource(R.string.share_position_qr_alt),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.share_position_qr_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                        )
                    }
                }
            }

            Text(
                text = geoUri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )

            FilledTonalButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, geoUri)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            send,
                            context.getString(R.string.share_position_chooser_title),
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null)
                Text(
                    text = stringResource(R.string.share_position_share_button),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Build the `geo:` URI we encode. We use BOTH the path form
 * (`geo:lat,lon`) and a `?q=` parameter — Android Maps and OsmAnd
 * honour the path form, magic-earth and a few minor apps fall back
 * to `?q=`. Six decimal places give ~11 cm precision, which is
 * already at the noise floor of consumer GNSS.
 */
private fun buildGeoUri(lat: Double, lon: Double): String {
    val fmt = "%.6f"
    val latStr = fmt.format(Locale.US, lat)
    val lonStr = fmt.format(Locale.US, lon)
    return "geo:$latStr,$lonStr?q=$latStr,$lonStr"
}
