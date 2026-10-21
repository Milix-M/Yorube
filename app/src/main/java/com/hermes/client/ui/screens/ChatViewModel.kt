package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.R
import com.hermes.client.data.api.ApprovalAnswer
import com.hermes.client.data.api.ApprovalRequest
import com.hermes.client.data.api.ClarifyRequest
import com.hermes.client.data.api.HermesApiException
import com.hermes.client.data.api.HistoryMessage
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.api.RunEvent
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class MsgKind { USER, ASSISTANT, TOOL, STATUS, ERROR }

data class UiMessage(
    val id: Long,
    val kind: MsgKind,
    val text: String,
)

data class ClarifyPrompt(
    val runId: String,
    val requestId: String,
    val executionGeneration: Long,
    val prompt: String,
)

class ChatViewModel(
    private val container: AppContainer,
    initialSessionId: String?,
) : ViewModel() {

    val messages = mutableStateListOf<UiMessage>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf("")
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var clarify by mutableStateOf<ClarifyPrompt?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sessionId by mutableStateOf(initialSessionId.orEmpty())
        private set

    private var runId: String? = null
    private var eventsJob: Job? = null
    private var assistantIndex: Int = -1
    private var idSeq = 0L

    private fun s(id: Int, vararg args: Any): String =
        container.appContext.getString(id, *args)

    init {
        if (sessionId.isNotBlank()) loadHistory(sessionId)
    }

    fun loadHistory(id: String) {
        viewModelScope.launch {
            statusLine = s(R.string.st_loading)
            try {
                val api = container.api()
                val history = api.getSessionMessages(id)
                messages.clear()
                for (m in history) {
                    val kind = if (m.role == "user") MsgKind.USER else MsgKind.ASSISTANT
                    if (m.text.isNotBlank()) messages.add(UiMessage(idSeq++, kind, m.text))
                }
                statusLine = ""
            } catch (e: Exception) {
                statusLine = ""
                error = friendlyError(e)
            }
        }
    }

    fun onSend() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        messages.add(UiMessage(idSeq++, MsgKind.USER, text))
        startRun(text, history = null)
    }

    fun onSteer() {
        val text = input.trim()
        val id = runId
        if (text.isEmpty() || id == null || !busy) return
        input = ""
        viewModelScope.launch {
            try {
                container.api().steerRun(id, text)
                statusLine = s(R.string.st_steered)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun onStop() {
        val id = runId ?: return
        viewModelScope.launch {
            try {
                container.api().stopRun(id)
                statusLine = s(R.string.st_stopping)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun answerApproval(choice: String) {
        val req = approval ?: return
        val id = runId ?: return
        approval = null
        viewModelScope.launch {
            try {
                container.api().answerApproval(id, ApprovalAnswer(req.requestId, req.executionGeneration, choice))
                statusLine = s(R.string.st_approved, choice)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun answerClarify(answer: String) {
        val prompt = clarify ?: return
        clarify = null
        viewModelScope.launch {
            try {
                container.api().answerClarify(
                    prompt.runId,
                    ClarifyRequest(prompt.requestId, prompt.executionGeneration, answer),
                )
                statusLine = s(R.string.st_answered)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun dismissError() {
        error = null
    }

    private fun startRun(text: String, history: List<HistoryMessage>?) {
        busy = true
        error = null
        statusLine = s(R.string.st_starting)
        assistantIndex = -1
        viewModelScope.launch {
            val settings = try {
                container.settingsStore.current()
            } catch (e: Exception) {
                busy = false
                error = friendlyError(e)
                return@launch
            }
            if (!settings.isConfigured) {
                busy = false
                error = s(R.string.err_not_configured)
                return@launch
            }
            val api = try {
                container.api()
            } catch (e: Exception) {
                busy = false
                error = friendlyError(e)
                return@launch
            }
            val id: String
            try {
                val accepted = api.createRun(
                    RunCreateRequest(
                        input = text,
                        sessionId = sessionId.ifBlank { null },
                        conversationHistory = history,
                        model = settings.model.ifBlank { null },
                        provider = settings.provider.ifBlank { null },
                    ),
                    idempotencyKey = UUID.randomUUID().toString(),
                )
                id = accepted.runId
                runId = id
                // Runs responses do not carry the session id. Read it back once.
                try {
                    val status = api.getRun(id)
                    status.sessionId?.ifBlank { null }?.let { sessionId = it }
                } catch (_: Exception) {
                }
                // Best-effort FCM watch so a closed app still gets the finish notice.
                // registerWatch never throws (returns false on failure), so no
                // try-catch needed here.
                container.watchRegistrar.registerWatch(
                    relayBaseUrl = settings.relayUrl,
                    runId = id,
                    sessionId = sessionId,
                    fcmToken = settings.fcmToken,
                )
            } catch (e: Exception) {
                busy = false
                statusLine = ""
                error = friendlyError(e)
                return@launch
            }
            subscribe(id, attempt = 0)
        }
    }

    private fun subscribe(id: String, attempt: Int) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            try {
                val api = container.api()
                var terminalSeen = false
                api.runEvents(id).collect { event -> if (handleEvent(id, event)) terminalSeen = true }
                if (!terminalSeen) reconnectOrFinish(id, attempt)
            } catch (e: Exception) {
                reconnectOrFinish(id, attempt, cause = e)
            }
        }
    }

    /** Returns true when a terminal event arrived. */
    private suspend fun handleEvent(id: String, event: RunEvent): Boolean {
        when (event) {
            is RunEvent.Delta -> {
                if (event.text.isEmpty()) return false
                if (assistantIndex < 0) {
                    assistantIndex = messages.size
                    messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, event.text))
                } else {
                    val cur = messages[assistantIndex]
                    messages[assistantIndex] = cur.copy(text = cur.text + event.text)
                }
                statusLine = s(R.string.st_writing)
            }
            is RunEvent.Interim -> {
                if (!event.alreadyStreamed && event.text.isNotBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.STATUS, event.text))
                }
            }
            is RunEvent.ToolStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.TOOL, s(R.string.tool_started, event.tool, event.preview).trim()))
            is RunEvent.ToolCompleted ->
                messages.add(
                    UiMessage(
                        idSeq++,
                        if (event.isError) MsgKind.ERROR else MsgKind.TOOL,
                        s(R.string.tool_finished, event.tool, event.durationSeconds.toString(), event.preview).trim(),
                    )
                )
            is RunEvent.SubagentStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.delegating, event.summary)))
            is RunEvent.SubagentCompleted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.child_done, event.status, event.summary)))
            is RunEvent.ApprovalRequested -> {
                approval = event.request
                statusLine = s(R.string.st_waiting_approval)
            }
            is RunEvent.ClarifyRequested -> {
                clarify = ClarifyPrompt(id, event.requestId, event.executionGeneration, event.prompt)
                statusLine = s(R.string.st_question)
            }
            is RunEvent.Terminal -> {
                finishTerminal(event)
                return true
            }
            is RunEvent.Unknown -> {
                statusLine = event.name
            }
        }
        return false
    }

    private fun finishTerminal(event: RunEvent.Terminal) {
        busy = false
        runId = null
        statusLine = when (event.status) {
            "completed" -> ""
            "cancelled" -> s(R.string.st_cancelled)
            "failed" -> s(R.string.st_failed)
            else -> event.status
        }
        if (assistantIndex < 0 && !event.output.isNullOrBlank()) {
            messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, event.output))
        }
        if (!event.error.isNullOrBlank() && event.status != "completed") {
            messages.add(UiMessage(idSeq++, MsgKind.ERROR, event.error))
        }
        event.pendingSteer?.ifBlank { null }?.let {
            messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.st_undelivered, it)))
        }
    }

    private suspend fun reconnectOrFinish(id: String, attempt: Int, cause: Exception? = null) {
        if (attempt >= 3) {
            busy = false
            runId = null
            statusLine = ""
            error = cause?.let { friendlyError(it) } ?: s(R.string.st_stream_ended)
            return
        }
        statusLine = s(R.string.st_reconnecting)
        try {
            val status = container.api().getRun(id)
            when (status.status) {
                "completed", "failed", "cancelled", "interrupted" -> {
                    finishTerminal(
                        RunEvent.Terminal(status.status, status.output, status.error, status.pendingSteer)
                    )
                    return
                }
                "unknown" -> {
                    busy = false
                    runId = null
                    statusLine = ""
                    error = s(R.string.st_unknown_restart)
                    return
                }
            }
            status.sessionId?.ifBlank { null }?.let { sessionId = it }
        } catch (e: Exception) {
            busy = false
            runId = null
            statusLine = ""
            error = friendlyError(cause ?: e)
            return
        }
        subscribe(id, attempt = attempt + 1)
    }

    private fun friendlyError(e: Exception): String {
        if (e is HermesApiException) {
            return when (e.code) {
                "admission_conflict", "idempotency_key_conflict" ->
                    s(R.string.err_conflict, e.status)
                "unknown_execution" ->
                    s(R.string.err_unknown_exec)
                "permission_denied", "not_found" ->
                    s(R.string.err_foreign, e.status)
                "runtime_draining", "storage_unavailable" ->
                    s(R.string.err_draining, e.status)
                else -> s(R.string.err_server, e.status, e.message.take(200))
            }
        }
        if (e is IllegalArgumentException) return e.message ?: "Bad settings."
        return (e.message ?: e.javaClass.simpleName).take(200)
    }
}
