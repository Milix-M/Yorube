package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertTrue(vm.error!!.contains("409"))
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
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            var creates = 0
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" -> {
                        creates++
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    }
                    request.path == "/v1/runs/r1" ->
                        MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
                    request.path == "/v1/runs/r1/events" ->
                        MockResponse().setBody("").setHeader("Content-Type", "text/event-stream")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        val c = containerWithSettings()
        val vm = ChatViewModel(c, null)
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.busy }
        awaitCondition { server.requestCount >= 3 }
        vm.input = "nudge"
        vm.onSteer()
        val steerPaths = pollPaths { it.endsWith("/steer") }
        assertTrue("no steer POST, got=$steerPaths", steerPaths.any { it.endsWith("/steer") })
        vm.onStop()
        val stopPaths = pollPaths { it.endsWith("/stop") }
        assertTrue("no stop POST, got=$stopPaths", stopPaths.any { it.endsWith("/stop") })
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
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" ->
                        MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
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
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.approval != null }
        vm.answerApproval("once")
        val bodies = pollBodies { it.startsWith("/v1/runs/r1/approval") }
        assertTrue(
            "bodies=$bodies error=${vm.error} busy=${vm.busy}",
            bodies.any { it.startsWith("/v1/runs/r1/approval") && it.contains("\"choice\":\"once\"") },
        )
    }

    @Test
    fun clarifyAnswer_postsAnswer() = runVmTest {
        var eventsServed = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/runs" && request.method == "POST" ->
                        MockResponse().setResponseCode(202)
                            .setBody("""{"run_id":"r1","status":"queued"}""")
                    request.path == "/v1/runs/r1" ->
                        MockResponse().setBody("""{"run_id":"r1","status":"running"}""")
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
        vm.input = "hi"
        vm.onSend()
        awaitCondition { vm.clarify != null }
        vm.answerClarify("this one")
        val bodies = pollBodies { it.startsWith("/v1/runs/r1/clarify") }
        assertTrue(
            "bodies=$bodies",
            bodies.any { it.startsWith("/v1/runs/r1/clarify") && it.contains("\"answer\":\"this one\"") },
        )
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
                        MockResponse().setBody("").setHeader("Content-Type", "text/event-stream")
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
            // Must come from the read failure, not from the empty-settings guard.
            assertTrue(vm.error!!.isNotBlank())
            assertFalse(vm.error!!.contains("are not set"))
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
}
