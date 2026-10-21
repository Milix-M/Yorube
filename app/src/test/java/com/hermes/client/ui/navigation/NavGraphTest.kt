package com.hermes.client.ui.navigation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
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
class NavGraphTest {

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

    private fun saveSettings() {
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
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun fullFlow_listToChatToSettingsAndBack() {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}"""))
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        saveSettings()
        compose.setContent {
            HermesAppTheme {
                NavGraph(null)
            }
        }
        waitFor("Hello")
        // List -> chat via item tap (covers onOpenChat navigation).
        compose.onNodeWithText("Hello").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Chat -> settings via gear (covers chat onOpenSettings navigation).
        compose.onNodeWithTag("settingsButton").performClick()
        waitFor("Settings")
        // Settings -> back to chat (covers settings onNavigateBack).
        compose.onNodeWithTag("backButton").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Chat -> back to list (covers chat onNavigateBack).
        compose.onNodeWithTag("backButton").performClick()
        compose.onNodeWithText("Hermes").assertIsDisplayed()
    }

    @Test
    fun chatRoute_rendersInput() {
        compose.setContent {
            HermesAppTheme {
                NavGraph("s1")
            }
        }
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun drawerMenu_newChatStaysOnChat() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/sessions" ->
                        MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}""")
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        saveSettings()
        compose.setContent {
            HermesAppTheme {
                NavGraph(null)
            }
        }
        waitFor("Hello")
        compose.onNodeWithText("Hello").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Open ChatGPT-like drawer and start a new chat from it.
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("newChat").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun drawerMenu_closeReturnsToChat() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/sessions" ->
                        MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}""")
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        saveSettings()
        compose.setContent {
            HermesAppTheme {
                NavGraph(null)
            }
        }
        waitFor("Hello")
        compose.onNodeWithText("Hello").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("closeDrawer").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }
}
