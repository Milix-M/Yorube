package com.hermes.client.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parser coverage for [parseSlashCommand] and the Hermes catalog. */
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
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/NEW"))
        assertEquals(SlashCommand.Unsupported("/YOLO"), parseSlashCommand("/YOLO"))
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
        // The registered one-letter alias resolves to the same command.
        assertEquals(SlashCommand.Steer("x"), parseSlashCommand("/s x"))
    }

    @Test
    fun new_variants() {
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/new"))
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/clear"))
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/reset"))
        // Confirmation-skip flags are stripped wherever they appear.
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/reset now"))
        assertEquals(SlashCommand.NewSession(null), parseSlashCommand("/new -y"))
        assertEquals(
            SlashCommand.NewSession("my experiment"),
            parseSlashCommand("/new my experiment"),
        )
        assertEquals(
            SlashCommand.NewSession("my experiment"),
            parseSlashCommand("/new --yes my experiment"),
        )
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
    fun simpleCommands_parse() {
        assertEquals(SlashCommand.Retry, parseSlashCommand("/retry"))
        assertEquals(SlashCommand.Sessions, parseSlashCommand("/sessions"))
        assertEquals(SlashCommand.StatusInfo, parseSlashCommand("/status"))
        assertEquals(SlashCommand.UsageInfo, parseSlashCommand("/usage"))
        assertEquals(SlashCommand.Toolsets, parseSlashCommand("/toolsets"))
        assertEquals(SlashCommand.Deny, parseSlashCommand("/deny"))
        assertEquals(SlashCommand.Commands(null), parseSlashCommand("/commands"))
        assertEquals(SlashCommand.Commands("3"), parseSlashCommand("/commands 3"))
        assertEquals(SlashCommand.Resume(null), parseSlashCommand("/resume"))
        assertEquals(SlashCommand.Resume("old chat"), parseSlashCommand("/resume old chat"))
        assertEquals(SlashCommand.Title(null), parseSlashCommand("/title"))
        assertEquals(SlashCommand.Title("My Chat"), parseSlashCommand("/title My Chat"))
        assertEquals(SlashCommand.Skills(null), parseSlashCommand("/skills"))
        assertEquals(SlashCommand.Skills("pending"), parseSlashCommand("/skills pending"))
        assertEquals(SlashCommand.Background(null), parseSlashCommand("/bg"))
        assertEquals(
            SlashCommand.Background("check the deploy"),
            parseSlashCommand("/bg check the deploy"),
        )
        assertEquals(SlashCommand.Approve(null), parseSlashCommand("/approve"))
        assertEquals(SlashCommand.Approve("always"), parseSlashCommand("/approve always"))
    }

    @Test
    fun branch_variants() {
        assertEquals(SlashCommand.Branch(null), parseSlashCommand("/branch"))
        assertEquals(SlashCommand.Branch(null), parseSlashCommand("/branch --here"))
        assertEquals(SlashCommand.Branch("alt"), parseSlashCommand("/branch --here alt"))
        assertEquals(SlashCommand.Branch("alt"), parseSlashCommand("/branch alt"))
        // /fork is the registered alias.
        assertEquals(SlashCommand.Branch("alt"), parseSlashCommand("/fork alt"))
    }

    @Test
    fun queue_variants() {
        assertEquals(
            SlashCommand.Queue(QueueOp.ShowList),
            parseSlashCommand("/queue"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.ShowList),
            parseSlashCommand("/queue list"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.ShowList),
            parseSlashCommand("/q LIST"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Clear),
            parseSlashCommand("/queue clear"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Remove(2)),
            parseSlashCommand("/queue rm 2"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Remove(3)),
            parseSlashCommand("/queue remove 3"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Remove(null)),
            parseSlashCommand("/queue rm"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Remove(null)),
            parseSlashCommand("/queue rm x"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.ShowList),
            parseSlashCommand("/queue add"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Add("list files")),
            parseSlashCommand("/queue add list files"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Add("hello")),
            parseSlashCommand("/queue hello"),
        )
        assertEquals(
            SlashCommand.Queue(QueueOp.Add("hello world")),
            parseSlashCommand("/queue hello world"),
        )
        // edit/move manage the gateway's shared queue: no app equivalent.
        assertEquals(
            SlashCommand.Unsupported("/queue edit"),
            parseSlashCommand("/queue edit 1 x"),
        )
        assertEquals(
            SlashCommand.Unsupported("/queue move"),
            parseSlashCommand("/queue move 1 2"),
        )
    }

    @Test
    fun unsupportedCommands_areRecognized() {
        // A spread across every registry category, incl. aliases.
        for (typed in listOf(
            "/start", "/topic", "/redraw", "/history", "/save", "/prompt", "/compose",
            "/undo", "/handoff", "/worktree", "/compress", "/compact", "/rollback",
            "/snapshot", "/snap", "/discard", "/pause", "/btw", "/agents", "/tasks",
            "/journey", "/goal", "/heartbeat", "/hb", "/refine", "/review", "/loop",
            "/plan", "/moa", "/subgoal", "/egress", "/context", "/ctx", "/whoami",
            "/profile", "/sethome", "/export", "/import", "/config", "/codex-runtime",
            "/personality", "/statusbar", "/battery", "/timestamps", "/diff",
            "/verbose", "/focus", "/footer", "/yolo", "/approvals", "/reasoning",
            "/fast", "/skin", "/indicator", "/voice", "/wake", "/busy", "/autoprune",
            "/tools", "/memory", "/bundles", "/pet", "/hatch", "/learn", "/init",
            "/cron", "/suggestions", "/blueprint", "/curator", "/kanban", "/reload",
            "/reload-mcp", "/reload_skills", "/reload-skills", "/browser", "/plugins",
            "/palette", "/restart", "/subscription", "/login", "/topup", "/insights",
            "/platforms", "/platform", "/copy", "/paste", "/image", "/update",
            "/version", "/v", "/debug", "/quit", "/exit",
        )) {
            assertEquals("for $typed", SlashCommand.Unsupported(typed), parseSlashCommand(typed))
        }
    }

    @Test
    fun unknown_keepsTheTokenAsTyped() {
        assertEquals(SlashCommand.Unknown("/foo"), parseSlashCommand("/foo"))
        assertEquals(SlashCommand.Unknown("/Foo"), parseSlashCommand("/Foo bar"))
        assertEquals(SlashCommand.Unknown("/"), parseSlashCommand("/"))
        assertEquals(SlashCommand.Unknown("/stopx"), parseSlashCommand("/stopx"))
    }

    @Test
    fun catalog_coversTheWholeRegistry() {
        // hermes_cli/commands.py COMMAND_REGISTRY at research time.
        assertEquals(104, HERMES_COMMANDS.size)
        val names = HERMES_COMMANDS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        // Every name and alias resolves back to its own entry.
        for (command in HERMES_COMMANDS) {
            assertEquals(command, findHermesCommand(command.name))
            for (alias in command.aliases) {
                assertEquals("alias $alias", command, findHermesCommand(alias))
            }
        }
        assertEquals("new", findHermesCommand("reset")?.name)
        assertEquals("steer", findHermesCommand("S")?.name)
        assertNull(findHermesCommand("not-a-command"))
        assertTrue(HERMES_COMMANDS.count { it.action != SlashAction.UNSUPPORTED } >= 20)
    }
}
