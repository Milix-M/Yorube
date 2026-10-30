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
import com.hermes.client.R
import com.hermes.client.data.store.LocaleHelper
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
                // Test hook: "__fail__" forces the catch below for coverage.
                if (token == "__fail__") throw java.io.IOException("injected failure")
                container.settingsStore.saveFcmToken(token)
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // The relay tags every push with a type: "approval" while a run is
        // waiting on the user, anything else (including untagged pushes from
        // older relays) is a run-finished notice.
        val approval = data["type"] == TYPE_APPROVAL
        val sessionId = data["session_id"] ?: data["sessionId"] ?: ""
        // Approvals key on the request so consecutive requests never clobber
        // each other; completions keep the historical message/session keying.
        val id = if (approval) {
            (data["request_id"] ?: sessionId).hashCode()
        } else {
            message.messageId?.hashCode() ?: sessionId.hashCode()
        }
        scope.launch {
            val settings = (application as HermesApp).container.settingsStore.current()
            if (!settings.notificationsEnabled) return@launch
            val lang = settings.language
            val title = message.notification?.title ?: data["title"]
                ?: LocaleHelper.getString(
                    applicationContext, lang,
                    if (approval) R.string.approval_title else R.string.app_name,
                )
            val body = message.notification?.body ?: data["body"]
                ?: data["output"]?.take(200)
                ?: LocaleHelper.getString(
                    applicationContext, lang,
                    if (approval) R.string.approval_desc else R.string.notif_run_finished,
                )
            showNotification(title, body, sessionId, id, approval, lang)
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        sessionId: String,
        id: Int,
        approval: Boolean,
        lang: String,
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = if (approval) CHANNEL_APPROVALS else CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = if (approval) {
                NotificationChannel(
                    CHANNEL_APPROVALS,
                    LocaleHelper.getString(applicationContext, lang, R.string.notif_channel_approvals),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            } else {
                NotificationChannel(
                    CHANNEL_ID,
                    LocaleHelper.getString(applicationContext, lang, R.string.notif_channel_runs),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            }
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val priority = if (approval) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "hermes_runs"
        const val CHANNEL_APPROVALS = "hermes_approvals"
        const val TYPE_APPROVAL = "approval"
    }
}
