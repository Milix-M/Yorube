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
        compose.mainClock.autoAdvance = true
        server.shutdown()
    }

    @Test
    fun openAndDelete_flow() {
        // Dispatcher (not a fixed queue): the list auto-refreshes on resume,
        // so the number of GETs is not fixed. DELETE must still go through.
        val deleted = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.method == "DELETE" -> {
                        deleted.set(true)
                        MockResponse().setBody("{}")
                    }
                    (request.path ?: "").startsWith("/api/sessions") ->
                        MockResponse().setBody(if (deleted.get()) """{"sessions":[]}"""
                            else """{"sessions":[{"id":"s1","title":"Hello"}]}""")
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
        // Let the IO-backed initial fetch finish before advancing its loading animation.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesAppTheme {
                ConversationListScreen(onOpenChat = { id, _ -> opened = id })
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodes(hasText("Hello")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello").performClick()
        assertEquals("s1", opened)

        compose.onNodeWithText("Hello").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(1_000)
        // Long-press now opens the options sheet (pin / delete), then delete confirms.
        assertTrue(
            compose.onAllNodesWithText("Conversation options").fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag("deleteOption").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(
            compose.onAllNodesWithText("Delete conversation?").fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText("Cancel").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Hello").assertIsDisplayed()

        compose.onNodeWithText("Hello").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("deleteOption").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Delete").performClick()
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodes(hasText("Hello")).fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Throwable) {
            val visibleText = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.Text)).fetchSemanticsNodes().map {
                it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { text -> text.text }
            }
            throw AssertionError("DELETE received=${deleted.get()}, requests=${server.requestCount}, text=$visibleText", failure)
        }
        val paths = mutableListOf<String>()
        repeat(3) {
            val req = server.takeRequest(5, TimeUnit.SECONDS) ?: return@repeat
            paths.add(req.method + " " + (req.path ?: ""))
        }
        assertTrue(paths.any { it.startsWith("DELETE /api/sessions/s1") })
    }
}
