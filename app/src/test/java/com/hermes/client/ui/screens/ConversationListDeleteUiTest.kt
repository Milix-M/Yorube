package com.hermes.client.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.theme.HermesAppTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
class ConversationListDeleteUiTest {

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
    fun openAndDelete_flow() {
        // Dispatcher (not a fixed queue): the list auto-refreshes on resume,
        // so the number of GETs is not fixed. DELETE must still go through.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.method == "DELETE" -> MockResponse().setBody("{}")
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody("""{"sessions":[{"id":"s1","title":"Hello"}]}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        kotlinx.coroutines.runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
            val want = server.url("").toString().removeSuffix("/")
            app.container.settingsStore.save(ClientSettings(baseUrl = want, apiKey = "k"))
            val deadline = System.currentTimeMillis() + 10_000
            while (app.container.settingsStore.current().baseUrl != want) {
                if (System.currentTimeMillis() >= deadline) error("timed out saving settings")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
        var opened: String? = null
        compose.setContent {
            HermesAppTheme {
                ConversationListScreen(onOpenChat = { id, _ -> opened = id })
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("Hello")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello").performClick()
        assertEquals("s1", opened)

        compose.onNodeWithText("Hello").performTouchInput { longClick() }
        // Long-press now opens the options sheet (pin / delete), then delete confirms.
        assertTrue(
            compose.onAllNodesWithText("Conversation options").fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag("deleteOption").performClick()
        assertTrue(
            compose.onAllNodesWithText("Delete conversation?").fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Hello").assertIsDisplayed()

        compose.onNodeWithText("Hello").performTouchInput { longClick() }
        compose.onNodeWithTag("deleteOption").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("Hello")).fetchSemanticsNodes().isEmpty()
        }
        val paths = mutableListOf<String>()
        repeat(3) {
            val req = server.takeRequest(5, TimeUnit.SECONDS) ?: return@repeat
            paths.add(req.method + " " + (req.path ?: ""))
        }
        assertTrue(paths.any { it.startsWith("DELETE /api/sessions/s1") })
    }
}
