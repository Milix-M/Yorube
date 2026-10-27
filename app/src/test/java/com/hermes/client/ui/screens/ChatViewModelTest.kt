package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import com.hermes.client.data.api.HermesJson
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChatViewModelTest {

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

    private suspend fun TestScope.containerWithSettings(): AppContainer {
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitCondition { c.settingsStore.current().baseUrl == url }
        return c
    }

    private fun events(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { it + "\n\n" }
        return MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
    }

    private fun frame(event: String, data: String) = "event: $event\ndata: $data"

    @Test
    fun happyPath_streamsAndCompletes() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"api-x"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"Hello"}"""),
                frame("tool.started", """{"event":"tool.started","tool":"terminal"}"""),
                frame("run.completed", """{"event":"run.completed","output":"Hello"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertEquals("api-x", vm.sessionId)
        assertTrue(vm.messages.any { it.kind == MsgKind.USER && it.text == "hi" })
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "Hello" })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL })
        assertEquals("", vm.statusLine)
        assertNull(vm.error)
        // The accepted write marks the session as app-owned for filtering.
        awaitCondition { c.settingsStore.current().appOwnedSessionIds.contains("api-x") }

        val create = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/runs", create.path)
        assertNotNull(create.getHeader("Idempotency-Key"))
    }

    @Test
    fun terminalOnlyRun_setsOutput() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("run.failed", """{"event":"run.failed","error":"boom","pending_steer":"later"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertEquals("failed", vm.statusLine)
        assertTrue(vm.messages.none { it.kind == MsgKind.ASSISTANT })
        assertTrue(vm.messages.any { it.kind == MsgKind.ERROR && it.text == "boom" })
        assertTrue(vm.messages.any { it.kind == MsgKind.STATUS && it.text.contains("later") })
    }

    @Test
    fun allEventKinds_areHandled() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("message.interim", """{"event":"message.interim","text":"mid","already_streamed":false}"""),
                frame("message.interim", """{"event":"message.interim","text":"dup","already_streamed":true}"""),
                frame("tool.completed", """{"event":"tool.completed","tool":"t","duration":1.0,"error":true,"preview":"bad"}"""),
                frame("subagent.start", """{"event":"subagent.start","goal":"g"}"""),
                frame("subagent.complete", """{"event":"subagent.complete","status":"ok","summary":"s"}"""),
                frame("mystery.event", """{"event":"mystery.event"}"""),
                frame("run.cancelled", """{"event":"run.cancelled"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertEquals("cancelled", vm.statusLine)
        assertEquals("child ok: s", lastStatus(vm))
        assertEquals(
            listOf("mid", "delegating: g", "child ok: s"),
            vm.messages.filter { it.kind == MsgKind.STATUS }.map { it.text },
        )
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text.contains("bad") })
    }

    private fun lastStatus(vm: ChatViewModel): String =
        vm.messages.lastOrNull { it.kind == MsgKind.STATUS }?.text ?: ""

    @Test
    fun createConflict_reportsFriendlyError() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"message":"x","code":"admission_conflict"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.error!!.contains("409"))
    }

    @Test
    fun createUnknownExecution_suggestsNewChat() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"message":"x","code":"unknown_execution"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
    }

    @Test
    fun createDenied_reportsForeignSession() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"message":"x","code":"permission_denied"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("another surface"))
    }

    @Test
    fun steerAfterRunEnded_keepsInput() = runVmTest {
        val eventsHits = java.util.concurrent.atomic.AtomicInteger(0)
        val holdStream = java.util.concurrent.atomic.AtomicBoolean(true)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}""")
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    if (eventsHits.getAndIncrement() == 0) {
                        events(
                            frame("message.delta", """{"event":"message.delta","delta":"hi"}""")
                        )
                    } else {
                        // Hold the resubscription open while the run is active.
                        while (holdStream.get()) Thread.sleep(100)
                        MockResponse().setBody(": keepalive\n\n")
                    }
                }
                // The run is already gone server-side: not a dead conversation.
                request.method == "POST" && request.path == "/v1/runs/r1/steer" ->
                    MockResponse().setResponseCode(404).setBody("""{}""")
                else -> MockResponse().setBody("{}")
            }
        }
        try {
            val c = containerWithSettings()
            val vm = ChatViewModel(c, null)
            try {
                vm.input = "hi"
                vm.onSend()
                // The run is active and streaming.
                awaitCondition { vm.busy && vm.messages.any { it.kind == MsgKind.ASSISTANT } }
                vm.input = "again"
                vm.onSteer()
                awaitCondition { !vm.busy && vm.error != null }
                assertTrue(vm.error!!.contains("already ended"))
                // The unsent text is handed back so it can be resent.
                assertEquals("again", vm.input)
            } finally {
                // Stop background subscription work so later tests get a clean Main dispatcher.
                vm.viewModelScope.cancel()
            }
        } finally {
            holdStream.set(false)
        }
    }

    @Test
    fun createDraining_asksToWait() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":{"message":"x","code":"runtime_draining"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("503"))
    }

    @Test
    fun createServerError_reportsStatus() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaput"))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("500"))
    }

    @Test
    fun invalidBaseUrl_reportsSettingsError() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
        vm.dismissError()
        assertNull(vm.error)
    }

    private fun eventsBody(vararg frames: String): String =
        frames.joinToString("") { it + "\n\n" }

    @Test
    fun stopAndSteer_postControls() = runVmTest {
        // Simpler deterministic flow: dedicated responses per path.
        // Second and later status polls are delayed so the reconnect loop
        // cannot exhaust (clearing runId) before steer/stop are posted.
        // The delay blocks only IO threads; the test thread stays free.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            var creates = 0
            var getRuns = 0
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" -> {
                        creates++
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    }
                    request.path == "/v1/runs/r1" -> {
                        if (getRuns++ == 0) {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                        } else {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                                .setBodyDelay(5, TimeUnit.SECONDS)
                        }
                    }
                    request.path == "/v1/runs/r1/events" ->
                        MockResponse().setBody("").setHeader("Content-Type", "text/event-stream")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.busy }
            awaitCondition { server.requestCount >= 3 }
            vm.input = "nudge"
            vm.onSteer()
            awaitCondition { vm.statusLine.contains("steered") }
            val steerPaths = pollPaths { it.endsWith("/steer") }
            assertTrue("no steer POST, got=$steerPaths", steerPaths.any { it.endsWith("/steer") })
            vm.onStop()
            awaitCondition { vm.statusLine.contains("stopping") }
            val stopPaths = pollPaths { it.endsWith("/stop") }
            assertTrue("no stop POST, got=$stopPaths", stopPaths.any { it.endsWith("/stop") })
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }

    private suspend fun TestScope.pollPaths(match: (String) -> Boolean): List<String> {
        val paths = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && paths.none(match)) {
            testScheduler.advanceUntilIdle()
            while (true) {
                val r = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
                paths.add(r.path ?: "")
            }
        }
        return paths
    }

    @Test
    fun approvalAnswer_postsDecision() = runVmTest {
        var eventsServed = 0
        var getRuns = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" -> {
                        // Stall reconnect polls so runId survives until the answer is posted.
                        if (getRuns++ == 0) {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                        } else {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                                .setBodyDelay(5, TimeUnit.SECONDS)
                        }
                    }
                    request.path == "/v1/runs/r1/events" -> {
                        eventsServed++
                        if (eventsServed == 1) {
                            events(
                                frame("message.delta", """{"event":"message.delta","delta":"part"}"""),
                                frame(
                                    "approval.request",
                                    """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm"}""",
                                ),
                            )
                        } else {
                            events()
                        }
                    }
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.approval != null }
            vm.answerApproval("once")
            awaitCondition { vm.statusLine.contains("approved") }
            val bodies = pollBodies { it.startsWith("/v1/runs/r1/approval") }
            assertTrue(
                "bodies=$bodies error=${vm.error} busy=${vm.busy}",
                bodies.any { it.startsWith("/v1/runs/r1/approval") && it.contains("\"choice\":\"once\"") },
            )
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun clarifyAnswer_postsAnswer() = runVmTest {
        var eventsServed = 0
        var getRuns = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" -> {
                        // Stall reconnect polls so runId survives until the answer is posted.
                        if (getRuns++ == 0) {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                        } else {
                            MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                                .setBodyDelay(5, TimeUnit.SECONDS)
                        }
                    }
                    request.path == "/v1/runs/r1/events" -> {
                        eventsServed++
                        if (eventsServed == 1) {
                            events(
                                frame(
                                    "clarify.request",
                                    """{"event":"clarify.request","request_id":"c1","execution_generation":2,"prompt":"which?"}""",
                                ),
                            )
                        } else {
                            events()
                        }
                    }
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.clarify != null }
            vm.answerClarify("this one")
            awaitCondition { vm.statusLine.contains("answered") }
            val bodies = pollBodies { it.startsWith("/v1/runs/r1/clarify") }
            assertTrue(
                "bodies=$bodies",
                bodies.any { it.startsWith("/v1/runs/r1/clarify") && it.contains("\"answer\":\"this one\"") },
            )
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }

    private suspend fun TestScope.pollBodies(match: (String) -> Boolean): List<String> {
        val bodies = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && bodies.none(match)) {
            testScheduler.advanceUntilIdle()
            while (true) {
                val req = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
                bodies.add((req.path ?: "") + " " + req.body.readUtf8())
            }
        }
        return bodies
    }

    @Test
    fun controlErrors_setFriendlyError() = runVmTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" ->
                        MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                    request.path == "/v1/runs/r1/events" ->
                        // Hold the stream open briefly: no reconnect runs while
                        // the steer/stop errors are asserted, so nothing
                        // overwrites them. Short delay (not 60s) to avoid
                        // runTest timeout and MockWebServer shutdown timeout.
                        MockResponse().setHeadersDelay(5, TimeUnit.SECONDS)
                    request.path == "/v1/runs/r1/steer" ->
                        MockResponse().setResponseCode(500).setBody("nope")
                    request.path == "/v1/runs/r1/stop" ->
                        MockResponse().setResponseCode(500).setBody("nope")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.busy }
            awaitCondition { server.requestCount >= 3 }
            vm.input = "nudge"
            vm.onSteer()
            awaitCondition { vm.error != null }
            val seen = pollPaths { it.endsWith("/steer") }
            assertTrue(
                "error=${vm.error} requests=$seen",
                vm.error!!.contains("500"),
            )
            vm.dismissError()
            assertNull(vm.error)
            vm.onStop()
            awaitCondition { vm.error != null }
            assertTrue("error=${vm.error}", vm.error!!.contains("500"))
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun startRun_invalidBaseUrl_reportsError() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(ClientSettings(baseUrl = "http://example.com", apiKey = "k"))
        awaitCondition { c.settingsStore.current().baseUrl == "http://example.com" }
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("https"))
    }

    @Test
    fun nullRunIdControls_areNoOps() = runVmTest {
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.onStop()
        vm.onSteer()
        vm.answerApproval("once")
        vm.answerClarify("x")
        vm.input = ""
        vm.onSend()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loadHistory_mapsMessages() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"user","content":"q"},{"role":"assistant","content":"a"}]}"""
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 2 }
        assertEquals(MsgKind.USER, vm.messages[0].kind)
        assertEquals(MsgKind.ASSISTANT, vm.messages[1].kind)
    }

    @Test
    fun replyAfterHistory_showsStreamedAnswer() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"user","content":"q"},{"role":"assistant","content":"a"}]}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"followed up"}"""),
                frame("run.completed", """{"event":"run.completed","output":"done"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 2 }
        vm.input = "more"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text.contains("followed up") })
    }

    @Test
    fun replyAfterHistory_terminalOnlyAnswerShows() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"user","content":"q"},{"role":"assistant","content":"a"}]}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}"""))
        server.enqueue(
            events(
                frame("run.completed", """{"event":"run.completed","output":"late answer"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 2 }
        vm.input = "more"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text.contains("late answer") })
    }

    @Test
    fun loadHistory_errorWithoutSettings() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ChatViewModel(AppContainer(app), "s1")
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
    }

    @Test
    fun terminalInterrupted_setsStatus() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("run.interrupted", """{"event":"run.interrupted","error":"shut down"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertEquals("interrupted", vm.statusLine)
        assertTrue(vm.messages.any { it.kind == MsgKind.ERROR })
    }

    @Test
    fun runContinuesDespiteHelperFailures() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(baseUrl = url, apiKey = "k", relayUrl = "http://[::1")
        )
        awaitCondition { c.settingsStore.current().baseUrl == url }
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("gone"))
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"hi"}"""))
        )
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertNull(vm.error)
        assertEquals("", vm.sessionId)
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "hi" })
    }

    @Test
    fun multipleDeltas_appendToSingleMessage() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"a"}"""),
                frame("message.delta", """{"event":"message.delta","delta":"b"}"""),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        val assistants = vm.messages.filter { it.kind == MsgKind.ASSISTANT }
        assertEquals(1, assistants.size)
        assertEquals("ab", assistants[0].text)
    }

    @Test
    fun unknownRestart_suggestsNewChat() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"unknown"}"""))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
    }

    @Test
    fun reconnectTerminal_finishesRun() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(
            MockResponse().setBody("""{"run_id":"r1","status":"completed","output":"late answer"}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy && vm.error == null }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "late answer" })
    }

    @Test
    fun reconnectFailure_reportsError() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("gone"))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("500"))
    }

    @Test
    fun streamNotFound_reportsRunGone_notForeign() = runVmTest {
        // The run expired server-side (404 not_found on the SSE stream) while
        // the session itself is still usable: must surface as an ended run,
        // never as err_foreign ("another surface").
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}"""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"x","code":"not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue("error=${vm.error}", vm.error!!.contains("already ended"))
        assertTrue("error=${vm.error}", !vm.error!!.contains("another surface"))
        assertEquals(false, vm.busy)
        assertEquals("s1", vm.sessionId)
    }

    @Test
    fun streamRunNotFoundCode_reportsRunGone() = runVmTest {
        // DESIGN §13 documents the run-level code as run_not_found: same
        // handling as not_found, never err_foreign.
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"x","code":"run_not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue("error=${vm.error}", vm.error!!.contains("already ended"))
        assertTrue("error=${vm.error}", !vm.error!!.contains("another surface"))
        assertEquals(false, vm.busy)
    }

    @Test
    fun pollNotFound_reportsRunGone_notForeign() = runVmTest {
        // Stream drops cleanly, then the status poll finds the run gone:
        // still an ended run, not a dead conversation.
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"x","code":"not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue("error=${vm.error}", vm.error!!.contains("already ended"))
        assertTrue("error=${vm.error}", !vm.error!!.contains("another surface"))
        assertEquals(false, vm.busy)
        assertEquals("s1", vm.sessionId)
    }

    @Test
    fun approvalRunGone_reportsRunGone_notForeign() = runVmTest {
        var eventsServed = 0
        var getRuns = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" -> {
                        if (getRuns++ == 0) {
                            MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}""")
                        } else {
                            MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}""")
                                .setBodyDelay(5, TimeUnit.SECONDS)
                        }
                    }
                    request.path == "/v1/runs/r1/events" -> {
                        eventsServed++
                        if (eventsServed == 1) {
                            events(
                                frame(
                                    "approval.request",
                                    """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm"}""",
                                ),
                            )
                        } else {
                            events()
                        }
                    }
                    request.path == "/v1/runs/r1/approval" ->
                        MockResponse().setResponseCode(404)
                            .setBody("""{"error":{"message":"x","code":"not_found"}}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.approval != null }
            vm.answerApproval("once")
            awaitCondition { vm.error != null }
            assertTrue("error=${vm.error}", vm.error!!.contains("already ended"))
            assertTrue("error=${vm.error}", !vm.error!!.contains("another surface"))
            assertEquals("s1", vm.sessionId)
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun streamFailure_reconnectsAndCompletes() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("broken stream"))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(events(frame("run.completed", """{"event":"run.completed","output":"recovered"}""")))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertNull(vm.error)
        assertTrue(vm.messages.any { it.text == "recovered" })
    }

    @Test
    fun corruptSettings_reportsError() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        val storeFile = java.io.File(app.filesDir, "datastore/hermes_settings.preferences_pb")
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            val vm = ChatViewModel(AppContainer(app), null)
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.error != null }
            // Corrupt datastore reads as empty settings (SettingsStore swallows
            // the failure), so the not-configured guard reports it.
            assertTrue(vm.error!!.isNotBlank())
        } finally {
            storeFile.delete()
            AppContainer(app).settingsStore.save(
                ClientSettings(baseUrl = "https://example.com", apiKey = "k")
            )
        }
    }

    @Test
    fun retriesExhausted_reportsStreamEnd() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        // One status read for the initial subscribe plus one per reconnect
        // poll, each followed by an empty stream: 16 rounds exhaust the
        // MAX_RECONNECT_ATTEMPTS budget (15 resubscribes).
        repeat(16) {
            server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
            server.enqueue(MockResponse().setBody(""))
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition(timeoutMs = 25_000) { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
        assertEquals(false, vm.busy)
    }

    @Test
    fun reconnectPollTransientFailure_retriesAndCompletes() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        // The status poll itself hits a transient transport failure (the same
        // abort that killed the stream). The poll retry must recover instead
        // of killing the run with a raw socket error.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"recovered"}"""))
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertNull("error=${vm.error}", vm.error)
        assertTrue(vm.messages.any { it.text == "recovered" })
    }

    @Test
    fun reconnectBackoff_isCappedAndBudgeted() {
        assertEquals(1000L, reconnectDelayMs(0))
        assertEquals(2000L, reconnectDelayMs(1))
        assertEquals(4000L, reconnectDelayMs(2))
        assertEquals(8000L, reconnectDelayMs(3))
        assertEquals(8000L, reconnectDelayMs(14))
        assertEquals(15, MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun foreignRun_offersForkAndContinues() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        server.enqueue(MockResponse().setBody("""{"session_id":"s9"}"""))
        server.enqueue(
            MockResponse().setBody("""{"messages":[{"role":"assistant","content":"old"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r2","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r2","status":"running","session_id":"s9"}"""))
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"resumed"}"""))
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { !vm.historyLoading }
        vm.input = "hi"
        vm.onSend()
        // The Discord-style ownership denial keeps the text as a fork offer.
        awaitCondition { vm.error != null && vm.forkOffer == "hi" }
        assertTrue(vm.error!!.contains("unavailable"))
        assertFalse(vm.busy)
        vm.forkAndContinue()
        awaitCondition { !vm.busy && vm.sessionId == "s9" }
        assertNull(vm.error)
        assertNull(vm.forkOffer)
        // The forked copy counts as app-owned from now on.
        awaitCondition { c.settingsStore.current().appOwnedSessionIds.contains("s9") }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "old" })
        assertTrue(vm.messages.any { it.kind == MsgKind.USER && it.text == "hi" })
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "resumed" })
    }

    @Test
    fun foreignRun_forkFails_reportsForeign() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { !vm.historyLoading }
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.forkOffer == "hi" }
        vm.forkAndContinue()
        awaitCondition { vm.error != null && !vm.busy }
        assertTrue("error=${vm.error}", vm.error!!.contains("unavailable"))
        assertNull(vm.forkOffer)
        assertEquals("s1", vm.sessionId)
    }

    @Test
    fun dismissError_clearsForkOffer() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { !vm.historyLoading }
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.forkOffer == "hi" }
        vm.dismissError()
        assertNull(vm.error)
        assertNull(vm.forkOffer)
    }

    @Test
    fun serverError_noForkOffer() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaput"))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("500"))
        assertNull(vm.forkOffer)
    }

    @Test
    fun untrustedDelta_foldsIntoActivityLog() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "message.delta",
                    """{"event":"message.delta","delta":"調べたよ: <untrusted_tool_result source=\"web_search\">result body here</untrusted_tool_result> 以上です"}""",
                ),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.none { it.text.contains("untrusted_tool_result") })
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text.contains("調べたよ") })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text.startsWith("web search:") })
    }

    @Test
    fun pureMarkupDelta_startsNoBubble() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "message.delta",
                    """{"event":"message.delta","delta":"<untrusted_tool_result source=\"web_search\">bulk data here</untrusted_tool_result>"}""",
                ),
                frame("message.delta", """{"event":"message.delta","delta":"done"}"""),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        val assistants = vm.messages.filter { it.kind == MsgKind.ASSISTANT }
        assertEquals(1, assistants.size)
        assertEquals("done", assistants[0].text)
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text == "web search: bulk data here" })
    }

    @Test
    fun untrustedInterim_foldsToOneLineStatus() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "message.interim",
                    """{"event":"message.interim","text":"<untrusted_tool_result source=\"mcp_issues\">some payload</untrusted_tool_result>","already_streamed":false}""",
                ),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.STATUS && it.text == "mcp issues: some payload" })
        assertTrue(vm.messages.none { it.text.contains("untrusted_tool_result") })
    }

    @Test
    fun terminalOutputWithMarkup_foldsBlock() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "run.completed",
                    """{"event":"run.completed","output":"答え <untrusted_tool_result source=\"web_extract\">抜粋</untrusted_tool_result>"}""",
                ),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "答え " })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text == "web extract: 抜粋" })
        assertTrue(vm.messages.none { it.text.contains("untrusted_tool_result") })
    }

    @Test
    fun loadHistory_foldsToolMarkup() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"user","content":"q"},{"role":"tool","content":"<untrusted_tool_result source=\"web_search\">bulk</untrusted_tool_result>"}]}"""
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 2 }
        assertEquals(MsgKind.USER, vm.messages[0].kind)
        assertEquals(MsgKind.TOOL, vm.messages[1].kind)
        assertEquals("web search: bulk", vm.messages[1].text)
    }

    @Test
    fun jsonDelta_foldsIntoActivityLog() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "message.delta",
                    """{"event":"message.delta","delta":"{\"success\":true,\"name\":\"grounded-citations\",\"x\":1}"}""",
                ),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.none { it.kind == MsgKind.ASSISTANT })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text.startsWith("grounded-citations: ") })
    }

    @Test
    fun historyToolJson_foldsNeverBubbles() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"assistant","content":"ok"},{"role":"tool","content":"{\"success\":true,\"name\":\"grounded-citations\",\"x\":1}"}]}"""
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 2 }
        assertEquals(MsgKind.ASSISTANT, vm.messages[0].kind)
        assertEquals(MsgKind.TOOL, vm.messages[1].kind)
        assertTrue(vm.messages.none { it.text.contains("\"success\"") })
    }

    @Test
    fun historySystemRole_foldsToLog() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"role":"system","content":"session started"}]}"""
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.messages.size == 1 }
        assertEquals(MsgKind.TOOL, vm.messages[0].kind)
        assertEquals("session started", vm.messages[0].text)
    }

    @Test
    fun reasoningEvent_foldsToStatus() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("reasoning.delta", """{"event":"reasoning.delta","text":"thinking hard"}"""),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.STATUS && it.text == "thinking hard" })
        assertTrue(vm.messages.none { it.kind == MsgKind.ASSISTANT })
    }

    @Test
    fun toolCompletedJsonPreview_isSummarized() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "tool.completed",
                    """{"event":"tool.completed","tool":"web_search","duration":1.0,"preview":"{\"success\":true,\"name\":\"grounded-citations\",\"x\":1}"}""",
                ),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.none { it.text.contains("\"success\"") })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text.contains("grounded-citations") })
    }

    @Test
    fun terminalJsonError_isSummarized() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame(
                    "run.failed",
                    """{"event":"run.failed","error":"{\"tool_name\":\"t\",\"error\":\"kaput\"}"}""",
                ),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.any { it.kind == MsgKind.ERROR && it.text == "t [error]: kaput" })
    }

    @Test
    fun tagPayloadDelta_foldsIntoActivityLog() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"<result>some dump</result>"}"""),
                frame("run.completed", """{"event":"run.completed"}"""),
            )
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { !vm.busy }
        assertTrue(vm.messages.none { it.kind == MsgKind.ASSISTANT })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text == "markup: <result>" })
    }

    @Test
    fun parsePendingControl_parsesApproval() {
        val parsed = parsePendingControl(
            HermesJson.parseToJsonElement(
                """{"request_id":"r1","execution_generation":3,"command":"rm","description":"d","choices":["once","deny"]}"""
            ).jsonObject
        )
        assertTrue(parsed is PendingControl.Approval)
        val req = (parsed as PendingControl.Approval).request
        assertEquals("r1", req.requestId)
        assertEquals(3L, req.executionGeneration)
        assertEquals("rm", req.command)
        assertEquals(listOf("once", "deny"), req.choices)
    }

    @Test
    fun parsePendingControl_parsesClarifyAndRejectsJunk() {
        val clarify = parsePendingControl(
            HermesJson.parseToJsonElement(
                """{"request_id":"c1","execution_generation":2,"prompt":"which?"}"""
            ).jsonObject
        )
        assertTrue(clarify is PendingControl.Clarify)
        assertEquals("which?", (clarify as PendingControl.Clarify).prompt)
        assertNull(parsePendingControl(HermesJson.parseToJsonElement("""{"foo":1}""").jsonObject))
        assertNull(parsePendingControl(HermesJson.parseToJsonElement("""{}""").jsonObject))
    }

    @Test
    fun approvalConflict_restoresPendingControl() = runVmTest {
        val eventsHits = java.util.concurrent.atomic.AtomicInteger(0)
        val holdStream = java.util.concurrent.atomic.AtomicBoolean(true)
        val pendingApproval =
            """{"request_id":"r1","execution_generation":3,"command":"rm","description":"d","choices":["once","deny"]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody(
                        """{"run_id":"r1","status":"running","session_id":"s1","pending_controls":[$pendingApproval]}"""
                    )
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    if (eventsHits.getAndIncrement() == 0) {
                        events(
                            frame(
                                "approval.request",
                                """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm","description":"d"}""",
                            )
                        )
                    } else {
                        // Hold the resubscription open while the run waits.
                        while (holdStream.get()) Thread.sleep(100)
                        MockResponse().setBody(": keepalive\n\n")
                    }
                }
                request.method == "POST" && request.path == "/v1/runs/r1/approval" ->
                    MockResponse().setResponseCode(409)
                        .setBody("""{"error":{"message":"stale","code":"admission_conflict"}}""")
                else -> MockResponse().setBody("{}")
            }
        }
        try {
            val c = containerWithSettings()
            val vm = ChatViewModel(c, null)
            try {
                vm.input = "hi"
                vm.onSend()
                awaitCondition { vm.approval != null }
                assertEquals("rm", vm.approval!!.command)
                vm.answerApproval("once")
                // The 409 recovery re-reads state and re-shows the waiting control.
                awaitCondition { vm.approval != null }
                assertNull(vm.error)
                assertEquals("rm", vm.approval!!.command)
                assertEquals("s1", vm.sessionId)
            } finally {
                // Stop background subscription work so later tests get a clean Main dispatcher.
                vm.viewModelScope.cancel()
            }
        } finally {
            holdStream.set(false)
        }
    }

    @Test
    fun loadHistory_404_reportsForeign() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("unavailable") || vm.error!!.contains("利用できません"))
    }

    @Test
    fun createRun_runNotFound_reportsGone() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"run_not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
    }

    private suspend fun TestScope.approvalVmWithHold(
        holdStream: java.util.concurrent.atomic.AtomicBoolean,
        approvalResponse: MockResponse,
    ): ChatViewModel {
        val eventsHits = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    if (eventsHits.getAndIncrement() == 0) {
                        events(
                            frame(
                                "approval.request",
                                """{"event":"approval.request","request_id":"r1","execution_generation":3,"command":"rm"}""",
                            )
                        )
                    } else {
                        while (holdStream.get()) Thread.sleep(100)
                        MockResponse().setBody(": keepalive\n\n")
                    }
                }
                request.method == "POST" && request.path == "/v1/runs/r1/approval" -> approvalResponse
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.approval != null }
        return vm
    }

    @Test
    fun answerApproval_runGone_reportsGone() = runVmTest {
        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val vm = approvalVmWithHold(
                hold,
                MockResponse().setResponseCode(404)
                    .setBody("""{"error":{"message":"gone","code":"run_not_found"}}"""),
            )
            try {
                vm.answerApproval("once")
                awaitCondition { vm.error != null }
                assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
                assertEquals(false, vm.busy)
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            hold.set(false)
        }
    }

    @Test
    fun answerApproval_unknownExecution_reportsUnknown() = runVmTest {
        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val vm = approvalVmWithHold(
                hold,
                MockResponse().setResponseCode(404)
                    .setBody("""{"error":{"message":"gone","code":"unknown_execution"}}"""),
            )
            try {
                vm.answerApproval("once")
                awaitCondition { vm.error != null }
                assertTrue(vm.error!!.isNotBlank())
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            hold.set(false)
        }
    }

    @Test
    fun answerClarify_runGone_reportsGone() = runVmTest {
        val eventsHits = java.util.concurrent.atomic.AtomicInteger(0)
        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    if (eventsHits.getAndIncrement() == 0) {
                        events(
                            frame(
                                "clarify.request",
                                """{"event":"clarify.request","request_id":"c1","execution_generation":2,"prompt":"which?"}""",
                            )
                        )
                    } else {
                        while (hold.get()) Thread.sleep(100)
                        MockResponse().setBody(": keepalive\n\n")
                    }
                }
                request.method == "POST" && request.path == "/v1/runs/r1/clarify" ->
                    MockResponse().setResponseCode(404)
                        .setBody("""{"error":{"message":"gone","code":"run_not_found"}}""")
                else -> MockResponse().setBody("{}")
            }
        }
        try {
            val c = containerWithSettings()
            val vm = ChatViewModel(c, null)
            try {
                vm.input = "hi"
                vm.onSend()
                awaitCondition { vm.clarify != null }
                vm.answerClarify("x")
                awaitCondition { vm.error != null }
                assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            hold.set(false)
        }
    }

    @Test
    fun errorMessage_followsSavedLanguage() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(ClientSettings(language = "ja"))
        awaitCondition { c.settingsStore.current().language == "ja" }
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertEquals("ja", vm.uiLanguage)
        assertTrue("error=${vm.error}", vm.error!!.contains("設定を開いて"))
    }

    @Test
    fun reconnect_streamRunGone_reportsGone() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"run_not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
        assertEquals(false, vm.busy)
    }

    private fun stallAfterFirstEvents(vararg firstFrames: String): Array<Boolean> {
        val hold = arrayOf(true)
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    if (hits.getAndIncrement() == 0) {
                        events(*firstFrames)
                    } else {
                        while (hold[0]) Thread.sleep(50)
                        events(frame("run.completed", """{"event":"run.completed","output":"done"}"""))
                    }
                }
                else -> MockResponse().setBody("{}")
            }
        }
        return hold
    }

    @Test
    fun awaitingReply_toolAfterDelta_stillThinking() = runVmTest {
        val holdRun = stallAfterFirstEvents(
            frame("message.delta", """{"event":"message.delta","delta":"hi"}"""),
            frame("tool.started", """{"event":"tool.started","tool":"web_search","preview":"q"}"""),
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            // Reply started but a skill is still running: keep thinking.
            awaitCondition { vm.messages.any { it.kind == MsgKind.TOOL } }
            assertTrue(vm.busy)
            assertTrue(vm.awaitingReply)
            holdRun[0] = false
            awaitCondition { !vm.busy }
            // Streaming settled: the reply holds the cursor, not thinking.
            assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT })
            assertFalse(vm.awaitingReply)
        } finally {
            holdRun[0] = false
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun awaitingReply_statusAfterDelta_stillThinking() = runVmTest {
        val holdRun = stallAfterFirstEvents(
            frame("message.delta", """{"event":"message.delta","delta":"hi"}"""),
            frame(
                "message.interim",
                """{"event":"message.interim","text":"working","already_streamed":false}""",
            ),
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            // The reply chunk alone shows the cursor, not the indicator.
            awaitCondition { vm.messages.any { it.kind == MsgKind.ASSISTANT } }
            awaitCondition { vm.messages.any { it.kind == MsgKind.STATUS } }
            // The trailing status step is still progress: keep thinking.
            assertTrue(vm.busy)
            assertTrue(vm.awaitingReply)
            holdRun[0] = false
            awaitCondition { !vm.busy }
            assertFalse(vm.awaitingReply)
        } finally {
            holdRun[0] = false
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun awaitingReply_approvalHidesThinking() = runVmTest {
        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val vm = approvalVmWithHold(
                hold,
                MockResponse().setResponseCode(409)
                    .setBody("""{"error":{"message":"x","code":"admission_conflict"}}"""),
            )
            try {
                // An approval dialog owns the wait: no thinking row behind it.
                assertTrue(vm.busy)
                assertFalse(vm.awaitingReply)
                vm.answerApproval("once")
                awaitCondition { vm.error != null || !vm.busy }
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            hold.set(false)
        }
    }

    @Test
    fun forkAndContinue_noopWhileBusy() = runVmTest {
        val holdRun = stallAfterFirstEvents(
            frame("message.delta", """{"event":"message.delta","delta":"hi"}"""),
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.messages.any { it.kind == MsgKind.ASSISTANT } }
            assertTrue(vm.busy)
            vm.forkAndContinue()
            // Still on the running conversation: no fork happened.
            assertEquals("", vm.sessionId)
            assertTrue(vm.busy)
            holdRun[0] = false
            awaitCondition { !vm.busy }
        } finally {
            holdRun[0] = false
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun forkAndContinue_noopWhileHistoryLoading() = runVmTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET" && request.path == "/api/sessions/s1/messages") {
                    Thread.sleep(2000)
                    MockResponse().setBody(
                        """{"messages":[{"role":"user","content":"q"}]}"""
                    )
                } else {
                    MockResponse().setBody("{}")
                }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "s1")
        awaitCondition { vm.historyLoading }
        vm.forkAndContinue()
        assertEquals("s1", vm.sessionId)
        assertFalse(vm.busy)
        awaitCondition { !vm.historyLoading }
        assertTrue(vm.messages.any { it.kind == MsgKind.USER })
    }

    @Test
    fun forkAndContinue_noopWithoutOffer() = runVmTest {
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.forkAndContinue()
        assertEquals("", vm.sessionId)
        assertFalse(vm.busy)
        assertNull(vm.error)
    }

    @Test
    fun foreignLoad_forkOpensHistoryWithoutResend() = runVmTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path == "/api/sessions/sF1/messages" ->
                    MockResponse().setResponseCode(404)
                        .setBody("""{"error":{"message":"gone","code":"not_found"}}""")
                request.method == "POST" && request.path == "/api/sessions/sF1/fork" ->
                    MockResponse().setBody("""{"session_id":"sF9"}""")
                request.method == "GET" && request.path == "/api/sessions/sF9/messages" ->
                    MockResponse().setBody(
                        """{"messages":[{"role":"assistant","content":"old"}]}"""
                    )
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "sF1")
        awaitCondition { vm.foreignBlocked && vm.forkDialogOpen }
        assertNull(vm.forkOffer)
        vm.dismissForkDialog()
        assertFalse(vm.forkDialogOpen)
        // The read-only banner stays until the fork lands.
        assertTrue(vm.foreignBlocked)
        vm.forkAndContinue()
        awaitCondition { !vm.busy && vm.sessionId == "sF9" }
        assertFalse(vm.foreignBlocked)
        assertFalse(vm.forkDialogOpen)
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text == "old" })
        awaitCondition { c.settingsStore.current().foreignSessionIds.contains("sF1") }
    }

    @Test
    fun forkAndContinue_apiFailure_reportsError() = runVmTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET" && request.path == "/api/sessions/sF2/messages") {
                    MockResponse().setResponseCode(404)
                        .setBody("""{"error":{"message":"gone","code":"not_found"}}""")
                } else {
                    MockResponse().setBody("{}")
                }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, "sF2")
        awaitCondition { vm.foreignBlocked }
        // Lose the server config: the fork attempt itself fails locally.
        c.settingsStore.save(ClientSettings())
        awaitCondition { c.settingsStore.current().baseUrl.isBlank() }
        vm.forkAndContinue()
        awaitCondition { vm.error != null && !vm.busy }
        assertTrue("error=${vm.error}", vm.error!!.contains("https"))
    }

    @Test
    fun freshSend_denied_marksForeignWithoutOffer() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.foreignBlocked }
        assertNull(vm.forkOffer)
        assertTrue(vm.forkDialogOpen)
        assertFalse(vm.busy)
        // Blank keys are never recorded.
        assertFalse(c.settingsStore.current().foreignSessionIds.contains(""))
    }

    @Test
    fun reconnect_streamUnrecoverable_failsFast() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
        assertEquals(false, vm.busy)
    }

    @Test
    fun reconnect_pollRunGone_reportsGone() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"run_not_found"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
    }

    @Test
    fun reconnect_pollUnrecoverable_reportsForeign() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"permission_denied"}}""")
        )
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("unavailable") || vm.error!!.contains("利用できません") || vm.error!!.contains("403"))
    }

    @Test
    fun reconnect_pollTransientExhausted_reportsNetwork() = runVmTest {
        // Every status poll drops at the transport level: 15 fruitless
        // reconnect cycles exhaust the budget and report a network error.
        val polls = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    if (polls.getAndIncrement() == 0) {
                        MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                    } else {
                        // AFTER_REQUEST (not AT_START): the server always reads
                        // the request first, so this drops reliably even on a
                        // reused keep-alive connection.
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                request.method == "GET" && request.path == "/v1/runs/r1/events" ->
                    MockResponse().setBody("")
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition(timeoutMs = 60_000) { vm.error != null }
        assertTrue("error=${vm.error}", vm.error!!.contains("Connection lost") || vm.error!!.contains("接続が切れ"))
        assertEquals(false, vm.busy)
    }

    @Test
    fun reconnect_progressResetsBudget() = runVmTest {
        // 16 drop-and-redeliver cycles: each delivered delta resets the
        // outage budget, so a long flaky run completes instead of dying at
        // the 15th consecutive failure.
        val eventsCalls = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                request.method == "GET" && request.path == "/v1/runs/r1/events" -> {
                    val n = eventsCalls.getAndIncrement()
                    if (n < 16) {
                        events(
                            frame(
                                "message.delta",
                                """{"event":"message.delta","delta":"p$n"}"""
                            )
                        )
                    } else {
                        events(
                            frame("run.completed", """{"event":"run.completed","output":"fin"}""")
                        )
                    }
                }
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition(timeoutMs = 60_000) { !vm.busy }
        assertNull("error=${vm.error}", vm.error)
        // All 16 chunks streamed across 16 reconnects (over the 15-cycle
        // budget): each delivery reset the outage counter. The terminal
        // output is intentionally not duplicated: the reply already arrived.
        val assistants = vm.messages.filter { it.kind == MsgKind.ASSISTANT }
        assertEquals(1, assistants.size)
        assertEquals((0 until 16).joinToString("") { "p$it" }, assistants[0].text)
    }

    @Test
    fun reconnect_causeRunGone_pollBlip_reportsGone() = runVmTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running"}"""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"not_found"}}""")
        )
        // Poll would also fail, but cause already RunGone so it short-circuits
        // before polling. Enqueue a fallback in case of retry.
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("already ended") || vm.error!!.contains("終了"))
    }

    @Test
    fun steer_ioError_reportsNetwork() = runVmTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/runs" ->
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r1","status":"queued"}""")
                request.method == "GET" && request.path == "/v1/runs/r1" ->
                    MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                // Hold the stream open briefly: no reconnect runs while the
                // steer error is asserted, so nothing overwrites it.
                request.method == "GET" && request.path == "/v1/runs/r1/events" ->
                    MockResponse().setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                request.method == "POST" && request.path == "/v1/runs/r1/steer" ->
                    MockResponse().setBody("partial")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        try {
            vm.input = "hi"
            vm.onSend()
            awaitCondition { vm.busy }
            awaitCondition { server.requestCount >= 3 }
            vm.input = "again"
            vm.onSteer()
            awaitCondition { vm.error != null }
            assertTrue("error=${vm.error}", vm.error!!.contains("Connection lost") || vm.error!!.contains("接続が切れ"))
        } finally {
            vm.viewModelScope.cancel()
            testScheduler.advanceUntilIdle()
        }
    }
}
