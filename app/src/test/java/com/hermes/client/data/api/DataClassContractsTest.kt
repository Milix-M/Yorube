package com.hermes.client.data.api

import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for every API data class: construction with all arguments,
 * construction with defaults, copy, component access, equality and string form.
 * These also lock the wire schema (field names via SerialName are asserted
 * through JSON round-trips).
 */
class DataClassContractsTest {

    private inline fun <reified T> roundTrip(value: T): T {
        val json = HermesJson.encodeToString(serializer(), value)
        return HermesJson.decodeFromString(json)
    }

    @Test
    fun capabilitiesResponse() {
        val full = CapabilitiesResponse(
            features = mapOf("a" to HermesJson.parseToJsonElement("true")),
            endpoints = mapOf("e" to HermesJson.parseToJsonElement("1")),
        )
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("features"))
        val defaults = CapabilitiesResponse()
        assertTrue(defaults.features.isEmpty())
        assertTrue(defaults.endpoints.isEmpty())
        assertEquals(defaults, CapabilitiesResponse())
        assertTrue(defaults.toString().isNotBlank())
    }

    @Test
    fun runCreateRequest() {
        val full = RunCreateRequest(
            input = "hi",
            sessionId = "s",
            conversationHistory = listOf(HistoryMessage("user", "hi")),
            previousResponseId = "p",
            model = "m",
            provider = "p",
            modelOptions = mapOf("k" to "v"),
        )
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("hi"))
        val (input, sessionId) = RunCreateRequest(input = "x", sessionId = "y")
        assertEquals("x", input)
        assertEquals("y", sessionId)
        val minimal = RunCreateRequest(input = "hi")
        assertEquals(null, minimal.sessionId)
        assertEquals(null, minimal.model)
    }

    @Test
    fun runAccepted() {
        val full = RunAccepted(runId = "r", status = "queued", replayed = true)
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("r"))
        val (runId, status, replayed) = full
        assertEquals("r", runId)
        assertEquals("queued", status)
        assertEquals(true, replayed)
        val minimal = RunAccepted(runId = "r")
        assertEquals("", minimal.status)
        assertEquals(false, minimal.replayed)
    }

    @Test
    fun runUsage() {
        val full = RunUsage(1, 2, 3, 4, 5)
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("1"))
        val (i, o, t, cr, cw) = full
        assertEquals(1L, i)
        assertEquals(2L, o)
        assertEquals(3L, t)
        assertEquals(4L, cr)
        assertEquals(5L, cw)
        val defaults = RunUsage()
        assertEquals(0L, defaults.inputTokens)
        assertEquals(defaults, RunUsage())
    }

    @Test
    fun runRuntime() {
        val full = RunRuntime(provider = "p", model = "m")
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("p"))
        val (provider, model) = full
        assertEquals("p", provider)
        assertEquals("m", model)
        assertEquals(RunRuntime(), RunRuntime(provider = "", model = ""))
    }

    @Test
    fun runStatusResponse() {
        val full = RunStatusResponse(
            runId = "r",
            status = "completed",
            sessionId = "s",
            model = "m",
            output = "o",
            error = "e",
            usage = RunUsage(1, 2, 3, 4, 5),
            runtime = RunRuntime("p", "m"),
            pendingSteer = "ps",
            turnExitReason = "t",
            pendingControls = emptyList(),
        )
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("completed"))
        val minimal = RunStatusResponse()
        assertEquals("", minimal.runId)
        assertEquals("", minimal.status)
        assertEquals(null, minimal.sessionId)
        assertEquals(null, minimal.output)
        assertEquals(null, minimal.usage)
        assertEquals(RunStatusResponse(), RunStatusResponse(runId = ""))
        val decoded = HermesJson.decodeFromString<RunStatusResponse>(
            """{"run_id":"r1","status":"running"}"""
        )
        assertEquals("r1", decoded.runId)
        assertEquals("running", decoded.status)
    }

    @Test
    fun approvalRequest() {
        val full = ApprovalRequest(
            requestId = "r",
            executionGeneration = 3L,
            command = "c",
            description = "d",
            choices = listOf("once"),
        )
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("r"))
        val (requestId, generation) = full
        assertEquals("r", requestId)
        assertEquals(3L, generation)
        val minimal = ApprovalRequest(requestId = "r", executionGeneration = 0L)
        assertEquals(null, minimal.command)
        assertEquals(listOf("once", "deny"), minimal.choices)
    }

    @Test
    fun historyMessage() {
        val m = HistoryMessage("user", "hi")
        assertEquals(m, m.copy())
        assertEquals(m, roundTrip(m))
        assertEquals(m.hashCode(), m.copy().hashCode())
        assertTrue(m.toString().contains("hi"))
        val (role, content) = m
        assertEquals("user", role)
        assertEquals("hi", content)
    }

    @Test
    fun sessionInfo() {
        val full = SessionInfo(id = "i", sessionId = "s", title = "t", updatedAt = 1.0)
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("t"))
        val minimal = SessionInfo()
        assertEquals("", minimal.key)
    }

    @Test
    fun sessionMessage() {
        val full = SessionMessage(id = "i", role = "user", text = "t")
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("t"))
        val (id, role, text) = full
        assertEquals("i", id)
        assertEquals("user", role)
        assertEquals("t", text)
        assertEquals(SessionMessage(), SessionMessage(id = "", role = "", text = ""))
    }

    @Test
    fun skillInfo() {
        val full = SkillInfo(name = "n", description = "d", category = "c")
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("n"))
        val (name, description, category) = full
        assertEquals("n", name)
        assertEquals("d", description)
        assertEquals("c", category)
        assertEquals(SkillInfo(), SkillInfo(name = "", description = "", category = ""))
    }

    @Test
    fun toolsetInfo() {
        val full = ToolsetInfo(
            name = "n", label = "l", description = "d",
            enabled = true, configured = true, tools = listOf("t"),
        )
        assertEquals(full, full.copy())
        assertEquals(full, roundTrip(full))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("n"))
        assertEquals(ToolsetInfo(), ToolsetInfo("", "", "", false, false, emptyList()))
    }

    @Test
    fun providerOption() {
        val o = ProviderOption("p", listOf("m"))
        assertEquals(o, o.copy())
        assertEquals(o.hashCode(), o.copy().hashCode())
        assertTrue(o.toString().contains("p"))
        val (name, models) = o
        assertEquals("p", name)
        assertEquals(listOf("m"), models)
    }

    @Test
    fun requestBodies() {
        val approval = ApprovalAnswer("r", 3L, "once")
        assertEquals(approval, approval.copy())
        assertEquals(approval, roundTrip(approval))
        assertEquals(approval.hashCode(), approval.copy().hashCode())
        assertTrue(approval.toString().contains("once"))
        val (requestId, generation, choice) = approval
        assertEquals("r", requestId)
        assertEquals(3L, generation)
        assertEquals("once", choice)

        val steer = SteerRequest("go")
        assertEquals(steer, steer.copy())
        assertEquals(steer, roundTrip(steer))
        assertEquals("go", steer.copy().text)
        assertTrue(steer.toString().contains("go"))

        val clarify = ClarifyRequest("c", 2L, "yes")
        assertEquals(clarify, clarify.copy())
        assertEquals(clarify, roundTrip(clarify))
        assertTrue(clarify.toString().contains("yes"))
        val (clarifyId, clarifyGen, answer) = clarify
        assertEquals("c", clarifyId)
        assertEquals(2L, clarifyGen)
        assertEquals("yes", answer)

        val resolve = ResolveUnknownRequest("a", 7L)
        assertEquals(resolve, resolve.copy())
        assertEquals(resolve, roundTrip(resolve))
        assertTrue(resolve.toString().contains("a"))
    }

    @Test
    fun runEventVariants() {        val delta = RunEvent.Delta("t")
        assertEquals(delta, delta.copy())
        assertTrue(delta.toString().contains("t"))
        val interim = RunEvent.Interim("t", true)
        assertEquals(interim, interim.copy())
        val started = RunEvent.ToolStarted("t", "p")
        assertEquals(started, started.copy())
        val completed = RunEvent.ToolCompleted("t", 1.0, true, "p")
        assertEquals(completed, completed.copy())
        val (tool, duration, isError, preview) = completed
        assertEquals("t", tool)
        assertEquals(1.0, duration, 0.0)
        assertEquals(true, isError)
        assertEquals("p", preview)
        val subStart = RunEvent.SubagentStarted("g")
        assertEquals(subStart, subStart.copy())
        val subDone = RunEvent.SubagentCompleted("ok", "s")
        assertEquals(subDone, subDone.copy())
        val (subStatus, subSummary) = subDone
        assertEquals("ok", subStatus)
        assertEquals("s", subSummary)
        val terminal = RunEvent.Terminal("completed", "o", null, null)
        assertEquals(terminal, terminal.copy())
        val unknown = RunEvent.Unknown("x")
        assertEquals(unknown, unknown.copy())
        assertTrue(unknown.toString().contains("x"))
        val reasoning = RunEvent.Reasoning("r")
        assertEquals(reasoning, reasoning.copy())
        assertTrue(reasoning.toString().contains("r"))
        val approval = RunEvent.ApprovalRequested(ApprovalRequest("r", 1L))
        assertEquals(approval, approval.copy())
        assertTrue(approval.toString().contains("r"))
        val clarify = RunEvent.ClarifyRequested("c", 2L, "p")
        assertEquals(clarify, clarify.copy())
        val (cid, gen, prompt) = clarify
        assertEquals("c", cid)
        assertEquals(2L, gen)
        assertEquals("p", prompt)
    }
}
