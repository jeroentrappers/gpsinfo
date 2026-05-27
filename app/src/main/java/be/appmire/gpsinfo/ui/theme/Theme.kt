package be.appmire.gpsinfo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    primaryContainer = AccentCyanDim,
    onPrimaryContainer = Color.Black,
    secondary = AccentCyanDim,
    onSecondary = Color.Black,
    background = NightBg,
    onBackground = Color.White,
    surface = NightSurface,
    onSurface = Color.White,
    surfaceVariant = NightSurfaceElev,
    onSurfaceVariant = NightTextDim,
    outline = NightOutline,
    outlineVariant = NightOutline
)

/**
 * Dim-red night chrome — preserves dark-adapted vision (sailor at the
 * helm, photographer at the eyepiece, anyone reading the dashboard in
 * a dark environment). Near-black background, every other surface
 * tinted in the dimmest legible red. Activated via a per-profile
 * [be.appmire.gpsinfo.data.model.ChromeStyle.NightDimRed].
 */
private val NightDimRedScheme = darkColorScheme(
    primary = Color(0xFFFF4040),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF400000),
    onPrimaryContainer = Color(0xFFFF8080),
    secondary = Color(0xFFCC2828),
    onSecondary = Color.Black,
    background = Color(0xFF050000),
    onBackground = Color(0xFFCC2020),
    surface = Color(0xFF0E0202),
    onSurface = Color(0xFFD03030),
    surfaceVariant = Color(0xFF1A0606),
    onSurfaceVariant = Color(0xFF8A1818),
    outline = Color(0xFF330000),
    outlineVariant = Color(0xFF220000),
    error = Color(0xFFFF6060),
)

private val LightScheme = lightColorScheme(
    primary = AccentTeal,
    onPrimary = Color.White,
    primaryContainer = AccentCyan,
    onPrimaryContainer = Color.Black,
    secondary = AccentTeal,
    onSecondary = Color.White,
    background = DayBg,
    onBackground = DayText,
    surface = DaySurface,
    onSurface = DayText,
    surfaceVariant = DaySurfaceElev,
    onSurfaceVariant = DayTextDim,
    outline = DayOutline,
    outlineVariant = DayOutline
)

/**
 * Theme entry point. [dynamicColor] is intentionally off by default —
 * the app leans on a bespoke instrument-panel identity (cyan / teal on
 * near-black, signal greens/oranges/reds for fix and SNR), and
 * Material You's per-device wallpaper-derived palette would dilute
 * that. Callers can opt in if they ever decide otherwise, but we
 * default to the painted scheme.
 */
@Composable
fun GPSinfoTheme(
    forceDark: Boolean? = null,
    dynamicColor: Boolean = false,
    nightDimRed: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = forceDark ?: systemDark
    val context = LocalContext.current
    val colors = when {
        nightDimRed -> NightDimRedScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val view = LocalView.current
    val lightBars = colors.background.luminance() > 0.5f
    if (!view.isInEditMode) {
        // Only fires when the value actually changes — previously this lived
        // in a SideEffect that ran after every successful composition.
        LaunchedEffect(lightBars) {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars
        }
    }
    MaterialTheme(colorScheme = colors, typography = GPSinfoTypography, content = content)
}
