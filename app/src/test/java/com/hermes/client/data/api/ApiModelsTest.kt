package com.hermes.client.data.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
