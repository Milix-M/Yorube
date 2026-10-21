package com.hermes.client

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun TestScope.awaitStored(c: AppContainer, expectedBaseUrl: String) {
        awaitCondition { c.settingsStore.current().baseUrl == expectedBaseUrl }
    }

    @Test
    fun api_sendsCloudflareHeadersWhenConfigured() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(
                baseUrl = url,
                apiKey = "k",
                cfClientId = "id",
                cfClientSecret = "s",
            )
        )
        awaitStored(c, url)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        c.api().checkHealth()
        val req = server.takeRequest()
        assertEquals("id", req.getHeader("CF-Access-Client-Id"))
        assertEquals("s", req.getHeader("CF-Access-Client-Secret"))
        assertEquals("Bearer k", req.getHeader("Authorization"))
    }

    @Test
    fun api_omitsCloudflareHeadersWhenBlank() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitStored(c, url)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        c.api().checkHealth()
        val req = server.takeRequest()
        assertNull(req.getHeader("CF-Access-Client-Id"))
        assertNull(req.getHeader("CF-Access-Client-Secret"))
    }
}
