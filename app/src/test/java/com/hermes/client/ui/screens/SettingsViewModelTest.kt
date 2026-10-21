package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.R
import com.hermes.client.data.store.ClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test: every field edited on the Settings screen must survive
 * save() into the store. A missing field here (e.g. Cloudflare tokens)
 * silently breaks auth at runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsViewModelTest {

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

    private fun container(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun save_persistsAllFields() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        val vm = SettingsViewModel(container)

        vm.baseUrl = "https://example.com:8642"
        vm.apiKey = "k"
        vm.model = "m"
        vm.provider = "p"
        vm.relayUrl = "https://relay:8090"
        vm.firebaseProjectId = "proj"
        vm.firebaseAppId = "appid"
        vm.firebaseApiKey = "fbkey"
        vm.firebaseSenderId = "sender"
        vm.cfClientId = "cid"
        vm.cfClientSecret = "csec"
        vm.save()

        val saved = awaitStored(container, "https://example.com:8642")
            assertEquals("https://example.com:8642", saved.baseUrl)
            assertEquals("k", saved.apiKey)
            assertEquals("m", saved.model)
            assertEquals("p", saved.provider)
            assertEquals("https://relay:8090", saved.relayUrl)
            assertEquals("proj", saved.firebaseProjectId)
            assertEquals("appid", saved.firebaseAppId)
            assertEquals("fbkey", saved.firebaseApiKey)
            assertEquals("sender", saved.firebaseSenderId)
            assertEquals("cid", saved.cfClientId)
            assertEquals("csec", saved.cfClientSecret)
    }

    private suspend fun TestScope.awaitStored(c: AppContainer, expectedBaseUrl: String): ClientSettings {
        awaitCondition { c.settingsStore.current().baseUrl == expectedBaseUrl }
        // Flush火-and-forget save() to its method-exit probe so JaCoCo marks
        // SettingsStore.save/saveFcmToken closing braces covered.
        testScheduler.advanceUntilIdle()
        kotlinx.coroutines.withContext(Dispatchers.IO) { Thread.sleep(500) }
        testScheduler.advanceUntilIdle()
        return c.settingsStore.current()
    }

    private suspend fun TestScope.awaitTested(vm: SettingsViewModel): String {
        awaitCondition { !vm.testing && vm.testResult.isNotEmpty() }
        return vm.testResult
    }

    @Test
    fun test_notConfigured() = runVmTest {
        val vm = SettingsViewModel(container())
        vm.test()
        val result = awaitTested(vm)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(ctx.getString(R.string.err_health_key), result)
    }

    @Test
    fun test_success() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val c = container()
        c.settingsStore.save(
            ClientSettings(
                baseUrl = url, apiKey = "k",
                firebaseProjectId = "p", firebaseAppId = "a",
                firebaseApiKey = "k", firebaseSenderId = "1",
            )
        )
        awaitStored(c, url)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(
            MockResponse().setBody("""{"features":{"run_stop":true},"endpoints":{}}""")
        )
        val vm = SettingsViewModel(c)
        vm.baseUrl = server.url("").toString().removeSuffix("/")
        vm.apiKey = "k"
        vm.firebaseProjectId = "p"
        vm.firebaseAppId = "a"
        vm.firebaseApiKey = "k"
        vm.firebaseSenderId = "1"
        vm.test()
        val result = awaitTested(vm)
        assertTrue(result.contains("health=true"))
        assertTrue(result.contains("run_stop=true"))
    }

    @Test
    fun test_success_withoutFirebase() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val c = container()
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitStored(c, url)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(
            MockResponse().setBody("""{"features":{"run_stop":true},"endpoints":{}}""")
        )
        val vm = SettingsViewModel(c)
        vm.baseUrl = server.url("").toString().removeSuffix("/")
        vm.apiKey = "k"
        // No firebase fields: covers the false branch of firebaseConfigured.
        vm.test()
        val result = awaitTested(vm)
        assertTrue(result.contains("health=true"))
        assertTrue(result.contains("fcm_ready=false"))
    }

    @Test
    fun test_capabilitiesFailure() = runVmTest {
        val url = server.url("").toString().removeSuffix("/")
        val c = container()
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitStored(c, url)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val vm = SettingsViewModel(c)
        vm.baseUrl = server.url("").toString().removeSuffix("/")
        vm.apiKey = "k"
        vm.test()
        val result = awaitTested(vm)
        assertTrue(result.contains("health=true"))
        assertTrue(result.contains("capabilities failed"))
    }

    @Test
    fun test_apiThrows() = runVmTest {
        val c = container()
        c.settingsStore.save(ClientSettings(baseUrl = "notaurl", apiKey = "k"))
        awaitStored(c, "notaurl")
        val vm = SettingsViewModel(c)
        vm.baseUrl = "https://example.com"
        vm.apiKey = "k"
        vm.test()
        val result = awaitTested(vm)
        assertTrue(result.contains("https"))
    }
}
