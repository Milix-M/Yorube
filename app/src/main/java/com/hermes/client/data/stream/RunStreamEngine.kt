package com.hermes.client.data.stream

import com.hermes.client.AppContainer
import com.hermes.client.data.api.HermesApiException
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.api.RunStatusResponse
import com.hermes.client.data.api.str
import com.hermes.client.data.api.long
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reconnect backoff before each status poll: 1s, 2s, 4s, then 8s capped. */
internal fun reconnectDelayMs(attempt: Int): Long = 1000L * (1L shl attempt.coerceIn(0, 3))

/**
 * The targeted run is gone server-side (finished, expired or unknown).
 * The conversation itself may still be fine, so this must not be
 * reported as a dead conversation. Bare 404s (e.g. proxy pages without
 * a machine code) count as well.
 */
internal fun isRunGoneFailure(e: Exception): Boolean {
    if (e !is HermesApiException) return false
    if (e.code == "not_found" || e.code == "run_not_found" || e.code == "unknown_execution") return true
    return e.status == 404 && e.code == null
}

/**
 * HTTP 401/403 and ownership failures cannot heal by polling: fail fast
 * instead of repeatedly polling and masking the real error
 * behind a later socket message. Run-level 404s (not_found/run_not_found
 * or a bare 404) are NOT included here: the run expired server-side but
 * the session itself is still usable, so they surface as an ended run
 * (isRunGoneFailure) instead of a foreign-session error.
 */
internal fun isUnrecoverableFailure(e: Exception): Boolean =
    e is HermesApiException && (e.status == 401 || e.status == 403 ||
        e.code == "permission_denied")

/** A server execution being tracked independently of its chat screen. */
data class ActiveRun(val runId: String, val sessionId: String)

/**
 * One thing the stream loop wants the chat UI to know. Events are the
 * server frames themselves; everything else is the reconnect machinery
 * reporting an outcome the UI settles with its existing handlers
 * (pending-control surfacing, terminal rendering, error settling).
 */
sealed interface RunStreamUpdate {
    val runId: String

    data class QueueStarting(override val runId: String) : RunStreamUpdate
    data class NextRun(override val runId: String, val next: ActiveRun, val prompt: String) : RunStreamUpdate
    data class ControlsCleared(override val runId: String) : RunStreamUpdate

    /** A live SSE frame for the run. */
    data class Event(override val runId: String, val event: RunEvent) : RunStreamUpdate

    /** The stream dropped and a status poll is about to happen. */
    data class Reconnecting(override val runId: String) : RunStreamUpdate

    /** A status poll found the run still going: re-surface its pending controls. */
    data class Resync(override val runId: String, val status: RunStatusResponse) : RunStreamUpdate

    /** A status poll found the run finished: render it as terminal. */
    data class PollTerminal(override val runId: String, val status: RunStatusResponse) : RunStreamUpdate

    /** A status poll reported "unknown": the outcome must not be resent. */
    data class UnknownOutcome(override val runId: String) : RunStreamUpdate

    /**
     * The stream is over without a terminal frame. [cause] is the failure
     * to report (null when the outage was only clean EOFs: the UI falls
     * back to its plain "stream ended" message).
     */
    data class Dead(override val runId: String, val cause: Exception?) : RunStreamUpdate
}

/**
 * App-scoped streams and queues. Each run has an independent connection and a
 * retained UI journal. Accepted run references survive process recreation;
 * transient outages are polled with backoff until the server reports an outcome.
 * All record mutations run on Main; network and durable commits run on IO.
 */
class RunStreamEngine(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val records = linkedMapOf<String, Record>()
    private val mutableActiveRuns = MutableStateFlow<List<ActiveRun>>(emptyList())
    val activeRuns: StateFlow<List<ActiveRun>> = mutableActiveRuns.asStateFlow()
    private val mutableActiveRun = MutableStateFlow<ActiveRun?>(null)
    val activeRun: StateFlow<ActiveRun?> = mutableActiveRun.asStateFlow()
    private val mutableUpdates = MutableSharedFlow<RunStreamUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<RunStreamUpdate> = mutableUpdates.asSharedFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working: StateFlow<Boolean> = mutableWorking.asStateFlow()
    private var starting = 0
    private val persistenceMutex = Mutex()
    private val saved = container.appContext.getSharedPreferences("active_runs", 0)

    private class Record(val active: ActiveRun, var endpoint: String = "") {
        val journal = ArrayList<RunStreamUpdate>()
        val version = MutableStateFlow(0L)
        var job: Job? = null
        var cursor: Long? = null
        var finished = false
        var finishedAt = 0L
        var completed = false
        var sessionId = active.sessionId
    }

    /** Retained per-run journal: a slow or absent UI cannot lose frames. */
    fun updatesFor(runId: String): Flow<RunStreamUpdate> = kotlinx.coroutines.flow.flow {
        val record = records.getOrPut(runId) { Record(ActiveRun(runId, "")) }
        var index = 0
        var deltaLength = 0
        record.version.collect {
            while (index < record.journal.size) {
                val update = record.journal[index]
                val delta = (update as? RunStreamUpdate.Event)?.event as? RunEvent.Delta
                if (delta != null) {
                    val suffix = delta.text.drop(deltaLength)
                    if (suffix.isNotEmpty()) emit(RunStreamUpdate.Event(runId, RunEvent.Delta(suffix)))
                    deltaLength = delta.text.length
                    if (index == record.journal.lastIndex) break
                } else {
                    emit(update)
                }
                index++
                deltaLength = 0
            }
        }
    }

    fun activeForSession(sessionId: String): ActiveRun? = activeRuns.value.lastOrNull { it.sessionId == sessionId }

    /** Idempotent; other runs and subscribers keep their original stream. */
    fun start(runId: String, sessionId: String, pollFirst: Boolean = false) {
        val previous = records[runId]
        if (previous?.job?.isActive == true || previous?.finished == true) return
        val record = if (previous != null && previous.active.sessionId == sessionId) previous
            else Record(ActiveRun(runId, sessionId)).also {
                // updatesFor can subscribe before start, so keep its journal signal.
                if (previous != null) return startExisting(previous, sessionId, pollFirst)
                records[runId] = it
            }
        launchRecord(record, pollFirst)
    }

    private fun startExisting(record: Record, sessionId: String, pollFirst: Boolean) {
        record.sessionId = sessionId
        launchRecord(record, pollFirst)
    }

    private fun publishActive() {
        mutableActiveRuns.value = records.values.filter { it.job != null && !it.finished }
            .map { ActiveRun(it.active.runId, it.sessionId) }
        mutableActiveRun.value = mutableActiveRuns.value.lastOrNull()
        mutableWorking.value = starting > 0 || mutableActiveRuns.value.isNotEmpty()
    }

    /** Creation belongs to app scope too: leaving the screen must not orphan an accepted run. */
    suspend fun createTracked(request: com.hermes.client.data.api.RunCreateRequest,
        settings: com.hermes.client.data.store.ClientSettings, key: String = java.util.UUID.randomUUID().toString(),
        onAccepted: suspend () -> Unit = {}): ActiveRun {
        return scope.async {
            starting++
            publishActive()
            // Start while the user action is still in the foreground, before waiting on the network.
            RunStreamService.start(container.appContext, "", request.sessionId.orEmpty())
            try {
                val api = container.api(settings)
                val accepted = api.createRun(request, key)
                // Persist the accepted ID before the optional session lookup.
                // Hermes uses run_id for a fresh session when neither session nor response chain is supplied.
                // Keep that ID even if the optional status lookup is temporarily unavailable.
                val session = request.sessionId ?: if (request.previousResponseId == null) accepted.runId else ""
                val record = Record(ActiveRun(accepted.runId, session), settings.baseUrl)
                records[accepted.runId] = record
                persist()
                try {
                    val status = api.getRun(accepted.runId)
                    status.sessionId?.takeIf { it.isNotBlank() }?.let { record.sessionId = it }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (record.sessionId.isNotBlank()) container.settingsStore.markAppOwned(record.sessionId)
                onAccepted()
                launchRecord(record, pollFirst = false, settings = settings)
                ActiveRun(accepted.runId, record.sessionId)
            } finally {
                starting--
                publishActive()
            }
        }.await()
    }

    private fun launchRecord(record: Record, pollFirst: Boolean, settings: com.hermes.client.data.store.ClientSettings? = null) {
        record.job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val configuration = settings ?: container.settingsStore.current()
                record.endpoint = configuration.baseUrl
                persist()
                val api = container.api(configuration)
                loop(record, api, pollFirst)
                if (record.completed) sendQueued(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(record, RunStreamUpdate.Dead(record.active.runId, e))
            } finally {
                record.finished = true
                record.finishedAt = android.os.SystemClock.elapsedRealtime()
                publishActive()
                persist()
                // Keep recently completed journals for screens still loading their history.
                records.entries.removeAll { it.value.finished && it.value !== record &&
                    android.os.SystemClock.elapsedRealtime() - it.value.finishedAt > 300_000 &&
                    it.value.version.subscriptionCount.value == 0 }
            }
        }
        publishActive()
        record.job!!.start()
    }

    private suspend fun persist() = persistenceMutex.withLock {
        val value = kotlinx.serialization.json.buildJsonArray {
            records.values.filter { !it.finished && it.endpoint.isNotBlank() }.forEach { r ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("run", kotlinx.serialization.json.JsonPrimitive(r.active.runId))
                    put("session", kotlinx.serialization.json.JsonPrimitive(r.sessionId))
                    put("endpoint", kotlinx.serialization.json.JsonPrimitive(r.endpoint))
                    // Do not persist the cursor without its transcript; restore replays the retained stream.
                })
            }
        }.toString()
        kotlinx.coroutines.withContext(Dispatchers.IO) { saved.edit().putString("runs", value).commit() }
    }

    suspend fun restore() {
        val endpoint = container.settingsStore.current().baseUrl
        val list = runCatching {
            com.hermes.client.data.api.HermesJson.parseToJsonElement(saved.getString("runs", "[]")!!)
                as kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return
        for (item in list) {
            val obj = item as? kotlinx.serialization.json.JsonObject ?: continue
            fun text(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (text("endpoint") == endpoint && text("run").isNotBlank() && !records.containsKey(text("run"))) {
                start(text("run"), text("session"), pollFirst = true)
            }
        }
    }

    private suspend fun publish(record: Record, update: RunStreamUpdate) {
        val last = record.journal.lastOrNull() as? RunStreamUpdate.Event
        val delta = (update as? RunStreamUpdate.Event)?.event as? RunEvent.Delta
        if (delta != null && last?.event is RunEvent.Delta) {
            record.journal[record.journal.lastIndex] = RunStreamUpdate.Event(update.runId,
                RunEvent.Delta((last.event as RunEvent.Delta).text + delta.text))
        } else {
            record.journal.add(update)
        }
        record.version.value++
        mutableUpdates.emit(update)
        try {
            container.runNotifications.onUpdate(ActiveRun(record.active.runId, record.sessionId), update)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // A notification failure must never break the execution stream.
        }
    }

    private suspend fun loop(record: Record, api: com.hermes.client.data.api.HermesApi, pollFirst: Boolean) {
        val id = record.active.runId
        var attempts = 0
        var needPoll = pollFirst
        while (true) {
            try {
                if (needPoll) {
                    val status = api.getRun(id)
                    status.sessionId?.takeIf { it.isNotBlank() }?.let { record.sessionId = it; publishActive(); persist() }
                    when (status.status) {
                        "completed", "failed", "cancelled", "interrupted" -> {
                            record.completed = status.status == "completed"
                            publish(record, RunStreamUpdate.PollTerminal(id, status)); return
                        }
                        "unknown" -> { publish(record, RunStreamUpdate.UnknownOutcome(id)); return }
                    }
                    publish(record, RunStreamUpdate.Resync(id, status))
                }
                var terminal = false
                api.runEventFrames(id, record.cursor).collect { frame ->
                    if (frame.sequence == null || record.cursor == null || frame.sequence > record.cursor!!) {
                        // A restored stream can replay already answered prompts. Confirm controls
                        // against the current server generation before showing or notifying them.
                        val control = when (val event = frame.event) {
                            is RunEvent.ApprovalRequested -> event.request.requestId to event.request.executionGeneration
                            is RunEvent.ClarifyRequested -> event.requestId to event.executionGeneration
                            else -> null
                        }
                        if (pollFirst && control != null) {
                            val current = api.getRun(id)
                            if (current.pendingControlsKnown && current.pendingControls.none {
                                    it.str("request_id", "prompt_id", "id") == control.first &&
                                        (it.long("execution_generation", "generation") ?: 0L) == control.second
                                }) {
                                frame.sequence?.let { record.cursor = it }
                                return@collect
                            }
                        }
                        publish(record, RunStreamUpdate.Event(id, frame.event))
                        frame.sequence?.let { record.cursor = it }
                    }
                    if (frame.event is RunEvent.Terminal) record.completed = frame.event.status == "completed"
                    terminal = terminal || frame.event is RunEvent.Terminal
                    attempts = 0
                }
                if (terminal) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRunGoneFailure(e) || isUnrecoverableFailure(e) ||
                    (e is HermesApiException && e.status in 400..499 && e.status !in listOf(408, 429))) {
                    publish(record, RunStreamUpdate.Dead(id, e)); return
                }
            }
            publish(record, RunStreamUpdate.Reconnecting(id))
            // Offline/5xx/429 are not a completed run. Keep trying while the service owns it.
            delay(if (attempts < 4) reconnectDelayMs(attempts) else 30_000L)
            attempts = (attempts + 1).coerceAtMost(30)
            needPoll = true
        }
    }

    private val queuePreferences = container.appContext.getSharedPreferences("run_queue", 0)
    private val sendingQueues = mutableSetOf<String>()
    fun queueBusy(session: String) = session in sendingQueues
    private val queues = mutableMapOf<String, MutableList<String>>()
    private fun queueKey(session: String, endpoint: String) = "$endpoint|$session"
    fun queue(session: String, endpoint: String = container.settingsStore.snapshot.baseUrl): MutableList<String> {
        val key = queueKey(session, endpoint)
        return queues.getOrPut(key) {
            runCatching {
                val array = com.hermes.client.data.api.HermesJson.parseToJsonElement(
                    queuePreferences.getString(key, "[]")!!) as kotlinx.serialization.json.JsonArray
                array.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toMutableList()
            }.getOrDefault(mutableListOf())
        }
    }
    fun saveQueue(session: String) {
        val array = kotlinx.serialization.json.JsonArray(queue(session).map { kotlinx.serialization.json.JsonPrimitive(it) })
        queuePreferences.edit().putString(queueKey(session, container.settingsStore.snapshot.baseUrl), array.toString()).apply()
    }
    private suspend fun sendQueued(record: Record) {
        val session = record.sessionId
        if (session.isBlank() || activeRuns.value.any { it.sessionId == session && it.runId != record.active.runId }) return
        val pending = queue(session, record.endpoint)
        val prompt = pending.firstOrNull() ?: return
        // A saved key makes a retry of this queued turn idempotent after process death.
        val storageKey = queueKey(session, record.endpoint)
        val keyName = "key:$storageKey"
        val key = queuePreferences.getString(keyName, null) ?: java.util.UUID.randomUUID().toString()
        sendingQueues.add(session)
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { queuePreferences.edit().putString(keyName, key).commit() }
            publish(record, RunStreamUpdate.QueueStarting(record.active.runId))
            val nextSettings = container.settingsStore.current()
            if (nextSettings.baseUrl != record.endpoint) return
            val next = createTracked(com.hermes.client.data.api.RunCreateRequest(input = prompt, sessionId = session,
                model = nextSettings.model.ifBlank { null }, provider = nextSettings.provider.ifBlank { null }, modelOptions = nextSettings.modelOptions), nextSettings, key) {
                if (pending.firstOrNull() == prompt) pending.removeAt(0)
                val array = kotlinx.serialization.json.JsonArray(pending.map { kotlinx.serialization.json.JsonPrimitive(it) })
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    queuePreferences.edit().putString(storageKey, array.toString()).remove(keyName).commit()
                }
            }
            publish(record, RunStreamUpdate.NextRun(record.active.runId, next, prompt))
        } finally { sendingQueues.remove(session) }
    }

    fun controlAnswered(runId: String) {
        val record = records[runId] ?: return
        scope.launch { publish(record, RunStreamUpdate.ControlsCleared(runId)) }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }
}
