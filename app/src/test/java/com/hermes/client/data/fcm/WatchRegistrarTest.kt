package com.hermes.client.data.fcm

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchRegistrarTest {

    private lateinit var server: MockWebServer
    private val registrar = WatchRegistrar()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun blankInputs_returnFalse() = runTest {
        assertFalse(registrar.registerWatch("", "r", "s", "tok"))
        assertFalse(registrar.registerWatch("http://localhost:1", "r", "s", ""))
    }

    @Test
    fun postsWatchRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val ok = registrar.registerWatch(
            server.url("").toString().removeSuffix("/"), "r1", "s1", "tok"
        )
        assertTrue(ok)
        val req = server.takeRequest()
        assertEquals("/watch", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"run_id\":\"r1\""))
        assertTrue(body.contains("\"fcm_token\":\"tok\""))
    }

    @Test
    fun serverError_returnsFalse() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        assertFalse(
            registrar.registerWatch(
                server.url("").toString().removeSuffix("/"), "r1", "s1", "tok"
            )
        )
    }
}
