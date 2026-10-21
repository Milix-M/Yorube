package com.hermes.client.data.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
            allowInsecureHosts = true,
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
            allowInsecureHosts = true,
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
}
