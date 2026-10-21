package com.hermes.client.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermes.client.ui.screens.SettingsScreen
import com.hermes.client.ui.theme.HermesAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithText("Saved ✓").assertIsDisplayed()
    }
}
