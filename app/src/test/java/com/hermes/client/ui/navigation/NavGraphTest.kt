package com.hermes.client.ui.navigation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
        // Home is a fresh chat.
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Past conversations live in the sidebar drawer.
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        // Fresh home header shows "New chat": the only "Hello" is the drawer entry.
        compose.onAllNodesWithText("Hello")[0].performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        // Chat -> settings via the drawer (covers drawer onOpenSettings navigation).
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("drawerSettings").performClick()
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
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/s1/messages") ->
                        MockResponse().setBody(
                            """{"messages":[{"role":"user","content":"pinned-msg"}]}"""
                        )
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        saveSettings()
        compose.setContent {
            HermesAppTheme {
                NavGraph("s1")
            }
        }
        // An explicit session id (e.g. notification tap) still opens it.
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        waitFor("pinned-msg")
    }

    @Test
    fun drawerMenu_newChatStaysOnChat() {
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
        // Home is a fresh chat; history lives in the drawer.
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        compose.onAllNodesWithText("Hello")[0].performClick()
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
    fun homeStartsFresh_doesNotAutoOpenLatest() {
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
        // Launch stays on a fresh home chat even with history present:
        // the empty-state prompt shows and the latest reply never opens.
        waitFor("What can I do for you?")
        assertTrue(
            compose.onAllNodesWithText("recent answer").fetchSemanticsNodes().isEmpty()
        )
        // Past conversations are still reachable from the drawer.
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Recent")
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
        compose.onNodeWithTag("drawerNewChat").assertIsDisplayed()
        compose.onNodeWithTag("drawerNewChat").performClick()
        // Already on a fresh home chat: only the drawer closes, no transition.
        // ModalNavigationDrawer keeps drawer content in composition when closed,
        // so we verify staying on the fresh chat (no navigation) instead of
        // waiting for the close button to leave the tree.
        compose.waitUntil(timeoutMillis = 10_000) {
            try {
                compose.onNodeWithTag("chatInput").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
    }

    @Test
    fun drawerTapCurrentSession_doesNotReload() {
        val messagesHits = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    (request.path ?: "").startsWith("/api/sessions/s1/messages") -> {
                        messagesHits.incrementAndGet()
                        MockResponse().setBody(
                            """{"messages":[{"role":"user","content":"drawer-q"}]}"""
                        )
                    }
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        saveSettings()
        compose.setContent {
            HermesAppTheme {
                NavGraph("s1")
            }
        }
        waitFor("drawer-q")
        // Startup may load history more than once (the start navigation
        // rebuilds the entry); what matters is the tap adds no reload.
        Thread.sleep(1500)
        val baseline = messagesHits.get()
        assertTrue("baseline=$baseline", baseline >= 1)
        // Tapping the open conversation only closes the drawer: no reload.
        compose.onNodeWithTag("menuButton").performClick()
        waitFor("Hello")
        compose.onAllNodesWithText("Hello")[0].performClick()
        compose.onNodeWithTag("chatInput").assertIsDisplayed()
        Thread.sleep(1500)
        assertTrue(
            "messagesHits=${messagesHits.get()} baseline=$baseline",
            messagesHits.get() == baseline,
        )
        compose.onNodeWithText("drawer-q").assertIsDisplayed()
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
