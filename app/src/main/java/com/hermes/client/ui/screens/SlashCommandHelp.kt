package com.hermes.client.ui.screens

import androidx.annotation.StringRes
import com.hermes.client.R

/** App-specific help: syntax describes only operations the local dispatcher supports. */
data class SlashCommandHelp(@param:StringRes val description: Int, @param:StringRes val usage: Int)

val SLASH_COMMAND_HELP = mapOf(
    "new" to SlashCommandHelp(R.string.slash_new_description, R.string.slash_new_usage),
    "clear" to SlashCommandHelp(R.string.slash_clear_description, R.string.slash_clear_usage),
    "redraw" to SlashCommandHelp(R.string.slash_history_description, R.string.slash_redraw_usage),
    "history" to SlashCommandHelp(R.string.slash_history_description, R.string.slash_history_usage),
    "save" to SlashCommandHelp(R.string.slash_save_description, R.string.slash_save_usage),
    "retry" to SlashCommandHelp(R.string.slash_retry_description, R.string.slash_retry_usage),
    "prompt" to SlashCommandHelp(R.string.slash_prompt_description, R.string.slash_prompt_usage),
    "title" to SlashCommandHelp(R.string.slash_title_description, R.string.slash_title_usage),
    "branch" to SlashCommandHelp(R.string.slash_branch_description, R.string.slash_branch_usage),
    "stop" to SlashCommandHelp(R.string.slash_stop_description, R.string.slash_stop_usage),
    "discard" to SlashCommandHelp(R.string.slash_discard_description, R.string.slash_discard_usage),
    "approve" to SlashCommandHelp(R.string.slash_approve_description, R.string.slash_approve_usage),
    "deny" to SlashCommandHelp(R.string.slash_deny_description, R.string.slash_deny_usage),
    "bg" to SlashCommandHelp(R.string.slash_bg_description, R.string.slash_bg_usage),
    "agents" to SlashCommandHelp(R.string.slash_agents_description, R.string.slash_agents_usage),
    "queue" to SlashCommandHelp(R.string.slash_queue_description, R.string.slash_queue_usage),
    "steer" to SlashCommandHelp(R.string.slash_steer_description, R.string.slash_steer_usage),
    "status" to SlashCommandHelp(R.string.slash_status_description, R.string.slash_status_usage),
    "resume" to SlashCommandHelp(R.string.slash_resume_description, R.string.slash_resume_usage),
    "sessions" to SlashCommandHelp(R.string.slash_sessions_description, R.string.slash_sessions_usage),
    "config" to SlashCommandHelp(R.string.slash_config_description, R.string.slash_config_usage),
    "model" to SlashCommandHelp(R.string.slash_model_description, R.string.slash_model_usage),
    "reasoning" to SlashCommandHelp(R.string.slash_reasoning_description, R.string.slash_reasoning_usage),
    "fast" to SlashCommandHelp(R.string.slash_fast_description, R.string.slash_fast_usage),
    "tools" to SlashCommandHelp(R.string.slash_tools_description, R.string.slash_tools_usage),
    "toolsets" to SlashCommandHelp(R.string.slash_tools_description, R.string.slash_toolsets_usage),
    "skills" to SlashCommandHelp(R.string.slash_skills_description, R.string.slash_skills_usage),
    "cron" to SlashCommandHelp(R.string.slash_cron_description, R.string.slash_cron_usage),
    "reload-skills" to SlashCommandHelp(R.string.slash_reload_skills_description, R.string.slash_reload_skills_usage),
    "commands" to SlashCommandHelp(R.string.slash_commands_description, R.string.slash_commands_usage),
    "help" to SlashCommandHelp(R.string.slash_help_description, R.string.slash_help_usage),
    "palette" to SlashCommandHelp(R.string.slash_help_description, R.string.slash_palette_usage),
    "usage" to SlashCommandHelp(R.string.slash_usage_description, R.string.slash_usage_usage),
    "copy" to SlashCommandHelp(R.string.slash_copy_description, R.string.slash_copy_usage),
    "version" to SlashCommandHelp(R.string.slash_version_description, R.string.slash_version_usage),
)

/** Bare slash lists available commands; arguments keep the exact command's help open. */
fun slashCommandSuggestions(input: String): List<HermesCommand> {
    val text = input.trimStart()
    if (!text.startsWith("/")) return emptyList()
    val token = text.drop(1).takeWhile { !it.isWhitespace() }
    if (text.drop(1 + token.length).isNotEmpty()) {
        return listOfNotNull(findHermesCommand(token))
    }
    return HERMES_COMMANDS.filter { command ->
        (token.isNotEmpty() || command.action != SlashAction.UNSUPPORTED) &&
            (command.name.startsWith(token, ignoreCase = true) ||
                command.aliases.any { it.startsWith(token, ignoreCase = true) })
    }.sortedWith(compareBy<HermesCommand> { it != findHermesCommand(token) }
        .thenBy { it.action == SlashAction.UNSUPPORTED }.thenBy { it.name })
}

/** Complete only the command token, preserving any arguments already entered. */
fun completeSlashCommand(input: String, command: HermesCommand): String {
    val arguments = input.trimStart().dropWhile { !it.isWhitespace() }
    return "/${command.name}" + arguments.ifEmpty { " " }
}
