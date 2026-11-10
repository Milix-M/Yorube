package com.hermes.client.data.api

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class HttpLifecycleTest {
    @Test fun urlValidationUsesTheActualHostAndRejectsEmbeddedCredentials() {
        listOf("http://localhost:8642@attacker.invalid", "http://127.0.0.1:80@attacker.invalid",
            "http://localhost.attacker.invalid", "https://user:password@example.com",
            "https://example.com?target=", "https://example.com#ignored", "ftp://localhost").forEach { url ->
            assertThrows("Must reject $url", IllegalArgumentException::class.java) { HermesApi(url, "k") }
        }
        assertEquals("https://example.com/p/team", HermesApi.validatedBaseUrl(" HTTPS://Example.COM:443/p/team/ "))
        assertEquals("http://[::1]:8642", HermesApi.validatedBaseUrl("http://[::1]:8642/"))
    }

    @Test fun profilePrefixSurvivesUrlNormalization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            HermesApi(server.url("/p/team/").toString(), "k").getCapabilities()
            assertEquals("/p/team/v1/capabilities", server.takeRequest().path)
        }
    }

    @Test fun healthCheckRequiresAnActualSuccessfulStatusField() = runBlocking {
        MockWebServer().use { server ->
            val api = HermesApi(server.url("/").toString(), "k")
            listOf("""{"status":"error","message":"ok"}""", """<html>\"ok\"</html>""", "[]").forEach {
                server.enqueue(MockResponse().setBody(it))
                assertFalse(api.checkHealth())
            }
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            assertTrue(api.checkHealth())
        }
    }

    @Test fun cancellingHeaderWaitClosesTheSocketPromptly() = runBlocking {
        cancellationClosesCall(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), health = false)
    }

    @Test fun cancellingHealthCheckPropagatesCancellationAndClosesTheSocket() = runBlocking {
        cancellationClosesCall(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), health = true)
    }

    @Test fun cancellingBodyReadClosesTheSocketPromptly() = runBlocking {
        cancellationClosesCall(MockResponse().setBody("{\"features\":{}}").setBodyDelay(2, TimeUnit.SECONDS),
            health = false, waitForHeaders = true)
    }

    private suspend fun cancellationClosesCall(response: MockResponse, health: Boolean, waitForHeaders: Boolean = false) = kotlinx.coroutines.coroutineScope {
        MockWebServer().use { server ->
            server.enqueue(response)
            val activeCall = AtomicReference<Call>()
            val headers = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val client = HermesApi.defaultClient().newBuilder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { headers.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { closed.countDown() }
            }).build()
            val api = HermesApi(server.url("/").toString(), "k", client = client)
            val job = launch(Dispatchers.Default) {
                if (health) api.checkHealth() else api.getCapabilities()
                fail("Cancelled HTTP work must not return a value")
            }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (waitForHeaders) assertTrue(headers.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(activeCall.get().isCanceled())
                assertTrue("Socket must close before the 60 second read timeout", closed.await(2, TimeUnit.SECONDS))
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun oversizedMultimodalPayloadIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            val api = HermesApi(server.url("/").toString(), "k")
            val images = List(2) { RunImage("image-$it.png", "data:image/png;base64," + "A".repeat(5_000_000)) }
            try {
                api.createRun(RunCreateRequest("describe", images = images), "key")
                fail("Combined Base64 payload must be bounded")
            } catch (e: HermesApiException) {
                assertEquals(413, e.status)
                assertEquals("request_too_large", e.code)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
