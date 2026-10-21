package com.hermes.client.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.ui.screens.ConversationListScreen
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
class ConversationListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTitleAndNewChatButton() {
        compose.setContent {
            HermesAppTheme {
                ConversationListScreen(onOpenChat = {}, onOpenSettings = {})
            }
        }
        compose.onNodeWithText("Hermes").assertIsDisplayed()
        compose.onNodeWithTag("newChat").assertIsDisplayed()
    }
}
