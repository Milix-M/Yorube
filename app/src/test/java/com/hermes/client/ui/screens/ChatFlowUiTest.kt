package com.hermes.client.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.theme.HermesAppTheme
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
    fun additionalInstructionsCanBeSentWithDefaultKeyboardSettings() {
        val steered = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request.path.orEmpty())
                return when (request.path) {
                "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"r"}""")
                "/v1/runs/r" -> MockResponse().setBody("""{"status":"running","session_id":"s"}""")
                "/v1/runs/r/events" -> MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                "/v1/runs/r/steer" -> {
                    steered.set(request.body.readUtf8())
                    MockResponse().setBody("{}")
                }
                else -> MockResponse().setBody("{}")
                }
            }
        }
        kotlinx.coroutines.runBlocking { saveSettings() }
        val app = ApplicationProvider.getApplicationContext<HermesApp>()
        try {
            compose.setContent { HermesAppTheme { ChatScreen(sessionId = null, onSessionCreated = { accepted.set(true) }) } }
            compose.onNodeWithTag("chatInput").performTextInput("start")
            compose.onNodeWithTag("sendButton").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                accepted.get()
            }
            compose.onNodeWithTag("chatInput").performTextInput("change direction")
            compose.onNodeWithTag("chatInput").assertTextEquals("change direction")
            compose.onNodeWithTag("steerButton").assertIsEnabled().performClick()
            try { compose.waitUntil(timeoutMillis = 10_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                steered.get() != null
            } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Requests: $requests", e) }
            org.junit.Assert.assertEquals("""{"text":"change direction"}""", steered.get())
        } finally { compose.runOnUiThread { app.container.runStreams.close() } }
    }

    @Test
    fun copyAnswerShowsConfirmationAndWritesClipboard() {
        server.enqueue(MockResponse().setBody("""{"messages":[{"role":"assistant","content":"copy this answer"}]}"""))
        kotlinx.coroutines.runBlocking { saveSettings() }
        compose.setContent { HermesAppTheme { ChatScreen(sessionId = "s", onNavigateBack = {}) } }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("copy this answer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Copied").assertIsDisplayed()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        org.junit.Assert.assertEquals("copy this answer", clipboard.primaryClip!!.getItemAt(0).text)
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Copied").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
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
                ChatScreen(sessionId = null, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        // Tool/MCP activity is folded into one-line expandable rows: expand them first.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("activityToggle", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // New activity blocks stream in over time; expand each toggle exactly once
        // (re-clicking would collapse an already-expanded block again).
        var expandedToggles = 0
        for (attempt in 0 until 20) {
            val toggles = compose.onAllNodesWithTag("activityToggle", useUnmergedTree = true)
            val count = toggles.fetchSemanticsNodes().size
            // Pin the list to the bottom first: the centered jump button only
            // shows while scrolled up, and it can swallow taps meant for the
            // last toggle without raising an error.
            try {
                if (count > 0) toggles[count - 1].performScrollTo()
            } catch (_: Throwable) {
            }
            var allClicked = true
            for (i in expandedToggles until count) {
                try {
                    toggles[i].performClick()
                } catch (_: Throwable) {
                    allClicked = false
                    break
                }
            }
            if (allClicked) expandedToggles = count
            Thread.sleep(500)
            val done = listOf("terminal", "working", "delegating", "child ok").all { want ->
                compose.onAllNodesWithText(want, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            if (done) break
        }
        // Note: the terminal "final" is not rendered on its own — the streamed
        // "answer" already holds the reply. Waiting for it would time out.
        // Error tool results stay as visible error rows; the rest lives in the activity log.
        for (text in listOf("final", "terminal", "boom", "working", "delegating", "child ok")) {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun markdown_rendersBoldAndCodeBlock() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "message.delta",
                    """{"event":"message.delta","delta":"Here is **bold** text\n\n```kotlin\nval x = 1\n```"}""",
                ),
                frame("run.completed", """{"event":"run.completed","output":"Here is **bold** text\n\n```kotlin\nval x = 1\n```"}"""),
            )
        )
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = null, onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        for (text in listOf("Here is", "bold", "val x = 1", "kotlin")) {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun waitingControl(event: String, payload: String): java.util.concurrent.atomic.AtomicReference<String?> {
        val posted = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val subscriptions = AtomicInteger()
        val action = if (event == "approval.request") "approval" else "clarify"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"r1"}""")
                "/v1/runs/r1" -> MockResponse().setBody("""{"status":"running","session_id":"s1"}""")
                "/v1/runs/r1/events" -> if (subscriptions.getAndIncrement() == 0) events(frame(event, payload))
                    else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                "/v1/runs/r1/$action" -> {
                    posted.set(request.body.readUtf8())
                    MockResponse().setBody("{}")
                }
                else -> MockResponse().setBody("{}")
            }
        }
        return posted
    }

    @Test
    fun approvalDialog_postsDecision() {
        val posted = waitingControl("approval.request",
            """{"request_id":"a1","execution_generation":3,"command":"rm","description":"d"}""")
        kotlinx.coroutines.runBlocking { saveSettings() }
        val app = ApplicationProvider.getApplicationContext<HermesApp>()
        try {
            compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
            compose.onNodeWithTag("chatInput").performTextInput("hi")
            compose.onNodeWithTag("sendButton").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Approval needed").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("rm").assertIsDisplayed()
            compose.onNodeWithText("Allow once").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Approval needed").fetchSemanticsNodes().isEmpty()
            }
            org.junit.Assert.assertEquals(com.hermes.client.data.api.ApprovalAnswer("a1", 3, "once"),
                com.hermes.client.data.api.HermesJson.decodeFromString<com.hermes.client.data.api.ApprovalAnswer>(posted.get()!!))
        } finally { compose.runOnUiThread { app.container.runStreams.close() } }
    }

    @Test
    fun clarifyDialog_postsAnswer() {
        val posted = waitingControl("clarify.request",
            """{"request_id":"c1","execution_generation":2,"prompt":"which?"}""")
        kotlinx.coroutines.runBlocking { saveSettings() }
        val app = ApplicationProvider.getApplicationContext<HermesApp>()
        try {
            compose.setContent { HermesAppTheme { ChatScreen(sessionId = null) } }
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
            org.junit.Assert.assertEquals(com.hermes.client.data.api.ClarifyRequest("c1", 2, "this one"),
                com.hermes.client.data.api.HermesJson.decodeFromString<com.hermes.client.data.api.ClarifyRequest>(posted.get()!!))
        } finally { compose.runOnUiThread { app.container.runStreams.close() } }
    }

    @Test
    fun followUpInHistory_showsThinkingUntilFirstDelta() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/api/sessions/s1/messages" ->
                    MockResponse().setBody(
                        """{"messages":[{"role":"user","content":"histq"},{"role":"assistant","content":"hista"}]}"""
                    )
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}""")
                // Delay the whole reply: the thinking indicator must show
                // during the wait even though history already contains an
                // assistant message. No reconnect is involved: the terminal
                // arrives on this same subscription.
                request.method == "GET" && request.path == "/v1/runs/r1/events" ->
                    events(
                        frame("run.completed", """{"event":"run.completed","output":"done"}""")
                    ).also { it.setHeadersDelay(3, TimeUnit.SECONDS) }
                else -> MockResponse().setBody("{}")
            }
        }
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = "s1", onNavigateBack = {})
            }
        }
        // History renders first.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("hista", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chatInput").performTextInput("more")
        compose.onNodeWithTag("sendButton").performClick()
        // No reply chunk yet, but history already has an assistant row: the
        // thinking indicator must still appear.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("thinkingIndicator", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The run completes: the reply lands and the indicator leaves.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("done", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("thinkingIndicator", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun foreignRun_snackbarAction_forksAndContinues() {
        val creates = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/api/sessions/s1/messages" ->
                    MockResponse().setBody("""{"messages":[]}""")
                request.method == "POST" && request.path == "/v1/runs" ->
                    if (creates.getAndIncrement() == 0) {
                        // Discord-style ownership denial on the first send.
                        MockResponse().setResponseCode(403)
                            .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
                    } else {
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r2","status":"queued"}""")
                    }
                request.method == "POST" && request.path == "/api/sessions/s1/fork" ->
                    MockResponse().setBody("""{"session_id":"s9"}""")
                request.method == "GET" && request.path == "/api/sessions/s9/messages" ->
                    MockResponse().setBody(
                        """{"messages":[{"role":"assistant","content":"old"}]}"""
                    )
                request.method == "GET" && request.path == "/v1/runs/r2" ->
                    MockResponse().setBody("""{"run_id":"r2","status":"running","session_id":"s9"}""")
                request.method == "GET" && request.path == "/v1/runs/r2/events" ->
                    events(
                        frame("run.completed", """{"event":"run.completed","output":"resumed"}""")
                    )
                else -> MockResponse().setBody("{}")
            }
        }
        kotlinx.coroutines.runBlocking { saveSettings() }

        compose.setContent {
            HermesAppTheme {
                ChatScreen(sessionId = "s1", onNavigateBack = {})
            }
        }
        compose.onNodeWithTag("chatInput").performTextInput("hi")
        compose.onNodeWithTag("sendButton").performClick()
        // The denial surfaces with a fork action.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Fork and continue").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Fork and continue").performClick()
        // The forked session runs the blocked text to completion.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("resumed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Fork and continue").fetchSemanticsNodes().isEmpty()
        }
    }
}
