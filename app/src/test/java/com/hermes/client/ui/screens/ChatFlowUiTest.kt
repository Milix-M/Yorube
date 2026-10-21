package com.hermes.client.ui.screens

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
        app.container.settingsStore.save(
            ClientSettings(
                baseUrl = server.url("").toString().removeSuffix("/"),
                apiKey = "k",
            )
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (app.container.settingsStore.current().baseUrl.isEmpty()) {
            if (System.currentTimeMillis() >= deadline) error("timed out saving settings")
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }

    @Test
    fun approvalDialog_postsDecision() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"part"}"""),
                frame(
                    "approval.request",
                    """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm","description":"d"}""",
                ),
            )
        )
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        val terminal = events(frame("run.completed", """{"event":"run.completed","output":"done"}"""))
        terminal.setBodyDelay(10, TimeUnit.SECONDS)
        server.enqueue(terminal)
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
        val bodies = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            bodies.none { it.startsWith("/v1/runs/r1/approval") }
        ) {
            val req = server.takeRequest(1, TimeUnit.SECONDS) ?: continue
            bodies.add((req.path ?: "") + " " + req.body.readUtf8())
        }
        assertTrue(bodies.any { it.startsWith("/v1/runs/r1/approval") && it.contains("\"choice\":\"once\"") })
    }

    @Test
    fun clarifyDialog_postsAnswer() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "clarify.request",
                    """{"event":"clarify.request","request_id":"c1","execution_generation":2,"prompt":"which?"}""",
                ),
            )
        )
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        val clarifyTerminal =
            events(frame("run.completed", """{"event":"run.completed","output":"ok"}"""))
        clarifyTerminal.setBodyDelay(10, TimeUnit.SECONDS)
        server.enqueue(clarifyTerminal)
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
        val bodies = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            bodies.none { it.startsWith("/v1/runs/r1/clarify") }
        ) {
            val req = server.takeRequest(1, TimeUnit.SECONDS) ?: continue
            bodies.add((req.path ?: "") + " " + req.body.readUtf8())
        }
        assertTrue(bodies.any { it.startsWith("/v1/runs/r1/clarify") && it.contains("\"answer\":\"this one\"") })
    }
}
