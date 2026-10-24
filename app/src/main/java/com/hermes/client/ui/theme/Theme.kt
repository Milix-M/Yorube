package com.hermes.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.composables.ui.theme.ColorScheme
import com.composables.ui.theme.ComposablesTheme
import com.composables.ui.theme.LocalColorScheme

// App theme on the established Composables UI kit (not hand-built, not
// Material): the kit's own light/dark tokens for every component. No custom
// palette, no gradients.
@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColorScheme provides if (darkTheme) ColorScheme.Dark else ColorScheme.Light
    ) {
        ComposablesTheme(content)
    }
}
