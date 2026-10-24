package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// App theme on top of the established Material 2 component kit (Google's
// official design system, not hand-built, not Material 3): restrained
// single-accent palette, flat surfaces, no gradients.

private val LightPalette = lightColors(
    primary = Color(0xFF0E7C5B),
    primaryVariant = Color(0xFF0A5C44),
    secondary = Color(0xFF0E7C5B),
    secondaryVariant = Color(0xFF0A5C44),
    background = Color(0xFFFBFAF7),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1B),
    onSurface = Color(0xFF1A1C1B),
    onError = Color(0xFFFFFFFF),
)

private val DarkPalette = darkColors(
    primary = Color(0xFF5BC8A3),
    primaryVariant = Color(0xFF3A9A76),
    secondary = Color(0xFF5BC8A3),
    secondaryVariant = Color(0xFF3A9A76),
    background = Color(0xFF121413),
    surface = Color(0xFF1C1F1E),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF06281D),
    onSecondary = Color(0xFF06281D),
    onBackground = Color(0xFFE7ECEB),
    onSurface = Color(0xFFE7ECEB),
    onError = Color(0xFF2A0A0A),
)

@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colors = if (darkTheme) DarkPalette else LightPalette,
        content = content,
    )
}
