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
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while a run is active: it keeps
 * the process in the foreground state so the [RunStreamEngine] SSE loop
 * survives the app being backgrounded (Doze, task switching) instead of
 * dying with the chat screen. It owns no stream itself — the engine in
 * the app container does — and stops itself as soon as the engine
 * reports no active run. Not sticky: a process restart loses the run
 * record anyway, and the chat re-derives state from history + status.
 */
class RunStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID)?.ifBlank { null }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        // startForegroundService obliges a prompt startForeground, even
        // when this start turns out to carry nothing to keep alive.
        startForeground(NOTIFICATION_ID, buildNotification(sessionId))
        if (runId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val engine = (application as HermesApp).container.runStreams
        if (engine.activeRun.value?.runId != runId) {
            engine.start(runId, sessionId)
        }
        watchEngine(engine)
        return START_NOT_STICKY
    }

    /** Stops the service the moment the engine's active run ends. */
    private fun watchEngine(engine: RunStreamEngine) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            engine.activeRun.collect { active ->
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
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
        fun start(context: Context, runId: String, sessionId: String) {
            val intent = Intent(context, RunStreamService::class.java).apply {
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
