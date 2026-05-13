package com.appmire.gpsinfo.ui.theme

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

@Composable
fun GPSinfoTheme(
    forceDark: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = forceDark ?: systemDark
    val context = LocalContext.current
    val colors = when {
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
