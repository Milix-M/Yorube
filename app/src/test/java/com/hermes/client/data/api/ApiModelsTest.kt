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

    @Test
    fun extractProviderOptions_coversAllBranches() {
        // Non-object item is skipped.
        val withPrimitive = HermesJson.parseToJsonElement(
            """{"providers":[123, {"name":"p","models":[]}]}"""
        )
        val opts = extractProviderOptions(withPrimitive)
        assertEquals(1, opts.size)
        assertEquals("p", opts[0].name)
        // Object without name is skipped.
        val noName = HermesJson.parseToJsonElement(
            """{"providers":[{"foo":"bar"}]}"""
        )
        assertTrue(extractProviderOptions(noName).isEmpty())
        // Primitive string models, object models, object without id, and
        // else branch (nested array) all covered.
        val mixed = HermesJson.parseToJsonElement(
            """{"providers":[{"name":"p","models":["m1", {"id":"m2"}, {"foo":"x"}, [1]]}]}"""
        )
        val mixedOpts = extractProviderOptions(mixed)
        assertEquals(listOf("m1", "m2"), mixedOpts[0].models)
        // Options key instead of providers, plus models under options.
        val viaOptions = HermesJson.parseToJsonElement(
            """{"options":[{"name":"q","options":["z"]}]}"""
        )
        assertEquals(listOf("z"), extractProviderOptions(viaOptions)[0].models)
        // Rows and data keys also accepted.
        val viaRows = HermesJson.parseToJsonElement(
            """{"rows":[{"name":"r","models":[]}]}"""
        )
        assertEquals("r", extractProviderOptions(viaRows)[0].name)
        val viaData = HermesJson.parseToJsonElement(
            """{"data":[{"name":"d","models":[]}]}"""
        )
        assertEquals("d", extractProviderOptions(viaData)[0].name)
    }

    @Test
    fun extractModelIds_coversModelsKey() {
        val viaModels = extractModelIds(
            HermesJson.parseToJsonElement("""{"models":[{"id":"m1"},{"name":"m2"},123]}""")
        )
        assertEquals(listOf("m1", "m2"), viaModels)
    }

    @Test
    fun messageText_coversArrayEdgeCases() {
        // Non-object part is skipped.
        val withPrimitivePart = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("plain"))
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", "text")
                put("text", "hi")
            })
        }
        assertEquals("hi", messageText(withPrimitivePart))
        // Object part without text yields nothing for that part.
        val noText = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject { put("type", "text") })
        }
        assertEquals("", messageText(noText))
        // Non-string type field is ignored, image still placeholder.
        val nonStringType = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject { put("type", 123) })
        }
        assertEquals("", messageText(nonStringType))
    }

    @Test
    fun jsonHelpers_coverBoolLongEdgeCases() {
        val o = kotlinx.serialization.json.buildJsonObject {
            put("s", "x")
            put("b", true)
            put("n", 7)
        }
        // bool: non-primitive skipped, true returned, missing -> null.
        assertEquals(true, o.bool("missing", "b"))
        assertEquals(null, o.bool("s"))
        assertEquals(null, o.bool("missing"))
        // long: non-primitive skipped are covered via str test; numeric present.
        assertEquals(7L, o.long("b", "n"))
        // str with non-string primitive skipped.
        assertEquals("x", o.str("b", "s"))
        assertEquals(null, o.str("b"))
    }
}
