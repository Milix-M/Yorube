package com.hermes.client.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.theme.HermesAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptySettingsSend_showsConfigError() {
        clearSettings()
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

    @Test
    fun emptyState_showsTitleWithoutSuggestions() {
        clearSettings()
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onOpenSettings = {}, onNavigateBack = {})
            }
        }
        // Suggestion chips were removed: only the headline remains, no subtitle.
        compose.onNodeWithText(app.getString(R.string.empty_chat_title)).assertIsDisplayed()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    private fun clearSettings() {
        // Hermetic: other tests' saves must not leak in; blank the store first.
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        kotlinx.coroutines.runBlocking {
            app.container.settingsStore.save(ClientSettings())
            val deadline = System.currentTimeMillis() + 10_000
            while (app.container.settingsStore.current().baseUrl.isNotBlank() ||
                app.container.settingsStore.current().apiKey.isNotBlank()
            ) {
                if (System.currentTimeMillis() >= deadline) error("timed out clearing settings")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
    }
}
