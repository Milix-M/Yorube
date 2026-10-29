package com.hermes.client.ui.screens

/**
 * A slash command typed into the chat input. Commands are parsed and
 * handled locally by [ChatViewModel]; they are never forwarded to the
 * server as message text.
 */
sealed interface SlashCommand {
    /** Not a command: submit as a normal message. */
    data object NotACommand : SlashCommand

    /** /stop — stop the current run. Extra words are ignored. */
    data object Stop : SlashCommand

    /** /steer <text> — steer the current run. [text] is null when missing. */
    data class Steer(val text: String?) : SlashCommand

    /** /new — leave this conversation for a fresh one. Extra words are ignored. */
    data object New : SlashCommand

    /** /help — list the commands locally. Extra words are ignored. */
    data object Help : SlashCommand

    /** /model or /model <arg> — [arg] is null without an argument. */
    data class Model(val arg: String?) : SlashCommand

    /** Any other "/..." input. [name] is the command token as typed, with "/". */
    data class Unknown(val name: String) : SlashCommand
}

/**
 * Parses raw input text into a [SlashCommand]. Text that does not start
 * with "/" (after trimming) is [SlashCommand.NotACommand]. The command
 * name is matched case-insensitively; the argument keeps its case and
 * inner spacing.
 */
fun parseSlashCommand(raw: String): SlashCommand {
    val text = raw.trim()
    if (!text.startsWith("/")) return SlashCommand.NotACommand
    val splitAt = text.indexOfFirst { it.isWhitespace() }
    val token = if (splitAt < 0) text else text.substring(0, splitAt)
    val rest = if (splitAt < 0) "" else text.substring(splitAt).trim()
    return when (token.lowercase()) {
        "/stop" -> SlashCommand.Stop
        "/steer" -> SlashCommand.Steer(rest.ifEmpty { null })
        "/new" -> SlashCommand.New
        "/help" -> SlashCommand.Help
        "/model" -> SlashCommand.Model(rest.ifEmpty { null })
        else -> SlashCommand.Unknown(token)
    }
}
