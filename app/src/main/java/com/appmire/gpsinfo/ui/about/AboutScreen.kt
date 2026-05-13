package com.appmire.gpsinfo.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.util.IntentHelpers

private const val APPMIRE_URL = "https://appmire.be"
private const val PAYPAL_URL = "https://paypal.me/jeroentrappers"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
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
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(20.dp))

            // Brand mark. No standalone Appmire logo asset is shipped with
            // the app, so we build a typographic wordmark in Compose: the
            // existing satellite glyph in a coloured disc, a custom-spaced
            // "appmire" wordmark, and the tagline beneath. Tap anywhere
            // on the block to open https://appmire.be.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.Center)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { IntentHelpers.openUrl(context, APPMIRE_URL) },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LogoMark()
                    Spacer(Modifier.height(14.dp))
                    AppmireWordmark()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = APPMIRE_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Given to you for free.\nDonations appreciated.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            FilledTonalButton(
                onClick = { IntentHelpers.openUrl(context, PAYPAL_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = PAYPAL_URL.removePrefix("https://"),
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { IntentHelpers.openUrl(context, APPMIRE_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Visit appmire.be")
            }

            Spacer(Modifier.weight(1f, fill = true))

            // Build info footer — keeps the screen feeling complete and
            // gives the user something to quote in a bug report.
            Text(
                text = buildBadge(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LogoMark() {
    // Cyan disc with the satellite glyph. Stand-in for a missing brand
    // mark, but visually consistent with the rest of the app's iconography.
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_satellite),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp),
        )
    }
}

@Composable
private fun AppmireWordmark() {
    // Tight monospaced lowercase wordmark. The dot accent at the end
    // mirrors the kind of mark Appmire uses elsewhere; without an SVG to
    // import this is the closest approximation we can ship in-app.
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "appmire",
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 36.sp,
            letterSpacing = (-1).sp,
        )
        Text(
            text = ".",
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 36.sp,
        )
    }
}

@Composable
private fun buildBadge(): String {
    val context = LocalContext.current
    val pkg = context.packageName
    val pm = context.packageManager
    val version = try {
        pm.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }
    return "GPSinfo · v$version"
}

