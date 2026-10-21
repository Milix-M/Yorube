package com.hermes.client.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.theme.HermesAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptySettingsSend_showsConfigError() {
        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onOpenSettings = {}, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hello")
        compose.onNodeWithTag("sendButton").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Base URL and API key are not set. Open Settings.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
