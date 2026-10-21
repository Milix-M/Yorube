package com.hermes.client.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermes.client.ui.screens.SettingsScreen
import com.hermes.client.ui.theme.HermesAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveFlow_showsSaved() {
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        compose.onNodeWithText("Server").assertIsDisplayed()
        compose.onNodeWithTag("baseUrl").performTextInput("https://example.com:8642")
        compose.onNodeWithTag("apiKey").performTextInput("k")
        compose.onNodeWithTag("save").performScrollTo()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Saved ✓").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cloudflareSection_isShown() {
        compose.setContent {
            HermesAppTheme {
                SettingsScreen(onNavigateBack = {})
            }
        }
        assertTrue(
            compose.onAllNodesWithText("Client ID").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            compose.onAllNodesWithText("Client Secret").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            compose.onAllNodesWithText("Firebase manual setup (optional)")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
