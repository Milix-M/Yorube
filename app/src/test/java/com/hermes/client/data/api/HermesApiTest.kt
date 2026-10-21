package com.hermes.client.data.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HermesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HermesApi(
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = "test-key",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun createRun_sendsInputAndIdempotencyKey() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1","status":"queued"}""")
        )
        val accepted = api.createRun(RunCreateRequest(input = "hi"), "key-1")
        assertEquals("run_1", accepted.runId)

        val recorded = server.takeRequest()
        assertEquals("/v1/runs", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals("key-1", recorded.getHeader("Idempotency-Key"))
        assertTrue(recorded.body.readUtf8().contains("\"hi\""))
    }

    @Test
    fun getRun_readsSessionId() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"run_id":"run_1","status":"running","session_id":"api-abc"}""")
        )
        val status = api.getRun("run_1")
        assertEquals("running", status.status)
        assertEquals("api-abc", status.sessionId)
    }

    @Test
    fun errorEnvelope_mapsCode() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"message":"nope","code":"admission_conflict"}}""")
        )
        try {
            api.getRun("run_x")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(409, e.status)
            assertEquals("admission_conflict", e.code)
        }
    }

    @Test
    fun checkHealth_trueOnOk() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertTrue(api.checkHealth())
    }

    @Test
    fun runEvents_parsesDeltaAndTerminal() = runTest {
        val body = "event: message.delta\n" +
            "data: {\"event\":\"message.delta\",\"delta\":\"hi\"}\n" +
            "\n" +
            "event: run.completed\n" +
            "data: {\"event\":\"run.completed\",\"output\":\"done\"}\n" +
            "\n"
        server.enqueue(
            MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
        )
        val events = api.runEvents("run_1").toList()
        assertEquals(listOf(RunEvent.Delta("hi"), RunEvent.Terminal("completed", "done", null, null)), events)
    }

    @Test
    fun runEvents_skipsKeepaliveComments() = runTest {
        val body = ": keepalive\n" +
            "\n" +
            "event: run.cancelled\n" +
            "data: {\"event\":\"run.cancelled\"}\n" +
            "\n"
        server.enqueue(MockResponse().setBody(body))
        val events = api.runEvents("run_2").toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is RunEvent.Terminal)
    }

    @Test
    fun extraHeaders_areSent() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val withCf = HermesApi(
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = "k",
            extraHeaders = mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "s"),
        )
        withCf.checkHealth()
        val recorded = server.takeRequest()
        assertEquals("id", recorded.getHeader("CF-Access-Client-Id"))
        assertEquals("s", recorded.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun plainHttp_outsideLoopback_isRejected() {
        try {
            HermesApi("http://example.com", "k")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun plainHttp_loopback_isAllowed() {
        HermesApi("http://localhost:8642", "k")
        HermesApi("http://127.0.0.1:8642/", "k")
        HermesApi("http://10.0.2.2:8642", "k")
    }

    @Test
    fun controls_sendCorrectPathsAndBodies() = runTest {
        repeat(5) { server.enqueue(MockResponse().setBody("{}")) }
        api.stopRun("run_1")
        api.steerRun("run_1", "go on")
        api.answerApproval("run_1", ApprovalAnswer("r1", 3L, "once"))
        api.answerClarify("run_1", ClarifyRequest("c1", 2L, "yes"))
        api.resolveUnknown("run_1", ResolveUnknownRequest("adm_1", 7L))

        assertEquals("/v1/runs/run_1/stop", server.takeRequest().path)
        var req = server.takeRequest()
        assertEquals("/v1/runs/run_1/steer", req.path)
        assertTrue(req.body.readUtf8().contains("go on"))
        req = server.takeRequest()
        assertEquals("/v1/runs/run_1/approval", req.path)
        val approval = req.body.readUtf8()
        assertTrue(approval.contains("\"request_id\":\"r1\"") && approval.contains("\"choice\":\"once\""))
        req = server.takeRequest()
        assertEquals("/v1/runs/run_1/clarify", req.path)
        assertTrue(req.body.readUtf8().contains("\"answer\":\"yes\""))
        req = server.takeRequest()
        assertEquals("/v1/runs/run_1/resolve-unknown", req.path)
        assertTrue(req.body.readUtf8().contains("\"admission_id\":\"adm_1\""))
    }

    @Test
    fun listSessions_shapes() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1","title":"t"}]}"""))
        assertEquals(listOf("s1"), api.listSessions().map { it.key })
        server.enqueue(MockResponse().setBody("""[{"session_id":"s2"}]"""))
        assertEquals(listOf("s2"), api.listSessions().map { it.key })
        server.enqueue(MockResponse().setBody("""{"nope":1}"""))
        assertTrue(api.listSessions().isEmpty())
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        try {
            api.listSessions()
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(500, e.status)
        }
    }

    @Test
    fun getSessionMessages_mixedContent() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[
                    {"role":"user","content":"hi"},
                    {"role":"assistant","content":[{"type":"text","text":"see"},{"type":"image_url"}]},
                    {"role":"assistant","content":""},
                    {"nope":true}
                ]}"""
            )
        )
        val messages = api.getSessionMessages("s1")
        assertEquals(2, messages.size)
        assertEquals("hi", messages[0].text)
        assertEquals("see\n[image]", messages[1].text)
        server.enqueue(MockResponse().setBody("broken"))
        assertTrue(api.getSessionMessages("s1").isEmpty())
    }

    @Test
    fun deleteAndFork() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        api.deleteSession("s1")
        assertEquals("/api/sessions/s1", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"session_id":"s9"}"""))
        assertEquals("s9", api.forkSession("s1", "alt"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        assertEquals(null, api.forkSession("s1"))
    }

    @Test
    fun discoveryEndpoints_parse() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m1"}]}"""))
        assertEquals(listOf("m1"), api.getModelIds())
        server.enqueue(MockResponse().setBody("""{"providers":[{"name":"p"}]}"""))
        assertTrue(api.getModelOptions().contains("providers"))
        server.enqueue(MockResponse().setBody("""{"skills":[{"name":"s"}]}"""))
        assertEquals("s", api.getSkills().first().name)
        server.enqueue(MockResponse().setBody("""{"toolsets":[{"name":"t"}]}"""))
        assertEquals("t", api.getToolsets().first().name)
    }

    @Test
    fun checkHealth_falseOnError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        assertEquals(false, api.checkHealth())
    }

    @Test
    fun runEvents_serverError_throws() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"denied","code":"forbidden"}}""")
        )
        try {
            api.runEvents("run_1").toList()
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(403, e.status)
            assertEquals("forbidden", e.code)
        }
    }

    @Test
    fun errorEnvelope_invalidJson_usesRawBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{oops"))
        try {
            api.getRun("r")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(400, e.status)
            assertEquals("{oops", e.message)
        }
    }

    @Test
    fun errorEnvelope_stringError_usesContent() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"oops"}"""))
        try {
            api.getRun("r")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(400, e.status)
            assertEquals("oops", e.message)
        }
    }

    @Test
    fun getModelIds_invalidJson_returnsEmpty() = runTest {
        server.enqueue(MockResponse().setBody("{oops"))
        assertTrue(api.getModelIds().isEmpty())
    }

    @Test
    fun runEvents_connectionRefused_throws() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("").toString().removeSuffix("/")
        dead.shutdown()
        val deadApi = HermesApi(baseUrl = url, apiKey = "k")
        try {
            deadApi.runEvents("r1").toList()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue((e.message ?: "").isNotEmpty())
        }
    }

    @Test
    fun runEvents_unreadableBody_throws() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("partial")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        try {
            api.runEvents("r1").toList()
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(500, e.status)
            assertEquals("empty response", e.message)
        }
    }

    @Test
    fun runEvents_midStreamDisconnect_throws() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "event: message.delta\n" +
                    "data: {\"event\":\"message.delta\",\"delta\":\"x\"}\n\ntrailer"
            ).setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        try {
            api.runEvents("r1").toList()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue((e.message ?: "").isNotBlank())
        }
    }

    @Test
    fun listSessions_invalidJson_returnsEmpty() = runTest {
        server.enqueue(MockResponse().setBody("{oops"))
        assertTrue(api.listSessions().isEmpty())
        server.enqueue(MockResponse().setBody("123"))
        assertTrue(api.listSessions().isEmpty())
    }

    @Test
    fun getSessionMessages_invalidJson_returnsEmpty() = runTest {
        server.enqueue(MockResponse().setBody("{oops"))
        assertTrue(api.getSessionMessages("s").isEmpty())
        server.enqueue(MockResponse().setBody("[\"oops\"]"))
        assertEquals(0, api.getSessionMessages("s").size)
    }
}
