package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// App theme on the established Material 3 component kit (Google's official
// design system, not hand-built): restrained single-accent palette, flat
// surfaces, no gradients.

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEDF),
    onPrimaryContainer = Color(0xFF0A3D2E),
    secondary = Color(0xFF0E7C5B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE8ECE9),
    onSurfaceVariant = Color(0xFF414943),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BC8A3),
    onPrimary = Color(0xFF06281D),
    primaryContainer = Color(0xFF0A5C44),
    onPrimaryContainer = Color(0xFFCDEEDF),
    secondary = Color(0xFF5BC8A3),
    onSecondary = Color(0xFF06281D),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE7ECEB),
    surface = Color(0xFF1C1F1E),
    onSurface = Color(0xFFE7ECEB),
    surfaceVariant = Color(0xFF2A332F),
    onSurfaceVariant = Color(0xFFBFC9C3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF5C1512),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
