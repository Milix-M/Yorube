package com.hermes.client.data.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One parsed SSE frame from GET /v1/runs/{id}/events. */
sealed interface RunEvent {
    data class Delta(val text: String) : RunEvent
    data class Interim(val text: String, val alreadyStreamed: Boolean) : RunEvent
    data class ToolStarted(val tool: String, val preview: String) : RunEvent
    data class ToolCompleted(
        val tool: String,
        val durationSeconds: Double,
        val isError: Boolean,
        val preview: String,
    ) : RunEvent
    data class SubagentStarted(val summary: String) : RunEvent
    data class SubagentCompleted(val status: String, val summary: String) : RunEvent
    data class ApprovalRequested(val request: ApprovalRequest) : RunEvent
    data class ClarifyRequested(
        val requestId: String,
        val executionGeneration: Long,
        val prompt: String,
    ) : RunEvent
    data class Terminal(
        val status: String,
        val output: String?,
        val error: String?,
        val pendingSteer: String?,
    ) : RunEvent
    data class Unknown(val name: String) : RunEvent
}

fun parseRunEvent(eventName: String, data: JsonObject): RunEvent {
    val name = eventName.trim()
    return when {
        name == "message.delta" -> {
            val text = data.str("delta", "text", "content", "token") ?: ""
            RunEvent.Delta(text)
        }
        name == "message.interim" -> {
            val text = data.str("text", "content", "delta") ?: ""
            RunEvent.Interim(text, data.bool("already_streamed", "alreadyStreamed") == true)
        }
        name == "tool.started" -> {
            RunEvent.ToolStarted(
                tool = data.str("tool", "name") ?: "",
                preview = data.str("preview", "text") ?: "",
            )
        }
        name == "tool.completed" || name == "tool.failed" -> {
            RunEvent.ToolCompleted(
                tool = data.str("tool", "name") ?: "",
                durationSeconds = (data["duration"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.doubleOrNull ?: 0.0,
                isError = data.bool("error", "is_error", "failed") == true,
                preview = data.str("preview", "text", "result") ?: "",
            )
        }
        name == "subagent.start" -> {
            RunEvent.SubagentStarted(data.str("goal", "summary", "preview") ?: "")
        }
        name == "subagent.complete" -> {
            RunEvent.SubagentCompleted(
                status = data.str("status") ?: "",
                summary = data.str("summary", "output_tail", "preview") ?: "",
            )
        }
        "clarify" in name -> {
            RunEvent.ClarifyRequested(
                requestId = data.str("request_id", "prompt_id", "id") ?: "",
                executionGeneration = data.long("execution_generation", "generation") ?: 0L,
                prompt = data.str("prompt", "question", "text") ?: "",
            )
        }
        "approval" in name -> {
            val requestId = data.str("request_id", "prompt_id", "id") ?: ""
            val generation = data.long("execution_generation", "generation") ?: 0L
            val choices = (data["choices"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.takeIf { it.isNotEmpty() } ?: listOf("once", "deny")
            RunEvent.ApprovalRequested(
                ApprovalRequest(
                    requestId = requestId,
                    executionGeneration = generation,
                    command = data.str("command"),
                    description = data.str("description"),
                    choices = choices,
                )
            )
        }
        name == "run.completed" || name == "run.failed" ||
            name == "run.cancelled" || name == "run.interrupted" -> {
            RunEvent.Terminal(
                status = name.substringAfter("run."),
                output = data.str("output"),
                error = data.str("error"),
                pendingSteer = data.str("pending_steer"),
            )
        }
        else -> RunEvent.Unknown(name)
    }
}

fun parseSseData(raw: String): JsonObject? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "[DONE]") return null
    return try {
        HermesJson.parseToJsonElement(trimmed).jsonObject
    } catch (_: Exception) {
        null
    }
}
