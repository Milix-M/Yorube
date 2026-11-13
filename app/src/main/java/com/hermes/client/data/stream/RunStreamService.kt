package com.hermes.client.data.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermes.client.HermesApp
import com.hermes.client.MainActivity
import com.hermes.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/** Keeps active run tracking in a foreground service; restores saved runs on restart. */
class RunStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID)?.ifBlank { null }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        // startForegroundService obliges a prompt startForeground, even
        // when this start turns out to carry nothing to keep alive.
        startForeground(NOTIFICATION_ID, buildNotification(sessionId))
        val engine = (application as HermesApp).container.runStreams
        watchJob?.cancel()
        watchJob = combine(engine.working, engine.activeRuns) { working, runs -> working to runs }
            .onStart {
                if (runId != null) engine.start(runId, sessionId)
                else if (intent == null) engine.restore()
            }.onEach { (working, runs) ->
                if (!working) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(runs.lastOrNull()?.sessionId.orEmpty()))
                }
            }.launchIn(scope)
        return START_STICKY
    }

    private fun buildNotification(sessionId: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.stream_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.hermes.client.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setContentText(getString(R.string.stream_notification_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "hermes_run_stream"
        const val NOTIFICATION_ID = 4242
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_SESSION_ID = "session_id"

        /** Asks the service to keep the process alive for [runId]. */
        fun start(context: Context, runId: String, sessionId: String): Boolean {
            val intent = Intent(context, RunStreamService::class.java).apply {
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: IllegalStateException) {
                false // Android forbids some background starts; the visible UI can retry.
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
