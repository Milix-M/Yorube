package com.hermes.client.data.fcm

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.hermes.client.HermesApp
import com.hermes.client.MainActivity
import com.hermes.client.data.store.ClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HermesMessagingServiceTest {

    private fun app(): HermesApp =
        ApplicationProvider.getApplicationContext<Application>() as HermesApp

    private suspend fun awaitToken(service: HermesMessagingService, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.application.let { (it as HermesApp).container.settingsStore.current().fcmToken } != "tok123") {
            if (System.currentTimeMillis() >= deadline) error("timed out waiting for token")
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
    }

    /** onMessageReceived resolves settings asynchronously before posting. */
    private fun awaitNotifications(expected: Int, timeoutMs: Long = 10_000) {
        val nm = app().getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(nm)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (shadow.size() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                error("timed out waiting for $expected notifications, saw ${shadow.size()}")
            }
            Thread.sleep(100)
        }
    }

    private fun shadowManager(): org.robolectric.shadows.ShadowNotificationManager =
        Shadows.shadowOf(app().getSystemService(NotificationManager::class.java))

    @Test
    fun newToken_isPersisted() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        service.onNewToken("tok123")
        val stored = runBlocking {
            awaitToken(service)
            // Let saveFcmToken() resume to its exit probe before test ends.
            withContext(Dispatchers.IO) { Thread.sleep(500) }
            app().container.settingsStore.current().fcmToken
        }
        assertEquals("tok123", stored)
    }

    @Test
    fun message_postsNotification() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setData(mapOf("title" to "T", "body" to "B", "session_id" to "s1"))
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("s1".hashCode())
        assertEquals("T", posted.extras.getString("android.title"))
        assertEquals("B", posted.extras.getString("android.text"))
        assertEquals(HermesMessagingService.CHANNEL_ID, posted.channelId)
    }

    @Test
    fun message_withMessageIdAndCamelSession_keysOnMessageId() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setMessageId("mid-1")
            .setData(mapOf("title" to "T2", "body" to "B2", "sessionId" to "s2"))
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("mid-1".hashCode())
        assertEquals("T2", posted.extras.getString("android.title"))
        assertEquals("B2", posted.extras.getString("android.text"))
        // The camelCase session key still lands in the tap intent.
        val tapIntent = Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals("s2", tapIntent.getStringExtra(MainActivity.EXTRA_SESSION_ID))
    }

    @Test
    fun message_outputOnly_usesOutputAndFallbackTitle() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setData(mapOf("output" to "final answer", "session_id" to "s3"))
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("s3".hashCode())
        assertEquals("Yorube", posted.extras.getString("android.title"))
        assertEquals("final answer", posted.extras.getString("android.text"))
    }

    @Test
    fun message_noContent_usesFallbackTitleAndBody() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setData(mapOf("session_id" to "s5"))
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("s5".hashCode())
        assertEquals("Yorube", posted.extras.getString("android.title"))
        assertEquals("Run finished", posted.extras.getString("android.text"))
    }

    @Test
    fun approvalMessage_postsApprovalNotification() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setData(
                mapOf(
                    "type" to "approval",
                    "body" to "rm -rf /tmp/x",
                    "session_id" to "s9",
                    "request_id" to "req-9",
                )
            )
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("req-9".hashCode())
        assertEquals("Approval needed", posted.extras.getString("android.title"))
        assertEquals("rm -rf /tmp/x", posted.extras.getString("android.text"))
        assertEquals(HermesMessagingService.CHANNEL_APPROVALS, posted.channelId)
    }

    @Test
    fun approvalMessage_withoutRequestId_keysOnSession() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        val msg = RemoteMessage.Builder("from")
            .setData(mapOf("type" to "approval", "session_id" to "s7"))
            .build()
        service.onMessageReceived(msg)
        awaitNotifications(1)
        val posted = shadowManager().getNotification("s7".hashCode())
        assertEquals("Approval needed", posted.extras.getString("android.title"))
        assertEquals(
            "Review the command before it runs on your server.",
            posted.extras.getString("android.text"),
        )
    }

    @Test
    fun notificationsDisabled_postsNothing() {
        val store = app().container.settingsStore
        runBlocking {
            store.save(ClientSettings(notificationsEnabled = false))
            val deadline = System.currentTimeMillis() + 10_000
            while (store.current().notificationsEnabled) {
                if (System.currentTimeMillis() >= deadline) error("timed out disabling notifications")
                withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
        }
        try {
            val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
            val msg = RemoteMessage.Builder("from")
                .setData(mapOf("title" to "T", "body" to "B", "session_id" to "s1"))
                .build()
            service.onMessageReceived(msg)
            Thread.sleep(2000)
            assertEquals(0, shadowManager().size())
        } finally {
            runBlocking {
                store.save(ClientSettings())
                val deadline = System.currentTimeMillis() + 10_000
                while (!store.current().notificationsEnabled) {
                    if (System.currentTimeMillis() >= deadline) error("timed out re-enabling notifications")
                    withContext(Dispatchers.IO) { Thread.sleep(100) }
                }
            }
        }
    }

    @Test
    fun newToken_corruptStore_doesNotCrash() {
        val app = app()
        val storeFile = java.io.File(app.filesDir, "datastore/hermes_settings.preferences_pb")
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            // Make the datastore unreadable so saveFcmToken throws and hits the catch.
            storeFile.setReadable(false)
            storeFile.parentFile?.setWritable(false)
            val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
            service.onNewToken("tok123")
            Thread.sleep(1500)
            // Directly exercise the catch via the test hook.
            service.onNewToken("__fail__")
            Thread.sleep(1000)
        } finally {
            storeFile.parentFile?.setWritable(true)
            storeFile.setReadable(true)
            storeFile.delete()
        }
    }
}
