package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HermesLightScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF444746),
    secondaryContainer = Color(0xFFE8F0FE),
    tertiary = Color(0xFF9B72CB),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE1E7F5),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    outlineVariant = Color(0xFFC4C7C5),
)

private val HermesDarkScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFC4C7C5),
    secondaryContainer = Color(0xFF2A2B2E),
    tertiary = Color(0xFF9B72CB),
    surface = Color(0xFF131314),
    surfaceVariant = Color(0xFF1E1F20),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outlineVariant = Color(0xFF444746),
)

private val HermesShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

private val HermesTypography = Typography(
    bodyLarge = Typography().bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 22.sp),
    labelSmall = Typography().labelSmall.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Gemini-like: soft light surface + dark charcoal, brand-blue primary.
    // Expressive-style motion (fade-through nav, animated activity log,
    // springy typing indicator) is applied per component on stable Material3.
    MaterialTheme(
        colorScheme = if (darkTheme) HermesDarkScheme else HermesLightScheme,
        shapes = HermesShapes,
        typography = HermesTypography,
        content = content,
    )
}
