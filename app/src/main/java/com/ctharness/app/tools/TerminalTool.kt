package com.ctharness.app.tools

import com.ctharness.app.terminal.InteractiveTerminalManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Several live shells instead of one: each session has its own PTY, its own
 * scrollback and its own process, and keeps running while the app is in the
 * background. The Terminal screen shows the same manager, so an agent-opened
 * session appears there as another tab.
 */
class TerminalTool(
    private val terminals: () -> InteractiveTerminalManager?,
    private val defaultCwd: () -> File,
) : Tool {
    override val name = NAME
    override val description =
        "Run a real shell with a pseudo-terminal. Unlike shell, the session persists between calls: " +
            "cd, exports and long-running programs keep their state, and more than one session can run at once. " +
            "Actions: open (start or reuse a session), write (type text; enter defaults to true), run (write a command " +
            "and return what it printed), read (last lines of the transcript), list, resize, close."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("open, write, run, read, list, resize or close."),
            "session" to Schema.string("Session id. Defaults to \"main\" for open and is required for the rest."),
            "path" to Schema.string("Working directory for a new session; defaults to the workspace root."),
            "text" to Schema.string("Text to type for write, or the command line for run."),
            "enter" to Schema.boolean("Send a newline after the text (default true)."),
            "lines" to Schema.integer("How many transcript lines to return (default 120, max 400)."),
            "wait_ms" to Schema.integer("How long run waits for output before returning (default 1500 ms, max 30000)."),
            "rows" to Schema.integer("Terminal rows for open or resize (default 24)."),
            "cols" to Schema.integer("Terminal columns for open or resize (default 100)."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val manager = terminals() ?: return ToolResult(false, "The interactive terminal is not available in this build.")
        if (!manager.supported) return ToolResult(false, "This device has no usable pseudo-terminal (/dev/ptmx).")
        return when (val action = args.string("action")?.lowercase()) {
            "open" -> runCatching {
                val key = args.string("session")?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY
                val cwd = args.string("path")?.let { resolve(ctx, it) } ?: defaultCwd()
                val rows = (args.int("rows") ?: 24).coerceIn(8, 200)
                val cols = (args.int("cols") ?: 100).coerceIn(20, 400)
                val session = manager.start(key, cwd, rows, cols)
                ToolResult(
                    true,
                    "Session \"$key\" is ${if (session.running) "running" else "finished"} with ${session.shellName} " +
                        "in ${cwd.path} (pid ${session.pid}, ${rows}x$cols).",
                )
            }.getOrElse { ToolResult(false, it.message ?: "Could not start a shell.") }

            "list" -> {
                val sessions = manager.sessions.value
                if (sessions.isEmpty()) ToolResult(true, "No terminal sessions yet. Use action=open.")
                else ToolResult(true, sessions.values.joinToString("\n") { session ->
                    val tail = session.transcript().lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty()
                    "${session.key}: ${session.shellName} pid=${session.pid} " +
                        "${if (session.running) "running" else "exited"} dir=${session.directory.value ?: "?"} " +
                        "last=\"$tail\""
                })
            }

            "write" -> withSession(manager, args, requireRunning = true) { session ->
                val text = args.string("text") ?: return@withSession ToolResult(false, "Give the text to write.")
                session.writeText(if (args.flag("enter", true)) "$text\r" else text)
                ToolResult(true, "Wrote ${text.length} characters to \"${session.key}\".")
            }

            "run" -> withSession(manager, args, requireRunning = true) { session ->
                val command = args.string("text") ?: args.string("command")
                    ?: return@withSession ToolResult(false, "Give the command to run.")
                val wait = (args.int("wait_ms") ?: 1_500).coerceIn(200, 30_000)
                val before = session.transcript().length
                session.writeText(command + "\r")
                delay(wait.toLong())
                val transcript = session.transcript()
                val answer = if (transcript.length > before) transcript.substring(before) else transcript.takeLast(4_000)
                ToolResult(true, "Ran in \"${session.key}\" (waited ${wait}ms):\n" + answer.trim().takeLast(6_000))
            }

            "read" -> withSession(manager, args) { session ->
                val lines = (args.int("lines") ?: 120).coerceIn(1, 400)
                val text = session.transcript()
                ToolResult(true, "Last $lines lines of \"${session.key}\":\n" + text.lineSequence().toList().takeLast(lines).joinToString("\n"))
            }

            "resize" -> withSession(manager, args) { session ->
                val rows = (args.int("rows") ?: 24).coerceIn(8, 200)
                val cols = (args.int("cols") ?: 100).coerceIn(20, 400)
                session.resize(rows, cols, 0, 0)
                ToolResult(true, "Resized \"${session.key}\" to ${rows}x$cols.")
            }

            "close" -> withSession(manager, args) { session ->
                manager.close(session.key)
                ToolResult(true, "Closed \"${session.key}\".")
            }

            else -> ToolResult(false, "Unknown action \"$action\". Use open, write, run, read, list, resize or close.")
        }
    }

    private suspend fun withSession(
        manager: InteractiveTerminalManager,
        args: JsonObject,
        requireRunning: Boolean = false,
        block: suspend (com.ctharness.app.terminal.TerminalSession) -> ToolResult,
    ): ToolResult {
        val key = args.string("session")?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Name the session, for example \"main\".")
        val session = manager.session(key) ?: return ToolResult(false, "No session \"$key\". Use action=open first.")
        if (requireRunning && !session.running) return ToolResult(false, "Session \"$key\" has exited. Open it again.")
        return block(session)
    }

    private fun resolve(ctx: ToolContext, path: String): File {
        val root = ctx.workspace.shellRoot ?: defaultCwd()
        val candidate = File(path).takeIf { it.isAbsolute } ?: File(root, path)
        return candidate.canonicalFile
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.flag(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default

    companion object {
        const val NAME = "terminal"
        const val DEFAULT_KEY = "main"
    }
}
