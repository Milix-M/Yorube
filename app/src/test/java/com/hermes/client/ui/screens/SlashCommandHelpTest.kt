package com.hermes.client.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SlashCommandHelpTest {
    @Test
    fun bareSlashListsEverySupportedCommandAndNoUnsupportedOnes() {
        val expected = HERMES_COMMANDS.filter { it.action != SlashAction.UNSUPPORTED }
        assertEquals(expected.toSet(), slashCommandSuggestions("/").toSet())
        assertEquals(expected.map { it.name }.toSet(), SLASH_COMMAND_HELP.keys)
        assertEquals(slashCommandSuggestions("/"), slashCommandSuggestions("  /"))
    }

    @Test
    fun ordinaryTextUrlsAndUnknownNamesDoNotShowHints() {
        for (input in listOf("", "hello", "hello /model", "https://example.com", "/not-a-command", "/not-a-command arg", "/ ")) {
            assertTrue(input, slashCommandSuggestions(input).isEmpty())
        }
    }

    @Test
    fun prefixesAliasesAndCaseFindTheCanonicalCommand() {
        assertEquals("model", slashCommandSuggestions("/Mo").first().name)
        assertEquals("new", slashCommandSuggestions("/reset").single().name)
        assertEquals("branch", slashCommandSuggestions("/fo").first().name)
        assertEquals("queue", slashCommandSuggestions("/q").first().name)
        assertEquals("steer", slashCommandSuggestions("/s").first().name)
        assertEquals(1, slashCommandSuggestions("/re").count { it.name == "new" })
    }

    @Test
    fun argumentsKeepOnlyExactHelpIncludingAliasesAndUnsupportedCommands() {
        for (input in listOf("/model ", "/MODEL provider/model", " /model\tvalue\nrest")) {
            assertEquals("model", slashCommandSuggestions(input).single().name)
        }
        assertEquals("queue", slashCommandSuggestions("/q edit 2 text").single().name)
        assertTrue(slashCommandSuggestions("/mo value").isEmpty())
        assertEquals(SlashAction.UNSUPPORTED, slashCommandSuggestions("/compress ").single().action)
        assertEquals(SlashAction.UNSUPPORTED, slashCommandSuggestions("/compr").single().action)
    }

    @Test
    fun completionPreservesArgumentsAndAddsSpaceForFreshInput() {
        val model = findHermesCommand("model")!!
        assertEquals("/model ", completeSlashCommand("/mo", model))
        assertEquals("/model ", completeSlashCommand("/", model))
        assertEquals("/model  provider/model\nmore", completeSlashCommand(" /mo  provider/model\nmore", model))
        assertEquals("/queue edit 2 Keep CASE", completeSlashCommand("/q edit 2 Keep CASE", findHermesCommand("queue")!!))
    }

    @Test
    fun everySupportedCommandHasDescriptionAndValidUsageResources() {
        assertHelpResources()
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseHelpIsAvailableForEverySupportedCommand() {
        assertHelpResources()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.getString(SLASH_COMMAND_HELP.getValue("model").description).contains("モデル"))
    }

    private fun assertHelpResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((name, help) in SLASH_COMMAND_HELP) {
            assertTrue(name, context.getString(help.description).isNotBlank())
            val syntax = context.getString(help.usage)
            assertTrue(name, syntax.lines().all { it.startsWith("/$name") })
        }
    }
}
