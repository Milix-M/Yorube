package com.hermes.client.data.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun defaultClient_hasBoundedReadTimeout() {
        // Infinite read timeouts hang forever on half-open mobile sockets;
        // the server keepalives every ~10s, so 60s only fires on dead ones.
        assertEquals(60_000, HermesApi.defaultClient().readTimeoutMillis)
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
    fun listSessions_acceptsVariantTimestamps() = runTest {
        val nowSec = System.currentTimeMillis() / 1000.0
        val nowMs = System.currentTimeMillis()
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[
                    {"id":"s1","updated_at":$nowSec},
                    {"id":"s2","updatedAt":$nowSec},
                    {"id":"s3","updated_at":$nowMs},
                    {"id":"s4","last_message_at":"2026-10-24T12:00:00Z"}
                ]}"""
            )
        )
        val sessions = api.listSessions()
        assertEquals(listOf("s1", "s2", "s3", "s4"), sessions.map { it.key })
        assertEquals(nowSec, sessions[0].updatedAt!!, 1.0)
        assertEquals(nowSec, sessions[1].updatedAt!!, 1.0)
        // Millis pass through raw; bucket/date formatters normalize them.
        assertEquals(nowMs.toDouble(), sessions[2].updatedAt!!, 1.0)
        assertEquals(
            java.time.Instant.parse("2026-10-24T12:00:00Z").epochSecond.toDouble(),
            sessions[3].updatedAt!!,
            0.0,
        )
    }

    @Test
    fun getSessionMessages_mixedContent() = runTest {        server.enqueue(
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
    fun skillsAndToolsets_invalidJson_returnsEmpty() = runTest {
        server.enqueue(MockResponse().setBody("{oops"))
        assertTrue(api.getSkills().isEmpty())
        server.enqueue(MockResponse().setBody("123"))
        assertTrue(api.getSkills().isEmpty())
        server.enqueue(MockResponse().setBody("""{"nope":1}"""))
        assertTrue(api.getSkills().isEmpty())
        server.enqueue(MockResponse().setBody("{oops"))
        assertTrue(api.getToolsets().isEmpty())
        server.enqueue(MockResponse().setBody("123"))
        assertTrue(api.getToolsets().isEmpty())
        server.enqueue(MockResponse().setBody("""{"nope":1}"""))
        assertTrue(api.getToolsets().isEmpty())
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

    @Test
    fun listSessions_coversAllShapes() = runTest {
        // Direct array shape.
        server.enqueue(MockResponse().setBody("""[{"id":"a"}]"""))
        assertEquals(listOf("a"), api.listSessions().map { it.key })
        // Data key shape.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"}]}"""))
        assertEquals(listOf("b"), api.listSessions().map { it.key })
        // Non-object item skipped.
        server.enqueue(MockResponse().setBody("""{"sessions":[123, {"id":"c"}]}"""))
        assertEquals(listOf("c"), api.listSessions().map { it.key })
        // Invalid item (type mismatch) is skipped via catch.
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":123}]}"""))
        assertTrue(api.listSessions().isEmpty())
        // Timestamp present (no fallback lookup).
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"d","updated_at":123.0}]}"""))
        assertEquals(listOf("d"), api.listSessions().map { it.key })
    }

    @Test
    fun getSessionMessages_coversShapes() = runTest {
        // Direct array.
        server.enqueue(MockResponse().setBody("""[{"role":"user","content":"hi"}]"""))
        assertEquals(1, api.getSessionMessages("s").size)
        // Data key.
        server.enqueue(MockResponse().setBody("""{"data":[{"role":"user","content":"hi"}]}"""))
        assertEquals(1, api.getSessionMessages("s").size)
        // Messages key with non-object skipped, blank text skipped.
        server.enqueue(
            MockResponse().setBody("""{"messages":[123, {"role":"user","content":""}, {"role":"user","content":"hi"}]}""")
        )
        assertEquals(1, api.getSessionMessages("s").size)
        // Content fallback via text/output keys when content blank.
        server.enqueue(
            MockResponse().setBody("""{"messages":[{"role":"user","text":"fallback"}]}""")
        )
        assertEquals("fallback", api.getSessionMessages("s").first().text)
        // No text at all -> skipped.
        server.enqueue(
            MockResponse().setBody("""{"messages":[{"role":"user"}]}""")
        )
        assertTrue(api.getSessionMessages("s").isEmpty())
    }

    @Test
    fun runEvents_cancellationException_propagates() = runTest {
        val cancelling = okhttp3.OkHttpClient.Builder()
            .addInterceptor { throw kotlinx.coroutines.CancellationException("cancel") }
            .build()
        val cancellingApi = HermesApi(
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = "k",
            client = cancelling,
        )
        try {
            cancellingApi.runEvents("r1").toList()
            fail("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertTrue((e.message ?: "").isNotEmpty())
        }
    }

    @Test
    fun getCapabilities_and_options() = runTest {
        server.enqueue(MockResponse().setBody("""{"features":{},"endpoints":{}}"""))
        api.getCapabilities()
        assertEquals("/v1/capabilities", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"providers":[]}"""))
        api.getModelOptions(refresh = true)
        assertEquals("/api/model/options?refresh=1", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"providers":[]}"""))
        api.getModelOptions(refresh = false)
        assertEquals("/api/model/options", server.takeRequest().path)
    }

    @Test
    fun deleteSession_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        api.deleteSession("s1")
        assertEquals("/api/sessions/s1", server.takeRequest().path)
    }

    @Test
    fun forkSession_handlesShapes() = runTest {
        server.enqueue(MockResponse().setBody("""{"session_id":"a"}"""))
        assertEquals("a", api.forkSession("s", "t"))
        server.enqueue(MockResponse().setBody("""{"id":"b"}"""))
        assertEquals("b", api.forkSession("s"))
        server.enqueue(MockResponse().setBody("""{"nope":1}"""))
        assertEquals(null, api.forkSession("s"))
        server.enqueue(MockResponse().setBody("notjson"))
        assertEquals(null, api.forkSession("s"))
    }

    @Test
    fun errorEnvelope_primitiveAndEmpty() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":123}"""))
        try {
            api.getRun("r")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(400, e.status)
        }
        server.enqueue(MockResponse().setResponseCode(400).setBody(""))
        try {
            api.getRun("r")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals("empty response", e.message)
        }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"c"}}"""))
        try {
            api.getRun("r")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals("c", e.code)
        }
    }

    @Test
    fun getModelIds_coversModelsKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[{"id":"m1"}]}"""))
        assertEquals(listOf("m1"), api.getModelIds())
    }

    @Test
    fun checkHealth_falseOnBlank() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"bad"}"""))
        assertEquals(false, api.checkHealth())
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        assertEquals(false, api.checkHealth())
    }

    @Test
    fun runEvents_readCancellation_isSwallowedAsCancel() = runTest {
        val cancellingReadClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = 0
                    override fun source(): okio.BufferedSource {
                        throw kotlinx.coroutines.CancellationException("read cancel")
                    }
                }
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        val cancellingReadApi = HermesApi(
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = "k",
            client = cancellingReadClient,
        )
        // Cancellation inside the producer closes the channel without an
        // error (callbackFlow treats CancellationException as cancel, not
        // failure). The producer still executes the CancellationException
        // branch for line coverage; the collector sees an empty stream.
        val events = cancellingReadApi.runEvents("r1").toList()
        assertTrue(events.isEmpty())
    }

    @Test
    fun createSession_wrappedShape_returnsIdAndSendsTitle() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"session":{"id":"s-new","title":"exp"}}""")
        )
        assertEquals("s-new", api.createSession("exp"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/sessions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"title\":\"exp\""))
    }

    @Test
    fun createSession_flatShapeAndNoTitle() = runTest {
        server.enqueue(MockResponse().setBody("""{"session_id":"s-flat"}"""))
        assertEquals("s-flat", api.createSession())
        val recorded = server.takeRequest()
        assertEquals("{}", recorded.body.readUtf8())
    }

    @Test
    fun createSession_errorAndUnreadable_returnNull() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"message":"no"}}""")
        )
        assertNull(api.createSession("x"))
        server.enqueue(MockResponse().setBody("""not json"""))
        assertNull(api.createSession("x"))
        server.enqueue(MockResponse().setBody("""{}"""))
        assertNull(api.createSession("x"))
    }

    @Test
    fun getSession_wrapped_readsTitle() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"session":{"id":"s1","title":"My Title","updated_at":123.0}}""")
        )
        val info = api.getSession("s1")
        assertEquals("s1", info?.key)
        assertEquals("My Title", info?.title)
        assertEquals(123.0, info?.updatedAt)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/sessions/s1", recorded.path)
    }

    @Test
    fun getSession_flatWithVariantTimestamp() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"id":"s2","title":null,"last_active":"2026-01-01T00:00:00Z"}""")
        )
        val info = api.getSession("s2")
        assertEquals("s2", info?.key)
        assertNull(info?.title)
        // last_active is not a SessionInfo field: the lenient fallback parses it.
        assertEquals(1767225600.0, info?.updatedAt)
    }

    @Test
    fun getSession_unreadable_returnsNull_httpErrorPropagates() = runTest {
        server.enqueue(MockResponse().setBody("""[1,2]"""))
        assertNull(api.getSession("s1"))
        server.enqueue(MockResponse().setBody("""not json"""))
        assertNull(api.getSession("s1"))
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"message":"gone"}}""")
        )
        try {
            api.getSession("s1")
            fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun updateSessionTitle_sendsPatch() = runTest {
        server.enqueue(MockResponse().setBody("""{}"""))
        api.updateSessionTitle("s1", "New \"Name\"")
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/sessions/s1", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"title\":\"New \\\"Name\\\"\""))
    }
}
