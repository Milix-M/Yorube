package com.hermes.client.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
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
    private val base = baseUrl.trim().removeSuffix("/")

    init {
        val host = base.substringAfter("://").substringBefore("/").substringBefore(":")
        val isHttps = base.startsWith("https://")
        // Loopback never crosses a network, so plain http stays allowed there
        // in every build type. Anything else must be https.
        val local = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
        if (!isHttps && !local) {
            throw IllegalArgumentException("Base URL must be https (http is only for loopback)")
        }
    }

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

    private fun parseError(status: Int, body: String): Nothing {
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
        throw HermesApiException(status, code, message)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) parseError(response.code, body)
            body
        }
    }

    // -- M0: connection --

    suspend fun checkHealth(): Boolean {
        return try {
            val body = execute(get("/health"))
            body.contains("\"ok\"")
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
        return parseList(body, "skills")
    }

    suspend fun getToolsets(): List<ToolsetInfo> {
        val body = execute(get("/v1/toolsets"))
        return parseList(body, "toolsets")
    }

    private inline fun <reified T> parseList(body: String, vararg keys: String): List<T> {
        return try {
            val el = HermesJson.parseToJsonElement(body)
            val arr = when {
                el is JsonArray -> el
                el is JsonObject -> keys.firstNotNullOfOrNull { el[it] as? JsonArray }
                else -> null
            } ?: return emptyList()
            HermesJson.decodeFromString(arr.toString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -- M1: runs --

    suspend fun createRun(request: RunCreateRequest, idempotencyKey: String): RunAccepted {
        val body = execute(
            post("/v1/runs", HermesJson.encodeToString(request), mapOf(IDEMPOTENCY_HEADER to idempotencyKey))
        )
        return HermesJson.decodeFromString(body)
    }

    suspend fun getRun(runId: String): RunStatusResponse {
        val body = execute(get("/v1/runs/$runId"))
        return HermesJson.decodeFromString(body)
    }

    suspend fun stopRun(runId: String) {
        execute(post("/v1/runs/$runId/stop", "{}"))
    }

    suspend fun steerRun(runId: String, text: String) {
        execute(post("/v1/runs/$runId/steer", HermesJson.encodeToString(SteerRequest(text))))
    }

    suspend fun answerApproval(runId: String, answer: ApprovalAnswer) {
        execute(post("/v1/runs/$runId/approval", HermesJson.encodeToString(answer)))
    }

    suspend fun answerClarify(runId: String, request: ClarifyRequest) {
        execute(post("/v1/runs/$runId/clarify", HermesJson.encodeToString(request)))
    }

    suspend fun resolveUnknown(runId: String, request: ResolveUnknownRequest) {
        execute(post("/v1/runs/$runId/resolve-unknown", HermesJson.encodeToString(request)))
    }

    /**
     * SSE stream of run events. Skips keepalive comment lines.
     * Ends after a terminal run.* event or when the collector cancels.
     */
    fun runEvents(runId: String): Flow<RunEvent> = callbackFlow {
        val call = client.newCall(get("/v1/runs/$runId/events"))
        val response = try {
            call.execute()
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        if (!response.isSuccessful) {
            val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
            response.close()
            try {
                parseError(response.code, body)
            } catch (e: HermesApiException) {
                close(e)
                return@callbackFlow
            }
        }
        try {
            // OkHttp always delivers a body here, even when empty.
            val responseBody = response.body ?: throw IOException("empty response")
            val reader = responseBody.charStream().buffered()
            var eventName = "message"
            val dataLines = StringBuilder()
            var terminated = false
            fun dispatch() {
                val data = parseSseData(dataLines.toString())
                dataLines.clear()
                if (data == null) {
                    eventName = "message"
                    return
                }
                val name = data.str("event")?.takeIf { it.isNotBlank() } ?: eventName
                trySend(parseRunEvent(name, data))
                if (data.str("event")?.startsWith("run.") == true) {
                    terminated = true
                    close()
                    return
                }
                eventName = "message"
            }
            reader.useLines { lines ->
                for (line in lines) {
                    when {
                        line.isEmpty() -> dispatch()
                        line.startsWith(":") -> { /* keepalive comment, skip */ }
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataLines.append(line.removePrefix("data:").trim()).append('\n')
                    }
                    if (terminated || isClosedForSend) break
                }
                if (dataLines.isNotBlank()) dispatch()
            }
            // A clean EOF without a terminal event ends the stream: close so the
            // collector can poll the run status and re-subscribe instead of hanging.
            if (!terminated) close()
        } catch (e: Exception) {
            close(e)
        } finally {
            try { response.close() } catch (_: Exception) { }
        }
        awaitClose { call.cancel() }
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
            HermesJson.decodeFromString<List<SessionInfo>>(arr.toString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getSessionMessages(sessionId: String): List<SessionMessage> {
        val body = execute(get("/api/sessions/$sessionId/messages"))
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

    suspend fun deleteSession(sessionId: String) {
        execute(delete("/api/sessions/$sessionId"))
    }

    suspend fun forkSession(sessionId: String, title: String? = null): String? {
        val payload = if (title != null) "{\"title\":${title.jsonQuote()}}" else "{}"
        return try {
            val body = execute(post("/api/sessions/$sessionId/fork", payload))
            val o = HermesJson.parseToJsonElement(body) as? JsonObject
            o?.str("session_id", "id")
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE streams stay open
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun String.jsonQuote(): String =
            "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
