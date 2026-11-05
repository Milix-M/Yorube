package com.hermes.client.audit

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.*
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.stream.*
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AuditRegressionTest {
    private lateinit var server: MockWebServer
    private val containers = mutableListOf<AppContainer>()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { containers.forEach { it.runStreams.close() }; server.shutdown() }
    private suspend fun TestScope.container(): AppContainer {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        containers.add(c)
        c.settingsStore.save(ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k"))
        return c
    }
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun frame(name: String, payload: String, id: Int? = null) =
        (id?.let { "id: $it\n" } ?: "") + "event: $name\ndata: $payload\n\n"
    private fun done(text: String = "done") = frame("run.completed", """{"output":"$text"}""")

    @Test fun burstAndNonterminalRunEventsAreLossless() = runVmTest {
        val body = frame("run.queued", """{"event":"run.queued"}""") +
            (1..600).joinToString("") { frame("message.delta", """{"data":{"delta":"x"}}""", it) } + done()
        server.enqueue(sse(body))
        val c = container()
        val frames = c.api().runEventFrames("r", 0).toList()
        assertEquals(602, frames.size)
        assertEquals(600, frames.filter { it.event == RunEvent.Delta("x") }.size)
        assertTrue(frames.last().event is RunEvent.Terminal)
        assertEquals("0", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test fun cancellingSilentSocketClosesReaderPromptly() = runVmTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c = container()
        val job = backgroundScope.launch { c.api().runEvents("silent").collect() }
        awaitCondition { server.requestCount == 1 }
        withContext(Dispatchers.IO) { withTimeout(3000) { job.cancelAndJoin() } }
        assertTrue(job.isCompleted)
    }

    @Test fun startingAnotherRunDoesNotCancelFirstAndRepeatedStartIsIdempotent() = runVmTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/v1/runs/a/events" -> sse(done("a")).setBodyDelay(1, TimeUnit.SECONDS)
                "/v1/runs/b/events" -> sse(done("b"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val c = container()
        c.runStreams.start("a", "sa")
        c.runStreams.start("a", "sa", pollFirst = true)
        c.runStreams.start("b", "sb")
        awaitCondition { server.requestCount >= 2 && c.runStreams.activeRuns.value.size == 1 }
        assertEquals("a", c.runStreams.activeRuns.value.single().runId)
        awaitCondition { c.runStreams.activeRuns.value.isEmpty() }
        assertEquals(2, server.requestCount)
    }

    @Test fun lateSubscriberGetsCompleteBurstAndTerminal() = runVmTest {
        server.enqueue(sse((1..600).joinToString("") { frame("message.delta", """{"delta":"x"}""") } + done()))
        val c = container()
        c.runStreams.start("a", "sa")
        awaitCondition { c.runStreams.activeRuns.value.isEmpty() }
        val journal = c.runStreams.updatesFor("a").take(2).toList()
        assertEquals("x".repeat(600), ((journal.first() as RunStreamUpdate.Event).event as RunEvent.Delta).text)
        assertTrue((journal.last() as RunStreamUpdate.Event).event is RunEvent.Terminal)
    }

    @Test fun retryableStatusFailureDoesNotEndRunAndReconnectUsesCursor() = runVmTest {
        val polls = AtomicInteger()
        val streams = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/v1/runs/a") return if (polls.incrementAndGet() == 1)
                    MockResponse().setResponseCode(503) else MockResponse().setBody("""{"status":"running"}""")
                streams.incrementAndGet()
                return if (request.getHeader("Last-Event-ID") == "1") sse(done())
                else sse(frame("message.delta", """{"delta":"a"}""", 1))
            }
        }
        val c = container()
        c.runStreams.start("a", "sa")
        awaitCondition { c.runStreams.activeRuns.value.isEmpty() }
        assertEquals(2, polls.get())
        assertEquals(2, streams.get())
        val requests = List(4) { server.takeRequest() }
        assertEquals("1", requests.last().getHeader("Last-Event-ID"))
    }

    @Test fun queueRunsExactlyOnceWithoutAnyScreen() = runVmTest {
        val creates = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/runs/a/events" -> sse(done("first"))
                "/v1/runs" -> { creates.incrementAndGet(); MockResponse().setResponseCode(202).setBody("""{"run_id":"b"}""") }
                "/v1/runs/b" -> MockResponse().setBody("""{"session_id":"sa","status":"running"}""")
                "/v1/runs/b/events" -> sse(done("second"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val c = container()
        c.runStreams.queue("sa").add("next")
        c.runStreams.saveQueue("sa")
        c.runStreams.start("a", "sa")
        awaitCondition { !c.runStreams.working.value }
        assertEquals(1, creates.get())
        assertTrue(c.runStreams.queue("sa").isEmpty())
    }

    @Test fun unknownOutcomeNeverSubmitsQueuedPrompt() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
        val c = container()
        c.runStreams.queue("sa").add("do not resend")
        c.runStreams.start("a", "sa", pollFirst = true)
        awaitCondition { !c.runStreams.working.value }
        assertEquals(1, server.requestCount)
        assertEquals(listOf("do not resend"), c.runStreams.queue("sa"))
    }

    @Test fun restorePollsSavedRunAndDoesNotUseDifferentServer() = runVmTest {
        val c = container()
        val saved = c.appContext.getSharedPreferences("active_runs", 0)
        val endpoint = c.settingsStore.current().baseUrl
        saved.edit().putString("runs", """[{"run":"a","session":"sa","endpoint":"$endpoint"},{"run":"other","session":"s","endpoint":"https://another.example"}]""").commit()
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":"done"}"""))
        c.runStreams.restore()
        awaitCondition { !c.runStreams.working.value }
        assertEquals(1, server.requestCount)
        assertEquals("/v1/runs/a", server.takeRequest().path)
    }

    @Test fun localNotificationsDeduplicateAndCancelResolvedApproval() = runVmTest {
        val c = container()
        val manager = c.appContext.getSystemService(NotificationManager::class.java)
        val run = ActiveRun("a", "sa")
        val event = RunStreamUpdate.Event("a", RunEvent.ApprovalRequested(ApprovalRequest("p", 1, "ls", null, listOf("once"))))
        c.runNotifications.onUpdate(run, event)
        c.runNotifications.onUpdate(run, event)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("hermes_approvals", manager.activeNotifications.single().notification.channelId)
        c.runNotifications.onUpdate(run, RunStreamUpdate.ControlsCleared("a"))
        assertTrue(manager.activeNotifications.isEmpty())
        c.runNotifications.onUpdate(run, RunStreamUpdate.Event("a", RunEvent.Terminal("completed", "answer", null, null)))
        assertEquals("hermes_runs", manager.activeNotifications.single().notification.channelId)
        c.runNotifications.onUpdate(run, RunStreamUpdate.Event("a", RunEvent.Terminal("completed", "answer", null, null)))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun disabledNotificationsStillAllowStreaming() = runVmTest {
        val c = container()
        c.settingsStore.save(c.settingsStore.current().copy(notificationsEnabled = false))
        server.enqueue(sse(done()))
        c.runStreams.start("a", "sa")
        awaitCondition { !c.runStreams.working.value }
        assertTrue(c.appContext.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun slashModelPreservesNamespacedModelId() = runVmTest {
        val c = container()
        val vm = com.hermes.client.ui.screens.ChatViewModel(c, null)
        vm.input = "/model org/model-name"
        vm.onSend()
        awaitCondition { c.settingsStore.current().model == "org/model-name" }
        assertEquals("", c.settingsStore.current().provider)
        assertEquals(0, server.requestCount)
    }

    @Test fun ambiguousResumeDoesNotOpenAnArbitraryConversation() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"sessions":[{"session_id":"a","title":"work"},{"session_id":"b","title":"work"}]}"""))
        val c = container()
        val vm = com.hermes.client.ui.screens.ChatViewModel(c, null)
        vm.input = "/resume work"
        vm.onSend()
        awaitCondition { vm.messages.any { it.kind == com.hermes.client.ui.screens.MsgKind.ERROR } }
        assertEquals("", vm.sessionId)
        assertEquals(1, server.requestCount)
    }

    @Test fun cronRoutesUseStructuredJsonAndEncodedIds() = runVmTest {
        val c = container()
        val api = c.api()
        server.enqueue(MockResponse().setBody("""{"jobs":[]}"""))
        api.cron("list")
        assertEquals("/api/jobs?include_disabled=true", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("{}"))
        api.cron("pause", "job/#x")
        val pause = server.takeRequest()
        assertEquals("POST", pause.method)
        assertEquals("/api/jobs/job%2F%23x/pause", pause.path)
        server.enqueue(MockResponse().setBody("{}"))
        api.cron("remove", "job")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun cronAddPreservesMultilinePromptAndNeverSubmitsAnAgentRun() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"job":{"id":"j1","name":"daily"}}"""))
        val c = container()
        val vm = com.hermes.client.ui.screens.ChatViewModel(c, null)
        vm.input = "/cron add daily | 0 9 * * * | line one\nline two"
        vm.onSend()
        awaitCondition { vm.messages.isNotEmpty() }
        val request = server.takeRequest()
        assertEquals("/api/jobs", request.path)
        val body = HermesJson.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals("line one\nline two", body.str("prompt"))
        assertEquals(1, server.requestCount)
    }

    @Test fun unknownDiscardUsesExactGenerationWithoutResendingPrompt() = runVmTest {
        server.enqueue(MockResponse().setBody("""{"status":"unknown","admission_id":"admission","execution_generation":7}"""))
        server.enqueue(MockResponse().setBody("{}"))
        val c = container()
        val vm = com.hermes.client.ui.screens.ChatViewModel(c, null)
        vm.input = "/discard run1"
        vm.onSend()
        awaitCondition { vm.messages.isNotEmpty() }
        assertEquals("GET", server.takeRequest().method)
        val resolve = server.takeRequest()
        assertEquals("/v1/runs/run1/resolve-unknown", resolve.path)
        assertEquals("""{"admission_id":"admission","execution_generation":7}""", resolve.body.readUtf8())
        assertEquals(2, server.requestCount)
    }

    @Test fun reasoningAndPriorityOptionsReachTheNextRun() = runVmTest {
        val c = container()
        val vm = com.hermes.client.ui.screens.ChatViewModel(c, null)
        vm.input = "/reasoning high"; vm.onSend()
        // DataStore can expose the new value before the command's coroutine
        // resumes and clears commandRunning. Wait for its UI acknowledgement
        // before sending the next command, as a real user would.
        awaitCondition { vm.messages.size == 1 }
        assertEquals(com.hermes.client.ui.screens.MsgKind.STATUS, vm.messages.last().kind)
        assertEquals("high", c.settingsStore.current().reasoningEffort)
        vm.input = "/fast on"; vm.onSend()
        awaitCondition { vm.messages.size == 2 }
        assertEquals(com.hermes.client.ui.screens.MsgKind.STATUS, vm.messages.last().kind)
        assertEquals("priority", c.settingsStore.current().serviceTier)
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"a"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"running","session_id":"sa"}"""))
        server.enqueue(sse(done()))
        vm.input = "hello"; vm.onSend()
        assertTrue(vm.busy)
        awaitCondition { !vm.busy }
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = request!!.body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertTrue(body.contains("\"service_tier\":\"priority\""))
    }

    @Test fun settingsTestUsesUnsavedConnectionDraft() = runVmTest {
        val c = container()
        c.settingsStore.save(c.settingsStore.current().copy(baseUrl = "https://unused.invalid", apiKey = "old"))
        val vm = com.hermes.client.ui.screens.SettingsViewModel(c)
        awaitCondition { vm.baseUrl == "https://unused.invalid" }
        vm.baseUrl = server.url("/").toString().trimEnd('/')
        vm.apiKey = "draft"
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"features":{}}"""))
        vm.test()
        awaitCondition { vm.testOk != null }
        assertEquals(true, vm.testOk)
        assertEquals("Bearer draft", server.takeRequest().getHeader("Authorization"))
        assertEquals("https://unused.invalid", c.settingsStore.current().baseUrl)
    }

    @Test fun queueCannotCrossServerBoundaries() = runVmTest {
        val c = container()
        c.runStreams.queue("same").add("private prompt")
        c.runStreams.saveQueue("same")
        c.settingsStore.save(c.settingsStore.current().copy(baseUrl = "https://another.invalid"))
        assertTrue(c.runStreams.queue("same").isEmpty())
    }

    @Test fun cancellingCreationObserverDoesNotOrphanAcceptedRun() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"a"}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"status":"running","session_id":"sa"}"""))
        server.enqueue(sse(done()))
        val observer = backgroundScope.launch { c.runStreams.createTracked(RunCreateRequest("hello"), c.settingsStore.current()) }
        awaitCondition { server.requestCount == 1 }
        observer.cancel()
        awaitCondition { server.requestCount == 3 && !c.runStreams.working.value }
        assertEquals(3, server.requestCount)
    }

    @Test fun restoredStreamDoesNotNotifyAlreadyAnsweredPrompts() = runVmTest {
        val c = container()
        val status = """{"status":"running","session_id":"sa","pending_controls":[]}"""
        server.enqueue(MockResponse().setBody(status))
        server.enqueue(sse(frame("approval.request", """{"request_id":"old","execution_generation":1,"command":"old command"}""", 1) + done()))
        server.enqueue(MockResponse().setBody(status))
        c.runStreams.start("a", "sa", pollFirst = true)
        awaitCondition { !c.runStreams.working.value }
        val notifications = c.appContext.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, notifications.size)
        assertEquals("hermes_runs", notifications.single().notification.channelId)
    }

    @Test fun completingAnotherRunDoesNotEraseLateSubscribersJournal() = runVmTest {
        val c = container()
        server.enqueue(sse(done("a")))
        c.runStreams.start("a", "sa")
        awaitCondition { !c.runStreams.working.value }
        server.enqueue(sse(done("b")))
        c.runStreams.start("b", "sb")
        awaitCondition { !c.runStreams.working.value }
        val update = c.runStreams.updatesFor("a").first() as RunStreamUpdate.Event
        assertEquals("a", (update.event as RunEvent.Terminal).output)
    }

    @Test fun newRunKeepsItsSessionWhenOptionalStatusLookupFails() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"new-session"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(sse(done()))
        val accepted = c.runStreams.createTracked(RunCreateRequest("hello"), c.settingsStore.current())
        awaitCondition { !c.runStreams.working.value }
        assertEquals("new-session", accepted.sessionId)
        assertTrue("new-session" in c.settingsStore.current().appOwnedSessionIds)
    }

    @Test @Config(sdk = [33])
    fun deniedNotificationPermissionDoesNotStopStreaming() = runVmTest {
        val c = container()
        org.robolectric.Shadows.shadowOf(c.appContext as android.app.Application)
            .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        server.enqueue(sse(done()))
        c.runStreams.start("a", "sa")
        awaitCondition { !c.runStreams.working.value }
        assertTrue(c.appContext.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertTrue((c.runStreams.updatesFor("a").first() as RunStreamUpdate.Event).event is RunEvent.Terminal)
    }

    @Test fun notificationsOpenDistinctConversations() = runVmTest {
        val c = container()
        val terminal = RunEvent.Terminal("completed", "done", null, null)
        c.runNotifications.onUpdate(ActiveRun("a", "sa"), RunStreamUpdate.Event("a", terminal))
        c.runNotifications.onUpdate(ActiveRun("b", "sb"), RunStreamUpdate.Event("b", terminal))
        val notifications = c.appContext.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(2, notifications.size)
        val intents = notifications.map { org.robolectric.Shadows.shadowOf(it.notification.contentIntent).savedIntent }
        assertEquals(setOf("sa", "sb"), intents.map { it.getStringExtra(com.hermes.client.MainActivity.EXTRA_SESSION_ID) }.toSet())
        assertEquals(2, intents.map { it.data }.toSet().size)
    }


    @Test fun concurrentPinAndOwnershipUpdatesPreserveConnectionAndRunOptions() = runVmTest {
        val c = container()
        val settings = c.settingsStore.current()
        coroutineScope {
            (1..20).forEach { index ->
                launch { c.settingsStore.setPinned("s$index", true) }
                launch { c.settingsStore.markAppOwned("s$index") }
            }
            launch { c.settingsStore.setRunOption("reasoning_effort", "high") }
        }
        c.settingsStore.setPinned("s1", false)
        c.settingsStore.setPinned("s2")
        val saved = c.settingsStore.current()
        assertEquals((3..20).map { "s$it" }.toSet(), saved.pinnedSessionIds)
        assertEquals((1..20).map { "s$it" }.toSet(), saved.appOwnedSessionIds)
        assertEquals(settings.baseUrl, saved.baseUrl)
        assertEquals(settings.apiKey, saved.apiKey)
        assertEquals("high", saved.reasoningEffort)
    }

}
