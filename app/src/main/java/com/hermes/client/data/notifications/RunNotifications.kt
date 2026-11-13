package com.hermes.client.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermes.client.AppContainer
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.store.LocaleHelper
import com.hermes.client.data.stream.ActiveRun
import com.hermes.client.data.stream.RunStreamUpdate
import com.hermes.client.ui.screens.PendingControl
import com.hermes.client.ui.screens.parsePendingControl

/** Device-local notifications; no token, relay or third-party transport. */
class RunNotifications(private val container: AppContainer) {
    private val context = container.appContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("run_notifications", 0)

    suspend fun onUpdate(run: ActiveRun, update: RunStreamUpdate,
        connection: com.hermes.client.data.store.ClientSettings? = null) {
        val settings = container.settingsStore.snapshot.takeIf { it.isConfigured } ?: container.settingsStore.current()
        if (connection != null && com.hermes.client.data.store.ConversationState.profileKey(connection) !=
            com.hermes.client.data.store.ConversationState.profileKey(settings)) return
        val terminal = when (update) {
            is RunStreamUpdate.Event -> update.event as? RunEvent.Terminal
            is RunStreamUpdate.PollTerminal -> RunEvent.Terminal(update.status.status, update.status.output, update.status.error, update.status.pendingSteer)
            else -> null
        }
        val delta = (update as? RunStreamUpdate.Event)?.event as? RunEvent.Delta
        if (delta?.text?.isNotEmpty() == true || terminal?.status == "completed" && !terminal.output.isNullOrBlank()) {
            container.conversations.recordReply(settings, run.sessionId, run.runId, terminal != null)
        }
        when (update) {
            is RunStreamUpdate.ControlsCleared -> dismissControl(run.runId)
            is RunStreamUpdate.Event -> onEvent(run, update.event)
            is RunStreamUpdate.PollTerminal -> onEvent(run, RunEvent.Terminal(
                update.status.status, update.status.output, update.status.error, update.status.pendingSteer))
            is RunStreamUpdate.Resync -> {
                val controls = update.status.pendingControls.mapNotNull(::parsePendingControl)
                if (controls.isEmpty() && update.status.pendingControlsKnown) dismissControl(run.runId)
                controls.forEach {
                    when (it) {
                        is PendingControl.Approval -> onEvent(run, RunEvent.ApprovalRequested(it.request))
                        is PendingControl.Clarify -> onEvent(run, RunEvent.ClarifyRequested(it.requestId, it.executionGeneration, it.prompt))
                    }
                }
            }
            is RunStreamUpdate.Dead, is RunStreamUpdate.UnknownOutcome -> {
                dismissControl(run.runId)
                show(run, "unavailable", R.string.notif_tracking_lost, "", false)
            }
            else -> Unit
        }
    }

    private suspend fun onEvent(run: ActiveRun, event: RunEvent) {
        when (event) {
            is RunEvent.ControlResolved -> dismissControl(run.runId)
            is RunEvent.Terminal -> {
                dismissControl(run.runId)
                show(run, "terminal", if (event.status == "completed") R.string.notif_run_finished else R.string.notif_run_stopped,
                    event.error ?: event.output.orEmpty(), false)
            }
            is RunEvent.ApprovalRequested -> show(run,
                "approval:${event.request.requestId}:${event.request.executionGeneration}", R.string.approval_title,
                event.request.description ?: event.request.command.orEmpty(), true)
            is RunEvent.ClarifyRequested -> show(run,
                "clarify:${event.requestId}:${event.executionGeneration}", R.string.st_question, event.prompt, true)
            else -> Unit
        }
    }

    fun dismissControl(runId: String) { manager.cancel("control:$runId", 0) }

    fun dismissSession(settings: com.hermes.client.data.store.ClientSettings, session: String) {
        val prefix = "session:${com.hermes.client.data.store.ConversationState.profileKey(settings)}:"
        preferences.all.filter { (key, value) -> key.startsWith(prefix) && value == session }.forEach { (key, _) ->
            val runId = key.removePrefix(prefix)
            manager.cancel("control:$runId", 0)
            manager.cancel("result:$runId", 0)
            preferences.edit().remove(key).apply()
        }
    }

    private suspend fun show(run: ActiveRun, eventKey: String, title: Int, body: String, control: Boolean) {
        val settings = container.settingsStore.current()
        val key = "${com.hermes.client.data.store.ConversationState.profileKey(settings)}|${run.runId}|$eventKey"
        val seen = preferences.getStringSet("seen", emptySet()).orEmpty()
        if (key in seen) return
        if (container.conversations.isVisible(settings, run.sessionId)) {
            preferences.edit().putStringSet("seen", (seen.toList().takeLast(255) + key).toSet()).apply()
            return
        }
        if (!settings.notificationsEnabled || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        fun s(resource: Int) = LocaleHelper.getString(context, settings.language, resource)
        val channel = if (control) "hermes_approvals" else "hermes_runs"
        manager.createNotificationChannel(NotificationChannel(channel,
            s(if (control) R.string.notif_channel_approvals else R.string.notif_channel_runs),
            if (control) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("yorube").authority("run").appendPath(run.runId).build()
            putExtra(MainActivity.EXTRA_SESSION_ID, run.sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val displayBody = body.ifBlank { s(title) }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(container.conversations.title(settings, run.sessionId) ?: s(R.string.new_chat))
            .setContentText(displayBody.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody.take(2000)))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {
            manager.notify("${if (control) "control" else "result"}:${run.runId}", 0, notification)
            preferences.edit().putStringSet("seen", (seen.toList().takeLast(255) + key).toSet()).apply()
            preferences.edit().putString("session:${com.hermes.client.data.store.ConversationState.profileKey(settings)}:${run.runId}", run.sessionId).apply()
        } catch (_: SecurityException) {
            // Permission can be revoked after the check; streaming must continue.
        }
    }
}
