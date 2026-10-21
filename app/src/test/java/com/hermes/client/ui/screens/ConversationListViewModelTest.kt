package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.SessionInfo
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConversationListViewModelTest {

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

    private suspend fun containerWithSettings(): AppContainer {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(
                baseUrl = server.url("").toString().removeSuffix("/"),
                apiKey = "k",
            )
        )
        awaitCondition { c.settingsStore.current().baseUrl.isNotEmpty() }
        return c
    }

    private suspend fun awaitCondition(timeoutMs: Long = 15_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) error("timed out waiting")
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }

    @Test
    fun refresh_listsSessions() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1","title":"t"}]}"""))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() || vm.error != null }
        assertEquals(listOf(SessionInfo(id = "s1", title = "t")), vm.sessions)
        assertNull(vm.error)
        assertEquals(false, vm.loading)
    }

    @Test
    fun refresh_errorWithoutSettings() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ConversationListViewModel(AppContainer(app))
        vm.refresh()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
        vm.dismissError()
        assertNull(vm.error)
    }

    @Test
    fun delete_removesSession() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1"}]}"""))
        server.enqueue(MockResponse().setBody("{}"))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() }
        vm.delete("s1")
        awaitCondition { vm.sessions.isEmpty() }
    }

    @Test
    fun delete_errorKeepsList() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() }
        vm.delete("s1")
        awaitCondition { vm.error != null }
        assertEquals(1, vm.sessions.size)
    }
}
