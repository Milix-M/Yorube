package com.hermes.client.ui.navigation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.theme.HermesAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
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
    fun fullFlow_chatToDrawerToSettingsAndBack() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody(
                            """{"sessions":[{"id":"s1","title":"Hello"},{"id":"s2","title":"Other"}]}"""
                        )
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
        // Home is the latest chat ("Hello" is also the top bar title).
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Past conversations live in the sidebar drawer.
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        // Open the other row, which is unambiguous.
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Chat -> settings via gear (covers chat onOpenSettings navigation).
        compose.onNodeWithTag("settingsButton").performClick()
        waitFor("Settings")
        // Settings -> back to chat (covers settings onNavigateBack).
        compose.onNodeWithTag("backButton").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Chat has no back arrow by design: it must not exist on the home chat.
        assertTrue(
            compose.onAllNodesWithTag("backButton").fetchSemanticsNodes().isEmpty()
        )
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
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody(
                            """{"sessions":[{"id":"s1","title":"Hello"},{"id":"s2","title":"Other"}]}"""
                        )
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
        // Home is the latest chat; history lives in the drawer.
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Open ChatGPT-like drawer and start a new chat from it.
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("drawerNewChat").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun drawerMenu_closeReturnsToChat() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}""")
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
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        compose.onNodeWithTag("closeDrawer").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun homeShowsLatestChat() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/s2/messages") ->
                        MockResponse().setBody(
                            """{"messages":[{"role":"user","content":"q"},{"role":"assistant","content":"recent answer"}]}"""
                        )
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody(
                            """{"sessions":[{"id":"s2","title":"Recent"},{"id":"s1","title":"Old"}]}"""
                        )
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
        // Launch lands on the latest conversation, not an empty chat.
        // (Assistant markdown may split text nodes: match by substring.)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("recent answer", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithText("recent answer", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun freshHomeNewChatClosesDrawerWithoutNavigating() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody("""{"sessions":[]}""")
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
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("drawerNewChat").performClick()
        // Already on a fresh home chat: only the drawer closes, no transition.
        // (Closed drawer content stays composed off-screen, so assert hidden.)
        compose.onNodeWithTag("drawerNewChat").assertIsNotDisplayed()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun drawerSearch_opensSearchAndFilters() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/") ->
                        MockResponse().setBody("""{"messages":[]}""")
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody(
                            """{"sessions":[{"id":"s1","title":"Hello"},{"id":"s2","title":"Other"}]}"""
                        )
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
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("drawerSearch").performClick()
        // Search screen shows the input and both conversations.
        compose.onNodeWithTag("searchScreenInput").assertIsDisplayed()
        waitFor("Other")
        // Typing filters the history list down to the match.
        compose.onNodeWithTag("searchScreenInput").performTextInput("Hello")
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Other").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasTextExactly("Hello", includeEditableText = false)).assertIsDisplayed()
        // Back returns to the home chat.
        compose.onNodeWithTag("searchBack").performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }
}
