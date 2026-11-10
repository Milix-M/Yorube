package com.hermes.client.audit

import com.hermes.client.testutil.takeRequestOrFail
import android.app.NotificationManager
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.*
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.stream.*
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import com.hermes.client.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamRecoveryCoverageTest {
    private lateinit var server: MockWebServer
    private val containers = mutableListOf<AppContainer>()
    private val models = mutableListOf<ChatViewModel>()
    private val release = AtomicBoolean(false)
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() {
        release.set(true)
        models.forEach { it.viewModelScope.cancel() }
        containers.forEach { it.runStreams.close() }
        server.shutdown()
    }
    private suspend fun container() = AppContainer(ApplicationProvider.getApplicationContext()).also {
        containers.add(it)
        it.settingsStore.save(ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k"))
    }
    private fun frame(name: String, data: String) = "event: $name\ndata: $data\n\n"
    private fun events(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private val clarify = """{"request_id":"q","execution_generation":2,"prompt":"Which?","type":"clarify"}"""

    @Test fun restoredQuestionNotifiesAndResolvedEventClearsIt() = runVmTest {
        val c = container()
        val status = HermesJson.decodeFromString<RunStatusResponse>("""{"status":"running","session_id":"s","pending_controls":[$clarify]}""")
        val run = ActiveRun("r", "s")
        c.runNotifications.onUpdate(run, RunStreamUpdate.Resync("r", status))
        val manager = c.appContext.getSystemService(NotificationManager::class.java)
        assertEquals("hermes_approvals", manager.activeNotifications.single().notification.channelId)
        val event = parseRunEvent("clarify.answered", HermesJson.parseToJsonElement("""{"request_id":"q"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(RunEvent.ControlResolved("q"), event)
        c.runNotifications.onUpdate(run, RunStreamUpdate.Event("r", event))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun subscriberBeforeStartKeepsJournalWhenSessionBecomesKnown() = runVmTest {
        val c = container()
        val received = mutableListOf<RunStreamUpdate>()
        backgroundScope.launch { c.runStreams.updatesFor("r").collect { received.add(it) } }
        testScheduler.runCurrent()
        server.enqueue(events(frame("run.completed", """{"output":"done"}""")))
        c.runStreams.start("r", "s")
        awaitCondition { !c.runStreams.working.value && received.isNotEmpty() }
        assertTrue(received.any { it is RunStreamUpdate.Event && it.event is RunEvent.Terminal })
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidConnectionSettlesTrackingAsDead() = runVmTest {
        val c = container()
        c.settingsStore.save(c.settingsStore.current().copy(baseUrl = "http://remote.invalid"))
        c.runStreams.start("r", "s")
        awaitCondition { !c.runStreams.working.value }
        val outcome = c.runStreams.updatesFor("r").first() as RunStreamUpdate.Dead
        assertTrue(outcome.cause is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test fun settingsCannotChangeServerWhileRunIsTracked() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        c.runStreams.start("r", "s")
        awaitCondition { server.requestCount == 1 }
        val vm = SettingsViewModel(c)
        val stored = c.settingsStore.current()
        awaitCondition { vm.baseUrl == stored.baseUrl }
        vm.apiKey = "different"
        vm.save()
        awaitCondition { vm.testOk == false }
        assertFalse(vm.saved)
        assertEquals(stored.apiKey, c.settingsStore.current().apiKey)
        assertTrue(c.runStreams.working.value)
        vm.viewModelScope.cancel()
    }

    @Test fun proxyCredentialChangesAreAlsoRejectedDuringTracking() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        c.runStreams.start("r", "s")
        awaitCondition { server.requestCount == 1 }
        val vm = SettingsViewModel(c)
        val stored = c.settingsStore.current()
        awaitCondition { vm.baseUrl == stored.baseUrl }
        vm.cfClientId = "changed"
        vm.save()
        awaitCondition { vm.testOk == false }
        assertEquals(stored.cfClientId, c.settingsStore.current().cfClientId)
        assertFalse(vm.saved)
        vm.viewModelScope.cancel()
    }

    @Test fun sessionMutationsCompleteOnIoDispatcher() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        withContext(Dispatchers.IO) {
            c.api().updateSessionTitle("s", "renamed")
            c.api().resolveUnknownRun("r", "admission", 2)
        }
        val title = server.takeRequestOrFail()
        assertEquals("PATCH", title.method)
        assertTrue(title.body.readUtf8().contains("renamed"))
        assertEquals("/v1/runs/r/resolve-unknown", server.takeRequestOrFail().path)
    }

    @Test fun cancelledSettingsReadPropagatesCancellation() = runVmTest {
        val c = container()
        var returned = false
        val job = launch {
            currentCoroutineContext().cancel()
            c.settingsStore.current()
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    @Test fun closingStreamDuringPublicationCancelsNotificationWork() = runVmTest {
        val c = container()
        server.enqueue(events(frame("message.delta", """{"delta":"partial"}""")))
        val received = mutableListOf<RunStreamUpdate>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            c.runStreams.updates.collect {
                received.add(it)
                c.runStreams.close()
            }
        }
        c.runStreams.start("r", "s")
        awaitCondition { received.isNotEmpty() && !c.runStreams.working.value }
        assertTrue(received.single() is RunStreamUpdate.Event)
        assertTrue(c.appContext.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun cancellingSessionCreationAndForkDoesNotReturnSuccess() = runVmTest {
        val c = container()
        for (fork in listOf(false, true)) {
            server.enqueue(MockResponse().setBody("""{"id":"s"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
            val before = server.requestCount
            var returned = false
            val job = launch {
                if (fork) c.api().forkSession("s") else c.api().createSession("new")
                returned = true
            }
            awaitCondition { server.requestCount > before }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(returned)
        }
    }

    private suspend fun TestScope.waitingQuestion(c: AppContainer, answer: MockResponse, control: String = clarify): ChatViewModel {
        val subscribed = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"r"}""")
                "/v1/runs/r" -> MockResponse().setBody("""{"status":"running","session_id":"s","pending_controls":[$control]}""")
                "/v1/runs/r/clarify", "/v1/runs/r/approval" -> answer
                "/v1/runs/r/events" -> {
                    subscribed.set(true)
                    while (!release.get()) Thread.sleep(10)
                    events(frame("run.completed", """{"output":"done"}"""))
                }
                else -> MockResponse().setBody("{}")
            }
        }
        // pollFirst restores the pending question independently of an open SSE connection.
        c.runStreams.start("r", "s", pollFirst = true)
        val vm = ChatViewModel(c, "s").also { models.add(it) }
        awaitCondition { (vm.clarify != null || vm.approval != null) && subscribed.get() }
        return vm
    }

    @Test fun cancelledApprovalDoesNotReportFailure() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS),
            """{"request_id":"a","execution_generation":1,"command":"ls"}""")
        val before = server.requestCount
        vm.answerApproval("once")
        awaitCondition { server.requestCount > before }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertNull(vm.error)
    }

    @Test fun failedControlRecoveryShowsOriginalConflict() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setResponseCode(409).setBody("""{"error":{"code":"stale_generation","message":"stale"}}"""))
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/v1/runs/r")
                MockResponse().setResponseCode(503)
                else original.dispatch(request)
        }
        vm.answerClarify("answer")
        awaitCondition { vm.error != null }
        assertTrue(vm.error!!.contains("409"))
        assertNull(vm.clarify)
    }

    @Test fun cancelledHistoryLoadDoesNotShowConnectionFailure() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("""{"messages":[]}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val vm = ChatViewModel(c, "s").also { models.add(it) }
        awaitCondition { server.requestCount == 1 }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertFalse(vm.historyLoading)
        assertNull(vm.error)
    }

    @Test fun replayedControlsMustMatchCurrentRequestAndGeneration() = runVmTest {
        val c = container()
        val status = """{"status":"running","session_id":"s","pending_controls":[$clarify]}"""
        server.enqueue(MockResponse().setBody(status))
        server.enqueue(events(frame("clarify.request", """{"request_id":"q","execution_generation":1,"prompt":"old"}""") +
            frame("clarify.request", clarify) + frame("run.completed", """{"output":"done"}""")))
        server.enqueue(MockResponse().setBody(status))
        server.enqueue(MockResponse().setBody(status))
        c.runStreams.start("r", "s", pollFirst = true)
        awaitCondition { !c.runStreams.working.value }
        val journal = c.runStreams.updatesFor("r").take(3).toList()
        val questions = journal.filterIsInstance<RunStreamUpdate.Event>().map { it.event }.filterIsInstance<RunEvent.ClarifyRequested>()
        assertEquals(listOf(RunEvent.ClarifyRequested("q", 2, "Which?")), questions)
        assertEquals(4, server.requestCount)
    }

    @Test fun staleQuestionAnswerRestoresLatestPendingControl() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setResponseCode(409).setBody("""{"error":{"code":"stale_generation"}}"""))
        vm.answerClarify("answer")
        awaitCondition { vm.clarify != null }
        assertEquals("q", vm.clarify!!.requestId)
        assertNull(vm.error)
        assertTrue(vm.busy)
    }

    @Test fun permissionFailureClearsQuestionAndBusyState() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setResponseCode(403).setBody("""{"error":{"code":"permission_denied","message":"denied"}}"""))
        vm.answerClarify("answer")
        awaitCondition { vm.error != null }
        assertFalse(vm.busy)
        assertNull(vm.clarify)
        assertNull(vm.approval)
    }

    @Test fun cancellationDoesNotBecomeQuestionError() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val before = server.requestCount
        vm.answerClarify("answer")
        awaitCondition { server.requestCount > before }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertNull(vm.error)
    }

    @Test fun cancelledCommandDoesNotPublishFailureOrCreateSession() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("""{"session_id":"created"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val vm = ChatViewModel(c, null).also { models.add(it) }
        vm.input = "/new named"; vm.onSend()
        awaitCondition { server.requestCount == 1 }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertEquals("", vm.sessionId)
        assertTrue(vm.messages.none { it.kind == MsgKind.ERROR })
    }

    @Test fun cancelledGenericCommandDoesNotReportAnError() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val vm = ChatViewModel(c, null).also { models.add(it) }
        vm.input = "/model"; vm.onSend()
        awaitCondition { server.requestCount == 1 }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertTrue(vm.messages.isEmpty())
    }

    @Test fun stoppingGoneRunSettlesLocally() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}"))
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/v1/runs/r/stop")
                MockResponse().setResponseCode(404).setBody("""{"error":{"code":"run_not_found"}}""")
                else original.dispatch(request)
        }
        vm.onStop()
        awaitCondition { !vm.busy }
        assertNull(vm.error)
        assertNull(vm.clarify)
        assertNull(vm.approval)
    }

    @Test fun cancelledStopDoesNotSetErrorOrSettleActiveRun() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}"))
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/v1/runs/r/stop")
                MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS)
                else original.dispatch(request)
        }
        val before = server.requestCount
        vm.onStop()
        awaitCondition { server.requestCount > before }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertNull(vm.error)
        assertTrue(vm.busy)
    }

    @Test fun cancelledSteerDoesNotBecomeAnError() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}"))
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/v1/runs/r/steer")
                MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS)
                else original.dispatch(request)
        }
        val before = server.requestCount
        vm.input = "steer"; vm.onSteer()
        awaitCondition { server.requestCount > before }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertNull(vm.error)
        assertTrue(vm.busy)
    }

    @Test fun resolvedControlsClearChatBeforeRunEnds() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"running","session_id":"s"}"""))
        server.enqueue(events(
            frame("approval.request", """{"request_id":"a","execution_generation":1,"command":"ls"}""") +
            frame("clarify.request", clarify) +
            frame("approval.responded", """{"request_id":"a"}""") +
            frame("clarify.answered", """{"request_id":"q"}""") +
            frame("message.delta", """{"delta":"continuing"}""")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val vm = ChatViewModel(c, null).also { models.add(it) }
        vm.input = "start"; vm.onSend()
        awaitCondition { vm.messages.any { it.text == "continuing" } }
        assertTrue(vm.busy)
        assertNull(vm.approval)
        assertNull(vm.clarify)
        assertNull(vm.error)
    }

    @Test fun activeRunIsListedAndHistoryReloadIsRejectedWhileBusy() = runVmTest {
        val c = container()
        val vm = waitingQuestion(c, MockResponse().setBody("{}"))
        vm.input = "/agents"; vm.onSend()
        assertTrue(vm.messages.last().text.contains("r  s"))
        vm.input = "/history"; vm.onSend()
        assertEquals(MsgKind.ERROR, vm.messages.last().kind)
        assertTrue(vm.busy)
    }

    @Test fun cancelledCreationObserverDoesNotRenderFailure() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"r"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"status":"running","session_id":"s"}"""))
        server.enqueue(events(frame("run.completed", """{"output":"done"}""")))
        val vm = ChatViewModel(c, null).also { models.add(it) }
        vm.input = "start"; vm.onSend()
        awaitCondition { server.requestCount == 1 }
        vm.viewModelScope.cancel()
        awaitCondition { server.requestCount == 3 && !c.runStreams.working.value }
        assertNull(vm.error)
        assertTrue(vm.messages.none { it.kind == MsgKind.ERROR })
    }

    @Test fun cancelledForkDoesNotTurnIntoForeignSessionFailure() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"permission_denied"}}"""))
        val vm = ChatViewModel(c, "s").also { models.add(it) }
        awaitCondition { vm.foreignBlocked && !vm.historyLoading }
        server.enqueue(MockResponse().setBody("""{"session_id":"forked"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        vm.forkAndContinue()
        awaitCondition { server.requestCount == 2 }
        vm.viewModelScope.cancel()
        withContext(Dispatchers.IO) { Thread.sleep(250) }
        testScheduler.runCurrent()
        assertNull(vm.error)
        assertEquals("s", vm.sessionId)
    }

    @Test fun rejectedHistoryCommandDoesNotStartAnotherRequest() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("""{"messages":[]}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val vm = ChatViewModel(c, "s").also { models.add(it) }
        vm.input = "/history"; vm.onSend()
        assertTrue(vm.messages.any { it.kind == MsgKind.ERROR })
        awaitCondition { !vm.historyLoading }
        assertEquals(1, server.requestCount)
    }
}
