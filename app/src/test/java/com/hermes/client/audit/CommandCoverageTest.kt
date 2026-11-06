package com.hermes.client.audit

import com.hermes.client.testutil.takeRequestOrFail
import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.*
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import com.hermes.client.ui.screens.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CommandCoverageTest {
    private lateinit var server: MockWebServer
    private val containers = mutableListOf<AppContainer>()
    private val models = mutableListOf<ChatViewModel>()

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        containers.forEach { it.runStreams.close() }
        server.shutdown()
    }

    private suspend fun container(): AppContainer = AppContainer(ApplicationProvider.getApplicationContext()).also {
        containers.add(it)
        it.settingsStore.save(ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "private-key"))
    }
    private fun model(c: AppContainer, session: String? = null) = ChatViewModel(c, session).also { models.add(it) }
    private suspend fun TestScope.command(vm: ChatViewModel, text: String): UiMessage {
        val count = vm.messages.size
        vm.input = text
        vm.onSend()
        awaitCondition { vm.messages.size > count }
        return vm.messages.last()
    }

    @Test fun localInspectionCommandsDoNotLeakCredentialsOrCreateRuns() = runVmTest {
        val c = container()
        c.settingsStore.save(c.settingsStore.current().copy(model = "m", provider = "p", cfClientSecret = "private-proxy"))
        val vm = model(c)
        assertTrue(command(vm, "/version").text.startsWith("Yorube "))
        assertTrue(command(vm, "/palette").text.contains("/help"))
        val config = command(vm, "/config").text
        assertTrue(config.contains("p/m"))
        assertFalse(config.contains("private-key"))
        assertFalse(config.contains("private-proxy"))
        assertEquals(MsgKind.STATUS, command(vm, "/agents").kind)
        assertEquals(MsgKind.ERROR, command(vm, "/version extra").kind)
        vm.input = "/prompt reusable prompt"; vm.onSend()
        assertEquals("reusable prompt", vm.input)
        assertEquals(0, server.requestCount)
    }

    @Test fun copyAndSaveExportOnlyConversationText() = runVmTest {
        val c = container(); val vm = model(c)
        assertEquals(MsgKind.ERROR, command(vm, "/copy").kind)
        assertEquals(MsgKind.ERROR, command(vm, "/save").kind)
        vm.messages.add(UiMessage(100, MsgKind.USER, "question"))
        vm.messages.add(UiMessage(101, MsgKind.ASSISTANT, "answer"))
        vm.messages.add(UiMessage(102, MsgKind.TOOL, "internal tool details"))
        vm.input = "/copy"; vm.onSend()
        assertEquals("answer", c.appContext.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text)
        vm.input = "/save"; vm.onSend()
        val chooser = shadowOf(c.appContext as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", share.type)
        assertEquals("user:\nquestion\n\nassistant:\nanswer", share.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(0, server.requestCount)
    }

    @Test fun unavailableShareHandlerShowsLocalError() = runVmTest {
        val context = object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext<android.content.Context>()) {
            override fun startActivity(intent: Intent) { throw android.content.ActivityNotFoundException("No share handler") }
        }
        val c = AppContainer(context).also { containers.add(it) }
        c.settingsStore.save(ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k"))
        val vm = model(c)
        vm.messages.add(UiMessage(1, MsgKind.ASSISTANT, "answer"))
        val message = command(vm, "/save")
        assertEquals(MsgKind.ERROR, message.kind)
        assertTrue(message.text.contains("No share handler"))
        assertEquals(0, server.requestCount)
    }

    @Test fun historySeparatesToolMarkupFromAssistantReply() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("""{"messages":[{"role":"assistant","content":"answer <untrusted_tool_result source=\"web\">tool output</untrusted_tool_result>"}]}"""))
        val vm = model(c, "s")
        awaitCondition { !vm.historyLoading }
        assertTrue(vm.messages.any { it.kind == MsgKind.ASSISTANT && it.text.trim() == "answer" })
        assertTrue(vm.messages.any { it.kind == MsgKind.TOOL && it.text.contains("tool output") })
        assertTrue(vm.messages.none { it.kind == MsgKind.ASSISTANT && it.text.contains("untrusted_tool_result") })
    }

    @Test fun resumeSearchesBeyondFirstPage() = runVmTest {
        val c = container(); val vm = model(c)
        val first = (1..50).joinToString(",") { """{"id":"s$it","title":"chat $it"}""" }
        server.enqueue(MockResponse().setBody("""{"sessions":[$first]}"""))
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"target","title":"last"}]}"""))
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        vm.input = "/resume target"; vm.onSend()
        awaitCondition { vm.sessionId == "target" && !vm.historyLoading }
        assertTrue(server.takeRequestOrFail().path!!.contains("offset=0"))
        assertTrue(server.takeRequestOrFail().path!!.contains("offset=50"))
        assertEquals("/api/sessions/target/messages", server.takeRequestOrFail().path)
    }

    @Test fun historyRequiresSessionAndReloadsCurrentConversation() = runVmTest {
        val c = container(); val empty = model(c)
        assertEquals(MsgKind.ERROR, command(empty, "/history").kind)
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        val vm = model(c, "s")
        awaitCondition { !vm.historyLoading }
        server.enqueue(MockResponse().setBody("""{"messages":[{"role":"user","content":"restored"}]}"""))
        vm.input = "/redraw"; vm.onSend()
        awaitCondition { !vm.historyLoading && vm.messages.any { it.text == "restored" } }
        assertEquals(2, server.requestCount)
        assertEquals("/api/sessions/s/messages", server.takeRequestOrFail().path)
        assertEquals("/api/sessions/s/messages", server.takeRequestOrFail().path)
    }

    @Test fun runOptionsValidateValuesAndReportStoredState() = runVmTest {
        val c = container(); val vm = model(c)
        assertTrue(command(vm, "/reasoning").text.contains("default"))
        assertTrue(command(vm, "/fast status").text.contains("default"))
        assertEquals(MsgKind.ERROR, command(vm, "/reasoning impossible").kind)
        assertEquals(MsgKind.ERROR, command(vm, "/fast impossible").kind)
        command(vm, "/fast on")
        assertEquals("priority", c.settingsStore.current().serviceTier)
        command(vm, "/fast off")
        assertEquals("default", c.settingsStore.current().serviceTier)
        command(vm, "/fast priority")
        assertEquals("priority", c.settingsStore.current().serviceTier)
        assertEquals(0, server.requestCount)
    }

    @Test fun cronShowAndEditUseEncodedIdAndStructuredBody() = runVmTest {
        val c = container(); val vm = model(c)
        server.enqueue(MockResponse().setBody("""{"job":{"id":"j","name":"daily"}}"""))
        assertTrue(command(vm, "/cron show job/#x").text.contains("daily"))
        assertEquals("/api/jobs/job%2F%23x", server.takeRequestOrFail().path)
        server.enqueue(MockResponse().setBody("{}"))
        command(vm, "/cron edit job/#x daily | 0 9 * * * | prompt")
        val edit = server.takeRequestOrFail()
        assertEquals("PATCH", edit.method)
        assertEquals("/api/jobs/job%2F%23x", edit.path)
        assertEquals("prompt", (HermesJson.parseToJsonElement(edit.body.readUtf8()) as kotlinx.serialization.json.JsonObject).str("prompt"))
    }

    @Test fun malformedCronArgumentsNeverReachServer() = runVmTest {
        val c = container(); val vm = model(c)
        for (cmd in listOf("show", "show one two", "list extra", "edit", "invalid", "add invalid")) {
            assertEquals(cmd, MsgKind.ERROR, command(vm, "/cron $cmd").kind)
        }
        assertEquals(0, server.requestCount)
        try { c.api().cron("invalid"); fail("Unknown operation must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun discardRejectsKnownOutcomeWithoutResolvingIt() = runVmTest {
        val c = container(); val vm = model(c)
        server.enqueue(MockResponse().setBody("""{"status":"completed"}"""))
        assertEquals(MsgKind.ERROR, command(vm, "/discard r").kind)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequestOrFail().method)
    }

    @Test fun queueEditAndMoveValidateIndexesAndPersistOrder() = runVmTest {
        val c = container()
        server.enqueue(MockResponse().setBody("""{"messages":[]}"""))
        val vm = model(c, "s")
        awaitCondition { !vm.historyLoading }
        c.runStreams.queue("s").addAll(listOf("first", "second", "third"))
        command(vm, "/queue edit 2 revised")
        assertEquals(listOf("first", "revised", "third"), c.runStreams.queue("s"))
        command(vm, "/queue move 3 1")
        assertEquals(listOf("third", "first", "revised"), c.runStreams.queue("s"))
        for (cmd in listOf("edit 0 nope", "edit invalid", "move 0 1", "move 1 9", "move invalid")) {
            assertEquals(MsgKind.ERROR, command(vm, "/queue $cmd").kind)
        }
        assertEquals(listOf("third", "first", "revised"), c.runStreams.queue("s"))
        assertEquals(1, server.requestCount)
    }

    @Test fun skillsSearchAndInspectionFilterResults() = runVmTest {
        val c = container(); val vm = model(c)
        for (operation in listOf("search document", "show Writer", "inspect writer")) {
            server.enqueue(MockResponse().setBody("""{"skills":[{"name":"Writer","description":"documents"},{"name":"Code","description":"programs"}]}"""))
            val output = command(vm, "/skills $operation").text
            assertTrue(output.contains("Writer"))
            assertFalse(output.contains("Code"))
        }
        server.enqueue(MockResponse().setBody("""{"skills":[]}"""))
        assertEquals(MsgKind.STATUS, command(vm, "/reload-skills").kind)
        server.enqueue(MockResponse().setBody("""{"toolsets":[]}"""))
        assertEquals(MsgKind.STATUS, command(vm, "/tools list").kind)
        assertEquals(MsgKind.ERROR, command(vm, "/tools edit").kind)
        assertEquals(5, server.requestCount)
    }

    @Test fun pinningAndDeletingPreserveServerOrderWithinGroups() = runVmTest {
        val c = container(); val vm = ConversationListViewModel(c)
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""))
        vm.refresh()
        awaitCondition { vm.sessions.size == 3 }
        vm.togglePin("c")
        awaitCondition { vm.isPinned("c") }
        assertEquals(listOf("c", "a", "b"), vm.sessions.map { it.key })
        vm.togglePin("c")
        awaitCondition { !vm.isPinned("c") }
        vm.togglePin("b")
        awaitCondition { vm.isPinned("b") }
        server.enqueue(MockResponse().setBody("{}"))
        vm.delete("b")
        awaitCondition { !vm.isPinned("b") && vm.sessions.size == 2 }
        assertFalse(c.settingsStore.current().pinnedSessionIds.contains("b"))
        vm.viewModelScope.cancel()
    }
}
