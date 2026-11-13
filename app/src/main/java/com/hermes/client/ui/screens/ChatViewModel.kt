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
import com.hermes.client.data.api.RunImage
import com.hermes.client.data.attachments.ImageAttachment
import com.hermes.client.data.attachments.ImageAttachments
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.api.SessionMessage
import com.hermes.client.data.api.extractModelIds
import com.hermes.client.data.api.extractProviderOptions
import com.hermes.client.data.api.long
import com.hermes.client.data.api.str
import com.hermes.client.data.stream.RunStreamService
import com.hermes.client.data.stream.RunStreamUpdate
import com.hermes.client.data.stream.isRunGoneFailure
import com.hermes.client.data.store.LANGUAGE_SYSTEM
import com.hermes.client.data.store.LocaleHelper
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class MsgKind { USER, ASSISTANT, TOOL, STATUS, ERROR }

data class UiMessage(
    val id: Long,
    val kind: MsgKind,
    val text: String,
    val images: List<com.hermes.client.data.attachments.MessageImage> = emptyList(),
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
    val attachments = mutableStateListOf<ImageAttachment>()
    var attaching by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf("")
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var clarify by mutableStateOf<ClarifyPrompt?>(null)
        private set
    var controlSending by mutableStateOf(false)
        private set
    var clarifyAnswer by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    /**
     * Text blocked by foreign-session ownership (e.g. Discord): the run was
     * rejected, but forking the session may let the user continue in an
     * app-owned copy. Non-null only together with [error].
     */
    var forkOffer by mutableStateOf<String?>(null)
        private set
    var sessionId by mutableStateOf(initialSessionId.orEmpty())
        private set
    var historyLoading by mutableStateOf(false)
        private set
    /**
     * Another surface (e.g. Discord) owns this session: the input stays
     * disabled and the user is asked to fork-and-continue instead.
     */
    var foreignBlocked by mutableStateOf(false)
        private set
    /** Fork prompt visibility. Dismissing it keeps the read-only banner. */
    var forkDialogOpen by mutableStateOf(false)
        private set
    /** In-app language code driving all strings from this ViewModel. */
    var uiLanguage by mutableStateOf(LANGUAGE_SYSTEM)
        private set
    /**
     * Set by ChatScreen: leaves this conversation for a fresh one (the
     * /new command). Navigation lives in the NavGraph, so the ViewModel
     * only holds the hook; without a host it stays a no-op.
     */
    var navigateToFreshChat: () -> Unit = {}
    var visibilityOwner: Any? = null

    /**
     * True while the run is still working toward its reply: no assistant
     * chunk yet (fresh or follow-up run), or the latest activity is a
     * tool/skill step after streamed text. Approval/clarify waits show
     * their own dialog instead.
     */
    val awaitingReply: Boolean
        get() = busy && approval == null && clarify == null &&
            (assistantIndex < 0 || messages.lastOrNull()?.kind == MsgKind.TOOL ||
                messages.lastOrNull()?.kind == MsgKind.STATUS)

    private var runId: String? = null
    private var eventsJob: Job? = null
    private var assistantIndex: Int = -1
    /** Last prompt actually sent as a run; /retry resends it. */
    private var lastSentText: String? = null
    private var lastSentImages: List<RunImage> = emptyList()
    private var clarifyAnswerIdentity: Triple<String, String, Long>? = null
    /** Most recent run id, kept after it ends so /usage can read it back. */
    private var lastRunId: String? = null
    /** Prompts queued by /queue while a run is active (FIFO). */
    private val pendingQueue: MutableList<String> get() = container.runStreams.queue(sessionId)
    private var idSeq = 0L

    private fun s(id: Int, vararg args: Any): String =
        LocaleHelper.getString(container.appContext, uiLanguage, id, *args)

    init {
        if (sessionId.isNotBlank()) {
            loadHistory(sessionId)
        }
    }

    /**
     * Refreshes the cached in-app language before any user-facing string is
     * resolved. One-shot reads only: an infinite settings collector would
     * outlive ownerless ViewModels (unit tests) and stall later DataStore
     * writes behind its dead dispatcher.
     */
    private suspend fun refreshLanguage() {
        uiLanguage = container.settingsStore.current().language
    }

    fun loadHistory(id: String) {
        // Set synchronously: callers awaiting !historyLoading must observe
        // the in-flight load, never a pre-launch false that lets a send
        // steal the history response from the shared queue.
        historyLoading = true
        statusLine = ""
        viewModelScope.launch {
            refreshLanguage()
            statusLine = s(R.string.st_loading)
            try {
                val api = container.api()
                container.runStreams.restore()
                val active = container.runStreams.activeForSession(id)
                val history = api.getSessionMessages(id)
                if (sessionId != id) return@launch
                renderHistory(history)
                statusLine = ""
                if (active != null) {
                    // The journal owns the running turn. History can already contain its partial output.
                    val lastUser = messages.indexOfLast { it.kind == MsgKind.USER }
                    if (lastUser >= 0) while (messages.size > lastUser + 1) messages.removeAt(messages.lastIndex)
                    assistantIndex = -1
                    runId = active.runId
                    busy = true
                    subscribe(active.runId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                statusLine = ""
                error = if (e is HermesApiException && e.status == 404) {
                    s(R.string.err_foreign)
                } else {
                    friendlyError(e)
                }
                // A gone-or-foreign session is read-only here: disable the
                // input and ask to fork (the fork may still fail when the
                // session was deleted, which surfaces as err_foreign).
                if (e is HermesApiException &&
                    (e.status == 404 || e.code == "permission_denied" || e.code == "not_found")
                ) {
                    // Record first: awaiting the flags below implies the
                    // write already landed (no late clobber of later saves).
                    markForeignSession(id)
                    foreignBlocked = true
                    forkDialogOpen = true
                }
            } finally {
                historyLoading = false
            }
        }
    }

    /**
     * Renders one session history into bubbles, folding machine content into
     * the activity log. Shared by history loads and fork-and-continue.
     */
    private suspend fun renderHistory(history: List<SessionMessage>) {
        val settings = container.settingsStore.current()
        val savedImages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var userIndex = 0
            val lastUser = history.count { it.role == "user" } - 1
            history.map { message ->
                if (message.role == "user") container.messageImages.restore(settings, sessionId, userIndex++, lastUser, message.text)
                else null
            }
        }
        messages.clear()
        for ((position, m) in history.withIndex()) {
            if (m.text.isBlank()) continue
            when (m.role) {
                "user" -> {
                    val saved = savedImages[position]
                    messages.add(UiMessage(idSeq++, MsgKind.USER, saved?.text ?: m.text, saved?.images.orEmpty()))
                }
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
    }

    fun attachImage(uri: android.net.Uri) {
        if (busy || attaching || historyLoading || foreignBlocked) return
        if (attachments.size >= ImageAttachments.MAX_IMAGES) {
            error = s(R.string.image_limit)
            return
        }
        attaching = true
        viewModelScope.launch {
            try {
                val image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ImageAttachments.read(container.appContext, uri)
                }
                attachments.add(image)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: s(R.string.image_unreadable) }
            finally { attaching = false }
        }
    }

    fun onSend() {
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (attachments.isEmpty() && dispatchCommand(text)) return
        if (busy || attaching || historyLoading || foreignBlocked || commandRunning) return
        input = ""
        val images = attachments.map { it.image }
        messages.add(UiMessage(idSeq++, MsgKind.USER, text))
        startRun(text, history = null, images = images)
    }

    fun onSteer() {
        if (attachments.isNotEmpty()) { error = s(R.string.image_while_running); return }
        val text = input.trim()
        if (text.isEmpty()) return
        if (dispatchCommand(text)) return
        steer(text)
    }

    /** Steers the current run with [text]; a no-op when nothing runs. */
    private fun steer(text: String) {
        val id = runId
        if (id == null || !busy) return
        input = ""
        viewModelScope.launch {
            refreshLanguage()
            try {
                container.api().steerRun(id, text)
                statusLine = s(R.string.st_steered)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            refreshLanguage()
            try {
                container.api().stopRun(id)
                statusLine = s(R.string.st_stopping)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
     * Handles a slash command typed into the input (see [SlashCommand]).
     * Returns true when [text] was a command: commands are handled
     * locally and are never sent to the server as a message. The
     * interception runs before the busy guards so /stop and /steer also
     * work while a run is active (the input bar routes Enter to
     * [onSteer] then). Read-only and settings-only commands also work
     * while busy; a model change applies from the next run. Commands the
     * REST API cannot perform get a local "not supported" notice
     * ([SlashCommand.Unsupported]) instead of reaching the agent as prose.
     */
    private fun dispatchCommand(text: String): Boolean {
        val cmd = parseSlashCommand(text)
        if (historyLoading && cmd != SlashCommand.NotACommand && cmd != SlashCommand.Help && cmd != SlashCommand.StatusInfo) {
            postErrorMessage(s(R.string.cmd_while_running)); return true
        }
        when (cmd) {
            SlashCommand.NotACommand -> return false
            is SlashCommand.Local -> { input = ""; handleLocal(cmd.name, cmd.arg) }
            SlashCommand.Stop -> {
                input = ""
                if (busy && runId != null) {
                    onStop()
                } else {
                    postErrorMessage(s(R.string.cmd_stop_not_running))
                }
            }
            is SlashCommand.Steer -> {
                input = ""
                when {
                    cmd.text == null ->
                        postErrorMessage(s(R.string.cmd_steer_usage))
                    !busy || runId == null ->
                        postErrorMessage(s(R.string.cmd_steer_not_running))
                    else -> steer(cmd.text)
                }
            }
            is SlashCommand.NewSession -> {
                input = ""
                val name = cmd.name
                if (name == null) navigateToFreshChat() else createTitledSession(name)
            }
            SlashCommand.Help -> {
                input = ""
                postStatusMessage(s(R.string.cmd_help) + "\n" + HERMES_COMMANDS.filter { it.action == SlashAction.LOCAL }.joinToString("  ") { "/${it.name}" })
            }
            is SlashCommand.Commands -> {
                input = ""
                showCommands(cmd.arg)
            }
            is SlashCommand.Model -> {
                input = ""
                handleModelCommand(cmd.arg)
            }
            SlashCommand.Retry -> {
                input = ""
                retryLast()
            }
            is SlashCommand.Queue -> {
                input = ""
                handleQueue(cmd.op)
            }
            is SlashCommand.Branch -> {
                input = ""
                branchSession(cmd.name)
            }
            SlashCommand.Sessions -> {
                input = ""
                listSessionsCommand()
            }
            is SlashCommand.Resume -> {
                input = ""
                resumeSession(cmd.name)
            }
            is SlashCommand.Title -> {
                input = ""
                handleTitle(cmd.name)
            }
            SlashCommand.StatusInfo -> {
                input = ""
                showStatus()
            }
            SlashCommand.UsageInfo -> {
                input = ""
                showUsage()
            }
            is SlashCommand.Skills -> {
                input = ""
                handleSkills(cmd.arg)
            }
            SlashCommand.Toolsets -> {
                input = ""
                listToolsets()
            }
            is SlashCommand.Background -> {
                input = ""
                startBackground(cmd.prompt)
            }
            is SlashCommand.Approve -> {
                input = ""
                handleApprove(cmd.choice)
            }
            SlashCommand.Deny -> {
                input = ""
                handleDeny()
            }
            is SlashCommand.Unsupported -> {
                input = ""
                postStatusMessage(s(R.string.cmd_unsupported, cmd.name))
            }
            is SlashCommand.Unknown -> {
                input = ""
                postErrorMessage(s(R.string.cmd_unknown, cmd.name))
            }
        }
        return true
    }

    private fun handleLocal(name: String, arg: String) {
        if (arg.isNotEmpty() && name !in setOf("prompt", "tools", "cron", "reasoning", "fast", "discard")) {
            postErrorMessage(s(R.string.cmd_unsupported, "/$name $arg")); return
        }
        when (name) {
            "cron" -> handleCron(arg)
            "reasoning", "fast" -> handleRunOption(name, arg)
            "discard" -> discardUnknown(arg)
            "history", "redraw" -> {
                if (busy || historyLoading) postErrorMessage(s(R.string.cmd_while_running))
                else if (sessionId.isBlank()) postErrorMessage(s(R.string.cmd_no_session))
                else loadHistory(sessionId)
            }
            "prompt" -> input = arg
            "palette" -> postStatusMessage(HERMES_COMMANDS.filter { it.action != SlashAction.UNSUPPORTED }
                .joinToString("\n") { "/${it.name} — ${it.description}" })
            "version" -> postStatusMessage("Yorube ${com.hermes.client.BuildConfig.VERSION_NAME}")
            "config" -> launchGuarded {
                val cur = container.settingsStore.current()
                // Credentials, proxy secrets and tokens never enter a chat transcript.
                postStatusMessage("${android.net.Uri.parse(cur.baseUrl).host.orEmpty()}\n${displayModel(cur.provider, cur.model)}")
            }
            "agents" -> postStatusMessage(container.runStreams.activeRuns.value
                .joinToString("\n") { "${it.runId}  ${it.sessionId}" }.ifEmpty { s(R.string.cmd_stop_not_running) })
            "tools" -> if (arg.isEmpty() || arg == "list") listToolsets()
                else postErrorMessage(s(R.string.cmd_unsupported, "/tools $arg"))
            "reload-skills" -> handleSkills(null)
            "copy" -> {
                val text = messages.lastOrNull { it.kind == MsgKind.ASSISTANT }?.text
                if (text != null) {
                    val clipboard = container.appContext.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Yorube", text))
                } else postErrorMessage(s(R.string.cmd_retry_none))
            }
            "save" -> {
                val text = messages.filter { it.kind == MsgKind.USER || it.kind == MsgKind.ASSISTANT }
                    .joinToString("\n\n") { "${it.kind.name.lowercase()}:\n${it.text}" }
                if (text.isBlank()) { postErrorMessage(s(R.string.cmd_no_session)); return }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                try {
                    container.appContext.startActivity(android.content.Intent.createChooser(intent, null)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: android.content.ActivityNotFoundException) { postErrorMessage(friendlyError(e)) }
            }
        }
    }

    private fun handleRunOption(name: String, arg: String) {
        val key = if (name == "reasoning") "reasoning_effort" else "service_tier"
        val value = if (name == "reasoning") arg.lowercase() else when (arg.lowercase()) {
            "on", "fast" -> "priority"
            "off", "normal" -> "default"
            else -> arg.lowercase()
        }
        val valid = if (name == "reasoning") setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
            else setOf("default", "priority")
        launchGuarded {
            val settings = container.settingsStore.current()
            if (arg.isBlank() || arg == "status") {
                postStatusMessage("/$name: " + (if (name == "reasoning") settings.reasoningEffort else settings.serviceTier).ifBlank { "default" })
            } else if (value in valid) {
                container.settingsStore.setRunOption(key, value)
                postStatusMessage(s(R.string.cmd_next_run_option, name, value))
            } else postErrorMessage(s(R.string.cmd_unsupported, "/$name $arg"))
        }
    }

    private fun discardUnknown(arg: String) {
        val id = arg.ifBlank { lastRunId.orEmpty() }
        if (busy || id.isBlank()) { postErrorMessage(s(R.string.cmd_discard_usage)); return }
        launchGuarded {
            val api = container.api()
            val status = api.getRun(id)
            if (status.status != "unknown" || status.admissionId == null || status.executionGeneration == null) {
                postErrorMessage(s(R.string.cmd_discard_usage)); return@launchGuarded
            }
            api.resolveUnknownRun(id, status.admissionId, status.executionGeneration)
            error = null
            postStatusMessage(s(R.string.cmd_discard_done))
        }
    }

    private fun handleCron(arg: String) {
        val action = arg.substringBefore(' ').ifBlank { "list" }.lowercase()
        val rest = arg.substringAfter(' ', "").trim()
        var id: String? = null
        var body: JsonObject? = null
        when (action) {
            "add", "create", "edit" -> {
                val fields = if (action == "edit") {
                    id = rest.substringBefore(' ').takeIf { it.isNotBlank() }
                    rest.substringAfter(' ', "")
                } else rest
                val parts = fields.split('|', limit = 3).map { it.trim() }
                if (parts.size != 3 || parts.any { it.isBlank() }) { postErrorMessage(s(R.string.cmd_cron_usage)); return }
                body = JsonObject(mapOf("name" to JsonPrimitive(parts[0]), "schedule" to JsonPrimitive(parts[1]), "prompt" to JsonPrimitive(parts[2])))
            }
            "show", "pause", "resume", "run", "remove" -> {
                if (rest.isBlank() || rest.any { it.isWhitespace() }) { postErrorMessage(s(R.string.cmd_cron_usage)); return }
                id = rest
            }
            "list" -> if (rest.isNotBlank()) { postErrorMessage(s(R.string.cmd_cron_usage)); return }
            else -> { postErrorMessage(s(R.string.cmd_cron_usage)); return }
        }
        launchGuarded {
            val response = container.api().cron(action, id, body)
            val root = HermesJson.parseToJsonElement(response) as? JsonObject
            val jobs = root?.get("jobs") as? JsonArray
            val job = root?.get("job") as? JsonObject
            fun describe(o: JsonObject): String = listOfNotNull(o.str("id", "job_id"), o.str("name"), o.str("schedule"), o.str("status")).joinToString("  ")
            val text = jobs?.mapNotNull { it as? JsonObject }?.joinToString("\n", transform = ::describe)
                ?: job?.let(::describe) ?: s(R.string.cmd_cron_done, action)
            postStatusMessage(text.ifBlank { s(R.string.cmd_cron_empty) })
        }
    }

    /** /commands [page] — the full Hermes catalog with app support marks. */
    private fun showCommands(arg: String?) {
        val pageSize = 20
        val totalPages = (HERMES_COMMANDS.size + pageSize - 1) / pageSize
        val requested = if (arg == null) 1 else arg.toIntOrNull()
        if (requested == null) {
            postErrorMessage(s(R.string.cmd_commands_usage))
            return
        }
        val page = requested.coerceIn(1, totalPages)
        val lines = HERMES_COMMANDS.drop((page - 1) * pageSize).take(pageSize).map { command ->
            val suffix =
                if (command.action == SlashAction.UNSUPPORTED) s(R.string.cmd_commands_unsupported) else ""
            "/${command.name} — ${command.description}$suffix"
        }
        postStatusMessage(
            s(R.string.cmd_commands_list, HERMES_COMMANDS.size, page, totalPages, lines.joinToString("\n"))
        )
    }

    /** /retry — resend the last prompt as a new run in this session. */
    private fun retryLast() {
        val text = lastSentText ?: messages.lastOrNull { it.kind == MsgKind.USER }?.text
        when {
            busy -> postErrorMessage(s(R.string.cmd_while_running))
            text == null -> postErrorMessage(s(R.string.cmd_retry_none))
            else -> {
                messages.add(UiMessage(idSeq++, MsgKind.USER, text))
                startRun(text, history = null, images = lastSentImages)
            }
        }
    }

    /**
     * /queue — app-local queue: prompts typed while a run is active go
     * out as the next turn from the app-scoped engine. With nothing running,
     * a queued prompt is simply sent at once (its turn is now).
     */
    private fun handleQueue(op: QueueOp) {
        if (historyLoading || foreignBlocked || (busy && runId == null)) { postErrorMessage(s(R.string.cmd_while_running)); return }
        if (container.runStreams.queueBusy(sessionId)) { postErrorMessage(s(R.string.cmd_while_running)); return }
        when (op) {
            is QueueOp.Add -> {
                if (busy) {
                    pendingQueue.add(op.text)
                    postStatusMessage(s(R.string.cmd_queue_added, pendingQueue.size))
                } else {
                    messages.add(UiMessage(idSeq++, MsgKind.USER, op.text))
                    startRun(op.text, history = null)
                }
            }
            is QueueOp.Edit -> {
                val index = op.index
                if (index == null || index !in 1..pendingQueue.size || op.text.isBlank())
                    postErrorMessage(s(R.string.cmd_queue_rm_invalid))
                else { pendingQueue[index - 1] = op.text; postStatusMessage(queueListText()) }
            }
            is QueueOp.Move -> {
                val from = op.from; val to = op.to
                if (from == null || to == null || from !in 1..pendingQueue.size || to !in 1..pendingQueue.size)
                    postErrorMessage(s(R.string.cmd_queue_rm_invalid))
                else { pendingQueue.add(to - 1, pendingQueue.removeAt(from - 1)); postStatusMessage(queueListText()) }
            }
            QueueOp.ShowList -> postStatusMessage(queueListText())
            QueueOp.Clear -> {
                pendingQueue.clear()
                postStatusMessage(s(R.string.cmd_queue_cleared))
            }
            is QueueOp.Remove -> {
                val index = op.index
                if (index == null || index < 1 || index > pendingQueue.size) {
                    postErrorMessage(s(R.string.cmd_queue_rm_invalid))
                } else {
                    val removed = pendingQueue.removeAt(index - 1)
                    postStatusMessage(s(R.string.cmd_queue_removed, removed))
                }
            }
        }
        container.runStreams.saveQueue(sessionId)
    }

    private fun queueListText(): String {
        if (pendingQueue.isEmpty()) return s(R.string.cmd_queue_empty)
        val lines = pendingQueue.mapIndexed { i, text -> "${i + 1}. $text" }
        return s(R.string.cmd_queue_list, lines.joinToString("\n"))
    }

    /**
     * /new <name> — pre-create a titled session row and switch to it in
     * place (the untitled form navigates to a fresh chat instead, whose
     * session is only minted by its first run).
     */
    private fun createTitledSession(name: String) {
        if (busy) {
            postErrorMessage(s(R.string.cmd_while_running))
            return
        }
        launchGuarded {
            val id = container.api().createSession(name)
            if (id.isNullOrBlank()) {
                postErrorMessage(s(R.string.cmd_new_failed))
            } else {
                container.conversations.rememberTitle(container.settingsStore.current(), id, name)
                markAppOwned(id)
                openSession(id)
            }
        }
    }

    /**
     * /branch — fork the current session server-side and continue in the
     * copy, in place (the app has no threads to open the branch in).
     */
    private fun branchSession(name: String?) {
        when {
            busy -> postErrorMessage(s(R.string.cmd_while_running))
            sessionId.isBlank() -> postErrorMessage(s(R.string.cmd_no_session))
            else -> launchGuarded {
                val api = container.api()
                val forked = api.forkSession(sessionId, name)
                if (forked.isNullOrBlank()) {
                    postErrorMessage(s(R.string.cmd_branch_failed))
                } else {
                    sessionId = forked
                    lastSentText = null
                    lastRunId = null
                    assistantIndex = -1
                    // Our own copy: never hide it as foreign.
                    markAppOwned(forked)
                    renderHistory(api.getSessionMessages(forked))
                    postStatusMessage(s(R.string.cmd_branch_done, forked.take(16)))
                }
            }
        }
    }

    /** /sessions — the server's session list as a local status message. */
    private fun listSessionsCommand() {
        launchGuarded {
            val sessions = container.api().listSessions()
            if (sessions.isEmpty()) {
                postStatusMessage(s(R.string.cmd_sessions_none))
            } else {
                val lines = sessions.take(20).map { info ->
                    val mark = if (info.key == sessionId) " " + s(R.string.cmd_sessions_current) else ""
                    "• ${info.key}  ${info.displayTitle}$mark"
                }
                postStatusMessage(s(R.string.cmd_sessions_list, lines.joinToString("\n")))
            }
        }
    }

    /**
     * /resume <name> — switch this chat to another session: exact id,
     * then exact title, then an id-prefix / title-substring match.
     */
    private fun resumeSession(name: String?) {
        when {
            busy -> postErrorMessage(s(R.string.cmd_while_running))
            name == null -> postErrorMessage(s(R.string.cmd_resume_usage))
            else -> launchGuarded {
                val api = container.api()
                val sessions = mutableListOf<com.hermes.client.data.api.SessionInfo>()
                var offset = 0
                while (true) {
                    val page = api.listSessions(limit = 50, offset = offset)
                    val added = page.filter { item -> sessions.none { it.key == item.key } }
                    sessions.addAll(added)
                    if (page.size < 50 || added.isEmpty()) break
                    offset += page.size
                }
                val exact = sessions.filter { it.key == name }.ifEmpty {
                    sessions.filter { it.title.equals(name, ignoreCase = true) }
                }
                val matches = exact.ifEmpty { sessions.filter {
                    it.key.startsWith(name) || it.title?.contains(name, ignoreCase = true) == true
                } }
                if (matches.size > 1) {
                    postErrorMessage(s(R.string.cmd_resume_ambiguous, matches.joinToString("\n") { "${it.key}  ${it.displayTitle}" }))
                    return@launchGuarded
                }
                val match = matches.singleOrNull()
                when {
                    match == null -> postErrorMessage(s(R.string.cmd_resume_not_found, name))
                    match.key == sessionId -> postStatusMessage(s(R.string.cmd_resume_current))
                    else -> openSession(match.key)
                }
            }
        }
    }

    /**
     * Switches this chat to [id] and loads its history (shared by
     * /resume and the titled /new). Any stale run control is dropped:
     * the caller has already established that nothing is running.
     */
    private fun openSession(id: String) {
        eventsJob?.cancel()
        lastSentText = null
        lastRunId = null
        assistantIndex = -1
        sessionId = id
        approval = null
        clarify = null
        error = null
        forkOffer = null
        foreignBlocked = false
        forkDialogOpen = false
        loadHistory(id)
    }

    /** /title — show the current title, or PATCH a new one. */
    private fun handleTitle(name: String?) {
        val targetSessionId = sessionId
        if (sessionId.isBlank()) {
            postErrorMessage(s(R.string.cmd_no_session))
            return
        }
        if (name == null) {
            launchGuarded {
                val title = container.api().getSession(targetSessionId)?.title?.ifBlank { null }
                if (title == null) {
                    postStatusMessage(s(R.string.cmd_title_unset))
                } else {
                    postStatusMessage(s(R.string.cmd_title_current, title))
                }
            }
        } else {
            launchGuarded {
                container.api().updateSessionTitle(targetSessionId, name)
                container.conversations.rememberTitle(container.settingsStore.current(), targetSessionId, name)
                postStatusMessage(s(R.string.cmd_title_set, name))
            }
        }
    }

    /** /status — local session snapshot: id, model, state, counts. */
    private fun showStatus() {
        launchGuarded {
            val cur = container.settingsStore.current()
            val model = displayModel(cur.provider, cur.model).ifBlank { s(R.string.cmd_model_unset) }
            val state = if (busy) s(R.string.cmd_status_running) else s(R.string.cmd_status_idle)
            val users = messages.count { it.kind == MsgKind.USER }
            val assistants = messages.count { it.kind == MsgKind.ASSISTANT }
            postStatusMessage(
                s(
                    R.string.cmd_status,
                    sessionId.ifBlank { "—" },
                    model,
                    state,
                    users,
                    assistants,
                    pendingQueue.size,
                )
            )
        }
    }

    /** /usage — read the latest run back and show its token usage. */
    private fun showUsage() {
        val id = runId ?: lastRunId
        if (id == null) {
            postErrorMessage(s(R.string.cmd_usage_none))
            return
        }
        launchGuarded {
            val status = container.api().getRun(id)
            val usage = status.usage
            if (usage == null) {
                postStatusMessage(s(R.string.cmd_usage_unavailable, status.status))
            } else {
                postStatusMessage(
                    s(
                        R.string.cmd_usage,
                        status.status,
                        usage.inputTokens,
                        usage.outputTokens,
                        usage.totalTokens,
                        usage.cacheReadTokens,
                        usage.cacheWriteTokens,
                    )
                )
            }
        }
    }

    /**
     * /skills — list what the server reports. Search/install and the
     * write-approval subcommands have no REST equivalent (the skills API
     * is read-only discovery), so any other argument is answered with
     * the unsupported notice.
     */
    private fun handleSkills(arg: String?) {
        val operation = arg?.substringBefore(' ')?.lowercase() ?: "list"
        val query = arg?.substringAfter(' ', "")?.trim().orEmpty()
        if (operation !in setOf("list", "search", "show", "inspect") ||
            (operation != "list" && query.isBlank())) {
            postStatusMessage(s(R.string.cmd_unsupported, "/skills ${arg.orEmpty()}")); return
        }
        launchGuarded {
            val skills = container.api().getSkills().filter {
                when (operation) {
                    "search" -> it.name.contains(query, true) || it.description.contains(query, true)
                    "show", "inspect" -> it.name.equals(query, true)
                    else -> true
                }
            }
            if (skills.isEmpty()) postStatusMessage(s(R.string.cmd_skills_none))
            else postStatusMessage(s(R.string.cmd_skills_list, skills.size,
                skills.joinToString("\n") { "/${it.name} — ${it.description}" }))
        }
    }

    /** /toolsets — list what the server reports (display-only API). */
    private fun listToolsets() {
        launchGuarded {
            val toolsets = container.api().getToolsets()
            if (toolsets.isEmpty()) {
                postStatusMessage(s(R.string.cmd_toolsets_none))
            } else {
                val lines = toolsets.map { toolset ->
                    val label = toolset.label.ifBlank { toolset.name }
                    if (toolset.description.isBlank()) label
                    else "$label — ${toolset.description.take(80)}"
                }
                postStatusMessage(s(R.string.cmd_toolsets_list, toolsets.size, lines.joinToString("\n")))
            }
        }
    }

    /**
     * /bg <prompt> — track a new session in the app-scoped stream engine.
     * The foreground conversation and its subscription are untouched.
     */
    private fun startBackground(prompt: String?) {
        if (prompt == null) {
            postErrorMessage(s(R.string.cmd_bg_usage))
            return
        }
        launchGuarded {
            val settings = container.settingsStore.current()
            if (!settings.isConfigured) {
                postErrorMessage(s(R.string.err_not_configured))
                return@launchGuarded
            }
            val accepted = container.runStreams.createTracked(RunCreateRequest(
                input = prompt, model = settings.model.ifBlank { null }, provider = settings.provider.ifBlank { null }, modelOptions = settings.modelOptions,
            ), settings)
            val bgSessionId = accepted.sessionId.ifBlank { null }
            postStatusMessage(s(R.string.cmd_bg_started, bgSessionId ?: accepted.runId))
        }
    }

    /**
     * /approve [session|always] — answer the waiting approval from the
     * keyboard; a bare /approve means "once". The choice must be one
     * the server actually offered for this request.
     */
    private fun handleApprove(choiceArg: String?) {
        val req = approval
        if (req == null) {
            postErrorMessage(s(R.string.cmd_approval_none))
            return
        }
        val choice = when (choiceArg?.lowercase()) {
            null -> "once"
            "once" -> "once"
            "session" -> "session"
            "always" -> "always"
            else -> {
                postErrorMessage(s(R.string.cmd_approve_usage))
                return
            }
        }
        if (choice !in req.choices) {
            postErrorMessage(s(R.string.cmd_approve_unavailable, choice))
            return
        }
        answerApproval(choice)
    }

    /** /deny — answer the waiting approval with "deny". */
    private fun handleDeny() {
        val req = approval
        if (req == null) {
            postErrorMessage(s(R.string.cmd_approval_none))
            return
        }
        if ("deny" !in req.choices) {
            postErrorMessage(s(R.string.cmd_approve_unavailable, "deny"))
            return
        }
        answerApproval("deny")
    }

    private fun postStatusMessage(text: String) {
        messages.add(UiMessage(idSeq++, MsgKind.STATUS, text))
    }

    private fun postErrorMessage(text: String) {
        messages.add(UiMessage(idSeq++, MsgKind.ERROR, text))
    }

    /**
     * /model with an argument: "<provider>/<model>" switches both stored
     * settings; a bare model name switches only the model and keeps the
     * current provider (the settings keep the two as separate fields, see
     * SettingsStore). The change applies from the next run because
     * doStartRun reads the settings on every start. /model without an
     * argument lists the current model and the server-reported options
     * as a local STATUS message; an API failure surfaces as a local
     * ERROR message instead of crashing.
     */
    private fun handleModelCommand(arg: String?) {
        if (arg == null) {
            showModelOptions()
            return
        }
        val parts = arg.trim().split(Regex("\\s+"))
        val newProvider: String?
        val newModel: String
        if (parts.size == 3 && parts[0] == "--provider") {
            newProvider = parts[1]
            newModel = parts[2]
        } else if (parts.size == 1 && !parts[0].startsWith("--")) {
            newProvider = null
            newModel = parts[0]
        } else {
            postErrorMessage(s(R.string.cmd_model_usage)); return
        }
        if (newModel.startsWith("/") || newModel.endsWith("/")) {
            postErrorMessage(s(R.string.cmd_model_usage)); return
        }
        launchGuarded {
            val store = container.settingsStore
            val cur = store.current()
            val provider = newProvider ?: cur.provider
            store.setModel(newModel, newProvider)
            postStatusMessage(s(R.string.cmd_model_set, displayModel(provider, newModel)))
        }
    }

    private fun showModelOptions() {
        launchGuarded {
            val cur = container.settingsStore.current()
            val current = displayModel(cur.provider, cur.model)
                .ifBlank { s(R.string.cmd_model_unset) }
            val body = container.api().getModelOptions()
            val root = HermesJson.parseToJsonElement(body)
            val lines = mutableListOf<String>()
            val providers = extractProviderOptions(root)
            if (providers.isNotEmpty()) {
                for (p in providers) {
                    lines.add(
                        if (p.models.isEmpty()) p.name
                        else "${p.name}: ${p.models.joinToString(", ")}"
                    )
                }
            } else {
                val ids = extractModelIds(root)
                if (ids.isNotEmpty()) lines.add(ids.joinToString(", "))
            }
            val available = if (lines.isEmpty()) {
                s(R.string.cmd_model_none)
            } else {
                lines.joinToString("\n")
            }
            postStatusMessage(s(R.string.cmd_model_list, current, available))
        }
    }

    /**
     * Runs a slash command's async work, surfacing any failure as a
     * local ERROR message instead of crashing.
     */
    private var commandRunning = false
    private fun launchGuarded(block: suspend () -> Unit) {
        if (commandRunning) { postErrorMessage(s(R.string.cmd_while_running)); return }
        commandRunning = true
        viewModelScope.launch {
            refreshLanguage()
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                postErrorMessage(friendlyError(e))
            } finally { commandRunning = false }
        }
    }

    /** "provider/model" display form; either side may be blank. */
    private fun displayModel(provider: String, model: String): String = when {
        provider.isBlank() -> model
        model.isBlank() -> provider
        else -> "$provider/$model"
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
    private fun isRunGone(e: Exception): Boolean = isRunGoneFailure(e)

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
        if (controlSending) return
        val req = approval ?: return
        val id = runId ?: return
        controlSending = true
        viewModelScope.launch {
            refreshLanguage()
            try {
                container.api().answerApproval(id, ApprovalAnswer(req.requestId, req.executionGeneration, choice))
                if (approval == req) approval = null
                container.runStreams.controlAnswered(id)
                statusLine = s(R.string.st_approved, choice)
            } catch (e: HermesApiException) {
                // Stale generations recover via DESIGN §9.4 first: a 409 here
                // usually means the answer missed, not that the session died.
                if (e.status == 409) {
                    if (approval == req) approval = null
                    if (recoverPendingControl(id)) return@launch
                }
                if (isRunGone(e)) {
                    // The run already ended: drop the dialog locally without
                    // blaming the conversation (the session is still usable).
                    busy = false
                    runId = null
                    approval = null
                    clarify = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // API status failures were handled by the HermesApiException catch above.
                error = friendlyError(e)
            } finally {
                controlSending = false
            }
        }
    }

    fun answerClarify(answer: String) {
        if (controlSending) return
        val prompt = clarify ?: return
        controlSending = true
        viewModelScope.launch {
            refreshLanguage()
            try {
                container.api().answerClarify(
                    prompt.runId,
                    ClarifyRequest(prompt.requestId, prompt.executionGeneration, answer),
                )
                if (clarify == prompt) { clarify = null; clarifyAnswer = "" }
                container.runStreams.controlAnswered(prompt.runId)
                statusLine = s(R.string.st_answered)
            } catch (e: HermesApiException) {
                // Same §9.4 recovery as approvals: the answer may have
                // missed its generation while a control still waits.
                if (e.status == 409) {
                    if (clarify == prompt) clarify = null
                    if (recoverPendingControl(prompt.runId)) return@launch
                }
                if (isRunGone(e)) {
                    busy = false
                    runId = null
                    approval = null
                    clarify = null
                    statusLine = ""
                    error = runGoneError(e)
                } else if (isSessionGone(e)) abandonRun(friendlyError(e)) else error = friendlyError(e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                controlSending = false
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
        // PendingControl has only Approval and Clarify variants. A nonempty list
        // without approvals therefore always contains a question.
        val question = parsed.filterIsInstance<PendingControl.Clarify>().last()
        showClarify(ClarifyPrompt(id, question.requestId, question.executionGeneration, question.prompt))
        statusLine = s(R.string.st_question)
        return true
    }

    private fun showClarify(prompt: ClarifyPrompt) {
        val identity = Triple(prompt.runId, prompt.requestId, prompt.executionGeneration)
        if (identity != clarifyAnswerIdentity) clarifyAnswer = ""
        clarifyAnswerIdentity = identity
        clarify = prompt
    }

    fun dismissError() {
        error = null
        // A dismissed foreign error also drops its fork offer: the action is
        // only meaningful together with the message.
        forkOffer = null
    }

    fun dismissForkDialog() {
        forkDialogOpen = false
    }

    /**
     * Records a session key as app-owned: the server accepted our write, so
     * it is provably "made by this app" regardless of owner metadata. Used
     * by the history filter to never hide our own conversations.
     */
    private suspend fun markAppOwned(id: String) {
        if (id.isBlank()) return
        return container.settingsStore.markAppOwned(id)
    }

    /**
     * Records a session key as owned by another surface so history can hide
     * it when the user opts out. Set-union keeps this idempotent. Suspends
     * so callers finish the write before reporting the block (no
     * fire-and-forget leak into the next test or screen).
     */
    private suspend fun markForeignSession(id: String) {
        if (id.isBlank()) return
        return container.settingsStore.markForeign(id)
    }

    /**
     * Ownership-denied runs (other-surface sessions): 401/403 or an explicit
     * permission_denied. Bare 404s stay out: a deleted session cannot be
     * forked either.
     */
    private fun isForeignOwned(e: Exception): Boolean =
        e is HermesApiException && (e.code == "permission_denied" || e.status == 401 || e.status == 403)

    /**
     * Forks a foreign-owned session into an app-owned copy and continues
     * there: the blocked text is resent when there is one (send-time
     * denial), otherwise the forked history just opens (history-load
     * denial). No-op while running/loading or with nothing to fork.
     */
    fun forkAndContinue() {
        if (busy || historyLoading) return
        if (!foreignBlocked && forkOffer.isNullOrBlank()) return
        val text = forkOffer
        forkOffer = null
        forkDialogOpen = false
        // Clear synchronously so waiters can't pass on the stale error
        // before the launch below takes effect.
        error = null
        viewModelScope.launch {
            refreshLanguage()
            busy = true
            statusLine = s(R.string.st_loading)
            try {
                val api = container.api()
                val forked = api.forkSession(sessionId, null)
                if (forked.isNullOrBlank()) {
                    busy = false
                    statusLine = ""
                    error = s(R.string.err_foreign)
                    return@launch
                }
                sessionId = forked
                // Our own copy: never hide it as foreign.
                markAppOwned(forked)
                renderHistory(api.getSessionMessages(forked))
                foreignBlocked = false
                if (!text.isNullOrBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.USER, text))
                    startRun(text, null)
                } else {
                    busy = false
                    statusLine = ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                statusLine = ""
                error = friendlyError(e)
            }
        }
    }

    private fun startRun(text: String, history: List<HistoryMessage>?, images: List<RunImage> = emptyList()) {
        busy = true
        error = null
        statusLine = s(R.string.st_starting)
        assistantIndex = -1
        lastSentText = text
        lastSentImages = images
        val messageId = messages.lastOrNull { it.kind == MsgKind.USER }?.id
        val userIndex = (messages.count { it.kind == MsgKind.USER } - 1).coerceAtLeast(0)
        viewModelScope.launch { doStartRun(text, history, images, messageId, userIndex) }
    }

    private suspend fun doStartRun(text: String, history: List<HistoryMessage>?, images: List<RunImage>, messageId: Long?, userIndex: Int) {
        val settings = container.settingsStore.current()
        uiLanguage = settings.language
        if (!settings.isConfigured) {
            busy = false
            error = s(R.string.err_not_configured)
            return
        }
        val id: String
        try {
            if (images.isNotEmpty()) {
                val previews = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.messageImages.stage(settings, images)
                }
                val index = messages.indexOfFirst { it.id == messageId }
                if (index >= 0) messages[index] = messages[index].copy(images = previews)
            }
            val accepted = container.runStreams.createTracked(
                RunCreateRequest(input = text, sessionId = sessionId.ifBlank { null }, conversationHistory = history,
                    model = settings.model.ifBlank { null }, provider = settings.provider.ifBlank { null }, modelOptions = settings.modelOptions,
                    images = images), settings, userMessageIndex = userIndex)
            id = accepted.runId
            runId = id
            sessionId = accepted.sessionId
            container.conversations.adopt(visibilityOwner, settings, sessionId)
            if (container.conversations.title(settings, sessionId).isNullOrBlank()) {
                container.conversations.rememberTitle(settings, sessionId, text.lineSequence().firstOrNull().orEmpty().take(80))
            }
            attachments.clear()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            busy = false
            statusLine = ""
            if (input.isBlank()) input = text
            error = friendlyError(e)
            // Other-surface sessions (e.g. Discord) reject runs: lock the
            // input, remember the key for history filtering, and ask to
            // fork-and-continue instead of hitting a dead end.
            if (isForeignOwned(e)) {
                // Record first: awaiting the flags below implies the write
                // already landed (no late clobber of later saves).
                markForeignSession(sessionId)
                forkOffer = if (sessionId.isNotBlank()) text else null
                foreignBlocked = true
                forkDialogOpen = true
            } else {
                forkOffer = null
            }
            return
        }
        // The retained journal also delivers events received during creation.
        subscribe(id)

    }

    /**
     * Renders what the stream engine publishes for this run. The engine
     * owns the subscription, the reconnect backoff and the status polls;
     * this collector only settles the outcomes into chat state, with the
     * same handlers the former in-ViewModel loop used.
     */
    private fun subscribe(id: String) {
        eventsJob?.cancel()
        eventsJob = container.runStreams.updatesFor(id)
            .onStart { refreshLanguage() }
            .onEach { update -> handleStreamUpdate(id, update) }
            .launchIn(viewModelScope)
    }

    private suspend fun handleStreamUpdate(id: String, update: RunStreamUpdate) {
        when (update) {
            is RunStreamUpdate.ControlsCleared -> { approval = null; clarify = null }
            is RunStreamUpdate.QueueStarting -> { busy = true; statusLine = s(R.string.st_starting) }
            is RunStreamUpdate.NextRun -> {
                messages.add(UiMessage(idSeq++, MsgKind.USER, update.prompt))
                assistantIndex = -1
                runId = update.next.runId
                lastSentText = update.prompt
                busy = true
                subscribe(update.next.runId)
            }
            is RunStreamUpdate.Event -> handleEvent(id, update.event)
            is RunStreamUpdate.Reconnecting -> statusLine = s(R.string.st_reconnecting)
            is RunStreamUpdate.Resync -> {
                update.status.sessionId?.ifBlank { null }?.let { sessionId = it }
                // A drop may have swallowed the approval/clarify request
                // event while the run still waits: re-surface it.
                if (update.status.pendingControlsKnown) { approval = null; clarify = null }
                surfacePendingControls(id, update.status.pendingControls)
            }
            is RunStreamUpdate.PollTerminal -> finishTerminal(
                RunEvent.Terminal(
                    update.status.status,
                    update.status.output,
                    update.status.error,
                    update.status.pendingSteer,
                )
            )
            is RunStreamUpdate.UnknownOutcome -> {
                lastRunId = runId
                approval = null
                clarify = null
                busy = false
                runId = null
                statusLine = ""
                error = s(R.string.st_unknown_restart)
            }
            is RunStreamUpdate.Dead -> settleStreamDead(update.cause)
        }
    }

    /**
     * The stream engine gave up on this run (or the run vanished
     * server-side): settle locally with the message the failure deserves.
     * A gone run is an ended run, never a dead conversation. Transient
     * EOFs keep reconnecting in the engine and do not reach this handler.
     */
    private fun settleStreamDead(cause: Exception) {
        busy = false
        runId = null
        statusLine = ""
        error = if (isRunGone(cause)) runGoneError(cause) else friendlyError(cause)
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
                // Failures stay one-line activity rows too: a red error card
                // reads like a stop button and overstates a retried tool step.
                val preview = humanPreview(event.preview)
                val shown = summarizeMachineJson(preview.trim()) ?: preview
                messages.add(
                    UiMessage(
                        idSeq++,
                        MsgKind.TOOL,
                        s(R.string.tool_finished, event.tool, event.durationSeconds.toString(), shown).trim(),
                    )
                )
            }
            is RunEvent.SubagentStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.delegating, event.summary)))
            is RunEvent.SubagentCompleted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.child_done, event.status, event.summary)))
            is RunEvent.ControlResolved -> {
                if (event.requestId == null || approval?.requestId == event.requestId) approval = null
                if (event.requestId == null || clarify?.requestId == event.requestId) clarify = null
            }
            is RunEvent.ApprovalRequested -> {
                approval = event.request
                statusLine = s(R.string.st_waiting_approval)
            }
            is RunEvent.ClarifyRequested -> {
                showClarify(ClarifyPrompt(id, event.requestId, event.executionGeneration, event.prompt))
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
                statusLine = if (event.name == "replay.truncated") s(R.string.stream_replay_truncated) else event.name
            }
        }
        return false
    }

    private fun finishTerminal(event: RunEvent.Terminal) {
        lastRunId = runId
        approval = null
        clarify = null
        busy = event.status == "completed" && pendingQueue.isNotEmpty()
        runId = null
        statusLine = when (event.status) {
            "completed" -> ""
            "cancelled" -> s(R.string.st_cancelled)
            "failed" -> s(R.string.st_failed)
            else -> event.status
        }
        if (!event.output.isNullOrBlank()) {
            if (assistantIndex >= 0 && assistantIndex < messages.size) {
                messages[assistantIndex] = messages[assistantIndex].copy(text = event.output)
            } else messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, event.output))
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

    private fun friendlyError(e: Exception): String {
        if (e is HermesApiException) {
            return when (e.code) {
                "request_too_large" -> s(R.string.message_too_large)
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
        if (split.visible.isNotEmpty()) appendAssistantText(split.visible)
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
    // A successfully parsed value starting with '[' is necessarily an array;
    // the opening-character guard above rules out primitive JSON values.
    val array = el as JsonArray
    if (array.isEmpty()) return null
    val first = array.first() as? JsonObject ?: return null
    if (first.keys.none { it == "url" || it == "link" || it == "href" || it == "title" }) return null
    val head = (first["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    val links = if (head.isEmpty()) "${array.size} links" else "$head (+${array.size - 1})"
    return "links: $links"
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
        o.str("type", "kind").equals("approval", ignoreCase = true)
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
