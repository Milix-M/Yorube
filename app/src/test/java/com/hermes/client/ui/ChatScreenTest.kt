package com.hermes.client.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
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
    fun assistantAvatarHasUsableDefaultDimensions() {
        compose.setContent {
            HermesAppTheme { com.hermes.client.ui.theme.GeminiSparkAvatar(modifier = Modifier.testTag("avatar")) }
        }
        compose.onNodeWithTag("avatar").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
    }

    @Test
    fun emptySettingsSend_showsConfigError() {
        clearSettings()
        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onNavigateBack = {})
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
                ChatScreen(sessionId = null, onNavigateBack = {})
            }
        }
        // Suggestion chips were removed: only the headline remains, no subtitle.
        compose.onNodeWithText(app.getString(R.string.empty_chat_title)).assertIsDisplayed()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun attachButton_isEnabledWithRegistryOwner() {
        // The attach picker registers against the host activity: normal
        // hosts keep it enabled (the null-owner path degrades silently).
        clearSettings()
        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("attachButton").assertIsEnabled()
    }

    @Test
    fun slashHelpCompletesCommandWithoutSendingAndStaysVisibleForArguments() {
        clearSettings()
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
        compose.onNodeWithTag("chatInput").performTextInput("/mo")
        compose.onNodeWithTag("slashHint-model").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chatInput").assertTextEquals("/model ")
        compose.onNodeWithText("Show models or select a model for future runs.").assertIsDisplayed()
        compose.onNodeWithTag("chatInput").performTextInput("provider/model")
        compose.onNodeWithTag("chatInput").assertTextEquals("/model provider/model")
        compose.onNodeWithTag("slashHint-model").assertIsDisplayed()
        compose.onNodeWithText("Base URL and API key are not set. Open Settings.").assertDoesNotExist()
        compose.onNodeWithTag("chatInput").performTextReplacement("ordinary message")
        compose.onNodeWithTag("slashCommandHints").assertDoesNotExist()
    }

    @Test
    fun aliasHelpPreservesArgumentsOnSelectionAndClearsWithInput() {
        clearSettings()
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
        compose.onNodeWithTag("chatInput").performTextInput("/fork My conversation")
        compose.onNodeWithTag("slashHint-branch").performClick()
        compose.onNodeWithTag("chatInput").assertTextEquals("/branch My conversation")
        compose.onNodeWithTag("chatInput").performTextReplacement("")
        compose.onNodeWithTag("slashCommandHints").assertDoesNotExist()
    }

    @Test
    fun unsupportedCommandShowsUnavailableHelpWithoutCompletion() {
        clearSettings()
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
        compose.onNodeWithTag("chatInput").performTextInput("/compress")
        compose.onNodeWithText("Not available in Yorube.").assertIsDisplayed()
        compose.onNodeWithTag("slashHint-compress").assertIsNotEnabled()
        compose.onNodeWithTag("chatInput").assertTextEquals("/compress")
    }

    @Test
    fun bareSlashListScrollsAndResetsWhenFiltering() {
        clearSettings()
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
        compose.onNodeWithTag("chatInput").performTextInput("/")
        val versionIndex = com.hermes.client.ui.screens.slashCommandSuggestions("/").indexOfFirst { it.name == "version" }
        compose.onNodeWithTag("slashHintsList").performScrollToIndex(versionIndex)
        compose.onNodeWithTag("slashHint-version").assertIsDisplayed()
        compose.onNodeWithTag("chatInput").performTextReplacement("/que")
        compose.onNodeWithTag("slashHint-queue").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chatInput").assertTextEquals("/queue ")
        compose.onNodeWithText("Queue the next prompt, or edit the queue. Positions start at 1. Sends immediately when idle.")
            .assertIsDisplayed()
        compose.onNodeWithText("/queue edit <position> <text>", substring = true).assertExists()
        compose.onNodeWithTag("chatInput").performTextInput("edit 2 New prompt")
        compose.onNodeWithTag("chatInput").assertTextEquals("/queue edit 2 New prompt")
        compose.onNodeWithTag("slashHint-queue").assertExists()
    }

    @Test
    @Config(sdk = [30], qualifiers = "ja")
    fun commandHelpUsesJapaneseAndDisappearsAfterSending() {
        clearSettings()
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
        compose.onNodeWithTag("chatInput").performTextInput("/version")
        compose.onNodeWithText("Yorubeのバージョンを表示します。").assertIsDisplayed()
        compose.onNodeWithTag("sendButton").performClick()
        compose.onNodeWithTag("chatInput").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithTag("slashCommandHints").assertDoesNotExist()
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
