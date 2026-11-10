package com.hermes.client.data.stream

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.HermesJson
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.api.RunImage
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.SecretCipher
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RunOutboxTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val containers = mutableListOf<AppContainer>()

    @Before fun setup() {
        context.getSharedPreferences("run_outbox", 0).edit().clear().commit()
        context.getSharedPreferences("active_runs", 0).edit().clear().commit()
        context.getSharedPreferences("run_queue", 0).edit().clear().commit()
        server = MockWebServer().apply { start() }
    }
    @After fun cleanup() {
        containers.forEach { it.runStreams.close() }
        server.shutdown()
    }

    private fun settings() = ClientSettings(server.url("/").toString().trimEnd('/'), "secret-key")
    private fun container() = AppContainer(context).also { containers.add(it) }
    private val capabilities = """{"features":{"runs_idempotency":{"supported":true,"durable":true,"retention_seconds":86400}}}"""

    @Test fun pendingRecordSurvivesRecreationAndKeepsOriginalPayloadAndKey() {
        val settings = settings()
        val first = RunOutbox(context).prepare(RunCreateRequest("private prompt", model = "model-a",
            images = listOf(RunImage("private.png", "data:image/png;base64,AQID"))), settings, null)
        val restored = RunOutbox(context).prepare(first.pending.request.copy(model = "model-b"), settings, null)
        assertFalse(first.replay)
        assertTrue(restored.replay)
        assertEquals(first.pending, restored.pending)
        val rotatedAccess = settings.copy(cfClientId = "renewed-id", cfClientSecret = "renewed-secret")
        assertEquals(first.pending, RunOutbox(context).prepare(first.pending.request, rotatedAccess, null).pending)
        val stored = context.getSharedPreferences("run_outbox", 0).all.toString()
        assertFalse(stored.contains("private prompt"))
        assertFalse(stored.contains("secret-key"))
        assertFalse(stored.contains("data:image"))
        val changedPrincipal = RunOutbox(context).prepare(first.pending.request, settings.copy(apiKey = "other-key"), null)
        assertNotEquals(first.pending.key, changedPrincipal.pending.key)
        val explicit = RunOutbox(context).prepare(RunCreateRequest("queued"), settings, "queue-key")
        assertEquals("queue-key", explicit.pending.key)
        assertEquals(explicit.pending, RunOutbox(context).prepare(RunCreateRequest("queued"), settings, "queue-key").pending)
        assertThrows(IllegalStateException::class.java) {
            RunOutbox(context).prepare(RunCreateRequest("edited after uncertain send"), settings, "queue-key")
        }
        RunOutbox(context).accepted(first.pending.key)
        assertFalse(RunOutbox(context).pending(settings).any { it.key == first.pending.key })
    }

    @Test fun lostAcceptanceRestoresSameKeyAndCreatesOnlyOneServerExecution() = runVmTest {
        val posts = mutableListOf<Pair<String?, String>>()
        val executions = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/runs" -> {
                    synchronized(posts) { posts.add(request.getHeader("Idempotency-Key") to request.body.readUtf8()) }
                    if (posts.size == 1) {
                        executions.incrementAndGet()
                        // The server accepted it, but the response was truncated in transit.
                        MockResponse().setResponseCode(202).setBody("{\"run_id\":")
                    } else MockResponse().setResponseCode(202).setBody("""{"run_id":"r","replayed":true}""")
                }
                "/v1/capabilities" -> MockResponse().setBody(capabilities)
                "/v1/runs/r" -> MockResponse().setBody("""{"status":"completed","session_id":"s","output":"done"}""")
                "/v1/runs/r/events" -> MockResponse().setBody("event: run.completed\ndata: {\"output\":\"done\"}\n\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val settings = settings()
        val first = container()
        first.settingsStore.save(settings)
        try { first.runStreams.createTracked(RunCreateRequest("execute once"), settings); fail("truncated acceptance must fail") }
        catch (_: kotlinx.serialization.SerializationException) { }
        first.runStreams.close()
        val restored = container()
        restored.runStreams.restore()
        awaitCondition { !restored.runStreams.working.value }
        assertEquals(2, posts.size)
        assertEquals(posts[0], posts[1])
        assertEquals(1, executions.get())
        assertTrue(RunOutbox(context).pending(settings).isEmpty())
    }

    @Test fun uncertainRetryWithoutDurableServerSupportDoesNotPostAgain() = runVmTest {
        val settings = settings()
        RunOutbox(context).prepare(RunCreateRequest("execute"), settings, null)
        server.enqueue(MockResponse().setBody("""{"features":{}}"""))
        val c = container()
        try { c.runStreams.createTracked(RunCreateRequest("execute"), settings); fail("unsafe replay must fail") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("previous message")) }
        assertEquals("/v1/capabilities", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals(1, server.requestCount)
        assertEquals(1, RunOutbox(context).pending(settings).size)
        c.settingsStore.save(settings)
        server.enqueue(MockResponse().setBody("""{"features":{}}"""))
        c.runStreams.restore()
        assertEquals(2, server.requestCount)
        assertEquals(1, RunOutbox(context).pending(settings).size)
    }

    @Test fun expiredRetryDoesNotStartAnotherExecution() = runVmTest {
        val settings = settings()
        val prepared = RunOutbox(context).prepare(RunCreateRequest("execute"), settings, null)
        val expired = prepared.pending.copy(createdAt = 0)
        context.getSharedPreferences("run_outbox", 0).edit().putString(expired.key,
            SecretCipher.encrypt(HermesJson.encodeToString(expired), expired.key)).commit()
        server.enqueue(MockResponse().setBody(capabilities))
        try { container().runStreams.createTracked(expired.request, settings); fail("expired replay must fail") }
        catch (_: IllegalStateException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun definiteRejectionRemovesPendingRecord() = runVmTest {
        val settings = settings()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid input"}"""))
        try { container().runStreams.createTracked(RunCreateRequest("invalid"), settings); fail("rejection must fail") }
        catch (_: com.hermes.client.data.api.HermesApiException) { }
        assertTrue(RunOutbox(context).pending(settings).isEmpty())
    }

    @Test fun simultaneousRecoverySharesOneRequest() = runVmTest {
        val settings = settings()
        val posts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/runs" -> {
                    posts.incrementAndGet()
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"r"}""").setBodyDelay(500, TimeUnit.MILLISECONDS)
                }
                "/v1/runs/r" -> MockResponse().setBody("""{"status":"running","session_id":"s"}""")
                "/v1/runs/r/events" -> MockResponse().setBody("event: run.completed\ndata: {\"output\":\"done\"}\n\n")
                else -> MockResponse().setResponseCode(500)
            }
        }
        val c = container()
        c.settingsStore.save(settings)
        val first = async { c.runStreams.createTracked(RunCreateRequest("same"), settings) }
        val second = async { c.runStreams.createTracked(RunCreateRequest("same"), settings) }
        awaitCondition { posts.get() == 1 }
        c.runStreams.restore()
        awaitCondition { first.isCompleted && second.isCompleted }
        assertEquals(first.await(), second.await())
        assertEquals(1, posts.get())
    }

    @Test fun cancellingRestoreLeavesUncertainRecordIntact() = runVmTest {
        val settings = settings()
        val c = container()
        c.settingsStore.save(settings)
        RunOutbox(context).prepare(RunCreateRequest("uncertain"), settings, null)
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val restore = launch { c.runStreams.restore() }
        awaitCondition { server.requestCount == 1 }
        restore.cancel()
        awaitCondition { restore.isCompleted }
        assertTrue(restore.isCancelled)
        assertEquals(1, RunOutbox(context).pending(settings).size)
    }

    @Test fun queueUnlocksAfterDefiniteRejectionAndStaysLockedForUncertainOutcome() = runVmTest {
        for (status in listOf(400, 503)) {
            val settings = settings()
            val c = container()
            c.settingsStore.save(settings)
            c.runStreams.queue("s").add("queued-$status")
            c.runStreams.saveQueue("s")
            server.enqueue(MockResponse().setBody("""{"status":"completed","session_id":"s"}"""))
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"rejected"}"""))
            c.runStreams.start("parent-$status", "s", pollFirst = true)
            awaitCondition { !c.runStreams.working.value }
            assertEquals(status == 503, c.runStreams.queueBusy("s"))
            assertEquals(listOf("queued-$status"), c.runStreams.queue("s"))
            c.runStreams.queue("s").clear()
            c.runStreams.saveQueue("s")
        }
    }

    @Test fun restoredQueueRemovesAcknowledgedHeadAndContinuesWithNextMessage() = runVmTest {
        verifyQueueRecovery(savedAcceptance = false)
    }

    @Test fun crashAfterSavingAcceptanceDoesNotRaceQueueRecoveryWithSavedRun() = runVmTest {
        verifyQueueRecovery(savedAcceptance = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyQueueRecovery(savedAcceptance: Boolean) {
        val posts = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, String>>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/capabilities" -> MockResponse().setBody(capabilities)
                request.path == "/v1/runs" -> {
                    val body = request.body.readUtf8()
                    posts.add(request.getHeader("Idempotency-Key") to body)
                    when {
                        posts.size == 1 -> MockResponse().setResponseCode(202).setBody("{\"run_id\":")
                        body.contains("first") -> MockResponse().setResponseCode(202).setBody("""{"run_id":"q1","replayed":true}""")
                        else -> MockResponse().setResponseCode(202).setBody("""{"run_id":"q2"}""")
                    }
                }
                request.path!!.endsWith("/events") -> MockResponse().setBody("event: run.completed\ndata: {\"output\":\"done\"}\n\n")
                else -> MockResponse().setBody("""{"status":"completed","session_id":"s","output":"done"}""")
            }
        }
        val settings = settings()
        val first = container()
        first.settingsStore.save(settings)
        first.runStreams.queue("s").addAll(listOf("first", "second"))
        first.runStreams.saveQueue("s")
        first.runStreams.start("parent", "s", pollFirst = true)
        awaitCondition { !first.runStreams.working.value }
        assertEquals(1, posts.size)
        assertTrue(first.runStreams.queueBusy("s"))
        first.runStreams.close()
        if (savedAcceptance) {
            // A process can die after saving the accepted ID but before acknowledging the queue.
            context.getSharedPreferences("active_runs", 0).edit().putString("runs",
                """[{"run":"q1","session":"s","endpoint":"${settings.baseUrl}"}]""").commit()
        }
        val restored = container()
        restored.runStreams.restore()
        awaitCondition { !restored.runStreams.working.value }
        assertEquals(3, posts.size)
        assertEquals(posts[0], posts[1])
        assertTrue(posts[2].second.contains("second"))
        assertNotEquals(posts[1].first, posts[2].first)
        assertFalse(restored.runStreams.queueBusy("s"))
        assertTrue(restored.runStreams.queue("s").isEmpty())
        assertTrue(RunOutbox(context).pending(settings).isEmpty())
        val recreated = container()
        recreated.settingsStore.current()
        assertTrue(recreated.runStreams.queue("s").isEmpty())
        assertFalse(recreated.runStreams.queueBusy("s"))
    }

    @Test fun locallyRejectedOversizedMessageDoesNotLeaveAnUncertainSend() = runVmTest {
        val settings = settings()
        val c = container()
        c.settingsStore.save(settings)
        try {
            c.runStreams.createTracked(RunCreateRequest("あ".repeat(3_400_000)), settings)
            fail("UTF-8 payload must fit the request limit")
        } catch (e: com.hermes.client.data.api.HermesApiException) {
            assertEquals(413, e.status)
        }
        assertEquals(0, server.requestCount)
        assertTrue(RunOutbox(context).pending(settings).isEmpty())
        assertFalse(c.runStreams.working.value)
    }

    @Test fun definiteRejectionDuringRecoveryUnlocksQueueForEditing() = runVmTest {
        val settings = settings()
        val c = container()
        c.settingsStore.save(settings)
        c.runStreams.queue("s").add("fix this")
        c.runStreams.saveQueue("s")
        context.getSharedPreferences("run_queue", 0).edit()
            .putString("key:${settings.baseUrl}|s", "saved-key").commit()
        RunOutbox(context).prepare(RunCreateRequest("fix this", sessionId = "s"), settings, "saved-key")
        server.enqueue(MockResponse().setBody(capabilities))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid"}"""))
        c.runStreams.restore()
        assertFalse(c.runStreams.queueBusy("s"))
        assertEquals(listOf("fix this"), c.runStreams.queue("s"))
        assertTrue(RunOutbox(context).pending(settings).isEmpty())
    }
}
