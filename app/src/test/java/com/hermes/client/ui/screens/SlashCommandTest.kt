package com.hermes.client.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure parser coverage for [parseSlashCommand]. */
class SlashCommandTest {

    @Test
    fun plainText_isNotACommand() {
        assertEquals(SlashCommand.NotACommand, parseSlashCommand("hello"))
        assertEquals(SlashCommand.NotACommand, parseSlashCommand("see /help for details"))
        assertEquals(SlashCommand.NotACommand, parseSlashCommand(""))
        assertEquals(SlashCommand.NotACommand, parseSlashCommand("   "))
    }

    @Test
    fun stop_ignoresTrailingWords() {
        assertEquals(SlashCommand.Stop, parseSlashCommand("/stop"))
        assertEquals(SlashCommand.Stop, parseSlashCommand("/stop now"))
        assertEquals(SlashCommand.Stop, parseSlashCommand("  /stop  "))
    }

    @Test
    fun commandNames_areCaseInsensitive() {
        assertEquals(SlashCommand.Stop, parseSlashCommand("/STOP"))
        assertEquals(SlashCommand.Help, parseSlashCommand("/Help"))
        assertEquals(SlashCommand.New, parseSlashCommand("/NEW"))
    }

    @Test
    fun steer_withAndWithoutText() {
        assertEquals(
            SlashCommand.Steer("focus on tests"),
            parseSlashCommand("/steer focus on tests"),
        )
        // Edges are trimmed; inner spacing and case are preserved.
        assertEquals(
            SlashCommand.Steer("a  B"),
            parseSlashCommand("  /steer   a  B  "),
        )
        assertEquals(SlashCommand.Steer(null), parseSlashCommand("/steer"))
        assertEquals(SlashCommand.Steer(null), parseSlashCommand("/steer   "))
        assertNull((parseSlashCommand("/steer") as SlashCommand.Steer).text)
    }

    @Test
    fun newAndHelp() {
        assertEquals(SlashCommand.New, parseSlashCommand("/new"))
        assertEquals(SlashCommand.Help, parseSlashCommand("/help"))
    }

    @Test
    fun model_withAndWithoutArgument() {
        assertEquals(SlashCommand.Model(null), parseSlashCommand("/model"))
        assertEquals(SlashCommand.Model(null), parseSlashCommand("/model  "))
        assertEquals(
            SlashCommand.Model("openai/gpt-5"),
            parseSlashCommand("/model openai/gpt-5"),
        )
        assertEquals(
            SlashCommand.Model("claude-sonnet-5"),
            parseSlashCommand("/model  claude-sonnet-5 "),
        )
    }

    @Test
    fun unknown_keepsTheTokenAsTyped() {
        assertEquals(SlashCommand.Unknown("/foo"), parseSlashCommand("/foo"))
        assertEquals(SlashCommand.Unknown("/Foo"), parseSlashCommand("/Foo bar"))
        assertEquals(SlashCommand.Unknown("/"), parseSlashCommand("/"))
        assertEquals(SlashCommand.Unknown("/stopx"), parseSlashCommand("/stopx"))
    }
}
