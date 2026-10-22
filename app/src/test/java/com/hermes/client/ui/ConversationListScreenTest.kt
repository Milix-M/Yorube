package com.hermes.client.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.screens.ConversationListScreen
import com.hermes.client.ui.theme.HermesAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
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

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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

    @Test
    fun groupsByUpdatedAt_showsTodayHeader() {
        val nowSec = System.currentTimeMillis() / 1000.0
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[{"id":"s1","title":"Hello","updated_at":$nowSec}]}"""
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        kotlinx.coroutines.runBlocking {
            val want = server.url("").toString().removeSuffix("/")
            app.container.settingsStore.save(ClientSettings(baseUrl = want, apiKey = "k"))
            val deadline = System.currentTimeMillis() + 10_000
            while (app.container.settingsStore.current().baseUrl != want) {
                if (System.currentTimeMillis() >= deadline) error("timed out saving settings")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
        compose.setContent {
            HermesAppTheme {
                ConversationListScreen(onOpenChat = {}, onOpenSettings = {})
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Hello").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Hello").assertIsDisplayed()
    }
}
