package com.hermes.client.data.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hermes.client.HermesApp
import com.hermes.client.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HermesMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val container = (application as HermesApp).container
        scope.launch {
            try {
                container.settingsStore.saveFcmToken(token)
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title
            ?: data["title"] ?: "Hermes"
        val body = message.notification?.body
            ?: data["body"] ?: data["output"]?.take(200) ?: "run finished"
        val sessionId = data["session_id"] ?: data["sessionId"] ?: ""
        showNotification(title, body, sessionId, message.messageId?.hashCode() ?: sessionId.hashCode())
    }

    private fun showNotification(title: String, body: String, sessionId: String, id: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hermes runs", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "hermes_runs"
    }
}
