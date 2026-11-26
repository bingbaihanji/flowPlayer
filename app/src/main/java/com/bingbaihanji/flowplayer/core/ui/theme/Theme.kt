package com.bingbaihanji.flowplayer.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = EvPrimary,
    onPrimary = EvOnPrimary,
    primaryContainer = EvPrimaryContainer,
    onPrimaryContainer = EvOnPrimaryContainer,
    secondary = EvSecondary,
    onSecondary = EvOnSecondary,
    background = EvBackground,
    onBackground = EvOnBackground,
    surface = EvSurface,
    onSurface = EvOnSurface,
    onSurfaceVariant = EvOnSurfaceVariant,
    outline = EvOutline,
)

private val DarkColors = darkColorScheme(
    primary = EvPrimary,
    onPrimary = EvOnPrimary,
    primaryContainer = EvPrimaryContainer,
    onPrimaryContainer = EvOnPrimaryContainer,
    secondary = EvSecondary,
    onSecondary = EvOnSecondary,
    background = EvDarkBackground,
    onBackground = EvDarkOnSurface,
    surface = EvDarkSurface,
    onSurface = EvDarkOnSurface,
    onSurfaceVariant = EvDarkOnSurfaceVariant,
    outline = EvDarkOutline,
)

@Composable
fun FlowPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
