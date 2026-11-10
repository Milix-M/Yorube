package com.hermes.client.audit

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.*
import com.hermes.client.data.crash.CrashLog
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.stream.*
import com.hermes.client.testutil.runVmTest
import com.hermes.client.testutil.awaitCondition
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlatformFailureCoverageTest {
    @Test fun foregroundStartFailureReturnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (error in listOf(IllegalStateException("background"), SecurityException("permission"))) {
            val denied = object : ContextWrapper(context) {
                override fun startForegroundService(intent: Intent): ComponentName? = throw error
            }
            assertFalse(RunStreamService.start(denied, "r", "s"))
        }
    }

    @Implements(NotificationManager::class)
    class PermissionRevokedNotifications : ShadowNotificationManager() {
        var attempts = 0
        @Implementation protected override fun notify(tag: String?, id: Int, notification: Notification) {
            attempts++
            throw SecurityException("permission revoked between check and notify")
        }
    }

    @Implements(NotificationManager::class)
    class UnavailableNotifications : ShadowNotificationManager() {
        var attempts = 0
        @Implementation protected override fun notify(tag: String?, id: Int, notification: Notification) {
            attempts++
            throw IllegalStateException("notification service unavailable")
        }
    }

    @Test
    @Config(shadows = [UnavailableNotifications::class])
    fun notificationServiceFailureDoesNotReplaceSuccessfulRunOutcome() = runVmTest {
        MockWebServer().use { server ->
            server.start()
            val c = AppContainer(ApplicationProvider.getApplicationContext())
            c.settingsStore.save(ClientSettings(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = "k"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: run.completed\ndata: {\"output\":\"done\"}\n\n"))
            try {
                c.runStreams.start("r", "s")
                awaitCondition { !c.runStreams.working.value }
                val result = c.runStreams.updatesFor("r").first() as RunStreamUpdate.Event
                assertEquals(RunEvent.Terminal("completed", "done", null, null), result.event)
                assertTrue(c.appContext.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
                val manager = c.appContext.getSystemService(NotificationManager::class.java)
                assertEquals(1, org.robolectric.shadow.api.Shadow.extract<UnavailableNotifications>(manager).attempts)
            } finally { c.runStreams.close() }
        }
    }

    @Test
    @Config(shadows = [PermissionRevokedNotifications::class])
    fun permissionRevokedDuringNotificationDoesNotMarkItDelivered() = runVmTest {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        c.settingsStore.save(ClientSettings(baseUrl = "https://example.com", apiKey = "k"))
        c.runNotifications.onUpdate(ActiveRun("r", "s"), RunStreamUpdate.Event("r", RunEvent.Terminal("completed", "done", null, null)))
        assertTrue(c.appContext.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        val manager = c.appContext.getSystemService(NotificationManager::class.java)
        assertEquals(1, org.robolectric.shadow.api.Shadow.extract<PermissionRevokedNotifications>(manager).attempts)
        assertTrue(c.appContext.getSharedPreferences("run_notifications", 0).getStringSet("seen", emptySet()).orEmpty().isEmpty())
    }

    @Test fun crashLogRemainsBestEffortWhenPrivateStorageIsUnavailable() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = throw IllegalStateException("unavailable")
        }
        CrashLog.writeReport(context, "crash")
        assertEquals("", CrashLog.read(context))
        CrashLog.clear(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            CrashLog.install(context)
            val malformed = object : RuntimeException("broken trace") {
                override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("trace unavailable")
            }
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), malformed)
            assertTrue(delegated)
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun legacyNotificationSettingsRemainReadableWithoutEnablingTransport() = runVmTest {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        val legacy = ClientSettings(firebaseProjectId = "project", firebaseAppId = "app", firebaseApiKey = "key", firebaseSenderId = "sender")
        assertTrue(legacy.firebaseConfigured)
        assertFalse(legacy.copy(firebaseSenderId = "").firebaseConfigured)
        c.settingsStore.saveFcmToken("old-token")
        assertEquals("old-token", c.settingsStore.current().fcmToken)
    }

    @Test fun timestampsFallBackFromMalformedIsoToNumericAndLaterKeys() {
        fun parse(text: String) = extractSessionTimestamp(HermesJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)
        assertEquals(123.0, parse("""{"updated_at":"123"}"""))
        assertEquals(456.0, parse("""{"updated_at":"invalid","created_at":"456"}"""))
        assertNull(parse("""{"updated_at":"invalid"}"""))
    }
}
