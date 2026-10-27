package com.hermes.client.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val HermesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v.isString) return v.content
    }
    return null
}

fun JsonObject.bool(vararg keys: String): Boolean? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        v.booleanOrNull?.let { return it }
    }
    return null
}

fun JsonObject.long(vararg keys: String): Long? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        v.longOrNull?.let { return it }
    }
    return null
}

/** Capabilities feature flag lookup. Values are mixed (bool and header names). */
@Serializable
data class CapabilitiesResponse(
    val features: Map<String, JsonElement> = emptyMap(),
    val endpoints: Map<String, JsonElement> = emptyMap(),
)

fun CapabilitiesResponse.featureEnabled(name: String): Boolean =
    (features[name] as? JsonPrimitive)?.booleanOrNull == true

@Serializable
data class HistoryMessage(
    val role: String,
    val content: String,
)

@Serializable
data class RunCreateRequest(
    val input: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("conversation_history") val conversationHistory: List<HistoryMessage>? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("model_options") val modelOptions: Map<String, String>? = null,
)

@Serializable
data class RunAccepted(
    @SerialName("run_id") val runId: String,
    val status: String = "",
    val replayed: Boolean = false,
)

@Serializable
data class RunUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
)

@Serializable
data class RunRuntime(
    val provider: String = "",
    val model: String = "",
)

@Serializable
data class RunStatusResponse(
    @SerialName("run_id") val runId: String = "",
    val status: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val model: String? = null,
    val output: String? = null,
    val error: String? = null,
    val usage: RunUsage? = null,
    val runtime: RunRuntime? = null,
    @SerialName("pending_steer") val pendingSteer: String? = null,
    @SerialName("turn_exit_reason") val turnExitReason: String? = null,
    @SerialName("pending_controls") val pendingControls: List<JsonObject> = emptyList(),
)

@Serializable
data class ApprovalAnswer(
    @SerialName("request_id") val requestId: String,
    @SerialName("execution_generation") val executionGeneration: Long,
    val choice: String,
)

@Serializable
data class SteerRequest(val text: String)

@Serializable
data class ClarifyRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("execution_generation") val executionGeneration: Long,
    val answer: String,
)

@Serializable
data class ResolveUnknownRequest(
    @SerialName("admission_id") val admissionId: String,
    @SerialName("execution_generation") val executionGeneration: Long,
)

/** Best-effort approval request raised by the run stream. */
data class ApprovalRequest(
    val requestId: String,
    val executionGeneration: Long,
    val command: String? = null,
    val description: String? = null,
    val choices: List<String> = listOf("once", "deny"),
)

@Serializable
data class SessionInfo(
    val id: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    /**
     * Owner/surface hints when the server sends them (e.g. "discord",
     * "cli", "desktop", "app"). Absent on older servers: defaults keep
     * decoding working and the session counts as ours.
     */
    val owner: String? = null,
    val surface: String? = null,
    val source: String? = null,
    val origin: String? = null,
) {
    val key: String get() = id.ifEmpty { sessionId }
    val displayTitle: String get() = title?.ifBlank { null } ?: key.take(16)
}

/**
 * Surfaces known to belong to another tool. Only these hide a session:
 * anything else (blank metadata, "app", server-specific markers like
 * "api") counts as ours. An allowlist would mass-hide app sessions the
 * moment the server sends an unknown marker.
 */
private val FOREIGN_SURFACE_TOKENS = setOf(
    "discord", "slack", "telegram", "line",
    "cli", "desktop", "vscode", "cursor",
)

/**
 * True when server metadata explicitly marks this session as owned by
 * another surface (e.g. Discord). Unknown or blank metadata counts as ours
 * so older servers and new markers never hide everything.
 */
fun SessionInfo.isForeignSurface(): Boolean {
    val tags = listOfNotNull(owner, surface, source, origin)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    if (tags.isEmpty()) return false
    return tags.any { it in FOREIGN_SURFACE_TOKENS }
}

data class SessionMessage(
    val id: String = "",
    val role: String = "",
    val text: String = "",
)

fun messageText(content: JsonElement?): String {
    if (content == null) return ""
    if (content is JsonPrimitive && content.isString) return content.content
    if (content is JsonArray) {
        return content.mapNotNull { part ->
            if (part !is JsonObject) return@mapNotNull null
            val type = (part["type"] as? JsonPrimitive)?.content?.lowercase() ?: ""
            if ("image" in type) return@mapNotNull "[image]"
            val text = part["text"]
            if (text is JsonPrimitive && text.isString) text.content else null
        }.joinToString("\n")
    }
    return ""
}

@Serializable
data class SkillInfo(
    val name: String = "",
    val description: String = "",
    val category: String = "",
)

@Serializable
data class ToolsetInfo(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val tools: List<String> = emptyList(),
)

/** Lenient provider/model pair extracted from /api/model/options. */
data class ProviderOption(val name: String, val models: List<String>)

fun extractProviderOptions(root: JsonElement): List<ProviderOption> {
    val obj = root as? JsonObject ?: return emptyList()
    val lists = mutableListOf<JsonArray>()
    for (key in listOf("providers", "rows", "options", "data")) {
        (obj[key] as? JsonArray)?.let { lists.add(it) }
    }
    if (lists.isEmpty()) return emptyList()
    return lists.first().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val name = o.str("name", "id", "provider", "label") ?: return@mapNotNull null
        val models = mutableListOf<String>()
        val rawModels = o["models"] ?: o["options"]
        if (rawModels is JsonArray) {
            for (m in rawModels) {
                when (m) {
                    is JsonPrimitive -> if (m.isString) models.add(m.content)
                    is JsonObject -> m.str("id", "name", "model")?.let { models.add(it) }
                    else -> {}
                }
            }
        }
        ProviderOption(name, models)
    }
}

fun extractModelIds(root: JsonElement): List<String> {
    val obj = root as? JsonObject ?: return emptyList()
    val arr = (obj["data"] as? JsonArray) ?: (obj["models"] as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { (it as? JsonObject)?.str("id", "name") }
}

/** Timestamp keys observed across session list shapes (snake/camel, activity/creation). */
private val SessionTimestampKeys = listOf(
    "updated_at", "updatedAt", "updated",
    "last_message_at", "lastMessageAt",
    "last_active", "lastActiveAt", "last_activity",
    "modified_at", "modifiedAt",
    "created_at", "createdAt",
    "timestamp", "time",
)

/**
 * Lenient timestamp lookup for one raw session entry. Accepts epoch
 * seconds/millis/micros as numbers or strings, plus ISO-8601 strings.
 * Returns null when the entry carries nothing usable.
 */
internal fun extractSessionTimestamp(o: JsonObject): Double? {
    for (key in SessionTimestampKeys) {
        val v = o[key] as? JsonPrimitive ?: continue
        if (v.isString) {
            val raw = v.content.trim()
            if (raw.isEmpty()) continue
            try {
                return java.time.Instant.parse(raw).epochSecond.toDouble()
            } catch (_: Exception) {
            }
            raw.toDoubleOrNull()?.let { return it }
            continue
        }
        (v.longOrNull?.toDouble() ?: v.doubleOrNull)?.let { return it }
    }
    return null
}
