package com.hermes.client.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import com.hermes.client.R

/** Gemini brand gradient: blue -> violet -> rose. Shared by hero, avatar, send button. */
val GeminiBlue = Color(0xFF4285F4)
val GeminiViolet = Color(0xFF9B72CB)
val GeminiRose = Color(0xFFD96570)

@Composable
fun geminiBrush(): Brush = Brush.linearGradient(listOf(GeminiBlue, GeminiViolet, GeminiRose))

@Composable
fun geminiHorizontalBrush(): Brush =
    Brush.horizontalGradient(listOf(GeminiBlue, GeminiViolet, GeminiRose))

/** Small gradient spark avatar used for assistant rows, drawer header and empty state. */
@Composable
fun GeminiSparkAvatar(
    size: Dp = 28.dp,
    iconSize: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(geminiBrush()),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_hero_spark),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Headline text with the Gemini gradient wash. */
@Composable
fun geminiHeadlineStyle() = MaterialTheme.typography.headlineSmall.copy(
    brush = geminiHorizontalBrush(),
)
