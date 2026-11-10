package com.hermes.client.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HermesApiException(
    val status: Int,
    val code: String?,
    override val message: String,
) : IOException("HTTP $status${code?.let { " [$it]" } ?: ""}: $message")

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private const val IDEMPOTENCY_HEADER = "Idempotency-Key"

/**
 * HTTP client for the Hermes API Server (gateway/platforms/api_server.py).
 * Base URL looks like https://host:port, paths start with /v1/ and /api/.
 */
class HermesApi(
    baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = validatedBaseUrl(baseUrl)

    // -- low level --

    private fun get(path: String, extra: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(base + path).get()
        b.header("Authorization", "Bearer $apiKey")
        for ((k, v) in extraHeaders) if (v.isNotBlank()) b.header(k, v)
        for ((k, v) in extra) b.header(k, v)
        return b.build()
    }

    private fun post(path: String, body: String?, extra: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(base + path)
            .post((body ?: "").toRequestBody(JSON_MEDIA))
        b.header("Authorization", "Bearer $apiKey")
        for ((k, v) in extraHeaders) if (v.isNotBlank()) b.header(k, v)
        for ((k, v) in extra) b.header(k, v)
        return b.build()
    }

    private fun delete(path: String): Request {
        val b = Request.Builder().url(base + path).delete()
        b.header("Authorization", "Bearer $apiKey")
        for ((k, v) in extraHeaders) if (v.isNotBlank()) b.header(k, v)
        return b.build()
    }

    private fun patch(path: String, body: String?): Request {
        val b = Request.Builder().url(base + path)
            .patch((body ?: "").toRequestBody(JSON_MEDIA))
        b.header("Authorization", "Bearer $apiKey")
        for ((k, v) in extraHeaders) if (v.isNotBlank()) b.header(k, v)
        return b.build()
    }

    private fun parseError(status: Int, body: String): HermesApiException {
        var code: String? = null
        var message = body.ifBlank { "empty response" }.take(300)
        try {
            val root = HermesJson.parseToJsonElement(body)
            val err = if (root is JsonObject) root["error"] else root
            if (err is JsonObject) {
                code = err.str("code")
                err.str("message")?.let { message = it }
            } else if (err is kotlinx.serialization.json.JsonPrimitive && err.isString) {
                message = err.content
            }
        } catch (_: Exception) {
        }
        return HermesApiException(status, code, message)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // Read/close on OkHttp's worker; cancellation also interrupts a stalled body.
                continuation.resumeWith(runCatching {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw parseError(it.code, body)
                        body
                    }
                })
            }
        })
    }

    // -- M0: connection --

    suspend fun checkHealth(): Boolean {
        return try {
            val body = execute(get("/health"))
            HermesJson.parseToJsonElement(body).jsonObject.str("status") == "ok"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getCapabilities(): CapabilitiesResponse {
        val body = execute(get("/v1/capabilities"))
        return HermesJson.decodeFromString(body)
    }

    suspend fun getModelIds(): List<String> {
        val body = execute(get("/v1/models"))
        return try {
            extractModelIds(HermesJson.parseToJsonElement(body))
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getModelOptions(refresh: Boolean = false): String {
        val path = if (refresh) "/api/model/options?refresh=1" else "/api/model/options"
        return execute(get(path))
    }

    suspend fun getSkills(): List<SkillInfo> {
        val body = execute(get("/v1/skills"))
        return parseSkills(body)
    }

    suspend fun getToolsets(): List<ToolsetInfo> {
        val body = execute(get("/v1/toolsets"))
        return parseToolsets(body)
    }

    private fun parseSkills(body: String): List<SkillInfo> {
        return try {
            val el = HermesJson.parseToJsonElement(body)
            val arr = when {
                el is JsonArray -> el
                el is JsonObject -> el["skills"] as? JsonArray
                else -> null
            } ?: return emptyList()
            HermesJson.decodeFromString<List<SkillInfo>>(arr.toString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseToolsets(body: String): List<ToolsetInfo> {
        return try {
            val el = HermesJson.parseToJsonElement(body)
            val arr = when {
                el is JsonArray -> el
                el is JsonObject -> el["toolsets"] as? JsonArray
                else -> null
            } ?: return emptyList()
            HermesJson.decodeFromString<List<ToolsetInfo>>(arr.toString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -- M1: runs --

    suspend fun createRun(request: RunCreateRequest, idempotencyKey: String): RunAccepted {
        val payload = HermesJson.encodeToJsonElement(request).jsonObject.toMutableMap()
        payload.remove("images")
        if (request.images.isNotEmpty()) {
            payload["input"] = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (request.input.isNotBlank()) add(buildJsonObject {
                            put("type", "text"); put("text", request.input)
                        })
                        request.images.forEach { image -> add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", image.dataUrl) })
                        }) }
                    })
                })
            }
        }
        val httpRequest = post("/v1/runs", JsonObject(payload).toString(), mapOf(IDEMPOTENCY_HEADER to idempotencyKey))
        if (httpRequest.body!!.contentLength() > MAX_RUN_BYTES) {
            throw HermesApiException(413, "request_too_large", "The message exceeds the 10 MB request limit")
        }
        val body = execute(httpRequest)
        return HermesJson.decodeFromString(body)
    }

    suspend fun getRun(runId: String): RunStatusResponse {
        val body = execute(get("/v1/runs/${runId.pathSegment()}"))
        return HermesJson.decodeFromString<RunStatusResponse>(body).copy(
            pendingControlsKnown = "pending_controls" in HermesJson.parseToJsonElement(body).jsonObject)
    }

    suspend fun stopRun(runId: String) { execute(post("/v1/runs/${runId.pathSegment()}/stop", "{}")) }

    suspend fun steerRun(runId: String, text: String) { execute(post("/v1/runs/${runId.pathSegment()}/steer", HermesJson.encodeToString(SteerRequest(text)))) }

    suspend fun answerApproval(runId: String, answer: ApprovalAnswer) { execute(post("/v1/runs/${runId.pathSegment()}/approval", HermesJson.encodeToString(answer))) }

    suspend fun answerClarify(runId: String, request: ClarifyRequest) { execute(post("/v1/runs/${runId.pathSegment()}/clarify", HermesJson.encodeToString(request))) }

    suspend fun resolveUnknown(runId: String, request: ResolveUnknownRequest) { execute(post("/v1/runs/${runId.pathSegment()}/resolve-unknown", HermesJson.encodeToString(request))) }

    /**
     * SSE stream of run events. Skips keepalive comment lines.
     * Ends after a terminal run.* event or when the collector cancels.
     */
    fun runEvents(runId: String): Flow<RunEvent> = runEventFrames(runId).let { frames ->
        kotlinx.coroutines.flow.flow { frames.collect { emit(it.event) } }
    }

    /** Resume with the last frame acknowledged by the engine, never the socket reader. */
    fun runEventFrames(runId: String, after: Long? = null): Flow<RunFrame> = callbackFlow {
        val headers = after?.let { mapOf("Last-Event-ID" to it.toString()) } ?: emptyMap()
        val call = client.newCall(get("/v1/runs/${runId.pathSegment()}/events", headers))
        val readerJob = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw parseError(response.code, runCatching { response.body?.string().orEmpty() }.getOrDefault(""))
                    val reader = (response.body ?: throw IOException("empty response")).charStream().buffered()
                    var name = "message"
                    var sequence: Long? = null
                    val data = StringBuilder()
                    var terminal = false
                    suspend fun dispatch() {
                        val obj = parseSseData(data.toString())
                        if (obj != null) {
                            val payload = (obj["data"] as? JsonObject) ?: obj
                            val event = parseRunEvent(obj.str("event")?.ifBlank { null } ?: name, payload)
                            send(RunFrame(sequence ?: obj.long("seq"), event))
                            terminal = event is RunEvent.Terminal
                        }
                        data.clear()
                        name = "message"
                        sequence = null
                    }
                    while (!terminal) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith("event:") -> name = line.substring(6).trim()
                            line.startsWith("id:") -> sequence = line.substring(3).trim().toLongOrNull()
                            line.startsWith("data:") -> data.append(line.substring(5).removePrefix(" ")).append('\n')
                        }
                    }
                    if (!terminal && data.isNotEmpty()) dispatch()
                }
                close()
            } catch (e: CancellationException) {
                close(e)
            } catch (e: Exception) {
                close(e)
            }
        }
        // Runs concurrently with the blocking reader, including while awaiting headers.
        awaitClose { call.cancel(); readerJob.cancel() }
    }

    // -- sessions (list and history display) --

    suspend fun listSessions(limit: Int = 50, offset: Int = 0): List<SessionInfo> {
        val body = execute(get("/api/sessions?limit=$limit&offset=$offset"))
        return try {
            val el = HermesJson.parseToJsonElement(body)
            val arr = when {
                el is JsonArray -> el
                el is JsonObject ->
                    (el["sessions"] as? JsonArray) ?: (el["data"] as? JsonArray)
                else -> null
            } ?: return emptyList()
            arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                try {
                    val info = HermesJson.decodeFromString<SessionInfo>(o.toString())
                    // Servers vary on the timestamp field name; fall back to
                    // lenient keys so history sections keep working.
                    if (info.updatedAt == null) {
                        extractSessionTimestamp(o)?.let { info.copy(updatedAt = it) } ?: info
                    } else {
                        info
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getSessionMessages(sessionId: String): List<SessionMessage> {
        val body = execute(get("/api/sessions/${sessionId.pathSegment()}/messages"))
        return try {
            val el = HermesJson.parseToJsonElement(body)
            val arr = when {
                el is JsonArray -> el
                el is JsonObject ->
                    (el["messages"] as? JsonArray) ?: (el["data"] as? JsonArray)
                else -> null
            } ?: return emptyList()
            arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val text = messageText(o["content"]).ifBlank {
                    o.str("text", "output") ?: ""
                }
                if (text.isBlank()) return@mapNotNull null
                SessionMessage(
                    id = o.str("id", "row_id") ?: "",
                    role = o.str("role") ?: "assistant",
                    text = text,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun deleteSession(sessionId: String) { execute(delete("/api/sessions/${sessionId.pathSegment()}")) }

    suspend fun forkSession(sessionId: String, title: String? = null): String? {
        val payload = if (title != null) "{\"title\":${title.jsonQuote()}}" else "{}"
        return try {
            val body = execute(post("/api/sessions/${sessionId.pathSegment()}/fork", payload))
            val o = HermesJson.parseToJsonElement(body) as? JsonObject
            o?.str("session_id", "id")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POST /api/sessions — create an empty session row, optionally titled
     * (the /new <name> path). Returns the new session id, or null when
     * the server refuses or answers an unreadable body (older servers
     * without the endpoint included).
     */
    suspend fun createSession(title: String? = null): String? {
        val payload = if (title != null) "{\"title\":${title.jsonQuote()}}" else "{}"
        return try {
            val body = execute(post("/api/sessions", payload))
            val o = HermesJson.parseToJsonElement(body) as? JsonObject
            val session = o?.get("session") as? JsonObject ?: o
            session?.str("session_id", "id")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * GET /api/sessions/{id} — one session's metadata (title, timestamps).
     * Returns null when the body is unreadable; HTTP errors propagate so
     * callers can tell a dead session from an untitled one.
     */
    suspend fun getSession(sessionId: String): SessionInfo? {
        val body = execute(get("/api/sessions/${sessionId.pathSegment()}"))
        return try {
            val root = HermesJson.parseToJsonElement(body) as? JsonObject ?: return null
            val o = root["session"] as? JsonObject ?: root
            val info = HermesJson.decodeFromString<SessionInfo>(o.toString())
            // Same timestamp leniency as listSessions.
            if (info.updatedAt == null) {
                extractSessionTimestamp(o)?.let { info.copy(updatedAt = it) } ?: info
            } else {
                info
            }
        } catch (_: Exception) {
            null
        }
    }

    /** PATCH /api/sessions/{id} — rename a session (the /title path). */
    suspend fun updateSessionTitle(sessionId: String, title: String) {
        execute(patch("/api/sessions/${sessionId.pathSegment()}", "{\"title\":${title.jsonQuote()}}"))
    }

    suspend fun resolveUnknownRun(id: String, admission: String, generation: Long) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("admission_id", kotlinx.serialization.json.JsonPrimitive(admission))
            put("execution_generation", kotlinx.serialization.json.JsonPrimitive(generation))
        }
        execute(post("/v1/runs/${id.pathSegment()}/resolve-unknown", body.toString()))
    }

    /** Explicit cron operations exposed by the Hermes API; no generic command forwarding. */
    suspend fun cron(action: String, id: String? = null, body: JsonObject? = null): String = when (action) {
        "list" -> execute(get("/api/jobs?include_disabled=true"))
        "show" -> execute(get("/api/jobs/${requireNotNull(id).pathSegment()}"))
        "add", "create" -> execute(post("/api/jobs", requireNotNull(body).toString()))
        "edit" -> execute(patch("/api/jobs/${requireNotNull(id).pathSegment()}", requireNotNull(body).toString()))
        "remove" -> execute(delete("/api/jobs/${requireNotNull(id).pathSegment()}"))
        "pause", "resume", "run" -> execute(post("/api/jobs/${requireNotNull(id).pathSegment()}/$action", "{}"))
        else -> throw IllegalArgumentException("Unknown cron operation")
    }

    companion object {
        // Hermes API Server's default MAX_REQUEST_BYTES; includes JSON and Base64 overhead.
        internal const val MAX_RUN_BYTES = 10_000_000L

        internal fun validatedBaseUrl(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Base URL must not contain credentials, a query or a fragment"
            }
            require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")) {
                "Base URL must be https (http is only for loopback)"
            }
            return url.toString().trimEnd('/')
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The server sends an SSE keepalive comment every ~10s (DESIGN §8),
            // so 60s without a byte means a half-open socket (NAT timeout,
            // Doze, tunnel restart): time out here so the caller can poll the
            // run status and resubscribe instead of hanging forever.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Access service-token headers are custom headers: OkHttp only strips
            // Authorization on cross-origin redirects. Never forward credentials.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()

        private fun String.pathSegment(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

        private fun String.jsonQuote(): String = kotlinx.serialization.json.JsonPrimitive(this).toString()
    }
}
