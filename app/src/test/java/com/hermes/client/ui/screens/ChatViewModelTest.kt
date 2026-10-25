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
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertTrue(vm.messages.any { it.kind == MsgKind.ERROR && it.text.contains("bad") })
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
        repeat(4) {
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
}
