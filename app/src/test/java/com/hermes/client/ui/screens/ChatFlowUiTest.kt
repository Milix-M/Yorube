package com.hermes.client.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatFlowUiTest {

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

    private fun frame(event: String, data: String) = "event: $event\ndata: $data"

    private fun events(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { it + "\n\n" }
        return MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
    }

    private suspend fun saveSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        val want = server.url("").toString().removeSuffix("/")
        app.container.settingsStore.save(ClientSettings(baseUrl = want, apiKey = "k"))
        val deadline = System.currentTimeMillis() + 10_000
        while (app.container.settingsStore.current().baseUrl != want) {
            if (System.currentTimeMillis() >= deadline) error("timed out saving settings")
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }

    @Test
    fun allMessageKinds_render() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"answer"}"""),
                frame("tool.started", """{"event":"tool.started","tool":"terminal","preview":"ls"}"""),
                frame(
                    "tool.completed",
                    """{"event":"tool.completed","tool":"terminal","duration":1.0,"error":true,"preview":"boom"}""",
                ),
                frame("message.interim", """{"event":"message.interim","text":"working","already_streamed":false}"""),
                frame("subagent.start", """{"event":"subagent.start","goal":"research"}"""),
                frame("subagent.complete", """{"event":"subagent.complete","status":"ok","summary":"done"}"""),
                frame("run.completed", """{"event":"run.completed","output":"final"}"""),
            )
        )
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onOpenSettings = {}, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        // Note: the terminal "final" is not rendered on its own — the streamed
        // "answer" already holds the reply. Waiting for it would time out.
        for (text in listOf("answer", "terminal", "boom", "working", "delegating", "child ok")) {
            try {
                compose.waitUntil(timeoutMillis = 15_000) {
                    compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: AssertionError) {
                compose.onRoot().printToLog("UI-DUMP-waiting-for:$text")
                throw e
            }
        }
    }

    @Test
    fun approvalDialog_postsDecision() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        // No further responses: the reconnect stalls at getRun, so no terminal
        // can clear runId before the click. Deterministic by construction.
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"part"}"""),
                frame(
                    "approval.request",
                    """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm","description":"d"}""",
                ),
            )
        )
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onOpenSettings = {}, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Approval needed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("rm").assertIsDisplayed()
        compose.onNodeWithText("once").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Approval needed").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun clarifyDialog_postsAnswer() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        // No further responses: the reconnect stalls at getRun, so no terminal
        // can clear runId before the Send click. Deterministic by construction.
        server.enqueue(
            events(
                frame(
                    "clarify.request",
                    """{"event":"clarify.request","request_id":"c1","execution_generation":2,"prompt":"which?"}""",
                ),
            )
        )
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onOpenSettings = {}, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Question").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("which?").assertIsDisplayed()
        compose.onNodeWithTag("clarifyInput").performTextInput("this one")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Question").fetchSemanticsNodes().isEmpty()
        }
    }
}
