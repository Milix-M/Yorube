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
import okhttp3.mockwebserver.SocketPolicy
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
    private val containers = mutableListOf<AppContainer>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        containers.forEach { it.runStreams.close() }
        server.shutdown()
    }

    private suspend fun TestScope.containerWithSettings(): AppContainer {
        val url = server.url("").toString().removeSuffix("/")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app).also { containers.add(it) }
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
    fun start_otherConversation_keepsBothSubscriptionsActive() = runVmTest {
        // Both subscriptions stay open without a wall-clock response delay.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = containerWithSettings()
        c.runStreams.start("rA", "sA")
        awaitCondition { server.requestCount >= 1 }
        c.runStreams.start("rB", "sB")
        assertEquals(ActiveRun("rB", "sB"), c.runStreams.activeRun.value)
        awaitCondition { server.requestCount >= 2 }
        // Starting another conversation must preserve both independent runs.
        testScheduler.advanceUntilIdle()
        assertEquals(ActiveRun("rB", "sB"), c.runStreams.activeRun.value)
        assertEquals(setOf(ActiveRun("rA", "sA"), ActiveRun("rB", "sB")), c.runStreams.activeRuns.value.toSet())
        val requests = List(2) { server.takeRequest(5, TimeUnit.SECONDS)!!.path }.toSet()
        assertEquals(setOf("/v1/runs/rA/events", "/v1/runs/rB/events"), requests)
    }

    @Test
    fun updatesFor_otherRun_filtersOut() = runVmTest {
        server.enqueue(
            events(frame("run.completed", """{"event":"run.completed","output":"x"}"""))
        )
        val c = containerWithSettings()
        val received = collectUpdates(c, "rX")
        val actualRun = collectUpdates(c, "rY")
        c.runStreams.start("rY", "sY")
        awaitCondition { actualRun.any { it is RunStreamUpdate.Event && it.event is RunEvent.Terminal } }
        awaitCondition { c.runStreams.activeRun.value == null }
        assertEquals(1, server.requestCount)
        assertTrue(received.isEmpty())
    }

    @Test
    fun start_otherRunDuringPoll_finishesWithoutDisturbingOriginal() = runVmTest {
        // r1 is polling while a second conversation starts and completes.
        // Completion of r2 must leave r1's subscription active.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/runs/r1/events" -> MockResponse().setBody("")
                request.path == "/v1/runs/r1" ->
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
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
        val second = collectUpdates(c, "r2")
        c.runStreams.start("r2", "s2")
        awaitCondition { second.any { it is RunStreamUpdate.Event && it.event is RunEvent.Terminal } }
        awaitCondition { c.runStreams.activeRuns.value.none { it.runId == "r2" } }
        assertTrue(c.runStreams.activeRuns.value.any { it.runId == "r1" })
        c.runStreams.close()
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
