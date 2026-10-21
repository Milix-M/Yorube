package com.hermes.client

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.store.ClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppContainerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private suspend fun awaitStored(c: AppContainer) {
        val deadline = System.currentTimeMillis() + 10_000
        while (c.settingsStore.current().baseUrl.isEmpty()) {
            if (System.currentTimeMillis() >= deadline) error("timed out waiting")
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }

    @Test
    fun api_sendsCloudflareHeadersWhenConfigured() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(
                baseUrl = server.url("").toString().removeSuffix("/"),
                apiKey = "k",
                cfClientId = "id",
                cfClientSecret = "s",
            )
        )
        awaitStored(c)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        c.api().checkHealth()
        val req = server.takeRequest()
        assertEquals("id", req.getHeader("CF-Access-Client-Id"))
        assertEquals("s", req.getHeader("CF-Access-Client-Secret"))
        assertEquals("Bearer k", req.getHeader("Authorization"))
    }

    @Test
    fun api_omitsCloudflareHeadersWhenBlank() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(
                baseUrl = server.url("").toString().removeSuffix("/"),
                apiKey = "k",
            )
        )
        awaitStored(c)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        c.api().checkHealth()
        val req = server.takeRequest()
        assertNull(req.getHeader("CF-Access-Client-Id"))
        assertNull(req.getHeader("CF-Access-Client-Secret"))
    }
}
