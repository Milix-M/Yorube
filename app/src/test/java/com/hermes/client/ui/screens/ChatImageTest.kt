package com.hermes.client.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.api.RunImage
import com.hermes.client.data.attachments.ImageAttachment
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageTest {
    @Test fun sentImagesRemainInTheMessageAndReloadFromHistory() = runVmTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = MockResponse().setBody(when {
                    r.path == "/v1/runs" -> """{"run_id":"image-run","session_id":"image-session","status":"accepted"}"""
                    r.path.orEmpty().endsWith("/events") -> "event: run.completed\ndata: {\"output\":\"A blue image\"}\n\n"
                    r.path.orEmpty().endsWith("/messages") -> """{"messages":[{"role":"user","content":"Describe it\n\n[Image attached at: /tmp/server-photo.png]"},{"role":"assistant","content":"A blue image"}]}"""
                    else -> """{"status":"completed","session_id":"image-session","output":"A blue image"}"""
                }).apply { if (r.path.orEmpty().endsWith("/events")) setHeader("Content-Type", "text/event-stream") }
            }
            server.start()
            val c = AppContainer(ApplicationProvider.getApplicationContext<Application>())
            val settings = ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k")
            c.settingsStore.save(settings); c.settingsStore.current()
            val vm = ChatViewModel(c, null)
            try {
                val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                val image = RunImage("blue.png", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
                vm.attachments.add(ImageAttachment(image, bitmap))
                vm.input = "Describe it"; vm.onSend()
                awaitCondition { !vm.busy }
                val user = vm.messages.single { it.kind == MsgKind.USER }
                assertEquals("Describe it", user.text)
                assertEquals("blue.png", user.images.single().name)
                assertTrue(vm.attachments.isEmpty())
                assertEquals("image-session", vm.sessionId)
                val reopened = ChatViewModel(c, "image-session")
                try {
                    awaitCondition { !reopened.historyLoading && reopened.messages.any { it.kind == MsgKind.USER } }
                    assertEquals(user.images, reopened.messages.single { it.kind == MsgKind.USER }.images)
                    assertEquals("Describe it", reopened.messages.single { it.kind == MsgKind.USER }.text)
                } finally { reopened.viewModelScope.cancel() }
            } finally { vm.viewModelScope.cancel(); c.runStreams.close() }
        }
    }

    @Test fun previewFailureDoesNotOrphanAnAcceptedRun() = runVmTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"run_id":"r","session_id":"s"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"running","session_id":"s"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: run.completed\ndata: {\"output\":\"done\"}\n\n"))
            val c = AppContainer(ApplicationProvider.getApplicationContext<Application>())
            val settings = ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k")
            c.settingsStore.save(settings); c.settingsStore.current()
            try {
                val accepted = c.runStreams.createTracked(RunCreateRequest(input = "image", images = listOf(RunImage("bad", "bad data"))), settings)
                assertEquals("s", accepted.sessionId)
                awaitCondition { !c.runStreams.working.value }
                assertTrue(c.runStreams.activeRuns.value.isEmpty())
            } finally { c.runStreams.close() }
        }
    }
}
