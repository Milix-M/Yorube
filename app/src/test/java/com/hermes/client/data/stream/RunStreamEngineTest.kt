package com.hermes.client.data.stream

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.HermesApiException
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RunStreamEngineTest {

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
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app)
        c.settingsStore.save(ClientSettings(baseUrl = url, apiKey = "k"))
        awaitCondition { c.settingsStore.current().baseUrl == url }
        return c
    }

    private fun events(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { it + "\n\n" }
        return MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")
    }

    private fun frame(event: String, data: String) = "event: $event\ndata: $data"

    private fun TestScope.collectUpdates(
        c: AppContainer,
        runId: String,
    ): MutableList<RunStreamUpdate> {
        val received = Collections.synchronizedList(mutableListOf<RunStreamUpdate>())
        backgroundScope.launch {
            c.runStreams.updatesFor(runId).collect { received.add(it) }
        }
        return received
    }

    @Test
    fun start_streamsEventsAndClearsActiveOnTerminal() = runVmTest {
        server.enqueue(
            events(
                frame("message.delta", """{"event":"message.delta","delta":"Hello"}"""),
                frame("run.completed", """{"event":"run.completed","output":"Hello"}"""),
            )
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "r1")
        c.runStreams.start("r1", "s1")
        assertEquals(ActiveRun("r1", "s1"), c.runStreams.activeRun.value)
        awaitCondition { c.runStreams.activeRun.value == null }
        val seen = synchronized(received) { received.toList() }
        assertEquals(2, seen.size)
        val first = seen[0] as RunStreamUpdate.Event
        assertEquals(RunEvent.Delta("Hello"), first.event)
        val second = seen[1] as RunStreamUpdate.Event
        assertTrue(second.event is RunEvent.Terminal)
    }

    @Test
    fun start_pollFirst_pollsBeforeStreaming() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"running","session_id":"s1"}"""))
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"late"}"""))
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "r1")
        c.runStreams.start("r1", "s1", pollFirst = true)
        awaitCondition { c.runStreams.activeRun.value == null }
        val seen = synchronized(received) { received.toList() }
        assertTrue(seen.first() is RunStreamUpdate.Resync)
        assertTrue(seen.last() is RunStreamUpdate.Event)
        // The status poll went out before the SSE subscription.
        assertEquals("/v1/runs/r1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals("/v1/runs/r1/events", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun start_pollFirstTerminal_emitsPollTerminalWithoutStreaming() = runVmTest {
        server.enqueue(
            MockResponse().setBody("""{"run_id":"r1","status":"completed","output":"done","session_id":"s1"}""")
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "r1")
        c.runStreams.start("r1", "s1", pollFirst = true)
        awaitCondition { received.any { it is RunStreamUpdate.PollTerminal } }
        awaitCondition { c.runStreams.activeRun.value == null }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun start_pollFirstUnknown_emitsUnknownOutcome() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"run_id":"r1","status":"unknown"}"""))
        val c = containerWithSettings()
        val received = collectUpdates(c, "r1")
        c.runStreams.start("r1", "s1", pollFirst = true)
        awaitCondition { received.any { it is RunStreamUpdate.UnknownOutcome } }
        awaitCondition { c.runStreams.activeRun.value == null }
    }

    @Test
    fun start_supersededRun_keepsNewActiveRecord() = runVmTest {
        // Both streams hang on their headers, so both loops stay parked
        // in their first subscription while the records are checked.
        server.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS))
        val c = containerWithSettings()
        c.runStreams.start("rA", "sA")
        awaitCondition { server.requestCount >= 1 }
        c.runStreams.start("rB", "sB")
        assertEquals(ActiveRun("rB", "sB"), c.runStreams.activeRun.value)
        awaitCondition { server.requestCount >= 2 }
        // The superseded loop's cleanup must not clear the new record.
        testScheduler.advanceUntilIdle()
        assertEquals(ActiveRun("rB", "sB"), c.runStreams.activeRun.value)
    }

    @Test
    fun updatesFor_otherRun_filtersOut() = runVmTest {
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"x"}"""))
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "rX")
        c.runStreams.start("rY", "sY")
        awaitCondition { c.runStreams.activeRun.value == null }
        assertTrue(received.isEmpty())
    }

    @Test
    fun start_supersededDuringPoll_cancelsCleanly() = runVmTest {
        // r1's stream ends at once; its status poll then hangs on the
        // response headers, parking the engine inside the poll (inside
        // getRunWithRetry's try). Superseding it there must cancel the
        // loop through both layers without disturbing r2.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/runs/r1/events" -> MockResponse().setBody("")
                request.path == "/v1/runs/r1" ->
                    MockResponse().setHeadersDelay(3, TimeUnit.SECONDS)
                        .setBody("""{"run_id":"r1","status":"running"}""")
                request.path == "/v1/runs/r2/events" ->
                    events(frame("run.completed", """{"event":"run.completed","output":"b"}"""))
                else -> MockResponse().setBody("{}")
            }
        }
        val c = containerWithSettings()
        c.runStreams.start("r1", "s1")
        // Creep virtual time forward in small steps (the reconnect
        // backoff elapses) while the real IO hops complete, until the
        // poll request is out and hanging on its headers.
        val deadline = System.currentTimeMillis() + 10_000
        while (server.requestCount < 2) {
            if (System.currentTimeMillis() >= deadline) error("timed out waiting for the poll")
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100.milliseconds)
            withContext(Dispatchers.IO) { Thread.sleep(50) }
        }
        c.runStreams.start("r2", "s2")
        awaitCondition { c.runStreams.activeRun.value == null }
    }

    @Test
    fun start_runGoneStream_diesWithoutPoll() = runVmTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"gone","code":"run_not_found"}}""")
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "r1")
        c.runStreams.start("r1", "s1")
        awaitCondition { received.any { it is RunStreamUpdate.Dead } }
        val dead = received.first { it is RunStreamUpdate.Dead } as RunStreamUpdate.Dead
        assertTrue(dead.cause is HermesApiException)
        assertEquals(404, (dead.cause as HermesApiException).status)
        // A gone run is authoritative: no status poll follows.
        assertEquals(1, server.requestCount)
        assertNull(c.runStreams.activeRun.value)
    }
}
