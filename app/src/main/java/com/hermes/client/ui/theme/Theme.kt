package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // ChatGPT-like: keep Material3 defaults (dynamic-friendly neutral),
    // only tighten shapes + line-height for chat readability.
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        shapes = HermesShapes,
        typography = HermesTypography,
        content = content,
    )
}
