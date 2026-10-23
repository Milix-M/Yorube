package com.hermes.client.data.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {

    @Test
    fun messageText_plainString() {
        assertEquals("hi", messageText(JsonPrimitive("hi")))
    }

    @Test
    fun messageText_partsJoined_imageBecomesPlaceholder() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "see") })
            add(buildJsonObject { put("type", "image_url") })
        }
        assertEquals("see\n[image]", messageText(content))
    }

    @Test
    fun featureEnabled_readsBoolOnly() {
        val caps = HermesJson.decodeFromString<CapabilitiesResponse>(
            """{"features":{"run_stop":true,"session_key_header":"X-Hermes-Session-Key"}}"""
        )
        assertTrue(caps.featureEnabled("run_stop"))
        assertEquals(false, caps.featureEnabled("session_key_header"))
        assertEquals(false, caps.featureEnabled("missing"))
    }

    @Test
    fun extractModelIds_openAiShape() {
        val ids = extractModelIds(
            HermesJson.parseToJsonElement("""{"data":[{"id":"hermes-agent"},{"id":"other"}]}""")
        )
        assertEquals(listOf("hermes-agent", "other"), ids)
    }

    @Test
    fun extractProviderOptions_lenient() {
        val root = HermesJson.parseToJsonElement(
            """{"providers":[{"name":"openrouter","models":[{"id":"a"},{"id":"b"}]},{"id":"nous"}]}"""
        )
        val options = extractProviderOptions(root)
        assertEquals(2, options.size)
        assertEquals("openrouter", options[0].name)
        assertEquals(listOf("a", "b"), options[0].models)
        assertEquals("nous", options[1].name)
        assertTrue(options[1].models.isEmpty())
    }

    @Test
    fun sessionInfo_keyFallsBack() {
        val withId = HermesJson.decodeFromString<SessionInfo>("""{"id":"s1"}""")
        val withSessionId = HermesJson.decodeFromString<SessionInfo>("""{"session_id":"s2"}""")
        assertEquals("s1", withId.key)
        assertEquals("s2", withSessionId.key)
    }

    @Test
    fun parseSessionDate_scalesAndKeys() {
        val sec = 1_700_000_000.0
        assertEquals(sec, parseDate("{\"updated_at\":$sec}")!!)
        assertEquals(sec, parseDate("{\"updated_at\":${sec * 1000.0}}")!!, 0.001)
        assertEquals(sec, parseDate("{\"updated_at\":${sec * 1_000_000.0}}")!!, 1.0)
        assertEquals(sec, parseDate("{\"updatedAt\":$sec}")!!)
        assertEquals(sec, parseDate("{\"created_at\":$sec}")!!)
        assertEquals(null, parseDate("{}"))
        assertEquals(null, parseDate("{\"updated_at\":null}"))
        assertEquals(null, parseDate("{\"updated_at\":\"\"}"))
        assertEquals(null, parseDate("{\"updated_at\":\"not a date\"}"))
        assertEquals(null, parseDate("{\"updated_at\":true}"))
    }

    @Test
    fun parseSessionDate_isoStrings() {
        val epoch = parseSessionDate(
            HermesJson.parseToJsonElement("{\"updated_at\":\"2026-10-24T00:00:00Z\"}").jsonObject
        )
        assertEquals(1_792_800_000.0, epoch!!, 1.0)
        // Numeric strings also work.
        assertEquals(1_700_000_000.0, parseDate("{\"updated_at\":\"1700000000\"}")!!, 0.001)
    }

    private fun parseDate(raw: String) =
        parseSessionDate(HermesJson.parseToJsonElement(raw).jsonObject)

    @Test
    fun messageText_edgeCases() {
        assertEquals("", messageText(null))
        assertEquals("", messageText(JsonPrimitive(3)))
        assertEquals("", messageText(JsonPrimitive(false)))
    }

    @Test
    fun sessionInfo_displayTitle() {
        val titled = HermesJson.decodeFromString<SessionInfo>("""{"id":"s1","title":"hello"}""")
        assertEquals("hello", titled.displayTitle)
        val blank = HermesJson.decodeFromString<SessionInfo>("""{"id":"s1","title":""}""")
        assertEquals("s1", blank.displayTitle)
        val missing = HermesJson.decodeFromString<SessionInfo>("""{"id":"s1"}""")
        assertEquals("s1", missing.displayTitle)
    }

    @Test
    fun extractProviderOptions_emptyWhenNoLists() {
        assertTrue(extractProviderOptions(HermesJson.parseToJsonElement("""{"x":1}""")).isEmpty())
        assertEquals(emptyList<String>(), extractModelIds(HermesJson.parseToJsonElement("""{"x":1}""")))
        assertTrue(extractModelIds(HermesJson.parseToJsonElement("""{"data":[]}""")).isEmpty())
    }
}
