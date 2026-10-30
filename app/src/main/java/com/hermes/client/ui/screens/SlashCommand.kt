package com.hermes.client.ui.screens

/**
 * A slash command typed into the chat input. Commands are parsed and
 * handled locally by [ChatViewModel]; they are never forwarded to the
 * server as message text. (The Hermes API server does not dispatch
 * slash commands sent as run input — gateway/platforms/api_server_runs.py
 * passes `input` verbatim to the agent — so every command the app
 * supports is either mapped onto the REST API / local state here, or
 * answered with a local "not supported in this app" notice.)
 */
sealed interface SlashCommand {
    /** Not a command: submit as a normal message. */
    data object NotACommand : SlashCommand

    /** /stop — stop the current run. Extra words are ignored. */
    data object Stop : SlashCommand

    /** /steer <text> — steer the current run. [text] is null when missing. */
    data class Steer(val text: String?) : SlashCommand

    /**
     * /new [name] (alias /reset) — leave this conversation for a fresh
     * one. [name] is the optional title for the new session; the
     * confirmation-skip flags (now, --yes, -y) are stripped. /clear
     * resolves here too: clearing the screen has no meaning in the app,
     * a fresh session is its equivalent.
     */
    data class NewSession(val name: String?) : SlashCommand

    /** /help — list the commands the app supports. Extra words are ignored. */
    data object Help : SlashCommand

    /** /commands [page] — browse every Hermes command and its app support. */
    data class Commands(val arg: String?) : SlashCommand

    /** /model or /model <arg> — [arg] is null without an argument. */
    data class Model(val arg: String?) : SlashCommand

    /** /retry — resend the last user message. Extra words are ignored. */
    data object Retry : SlashCommand

    /** /queue … — queue a prompt for the next turn, or manage the queue. */
    data class Queue(val op: QueueOp) : SlashCommand

    /** /branch [--here] [name] (alias /fork) — fork the current session. */
    data class Branch(val name: String?) : SlashCommand

    /** /sessions — list previous sessions. Extra words are ignored. */
    data object Sessions : SlashCommand

    /** /resume <name> — switch to a previous session by title or id. */
    data class Resume(val name: String?) : SlashCommand

    /** /title [name] — show the session title, or set it with [name]. */
    data class Title(val name: String?) : SlashCommand

    /** /status — session, model and message counts. Extra words are ignored. */
    data object StatusInfo : SlashCommand

    /** /usage — token usage of the latest run. Extra words are ignored. */
    data object UsageInfo : SlashCommand

    /** /skills [list] — list skills reported by the server. */
    data class Skills(val arg: String?) : SlashCommand

    /** /toolsets — list toolsets reported by the server. */
    data object Toolsets : SlashCommand

    /** /bg <prompt> — start a prompt in a separate background session. */
    data class Background(val prompt: String?) : SlashCommand

    /** /approve [session|always] — approve the waiting dangerous command. */
    data class Approve(val choice: String?) : SlashCommand

    /** /deny — deny the waiting dangerous command. Extra words are ignored. */
    data object Deny : SlashCommand

    /**
     * A command Hermes knows but this app cannot perform through the
     * REST API. [name] is the command token as typed, with "/".
     */
    data class Unsupported(val name: String) : SlashCommand

    /** Any other "/..." input. [name] is the command token as typed, with "/". */
    data class Unknown(val name: String) : SlashCommand
}

/** Sub-operations of /queue parsed from its argument text. */
sealed interface QueueOp {
    /** Queue [text] for the next turn (sent at once when nothing runs). */
    data class Add(val text: String) : QueueOp

    /** /queue, /queue list — show the pending queue. */
    data object ShowList : QueueOp

    /** /queue clear — drop every queued prompt. */
    data object Clear : QueueOp

    /** /queue rm <n> — drop the 1-based entry; [index] null when unparsable. */
    data class Remove(val index: Int?) : QueueOp
}

/** How the app handles one Hermes command: the parse/dispatch shape. */
enum class SlashAction {
    STOP, STEER, NEW_SESSION, HELP, COMMANDS, MODEL, RETRY, QUEUE, BRANCH,
    SESSIONS, RESUME, TITLE, STATUS, USAGE, SKILLS, TOOLSETS, BACKGROUND,
    APPROVE, DENY, UNSUPPORTED,
}

/**
 * One built-in Hermes slash command, mirroring the server registry
 * (`hermes_cli/commands.py` COMMAND_REGISTRY in NousResearch/hermes-agent,
 * the same list the official Slash Commands Reference documents).
 * [description] is the registry's English one-liner, shortened where the
 * original is a paragraph. Dynamic skill commands (/<skill-name>),
 * plugin commands and user quick commands are server-configured and are
 * deliberately absent: they stay [SlashCommand.Unknown] in the app.
 */
data class HermesCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val action: SlashAction,
    val description: String,
)

/** Every built-in Hermes command, in registry order. */
val HERMES_COMMANDS: List<HermesCommand> = listOf(
    HermesCommand("start", action = SlashAction.UNSUPPORTED, description = "Acknowledge platform start pings without a reply"),
    HermesCommand("new", aliases = listOf("reset"), action = SlashAction.NEW_SESSION, description = "Start a new session (fresh session ID + history)"),
    HermesCommand("topic", action = SlashAction.UNSUPPORTED, description = "Enable or inspect Telegram DM topic sessions"),
    HermesCommand("clear", action = SlashAction.NEW_SESSION, description = "Clear screen and start a new session"),
    HermesCommand("redraw", action = SlashAction.UNSUPPORTED, description = "Force a full UI repaint"),
    HermesCommand("history", action = SlashAction.UNSUPPORTED, description = "Show conversation history"),
    HermesCommand("save", action = SlashAction.UNSUPPORTED, description = "Export the current conversation"),
    HermesCommand("retry", action = SlashAction.RETRY, description = "Retry the last message (resend to agent)"),
    HermesCommand("prompt", aliases = listOf("compose"), action = SlashAction.UNSUPPORTED, description = "Compose your next prompt in \$EDITOR, then send it"),
    HermesCommand("undo", action = SlashAction.UNSUPPORTED, description = "Back up N user turns and re-prompt (default 1)"),
    HermesCommand("title", action = SlashAction.TITLE, description = "Set a title for the current session"),
    HermesCommand("handoff", action = SlashAction.UNSUPPORTED, description = "Hand off this session to a messaging platform"),
    HermesCommand("branch", aliases = listOf("fork"), action = SlashAction.BRANCH, description = "Branch the current session"),
    HermesCommand("worktree", action = SlashAction.UNSUPPORTED, description = "Show, list, create, or prune isolated git worktrees"),
    HermesCommand("compress", aliases = listOf("compact"), action = SlashAction.UNSUPPORTED, description = "Compress conversation context"),
    HermesCommand("rollback", action = SlashAction.UNSUPPORTED, description = "List or restore filesystem checkpoints"),
    HermesCommand("snapshot", aliases = listOf("snap"), action = SlashAction.UNSUPPORTED, description = "Create or restore state snapshots of Hermes config/state"),
    HermesCommand("stop", action = SlashAction.STOP, description = "Kill all running background processes"),
    HermesCommand("discard", action = SlashAction.UNSUPPORTED, description = "Drop a turn lost when Hermes restarted"),
    HermesCommand("pause", action = SlashAction.UNSUPPORTED, description = "Pause new work globally (emergency stop)"),
    HermesCommand("approve", action = SlashAction.APPROVE, description = "Approve a pending dangerous command"),
    HermesCommand("deny", action = SlashAction.DENY, description = "Deny a pending dangerous command"),
    HermesCommand("bg", action = SlashAction.BACKGROUND, description = "Run a prompt in a separate background session"),
    HermesCommand("btw", action = SlashAction.UNSUPPORTED, description = "Ask a side question about the current conversation"),
    HermesCommand("agents", aliases = listOf("tasks"), action = SlashAction.UNSUPPORTED, description = "Show active agents and running tasks"),
    HermesCommand("journey", aliases = listOf("learning", "memory-graph"), action = SlashAction.UNSUPPORTED, description = "Open the learning journey timeline"),
    HermesCommand("queue", aliases = listOf("q"), action = SlashAction.QUEUE, description = "Queue a prompt for the next turn"),
    HermesCommand("steer", aliases = listOf("s"), action = SlashAction.STEER, description = "Inject a message after the next tool call without interrupting"),
    HermesCommand("goal", action = SlashAction.UNSUPPORTED, description = "Set a standing goal Hermes works on across turns"),
    HermesCommand("heartbeat", aliases = listOf("hb"), action = SlashAction.UNSUPPORTED, description = "Set a recurring prompt that re-enters this session when idle"),
    HermesCommand("refine", action = SlashAction.UNSUPPORTED, description = "Review this conversation now and save lessons to memory/skills"),
    HermesCommand("review", action = SlashAction.UNSUPPORTED, description = "Spawn an independent subagent to review the work just discussed"),
    HermesCommand("loop", aliases = listOf("proactive"), action = SlashAction.UNSUPPORTED, description = "Re-run a prompt on a recurring interval in this session"),
    HermesCommand("plan", action = SlashAction.UNSUPPORTED, description = "Write a markdown implementation plan without executing anything"),
    HermesCommand("moa", action = SlashAction.UNSUPPORTED, description = "Run one prompt through the default Mixture of Agents preset"),
    HermesCommand("subgoal", action = SlashAction.UNSUPPORTED, description = "Add or manage extra criteria on the active goal"),
    HermesCommand("status", action = SlashAction.STATUS, description = "Show session, model, token, and context info"),
    HermesCommand("egress", action = SlashAction.UNSUPPORTED, description = "Show Docker egress proxy status"),
    HermesCommand("context", aliases = listOf("ctx"), action = SlashAction.UNSUPPORTED, description = "Show detailed context window view"),
    HermesCommand("whoami", action = SlashAction.UNSUPPORTED, description = "Show your slash command access (admin / user)"),
    HermesCommand("profile", action = SlashAction.UNSUPPORTED, description = "Show active profile name and home directory"),
    HermesCommand("sethome", aliases = listOf("set-home"), action = SlashAction.UNSUPPORTED, description = "Set this chat as the home channel"),
    HermesCommand("resume", action = SlashAction.RESUME, description = "Resume a previously-named session"),
    HermesCommand("sessions", action = SlashAction.SESSIONS, description = "Browse and resume previous sessions"),
    HermesCommand("export", action = SlashAction.UNSUPPORTED, description = "Export a profile to a shareable archive"),
    HermesCommand("import", action = SlashAction.UNSUPPORTED, description = "Import a shared profile archive as a new profile"),
    HermesCommand("config", action = SlashAction.UNSUPPORTED, description = "Show current configuration"),
    HermesCommand("model", action = SlashAction.MODEL, description = "Switch model (session-scoped; --global to persist)"),
    HermesCommand("codex-runtime", aliases = listOf("codex_runtime"), action = SlashAction.UNSUPPORTED, description = "Toggle codex app-server runtime"),
    HermesCommand("personality", action = SlashAction.UNSUPPORTED, description = "Set a predefined personality"),
    HermesCommand("statusbar", aliases = listOf("sb"), action = SlashAction.UNSUPPORTED, description = "Toggle the context/model status bar"),
    HermesCommand("battery", action = SlashAction.UNSUPPORTED, description = "Toggle a color-coded battery indicator in the status bar"),
    HermesCommand("timestamps", aliases = listOf("ts"), action = SlashAction.UNSUPPORTED, description = "Toggle [HH:MM] timestamps on messages"),
    HermesCommand("diff", action = SlashAction.UNSUPPORTED, description = "Show git changes in the working directory"),
    HermesCommand("verbose", action = SlashAction.UNSUPPORTED, description = "Cycle tool progress display"),
    HermesCommand("focus", action = SlashAction.UNSUPPORTED, description = "Toggle focus view"),
    HermesCommand("footer", action = SlashAction.UNSUPPORTED, description = "Toggle gateway runtime-metadata footer on final replies"),
    HermesCommand("yolo", action = SlashAction.UNSUPPORTED, description = "Toggle YOLO mode (skip all dangerous command approvals)"),
    HermesCommand("approvals", action = SlashAction.UNSUPPORTED, description = "Show or set the persistent dangerous-command approval mode"),
    HermesCommand("reasoning", action = SlashAction.UNSUPPORTED, description = "Manage reasoning effort and display"),
    HermesCommand("fast", action = SlashAction.UNSUPPORTED, description = "Fast mode — provider priority/fast processing"),
    HermesCommand("skin", action = SlashAction.UNSUPPORTED, description = "Show or change the display skin/theme"),
    HermesCommand("indicator", action = SlashAction.UNSUPPORTED, description = "Pick the TUI busy-indicator style"),
    HermesCommand("voice", action = SlashAction.UNSUPPORTED, description = "Toggle voice mode"),
    HermesCommand("wake", action = SlashAction.UNSUPPORTED, description = "Toggle the 'Hey Hermes' wake word listener"),
    HermesCommand("busy", action = SlashAction.UNSUPPORTED, description = "Control how messages behave while Hermes is working"),
    HermesCommand("autoprune", action = SlashAction.UNSUPPORTED, description = "Turn automatic pruning of old sessions on or off"),
    HermesCommand("tools", action = SlashAction.UNSUPPORTED, description = "Manage tools for the current session"),
    HermesCommand("toolsets", action = SlashAction.TOOLSETS, description = "List available toolsets"),
    HermesCommand("skills", action = SlashAction.SKILLS, description = "Search, install, inspect, or manage skills"),
    HermesCommand("memory", action = SlashAction.UNSUPPORTED, description = "Review pending memory writes / toggle the approval gate"),
    HermesCommand("bundles", action = SlashAction.UNSUPPORTED, description = "List skill bundles"),
    HermesCommand("pet", action = SlashAction.UNSUPPORTED, description = "Toggle or adopt a petdex mascot"),
    HermesCommand("hatch", aliases = listOf("generate-pet"), action = SlashAction.UNSUPPORTED, description = "Generate a new petdex pet from a description"),
    HermesCommand("learn", action = SlashAction.UNSUPPORTED, description = "Learn a reusable skill from anything you describe"),
    HermesCommand("init", action = SlashAction.UNSUPPORTED, description = "Generate or update AGENTS.md project instructions"),
    HermesCommand("cron", action = SlashAction.UNSUPPORTED, description = "Manage scheduled tasks"),
    HermesCommand("suggestions", aliases = listOf("suggest"), action = SlashAction.UNSUPPORTED, description = "Review suggested automations"),
    HermesCommand("blueprint", aliases = listOf("bp"), action = SlashAction.UNSUPPORTED, description = "Set up an automation from a blueprint template"),
    HermesCommand("curator", action = SlashAction.UNSUPPORTED, description = "Background skill maintenance"),
    HermesCommand("kanban", action = SlashAction.UNSUPPORTED, description = "Multi-profile collaboration board"),
    HermesCommand("reload", action = SlashAction.UNSUPPORTED, description = "Reload .env variables into the running session"),
    HermesCommand("reload-mcp", aliases = listOf("reload_mcp"), action = SlashAction.UNSUPPORTED, description = "Reload MCP servers from config"),
    HermesCommand("reload-skills", aliases = listOf("reload_skills"), action = SlashAction.UNSUPPORTED, description = "Re-scan installed skills"),
    HermesCommand("browser", action = SlashAction.UNSUPPORTED, description = "Connect browser tools to your live Chromium browser"),
    HermesCommand("plugins", action = SlashAction.UNSUPPORTED, description = "List installed plugins and their status"),
    HermesCommand("commands", action = SlashAction.COMMANDS, description = "Browse all commands and skills (paginated)"),
    HermesCommand("help", action = SlashAction.HELP, description = "Show available commands"),
    HermesCommand("palette", action = SlashAction.UNSUPPORTED, description = "Open the fuzzy command palette"),
    HermesCommand("restart", action = SlashAction.UNSUPPORTED, description = "Gracefully restart the gateway"),
    HermesCommand("usage", action = SlashAction.USAGE, description = "Show token usage and rate limits"),
    HermesCommand("subscription", aliases = listOf("upgrade"), action = SlashAction.UNSUPPORTED, description = "View your Nous plan and change it in the browser"),
    HermesCommand("login", action = SlashAction.UNSUPPORTED, description = "Sign in with a Nous account"),
    HermesCommand("topup", action = SlashAction.UNSUPPORTED, description = "Show your Nous balance and manage billing"),
    HermesCommand("insights", action = SlashAction.UNSUPPORTED, description = "Show usage insights and analytics"),
    HermesCommand("platforms", aliases = listOf("gateway"), action = SlashAction.UNSUPPORTED, description = "Show gateway/messaging platform status"),
    HermesCommand("platform", action = SlashAction.UNSUPPORTED, description = "Pause, resume, or list a failing gateway platform"),
    HermesCommand("copy", action = SlashAction.UNSUPPORTED, description = "Copy the last assistant response to clipboard"),
    HermesCommand("paste", action = SlashAction.UNSUPPORTED, description = "Attach a clipboard image"),
    HermesCommand("image", action = SlashAction.UNSUPPORTED, description = "Attach a local image file for your next prompt"),
    HermesCommand("update", action = SlashAction.UNSUPPORTED, description = "Update Hermes Agent to the latest version"),
    HermesCommand("version", aliases = listOf("v"), action = SlashAction.UNSUPPORTED, description = "Show Hermes Agent version"),
    HermesCommand("debug", action = SlashAction.UNSUPPORTED, description = "Upload debug report and get shareable links"),
    HermesCommand("quit", aliases = listOf("exit"), action = SlashAction.UNSUPPORTED, description = "Exit the CLI"),
)

private val COMMAND_LOOKUP: Map<String, HermesCommand> = buildMap {
    for (command in HERMES_COMMANDS) {
        put(command.name, command)
        for (alias in command.aliases) put(alias, command)
    }
}

/** Resolves a command name or alias (no leading slash) to its registry entry. */
fun findHermesCommand(name: String): HermesCommand? = COMMAND_LOOKUP[name.lowercase()]

private val WhitespaceSplit = Regex("\\s+")

/** Confirmation-skip flags Hermes accepts on /new and /reset. */
private val NEW_SESSION_FLAGS = setOf("now", "--yes", "-y")

/**
 * The optional title of a /new (or /reset) invocation: the argument text
 * minus the confirmation-skip flags, which may appear anywhere.
 */
private fun sessionNameArg(rest: String): String? {
    if (rest.isEmpty()) return null
    val kept = rest.split(WhitespaceSplit).filter { it !in NEW_SESSION_FLAGS }
    return kept.joinToString(" ").ifEmpty { null }
}

/**
 * The optional title of a /branch invocation: --here is accepted and
 * ignored (the app always branches in place — it has no threads).
 */
private fun branchNameArg(rest: String): String? {
    if (rest.isEmpty()) return null
    val kept = rest.split(WhitespaceSplit).filter { it != "--here" }
    return kept.joinToString(" ").ifEmpty { null }
}

/**
 * Parses the /queue argument: no argument (or "list") shows the queue,
 * "clear" empties it, "rm <n>" drops one entry, "add <prompt>" queues a
 * prompt that itself starts with a management word, and anything else is
 * the prompt to queue. "edit"/"move" reorder the gateway's shared queue
 * and have no app equivalent, so they report as unsupported.
 */
private fun parseQueue(token: String, rest: String): SlashCommand {
    if (rest.isEmpty()) return SlashCommand.Queue(QueueOp.ShowList)
    val splitAt = rest.indexOfFirst { it.isWhitespace() }
    val head = if (splitAt < 0) rest else rest.substring(0, splitAt)
    val tail = if (splitAt < 0) "" else rest.substring(splitAt).trim()
    return when (head.lowercase()) {
        "list" -> SlashCommand.Queue(QueueOp.ShowList)
        "clear" -> SlashCommand.Queue(QueueOp.Clear)
        "rm", "remove" -> SlashCommand.Queue(QueueOp.Remove(tail.toIntOrNull()))
        "add" ->
            if (tail.isEmpty()) SlashCommand.Queue(QueueOp.ShowList)
            else SlashCommand.Queue(QueueOp.Add(tail))
        "edit", "move" -> SlashCommand.Unsupported("$token $head")
        else -> SlashCommand.Queue(QueueOp.Add(rest))
    }
}

/**
 * Parses raw input text into a [SlashCommand]. Text that does not start
 * with "/" (after trimming) is [SlashCommand.NotACommand]. The command
 * name is matched case-insensitively against the Hermes registry (names
 * and aliases); the argument keeps its case and inner spacing.
 */
fun parseSlashCommand(raw: String): SlashCommand {
    val text = raw.trim()
    if (!text.startsWith("/")) return SlashCommand.NotACommand
    val splitAt = text.indexOfFirst { it.isWhitespace() }
    val token = if (splitAt < 0) text else text.substring(0, splitAt)
    val rest = if (splitAt < 0) "" else text.substring(splitAt).trim()
    val info = findHermesCommand(token.removePrefix("/")) ?: return SlashCommand.Unknown(token)
    return when (info.action) {
        SlashAction.STOP -> SlashCommand.Stop
        SlashAction.STEER -> SlashCommand.Steer(rest.ifEmpty { null })
        SlashAction.NEW_SESSION -> SlashCommand.NewSession(sessionNameArg(rest))
        SlashAction.HELP -> SlashCommand.Help
        SlashAction.COMMANDS -> SlashCommand.Commands(rest.ifEmpty { null })
        SlashAction.MODEL -> SlashCommand.Model(rest.ifEmpty { null })
        SlashAction.RETRY -> SlashCommand.Retry
        SlashAction.QUEUE -> parseQueue(token, rest)
        SlashAction.BRANCH -> SlashCommand.Branch(branchNameArg(rest))
        SlashAction.SESSIONS -> SlashCommand.Sessions
        SlashAction.RESUME -> SlashCommand.Resume(rest.ifEmpty { null })
        SlashAction.TITLE -> SlashCommand.Title(rest.ifEmpty { null })
        SlashAction.STATUS -> SlashCommand.StatusInfo
        SlashAction.USAGE -> SlashCommand.UsageInfo
        SlashAction.SKILLS -> SlashCommand.Skills(rest.ifEmpty { null })
        SlashAction.TOOLSETS -> SlashCommand.Toolsets
        SlashAction.BACKGROUND -> SlashCommand.Background(rest.ifEmpty { null })
        SlashAction.APPROVE -> SlashCommand.Approve(rest.ifEmpty { null })
        SlashAction.DENY -> SlashCommand.Deny
        SlashAction.UNSUPPORTED -> SlashCommand.Unsupported(token)
    }
}
