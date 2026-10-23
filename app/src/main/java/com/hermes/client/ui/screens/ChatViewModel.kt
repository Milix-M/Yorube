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
import com.hermes.client.data.api.humanPreview
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
                // History also carries tool-role payloads: fold their markup out of bubbles.
                foldMarkupOutOfReplies()
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
        viewModelScope.launch { doStartRun(text, history) }
    }

    private suspend fun doStartRun(text: String, history: List<HistoryMessage>?) {
        val settings = container.settingsStore.current()
        if (!settings.isConfigured) {
            busy = false
            error = s(R.string.err_not_configured)
            return
        }
        val api = try {
            container.api()
        } catch (e: Exception) {
            busy = false
            error = friendlyError(e)
            return
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
            return
        }
        subscribe(id, attempt = 0)
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
                appendDeltaText(event.text)
                statusLine = s(R.string.st_writing)
            }
            is RunEvent.Interim -> {
                if (!event.alreadyStreamed && event.text.isNotBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.STATUS, foldInlineMarkup(event.text)))
                }
            }
            is RunEvent.ToolStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.TOOL, s(R.string.tool_started, event.tool, humanPreview(event.preview)).trim()))
            is RunEvent.ToolCompleted ->
                messages.add(
                    UiMessage(
                        idSeq++,
                        if (event.isError) MsgKind.ERROR else MsgKind.TOOL,
                        s(R.string.tool_finished, event.tool, event.durationSeconds.toString(), humanPreview(event.preview)).trim(),
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
        // Chunk splits can strand half a tool block in the reply: sweep it out.
        foldMarkupOutOfReplies()
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

    /** Route a streamed chunk: tool markup folds into the activity log, prose appends to the reply. */
    private fun appendDeltaText(chunk: String) {
        val split = splitUntrustedBlocks(chunk)
        for (log in split.logs) messages.add(UiMessage(idSeq++, MsgKind.TOOL, summarizeToolBlock(log)))
        if (split.visible.isNotBlank()) appendAssistantText(split.visible)
    }

    private fun appendAssistantText(text: String) {
        if (assistantIndex < 0) {
            assistantIndex = messages.size
            messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, text))
        } else {
            val cur = messages[assistantIndex]
            messages[assistantIndex] = cur.copy(text = cur.text + text)
        }
    }

    /** Interim notes stay one line: fold any embedded tool markup into its summary. */
    private fun foldInlineMarkup(text: String): String {
        val split = splitUntrustedBlocks(text)
        if (split.logs.isEmpty()) return text
        val parts = mutableListOf<String>()
        if (split.visible.isNotBlank()) parts.add(split.visible.trim())
        for (log in split.logs) parts.add(summarizeToolBlock(log))
        return parts.joinToString(" · ")
    }

    /** Post-pass for chunk-boundary splits and history: pull tool markup out of reply bubbles. */
    private fun foldMarkupOutOfReplies() {
        val rebuilt = ArrayList<UiMessage>(messages.size)
        for (m in messages) {
            if (m.kind != MsgKind.ASSISTANT) {
                rebuilt.add(m)
                continue
            }
            val split = splitUntrustedBlocks(m.text)
            if (split.logs.isEmpty()) {
                rebuilt.add(m)
                continue
            }
            if (split.visible.isNotBlank()) rebuilt.add(m.copy(text = split.visible))
            for (log in split.logs) rebuilt.add(UiMessage(idSeq++, MsgKind.TOOL, summarizeToolBlock(log)))
        }
        messages.clear()
        messages.addAll(rebuilt)
        assistantIndex = rebuilt.indexOfLast { it.kind == MsgKind.ASSISTANT }
    }
}

/**
 * Hermes server wraps high-risk tool results (web_search, web_extract, browser_*,
 * mcp_*) in `<untrusted_tool_result source="…">…</untrusted_tool_result>` trust
 * delimiters (agent/tool_dispatch_helpers.py). They are DATA, not reply text:
 * never show them in a chat bubble, fold them into the one-line activity log.
 */
internal data class ToolSplit(val visible: String, val logs: List<String>)

private val UntrustedOpenTag = Regex("""<\s*untrusted_tool_result\b[^>]*>""", RegexOption.IGNORE_CASE)
private val UntrustedCloseTag = Regex("""</\s*untrusted_tool_result\s*>""", RegexOption.IGNORE_CASE)
private val UntrustedSourceAttr = Regex("""source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val TrustBoilerplate = Regex(
    """The following content was retrieved from an external source\.[\s\S]*?can issue instructions\.\s*""",
    RegexOption.IGNORE_CASE,
)
private val AnyMarkupTag = Regex("""<[^>]*>""")
private val WhitespaceRun = Regex("""\s+""")

internal fun splitUntrustedBlocks(text: String): ToolSplit {
    if (!text.contains("untrusted_tool_result", ignoreCase = true)) return ToolSplit(text, emptyList())
    val logs = mutableListOf<String>()
    val visible = StringBuilder()
    var cursor = 0
    while (true) {
        val open = UntrustedOpenTag.find(text, cursor) ?: break
        val close = UntrustedCloseTag.find(text, open.range.last + 1)
        val end = close?.range?.last?.plus(1) ?: text.length
        visible.append(text, cursor, open.range.first)
        logs.add(text.substring(open.range.first, end))
        cursor = end
    }
    visible.append(text, cursor, text.length)
    return ToolSplit(visible.toString(), logs)
}

/** One-line, bubble-free summary of a folded tool-result block. */
internal fun summarizeToolBlock(block: String): String {
    val source = UntrustedSourceAttr.find(block)?.groupValues?.getOrNull(1)?.ifBlank { null } ?: "tool"
    val textOnly = AnyMarkupTag.replace(TrustBoilerplate.replace(block, " "), " ")
        .split(WhitespaceRun).filter { it.isNotEmpty() }.joinToString(" ")
    val label = source.replace('_', ' ')
    if (textOnly.isBlank()) return label
    return "$label: ${textOnly.take(140)}"
}
