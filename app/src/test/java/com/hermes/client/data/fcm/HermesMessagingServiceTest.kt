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
        val nm = app().getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(nm)
        assertEquals(1, shadow.size())
        val posted = shadow.getNotification("s1".hashCode())
        assertEquals("T", posted.extras.getString("android.title"))
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
        } finally {
            storeFile.parentFile?.setWritable(true)
            storeFile.setReadable(true)
            storeFile.delete()
        }
    }
}
