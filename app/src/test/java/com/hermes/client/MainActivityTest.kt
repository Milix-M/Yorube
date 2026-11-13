package com.hermes.client

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.store.ClientSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [MainActivityTest.Api33AudioManagerShadow::class])
class MainActivityTest {

    // Let Compose manage frame advancement and dispose the activity after the test.
    @get:Rule
    val compose = createEmptyComposeRule()

    // Robolectric 4.17's API 33 audio-service proxy resolves BluetoothAddress,
    // which does not exist on API 33. Model navigation sounds as disabled so
    // ViewRootImpl can attach the activity without instantiating that proxy.
    // Keep this scoped to the activity test; retain its API 33 coverage.
    @Implements(AudioManager::class)
    class Api33AudioManagerShadow : ShadowAudioManager() {
        @Implementation(minSdk = 33, maxSdk = 33)
        protected fun areNavigationRepeatSoundEffectsEnabled(): Boolean = false
    }

    @Test
    fun launchingRestoresTrackedRunAndStartsForegroundService() {
        val app = ApplicationProvider.getApplicationContext<HermesApp>()
        MockWebServer().use { server ->
            server.start()
            val endpoint = server.url("/").toString().trimEnd('/')
            runBlocking { app.container.settingsStore.save(ClientSettings(baseUrl = endpoint, apiKey = "k")) }
            app.getSharedPreferences("active_runs", 0).edit().putString("runs",
                """[{"run":"r","session":"s","endpoint":"$endpoint"}]""").commit()
            val polled = java.util.concurrent.atomic.AtomicBoolean(false)
            val subscribed = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/v1/runs/r" -> {
                        polled.set(true)
                        MockResponse().setBody("""{"status":"running","session_id":"s"}""")
                    }
                    "/v1/runs/r/events" -> {
                        subscribed.set(true)
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> MockResponse().setBody("""{"sessions":[]}""")
                }
            }
            try {
                Robolectric.buildActivity(MainActivity::class.java).use { controller ->
                    compose.runOnUiThread { controller.setup() }
                    compose.waitUntil(timeoutMillis = 15_000) {
                        shadowOf(android.os.Looper.getMainLooper()).idle()
                        polled.get() && subscribed.get()
                    }
                    val service = shadowOf(app).nextStartedService
                    assertEquals("r", service.getStringExtra("run_id"))
                    assertEquals("s", service.getStringExtra("session_id"))
                }
            } finally { compose.runOnUiThread { app.container.runStreams.close() } }
        }
    }

    @Test
    fun newSessionIntentUpdatesIntentAndRecreatesActivity() {
        // Manage the controller directly: onNewIntent recreates the activity,
        // which can leave ActivityScenario's invoker holding the old instance.
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            compose.runOnUiThread { controller.setup() }
            val activity = controller.get()
            compose.runOnUiThread {
                controller.newIntent(
                    Intent(activity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SESSION_ID, "s9"),
                )
                assertEquals("s9", activity.intent.getStringExtra(MainActivity.EXTRA_SESSION_ID))
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                controller.get() !== activity
            }
            assertNotSame(activity, controller.get())
        }
    }
}
