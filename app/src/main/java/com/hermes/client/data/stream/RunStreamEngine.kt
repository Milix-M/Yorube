package com.hermes.client.data.stream

import com.hermes.client.AppContainer
import com.hermes.client.data.api.HermesApiException
import com.hermes.client.data.api.RunEvent
import com.hermes.client.data.api.RunStatusResponse
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Consecutive fruitless resubscribes allowed before the stream is declared
 * dead. The counter resets on every received event, so it only binds a
 * persistent outage (no progress at all), never a long run over a flaky
 * network (Cloudflare idle kills, NAT timeouts, Doze, the DESIGN §8
 * five-minute transport buffer).
 */
internal const val MAX_RECONNECT_ATTEMPTS = 15

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
 * instead of burning the reconnect budget and masking the real error
 * behind a later socket message. Run-level 404s (not_found/run_not_found
 * or a bare 404) are NOT included here: the run expired server-side but
 * the session itself is still usable, so they surface as an ended run
 * (isRunGoneFailure) instead of a foreign-session error.
 */
internal fun isUnrecoverableFailure(e: Exception): Boolean =
    e is HermesApiException && (e.status == 401 || e.status == 403 ||
        e.code == "permission_denied")

/** The one run whose SSE stream the engine currently keeps alive. */
data class ActiveRun(val runId: String, val sessionId: String)

/**
 * One thing the stream loop wants the chat UI to know. Events are the
 * server frames themselves; everything else is the reconnect machinery
 * reporting an outcome the UI settles with its existing handlers
 * (pending-control surfacing, terminal rendering, error settling).
 */
sealed interface RunStreamUpdate {
    val runId: String

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
 * Owns the SSE subscription for the one active run, at app scope, so the
 * stream survives the chat screen: the ChatViewModel only renders what
 * this engine publishes, and [RunStreamService] keeps the process in the
 * foreground state while a run is active. This replaces the former
 * ViewModel-owned loop with the same policy (DESIGN §8): on a drop, poll
 * the run status, re-surface pending controls, resubscribe while the run
 * is going, and give up after [MAX_RECONNECT_ATTEMPTS] fruitless cycles.
 *
 * The control loop runs on the main dispatcher, mirroring the old
 * viewModelScope loop: every blocking call hops to IO inside HermesApi.
 */
class RunStreamEngine(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val mutableUpdates = MutableSharedFlow<RunStreamUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<RunStreamUpdate> = mutableUpdates.asSharedFlow()

    private val mutableActiveRun = MutableStateFlow<ActiveRun?>(null)
    val activeRun: StateFlow<ActiveRun?> = mutableActiveRun.asStateFlow()

    private var loopJob: Job? = null
    private var generation = 0

    /** Updates for one run only: the UI never sees another run's frames. */
    fun updatesFor(runId: String): Flow<RunStreamUpdate> = updates.filter { it.runId == runId }

    /**
     * Starts (or restarts) the stream loop for [runId]. One run at a
     * time: a new start supersedes the previous loop, whose cleanup never
     * clears the new loop's record (the generation check also covers a
     * restart of the same run). With [pollFirst] the loop polls the run
     * status before subscribing, so a UI (re)attaching to a live run
     * immediately re-derives its state (pending controls, terminal,
     * unknown) instead of waiting for the next frame.
     */
    fun start(runId: String, sessionId: String, pollFirst: Boolean = false) {
        val gen = ++generation
        mutableActiveRun.value = ActiveRun(runId, sessionId)
        loopJob?.cancel()
        loopJob = scope.launch {
            try {
                loop(runId, pollFirst)
            } finally {
                if (generation == gen && mutableActiveRun.value?.runId == runId) {
                    mutableActiveRun.value = null
                }
            }
        }
    }

    private suspend fun loop(runId: String, pollFirst: Boolean) {
        var attempts = 0
        var lastCause: Exception? = null
        var needPoll = pollFirst
        while (true) {
            if (needPoll) {
                needPoll = false
                val status = try {
                    getRunWithRetry(runId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // HTTP answers are authoritative: report and stop.
                    // A transient transport blip consumes one attempt and
                    // loops back to the stream instead of killing the run
                    // (dead zones, tunnel restarts).
                    if (e is HermesApiException) {
                        mutableUpdates.tryEmit(RunStreamUpdate.Dead(runId, e))
                        return
                    }
                    attempts++
                    lastCause = e
                    null
                }
                if (status != null) {
                    when (status.status) {
                        "completed", "failed", "cancelled", "interrupted" -> {
                            mutableUpdates.tryEmit(RunStreamUpdate.PollTerminal(runId, status))
                            return
                        }
                        "unknown" -> {
                            mutableUpdates.tryEmit(RunStreamUpdate.UnknownOutcome(runId))
                            return
                        }
                    }
                    mutableUpdates.tryEmit(RunStreamUpdate.Resync(runId, status))
                    attempts++
                }
                continue
            }
            var terminalSeen = false
            var cause: Exception? = null
            try {
                val api = container.api()
                api.runEvents(runId).collect { event ->
                    // A delivered event proves the stream is healthy again:
                    // reset the outage budget instead of letting hourly
                    // proxy kills accumulate over a long run.
                    attempts = 0
                    mutableUpdates.tryEmit(RunStreamUpdate.Event(runId, event))
                    if (event is RunEvent.Terminal) terminalSeen = true
                }
            } catch (e: CancellationException) {
                // Superseded by a newer start or scope teardown: not a
                // stream failure, never route into the reconnect path.
                throw e
            } catch (e: Exception) {
                cause = e
                lastCause = e
            }
            if (terminalSeen) return
            // The run is gone server-side but the session is still usable:
            // report it as an ended run, never as a dead conversation.
            if (cause != null && isRunGoneFailure(cause)) {
                mutableUpdates.tryEmit(RunStreamUpdate.Dead(runId, cause))
                return
            }
            // Permanent auth/ownership failures fail fast.
            if (cause != null && isUnrecoverableFailure(cause)) {
                mutableUpdates.tryEmit(RunStreamUpdate.Dead(runId, cause))
                return
            }
            if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                // Clean EOFs carry no cause: fall back to the latest real
                // failure so a transport outage reports "connection lost",
                // not a bare "stream ended".
                mutableUpdates.tryEmit(RunStreamUpdate.Dead(runId, cause ?: lastCause))
                return
            }
            mutableUpdates.tryEmit(RunStreamUpdate.Reconnecting(runId))
            // Spread retries so half-open sockets and tight empty-stream
            // loops (the §8 transport buffer expires after 5 min) don't
            // spin. delay() is also the cancellation point: teardown
            // during the wait throws here instead of polling pointlessly.
            delay(reconnectDelayMs(attempts))
            needPoll = true
        }
    }

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
}
