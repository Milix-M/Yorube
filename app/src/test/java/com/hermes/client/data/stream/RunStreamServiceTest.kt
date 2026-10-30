package com.hermes.client.data.stream

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RunStreamServiceTest {

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

    private fun app(): HermesApp =
        ApplicationProvider.getApplicationContext<Application>() as HermesApp

    private suspend fun TestScope.saveSettings() {
        val url = server.url("").toString().removeSuffix("/")
        val c = AppContainer(app())
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitCondition { c.settingsStore.current().baseUrl == url }
    }

    private fun events(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { it + "\n\n" }
        return MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
    }

    private fun frame(event: String, data: String) = "event: $event\ndata: $data"

    private fun serviceIntent(runId: String?, sessionId: String = "s1"): Intent {
        val intent = Intent(app(), RunStreamService::class.java)
        if (runId != null) intent.putExtra("run_id", runId)
        intent.putExtra("session_id", sessionId)
        return intent
    }

    @Test
    fun startCommand_noRunId_stopsSelf() {
        val controller = Robolectric.buildService(RunStreamService::class.java, serviceIntent(null))
            .create()
        val service = controller.get()
        controller.startCommand(0, 1)
        val shadow = Shadows.shadowOf(service)
        // startForeground ran (the startForegroundService contract), then
        // the service gave up: nothing to keep alive.
        assertNotNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun startCommand_blankRunId_stopsSelf() {
        val controller = Robolectric.buildService(RunStreamService::class.java, serviceIntent(""))
            .create()
        val service = controller.get()
        controller.startCommand(0, 1)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun startCommand_startsEngineAndStopsWhenRunEnds() = runVmTest {
        saveSettings()
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"done"}"""))
        )
        val controller = Robolectric.buildService(RunStreamService::class.java, serviceIntent("r1"))
            .create()
        val service = controller.get()
        controller.startCommand(0, 1)
        // The engine streamed the run to its terminal frame, cleared the
        // active record, and the service's watcher stopped the service.
        awaitCondition { Shadows.shadowOf(service).isStoppedBySelf }
        assertNull(app().container.runStreams.activeRun.value)
        controller.destroy()
    }

    @Test
    fun startCommand_engineAlreadyActive_keepsWatching() = runVmTest {
        saveSettings()
        // The run's stream hangs on its headers: the run stays active.
        server.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS))
        val engine = app().container.runStreams
        engine.start("r1", "s1")
        awaitCondition { server.requestCount >= 1 }
        val controller = Robolectric.buildService(RunStreamService::class.java, serviceIntent("r1"))
            .create()
        val service = controller.get()
        controller.startCommand(0, 1)
        // Already streaming this run: no restart, no stop. A second start
        // command reuses the same watcher instead of stacking another.
        controller.startCommand(0, 2)
        testScheduler.advanceUntilIdle()
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        assertNotNull(engine.activeRun.value)
        controller.destroy()
    }

    @Test
    fun onBind_returnsNull() {
        val controller = Robolectric.buildService(RunStreamService::class.java).create()
        val service = controller.get()
        assertNull(service.onBind(Intent(app(), RunStreamService::class.java)))
        controller.destroy()
    }
}
