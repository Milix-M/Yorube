package com.hermes.client.data.fcm

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.hermes.client.HermesApp
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun newToken_isPersisted() {
        val service = Robolectric.buildService(HermesMessagingService::class.java).create().get()
        service.onNewToken("tok123")
        val stored = kotlinx.coroutines.runBlocking {
            awaitToken(service)
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
        val nm = app().getSystemService(NotificationManager::class.java)
        assertEquals(1, Shadows.shadowOf(nm).size())
        assertEquals("T", Shadows.shadowOf(nm).getNotification(0).extras.getString("android.title"))
    }
}
