package com.hermes.client.data.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEventsTest {

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun delta_usesFirstNonBlankKey() {
        val e = parseRunEvent("message.delta", obj("delta" to "hel", "text" to "lo"))
        assertEquals(RunEvent.Delta("hel"), e)
    }

    @Test
    fun interim_respectsAlreadyStreamed() {
        val e = parseRunEvent(
            "message.interim",
            buildJsonObject {
                put("text", "working")
                put("already_streamed", true)
            }
        ) as RunEvent.Interim
        assertEquals("working", e.text)
        assertTrue(e.alreadyStreamed)
    }

    @Test
    fun toolCompleted_mapsErrorAndDuration() {
        val e = parseRunEvent(
            "tool.completed",
            buildJsonObject {
                put("tool", "terminal")
                put("duration", 1.5)
                put("error", true)
                put("preview", "exit 1")
            }
        ) as RunEvent.ToolCompleted
        assertEquals("terminal", e.tool)
        assertEquals(1.5, e.durationSeconds, 0.0)
        assertTrue(e.isError)
        assertEquals("exit 1", e.preview)
    }

    @Test
    fun toolFailed_isAlsoCompleted() {
        val e = parseRunEvent("tool.failed", obj("tool" to "t"))
        assertTrue(e is RunEvent.ToolCompleted)
    }

    @Test
    fun approval_mapsRequestIdAndChoices() {
        val e = parseRunEvent(
            "approval.request",
            buildJsonObject {
                put("request_id", "r1")
                put("execution_generation", 3)
                put("command", "rm -rf /tmp/x")
            }
        ) as RunEvent.ApprovalRequested
        assertEquals("r1", e.request.requestId)
        assertEquals(3L, e.request.executionGeneration)
        assertEquals("rm -rf /tmp/x", e.request.command)
        assertEquals(listOf("once", "deny"), e.request.choices)
    }

    @Test
    fun clarify_mapsPrompt() {
        val e = parseRunEvent(
            "clarify.request",
            buildJsonObject {
                put("request_id", "c1")
                put("execution_generation", 2)
                put("prompt", "which file?")
            }
        ) as RunEvent.ClarifyRequested
        assertEquals("c1", e.requestId)
        assertEquals(2L, e.executionGeneration)
        assertEquals("which file?", e.prompt)
    }

    @Test
    fun terminal_carriesFields() {
        val e = parseRunEvent(
            "run.completed",
            obj("output" to "done", "pending_steer" to "later")
        ) as RunEvent.Terminal
        assertEquals("completed", e.status)
        assertEquals("done", e.output)
        assertEquals("later", e.pendingSteer)
        assertNull(e.error)
    }

    @Test
    fun unknownName_isKept() {
        val e = parseRunEvent("something.new", JsonObject(emptyMap()))
        assertEquals(RunEvent.Unknown("something.new"), e)
    }

    @Test
    fun parseSseData_skipsDoneAndBlank() {
        assertNull(parseSseData(""))
        assertNull(parseSseData("[DONE]"))
        assertNull(parseSseData("not json"))
    }

    @Test
    fun parseSseData_prefersEventField() {
        val data = parseSseData("""{"event":"tool.started","tool":"t"}""")
        assertEquals(
            RunEvent.ToolStarted("t", ""),
            parseRunEvent(data!!.str("event") ?: "message", data),
        )
    }

    @Test
    fun str_helperReadsAliases() {
        val o = obj("a" to "x")
        assertEquals("x", o.str("missing", "a"))
        assertNull(o.str("missing"))
    }

    @Test
    fun long_readsFirstPresentKey() {
        val o = obj("a" to "x")
        assertNull(o.long("missing"))
        assertNull(o.long())
        val n = buildJsonObject { put("n", 42) }
        assertEquals(42L, n.long("missing", "n"))
        assertEquals(42L, n.long("n"))
    }
}
