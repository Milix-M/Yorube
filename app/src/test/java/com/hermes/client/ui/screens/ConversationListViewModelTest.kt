package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.SessionInfo
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
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun TestScope.containerWithSettings(): AppContainer {
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

    @Test
    fun refresh_listsSessions() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1","title":"t"}]}"""))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() || vm.error != null }
        assertEquals(listOf(SessionInfo(id = "s1", title = "t")), vm.sessions)
        assertNull(vm.error)
        assertEquals(false, vm.loading)
    }

    @Test fun refreshLoadsBeyondFirstFiftyAndKeepsPinsFirst() = runVmTest {
        val c = containerWithSettings()
        c.settingsStore.setPinned("s100", true)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                val end = minOf(offset + 50, 101)
                return MockResponse().setBody("""{"sessions":[${(offset until end).joinToString(",") { """{"id":"s$it","title":"title-$it"}""" }}]}""")
            }
        }
        val vm = ConversationListViewModel(c)
        vm.refresh()
        awaitCondition { !vm.loading }
        assertNull(vm.error)
        assertEquals(101, vm.sessions.size)
        assertEquals("s100", vm.sessions.first().key)
        assertEquals(3, server.requestCount)
    }

    @Test fun paginationStopsWhenServerIgnoresOffset() = runVmTest {
        val response = """{"sessions":[${(0 until 50).joinToString(",") { """{"id":"s$it"}""" }}]}"""
        server.enqueue(MockResponse().setBody(response))
        server.enqueue(MockResponse().setBody(response))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { !vm.loading }
        assertNull(vm.error)
        assertEquals(50, vm.sessions.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun refresh_errorWithoutSettings() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ConversationListViewModel(AppContainer(app))
        vm.refresh()
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.isNotBlank())
        vm.dismissError()
        assertNull(vm.error)
    }

    @Test
    fun delete_removesSession() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1"}]}"""))
        server.enqueue(MockResponse().setBody("{}"))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() }
        vm.delete("s1")
        awaitCondition { vm.sessions.isEmpty() }
    }

    @Test
    fun delete_errorKeepsList() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1"}]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val vm = ConversationListViewModel(containerWithSettings())
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() }
        vm.delete("s1")
        awaitCondition { vm.error != null }
        assertEquals(1, vm.sessions.size)
    }

    @Test
    fun filterForeign_shownByDefault() {
        val loaded = listOf(
            SessionInfo(id = "s1"),
            SessionInfo(id = "s2", surface = "discord"),
        )
        assertEquals(loaded, filterForeign(loaded, ClientSettings(showForeignSessions = true)))
    }

    @Test
    fun filterForeign_hiddenDropsForeign() {
        val loaded = listOf(
            SessionInfo(id = "s1"),
            SessionInfo(id = "s2", surface = "discord"),
            SessionInfo(id = "s3", owner = "app"),
            SessionInfo(id = "s4"),
        )
        val settings = ClientSettings(
            showForeignSessions = false,
            foreignSessionIds = setOf("s4"),
        )
        assertEquals(
            listOf(SessionInfo(id = "s1"), SessionInfo(id = "s3", owner = "app")),
            filterForeign(loaded, settings),
        )
    }

    @Test
    fun filterForeign_appOwnedAlwaysShown() {
        // Proof of "made by this app" wins over odd server metadata: a
        // session the app successfully ran on is never hidden.
        val loaded = listOf(
            SessionInfo(id = "s1"),
            SessionInfo(id = "s2", surface = "discord"),
        )
        val settings = ClientSettings(
            showForeignSessions = false,
            foreignSessionIds = setOf("s1"),
            appOwnedSessionIds = setOf("s1", "s2"),
        )
        assertEquals(loaded, filterForeign(loaded, settings))
    }

    @Test
    fun refresh_hidesForeignWhenOff() = runVmTest {
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[{"id":"s1","title":"Mine"},{"id":"s2","title":"D","surface":"discord"}]}"""
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(
            ClientSettings(
                baseUrl = server.url("").toString().removeSuffix("/"),
                apiKey = "k",
                showForeignSessions = false,
            )
        )
        awaitCondition { c.settingsStore.current().baseUrl.isNotEmpty() }
        val vm = ConversationListViewModel(c)
        vm.refresh()
        awaitCondition { vm.sessions.isNotEmpty() || vm.error != null }
        assertEquals(listOf("s1"), vm.sessions.map { it.key })
        assertEquals(false, vm.showForeign)
    }
}
