package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.hermes.client.ui.ds.HermesDsTheme

@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    HermesDsTheme(darkTheme, content)
}
