package com.hermes.client.data.stream

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class QueueDurabilityTest {
    private class FailingQueueContext(base: Context) : ContextWrapper(base) {
        var failCommit = 0
        var commits = 0
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (name != "run_queue") return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }
                        override fun commit(): Boolean {
                            commits++
                            return if (commits == failCommit) false else editor.commit()
                        }
                    }
                }
            }
        }
    }

    @Test fun queueKeyMustReachStorageBeforeAnyPost() = runVmTest {
        verifyStorageFailure(failCommit = 1, rejected = false)
    }

    @Test fun failedAcknowledgementPreservesQueueAndOutboxForRecovery() = runVmTest {
        verifyStorageFailure(failCommit = 2, rejected = false)
    }

    @Test fun failedRejectionCleanupDoesNotAllowEditingAnUnpersistedState() = runVmTest {
        verifyStorageFailure(failCommit = 2, rejected = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyStorageFailure(failCommit: Int, rejected: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = FailingQueueContext(app).apply { this.failCommit = failCommit }
        MockWebServer().use { server ->
            server.start()
            val settings = ClientSettings(server.url("/").toString().trimEnd('/'), "k")
            val posts = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/runs" -> {
                        posts.incrementAndGet()
                        if (rejected) MockResponse().setResponseCode(400).setBody("""{"error":"invalid"}""")
                        else MockResponse().setResponseCode(202).setBody("""{"run_id":"next"}""")
                    }
                    "/v1/capabilities" -> MockResponse().setBody("""{"features":{"runs_idempotency":{"supported":true,"durable":true,"retention_seconds":86400}}}""")
                    "/v1/runs/next/events" -> MockResponse().setBody("event: run.completed\ndata: {}\n\n")
                    else -> MockResponse().setBody("""{"status":"completed","session_id":"s"}""")
                }
            }
            val c = AppContainer(context)
            c.settingsStore.save(settings)
            try {
                c.runStreams.queue("s").add("queued")
                c.runStreams.saveQueue("s")
                c.runStreams.start("parent", "s", pollFirst = true)
                awaitCondition { !c.runStreams.working.value }
                assertEquals(if (failCommit == 1) 0 else 1, posts.get())
                assertEquals(listOf("queued"), c.runStreams.queue("s"))
                assertEquals(failCommit == 2, c.runStreams.queueBusy("s"))
                assertEquals(if (failCommit == 1) 0 else 1, RunOutbox(context).pending(settings).size)
                if (failCommit == 2) {
                    context.failCommit = 0
                    c.runStreams.restore()
                    awaitCondition { !c.runStreams.working.value }
                    assertFalse(c.runStreams.queueBusy("s"))
                    assertTrue(RunOutbox(context).pending(settings).isEmpty())
                    assertEquals(if (rejected) listOf("queued") else emptyList<String>(), c.runStreams.queue("s"))
                    assertEquals(2, posts.get())
                }
            } finally { c.runStreams.close() }
        }
    }
}
