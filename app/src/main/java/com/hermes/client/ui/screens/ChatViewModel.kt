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
import com.hermes.client.data.api.HermesJson
import com.hermes.client.data.api.HistoryMessage
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.api.RunStatusResponse
import com.hermes.client.data.api.long
import com.hermes.client.data.api.str
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * Total SSE resubscribes allowed per run before the stream is declared dead.
 * Drops are routine on mobile networks and proxied tunnels (Cloudflare
 * idle kills, NAT timeouts, Doze, the DESIGN §8 five-minute transport
 * buffer), so the budget covers a long agent run instead of dying on the
 * first blips.
 */
internal const val MAX_RECONNECT_ATTEMPTS = 15

/** Reconnect backoff before each status poll: 1s, 2s, 4s, then 8s capped. */
internal fun reconnectDelayMs(attempt: Int): Long = 1000L * (1L shl attempt.coerceIn(0, 3))

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
    var historyLoading by mutableStateOf(false)
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
            historyLoading = true
            try {
                val api = container.api()
                val history = api.getSessionMessages(id)
                messages.clear()
                for (m in history) {
                    if (m.text.isBlank()) continue
                    when (m.role) {
                        "user" -> messages.add(UiMessage(idSeq++, MsgKind.USER, m.text))
                        "assistant" -> {
                            val split = splitUserFacing(m.text)
                            if (split.visible.isNotBlank()) {
                                messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, split.visible))
                            }
                            for (summary in split.folded) {
                                messages.add(UiMessage(idSeq++, MsgKind.TOOL, summary))
                            }
                        }
                        // tool/system/function roles are machine payloads, never chat bubbles.
                        else -> {
                            val summary = foldForLog(m.text)
                            if (summary.isNotBlank()) {
                                messages.add(UiMessage(idSeq++, MsgKind.TOOL, summary))
                            }
                        }
                    }
                }
                // History also carries tool-role payloads: fold their markup out of bubbles.
                foldMarkupOutOfReplies()
                statusLine = ""
            } catch (e: Exception) {
                statusLine = ""
                // Session endpoint: any 404 means the session itself is gone
                // (deleted or never existed), regardless of the machine code.
                error = if (e is HermesApiException && e.status == 404) {
                    s(R.string.err_foreign)
                } else {
                    friendlyError(e)
                }
            } finally {
                historyLoading = false
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
                if (isRunGone(e)) {
                    // The run already ended server-side (finished, expired or
                    // unknown): finish locally and hand the text back instead
                    // of blaming the conversation.
                    busy = false
                    runId = null
                    approval = null
                    clarify = null
                    statusLine = ""
                    input = text
                    error = s(R.string.err_run_gone)
                } else {
                    val msg = friendlyError(e)
                    if (isSessionGone(e)) abandonRun(msg) else error = msg
                }
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
                if (isRunGone(e)) {
                    // Nothing left to stop: settle locally without an error.
                    busy = false
                    runId = null
                    approval = null
                    clarify = null
                    statusLine = s(R.string.st_cancelled)
                } else {
                    val msg = friendlyError(e)
                    if (isSessionGone(e)) abandonRun(msg) else error = msg
                }
            }
        }
    }

    /**
     * The session can no longer be used (deleted or owned by another
     * surface): release the run locally instead of leaving the chat stuck
     * busy behind an error toast.
     */
    private fun abandonRun(message: String) {
        busy = false
        runId = null
        approval = null
        clarify = null
        statusLine = ""
        error = message
    }

    private fun isSessionGone(e: Exception): Boolean =
        e is HermesApiException && (e.code == "permission_denied" || e.code == "not_found")

    /**
     * The targeted run is gone server-side (finished, expired or unknown).
     * The conversation itself may still be fine, so this must not be
     * reported as a dead conversation. Bare 404s (e.g. proxy pages without
     * a machine code) count as well.
     */
    private fun isRunGone(e: Exception): Boolean {
        if (e !is HermesApiException) return false
        if (e.code == "not_found" || e.code == "run_not_found" || e.code == "unknown_execution") return true
        return e.status == 404 && e.code == null
    }

    /**
     * Message for a gone run: the run ended server-side but the session
     * itself is still usable, so never blame the conversation. An unknown
     * outcome keeps its stronger warning (do not blindly resend).
     */
    private fun runGoneError(e: Exception): String {
        if (e is HermesApiException && e.code == "unknown_execution") {
            return s(R.string.err_unknown_exec)
        }
        return s(R.string.err_run_gone)
    }

    fun answerApproval(choice: String) {
        val req = approval ?: return
        val id = runId ?: return
        approval = null
        viewModelScope.launch {
            try {
                container.api().answerApproval(id, ApprovalAnswer(req.requestId, req.executionGeneration, choice))
                statusLine = s(R.string.st_approved, choice)
            } catch (e: HermesApiException) {
                // Stale generations recover via DESIGN §9.4 first: a 409 here
                // usually means the answer missed, not that the session died.
                if (e.status == 409 && recoverPendingControl(id)) {
                    return@launch
                }
                if (isRunGone(e)) {
                    // The run already ended: drop the dialog locally without
                    // blaming the conversation (the session is still usable).
                    busy = false
                    runId = null
                    clarify = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
            } catch (e: Exception) {
                if (isRunGone(e)) {
                    busy = false
                    runId = null
                    clarify = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
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
            } catch (e: HermesApiException) {
                // Same §9.4 recovery as approvals: the answer may have
                // missed its generation while a control still waits.
                if (e.status == 409 && recoverPendingControl(prompt.runId)) {
                    return@launch
                }
                if (isRunGone(e)) {
                    busy = false
                    runId = null
                    approval = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
            } catch (e: Exception) {
                if (isRunGone(e)) {
                    busy = false
                    runId = null
                    approval = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
            }
        }
    }

    /**
     * DESIGN §9.4 recovery: re-read the run and re-surface the latest pending
     * approval/clarify control (if any) so the conversation can continue
     * instead of stalling. Returns true when a control was restored.
     */
    private suspend fun recoverPendingControl(id: String): Boolean {
        return try {
            val status = container.api().getRun(id)
            status.sessionId?.ifBlank { null }?.let { sessionId = it }
            if (surfacePendingControls(id, status.pendingControls)) return true
            // A concurrent resubscription may have restored the same control first.
            approval != null || clarify != null
        } catch (_: Exception) {
            approval != null || clarify != null
        }
    }

    /**
     * Show the latest pending approval/clarify control from a run status,
     * e.g. after a reconnect that missed the original request event.
     * Never clobbers a dialog the user is already answering.
     * Returns true when something was surfaced.
     */
    private fun surfacePendingControls(id: String, controls: List<JsonObject>): Boolean {
        if (approval != null || clarify != null) return false
        val parsed = controls.mapNotNull(::parsePendingControl)
        if (parsed.isEmpty()) return false
        // Approval blocks the run: prefer it over clarify, latest wins.
        parsed.filterIsInstance<PendingControl.Approval>().lastOrNull()?.let {
            approval = it.request
            statusLine = s(R.string.st_waiting_approval)
            return true
        }
        parsed.filterIsInstance<PendingControl.Clarify>().lastOrNull()?.let {
            clarify = ClarifyPrompt(id, it.requestId, it.executionGeneration, it.prompt)
            statusLine = s(R.string.st_question)
            return true
        }
        return false
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
            } catch (e: CancellationException) {
                // Resubscribe, Stop, or scope teardown: not a stream failure,
                // never route into the reconnect path.
                throw e
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
            is RunEvent.ToolCompleted -> {
                val preview = humanPreview(event.preview)
                val shown = summarizeMachineJson(preview.trim()) ?: preview
                messages.add(
                    UiMessage(
                        idSeq++,
                        if (event.isError) MsgKind.ERROR else MsgKind.TOOL,
                        s(R.string.tool_finished, event.tool, event.durationSeconds.toString(), shown).trim(),
                    )
                )
            }
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
            is RunEvent.Reasoning -> {
                if (event.text.isNotBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.STATUS, foldForLog(event.text)))
                }
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
            val shown = summarizeMachineJson(event.error.trim()) ?: event.error
            messages.add(UiMessage(idSeq++, MsgKind.ERROR, shown))
        }
        event.pendingSteer?.ifBlank { null }?.let {
            messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.st_undelivered, it)))
        }
        // Chunk splits can strand half a tool block in the reply: sweep it out.
        foldMarkupOutOfReplies()
    }

    /**
     * HTTP 401/403 and ownership failures cannot heal by polling: fail fast
     * instead of burning the reconnect budget and masking the real error
     * behind a later socket message. Run-level 404s (not_found/run_not_found
     * or a bare 404) are NOT included here: the run expired server-side but
     * the session itself is still usable, so they surface as an ended run
     * (isRunGone) instead of err_foreign.
     */
    private fun isUnrecoverable(e: Exception): Boolean =
        e is HermesApiException && (e.status == 401 || e.status == 403 ||
            e.code == "permission_denied")

    /**
     * Status poll with a short retry for transport-level blips (the same
     * abort that killed the stream often still lingers for a second).
     * HTTP answers, including 5xx, are authoritative and not retried here.
     */
    private suspend fun getRunWithRetry(id: String): RunStatusResponse {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                return container.api().getRun(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesApiException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (i < 2) delay(1000)
            }
        }
        throw last!!
    }

    private suspend fun reconnectOrFinish(id: String, attempt: Int, cause: Exception? = null) {
        // The run is gone server-side but the session is still usable:
        // report it as an ended run, never as a dead conversation.
        if (cause != null && isRunGone(cause)) {
            busy = false
            runId = null
            statusLine = ""
            error = runGoneError(cause)
            return
        }
        // Permanent auth/ownership failures fail fast.
        if (cause != null && isUnrecoverable(cause)) {
            busy = false
            runId = null
            statusLine = ""
            error = friendlyError(cause)
            return
        }
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            busy = false
            runId = null
            statusLine = ""
            error = cause?.let { friendlyError(it) } ?: s(R.string.st_stream_ended)
            return
        }
        statusLine = s(R.string.st_reconnecting)
        // Spread retries so half-open sockets and tight empty-stream loops
        // (the §8 transport buffer expires after 5 min) don't spin.
        // delay() is also the cancellation point: teardown during the wait
        // throws here instead of polling pointlessly.
        delay(reconnectDelayMs(attempt))
        val status = try {
            getRunWithRetry(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            busy = false
            runId = null
            statusLine = ""
            // A run-level 404/unknown_execution from the poll means the run
            // expired server-side (the session is still usable): report it as
            // an ended run, never as err_foreign. A permanent auth/ownership
            // answer replaces the stale stream cause; a transient poll blip
            // keeps the original stream cause.
            error = when {
                e is HermesApiException && isRunGone(e) -> runGoneError(e)
                cause != null && isRunGone(cause) -> runGoneError(cause)
                e is HermesApiException && isUnrecoverable(e) -> friendlyError(e)
                else -> friendlyError(cause ?: e)
            }
            return
        }
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
        // A drop may have swallowed the approval/clarify request event
        // while the run still waits: re-surface it from status.
        surfacePendingControls(id, status.pendingControls)
        subscribe(id, attempt = attempt + 1)
    }

    private fun friendlyError(e: Exception): String {
        if (e is HermesApiException) {
            return when (e.code) {
                "admission_conflict", "idempotency_key_conflict" ->
                    s(R.string.err_conflict, e.status)
                "unknown_execution" ->
                    s(R.string.err_unknown_exec)
                "run_not_found" ->
                    // Run-level 404: the run expired server-side, but the
                    // session itself is still usable. Never err_foreign.
                    s(R.string.err_run_gone)
                "permission_denied", "not_found" ->
                    s(R.string.err_foreign)
                "runtime_draining", "storage_unavailable" ->
                    s(R.string.err_draining, e.status)
                else -> s(R.string.err_server, e.status, e.message.take(200))
            }
        }
        if (e is IllegalArgumentException) return e.message ?: "Bad settings."
        // Raw socket text (e.g. "Software caused connection abort") means
        // nothing to users: label it as a dropped connection instead.
        if (e is IOException) {
            val raw = (e.message ?: e.javaClass.simpleName).take(120)
            return s(R.string.err_network, raw)
        }
        return (e.message ?: e.javaClass.simpleName).take(200)
    }

    /** Route a streamed chunk: machine content folds into the activity log, prose appends to the reply. */
    private fun appendDeltaText(chunk: String) {
        val split = splitUserFacing(chunk)
        for (summary in split.folded) messages.add(UiMessage(idSeq++, MsgKind.TOOL, summary))
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

    /** Interim notes stay one line: fold any embedded machine content into its summary. */
    private fun foldInlineMarkup(text: String): String {
        val split = splitUserFacing(text)
        if (split.folded.isEmpty()) return text
        val parts = mutableListOf<String>()
        if (split.visible.isNotBlank()) parts.add(split.visible.trim())
        parts.addAll(split.folded)
        return parts.joinToString(" · ")
    }

    /** Post-pass for chunk-boundary splits and history: pull machine content out of reply bubbles. */
    private fun foldMarkupOutOfReplies() {
        val rebuilt = ArrayList<UiMessage>(messages.size)
        for (m in messages) {
            if (m.kind != MsgKind.ASSISTANT) {
                rebuilt.add(m)
                continue
            }
            val split = splitUserFacing(m.text)
            if (split.folded.isEmpty()) {
                rebuilt.add(m)
                continue
            }
            if (split.visible.isNotBlank()) rebuilt.add(m.copy(text = split.visible))
            for (summary in split.folded) rebuilt.add(UiMessage(idSeq++, MsgKind.TOOL, summary))
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

/**
 * MECE split of any inbound model text: user-facing prose stays visible,
 * everything machine-made (trust-delimited tool results, structured JSON
 * payloads, tag-wrapped dumps, [tool …] markers) folds into one-line summaries.
 */
internal data class VisibleSplit(val visible: String, val folded: List<String>)

private val ToolPrefixMarker = Regex("""^\[tool[^\]\n]*\]\s*""", RegexOption.IGNORE_CASE)
private val WholeTagPayload = Regex("""^<([A-Za-z][\w.-]*)(\s[^<>]*)?>[\s\S]*</\1\s*>$""")
private val MachineJsonKeys = setOf(
    "tool_name", "tool_call_id", "tool_call", "function_call", "tool_output", "results",
)

internal fun stripToolPrefix(text: String): String = ToolPrefixMarker.replace(text, "")

internal fun isMachineJsonObject(o: JsonObject): Boolean {
    if (o.keys.any { it in MachineJsonKeys }) return true
    if ("output" in o || "error" in o) return true
    return "success" in o && "name" in o
}

/** One-line summary of a structured tool-result JSON object. Null when not an object. */
internal fun summarizeJsonObject(o: JsonObject): String {
    fun s(key: String): String? {
        val v = o[key] as? JsonPrimitive ?: return null
        if (!v.isString) return null
        return v.content.trim().takeIf { it.isNotEmpty() }
    }
    val name = s("name") ?: s("tool_name") ?: s("tool") ?: "tool"
    s("output")?.let { return "$name: ${it.take(140)}" }
    s("error")?.let { return "$name [error]: ${it.take(140)}" }
    val results = o["results"] as? JsonArray
    if (results != null) {
        val head = (results.firstOrNull() as? JsonObject)?.let { first ->
            val t = (first["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val u = (first["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            listOf(t, u).filter { it.isNotEmpty() }.joinToString(" — ")
        }.orEmpty()
        return if (head.isEmpty()) "$name: ${results.size} results" else "$name: $head"
    }
    return "$name: {${o.keys.take(4).joinToString(", ")}}"
}

/**
 * Summarize a whole-message machine JSON payload such as
 * {"success":true,"name":"grounded-citations",…}. Null for prose or
 * non-machine JSON (e.g. an answer the user explicitly asked for as plain data).
 */
internal fun summarizeMachineJson(trimmed: String): String? {
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
    val el = try {
        HermesJson.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        return null
    }
    if (el is JsonObject) {
        if (!isMachineJsonObject(el)) return null
        return summarizeJsonObject(el)
    }
    if (el is JsonArray) {
        if (el.isEmpty()) return null
        val first = el.first() as? JsonObject ?: return null
        if (first.keys.none { it == "url" || it == "link" || it == "href" || it == "title" }) return null
        val head = (first["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val links = if (head.isEmpty()) "${el.size} links" else "$head (+${el.size - 1})"
        return "links: $links"
    }
    return null
}

/** Summarize a whole-message tag-wrapped dump (<foo>…</foo>). Null for prose. */
internal fun summarizeTagPayload(trimmed: String): String? {
    val m = WholeTagPayload.matchEntire(trimmed) ?: return null
    return "markup: <${m.groupValues[1]}>"
}

internal fun splitUserFacing(text: String): VisibleSplit {
    val untrusted = splitUntrustedBlocks(text)
    val folded = untrusted.logs.map(::summarizeToolBlock).toMutableList()
    var rest = stripToolPrefix(untrusted.visible)
    summarizeMachineJson(rest.trim())?.let {
        folded.add(it)
        rest = ""
    }
    summarizeTagPayload(rest.trim())?.let {
        folded.add(it)
        rest = ""
    }
    return VisibleSplit(rest, folded)
}

/** Single folded line for machine-role content (history tool/system messages, reasoning). */
internal fun foldForLog(text: String): String {
    val split = splitUserFacing(text)
    return (listOf(split.visible.trim()).filter { it.isNotEmpty() } + split.folded)
        .joinToString(" · ").take(200)
}

/** A still-waiting run control read back from a run status (DESIGN §9.4). */
internal sealed interface PendingControl {
    data class Approval(val request: ApprovalRequest) : PendingControl
    data class Clarify(val requestId: String, val executionGeneration: Long, val prompt: String) : PendingControl
}

/**
 * Lenient parse of one `pending_controls` entry. Approval-shaped entries
 * (command/choices/type) win over clarify-shaped ones (prompt/question).
 * Returns null when the entry carries nothing answerable.
 */
internal fun parsePendingControl(o: JsonObject): PendingControl? {
    val requestId = o.str("request_id", "prompt_id", "id").orEmpty()
    val generation = o.long("execution_generation", "generation") ?: 0L
    val command = o.str("command")
    val choices = (o["choices"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotEmpty() }
    if (command != null || choices != null ||
        o.str("type").equals("approval", ignoreCase = true)
    ) {
        if (requestId.isBlank() && command == null) return null
        return PendingControl.Approval(
            ApprovalRequest(
                requestId = requestId,
                executionGeneration = generation,
                command = command,
                description = o.str("description"),
                choices = choices ?: listOf("once", "deny"),
            )
        )
    }
    val prompt = o.str("prompt", "question", "text") ?: return null
    if (requestId.isBlank() && prompt.isBlank()) return null
    return PendingControl.Clarify(requestId, generation, prompt)
}
