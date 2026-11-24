package com.ctharness.app.tools

import com.ctharness.app.debug.DapClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Debugger control over the Debug Adapter Protocol. A session starts an
 * adapter such as debugpy, sets breakpoints, launches the program and then
 * answers step/stack/variable questions until it is stopped. One session stays
 * alive between calls, so the program keeps running while the agent reads
 * other files.
 */
class DapTool(
    private val shell: (String) -> ProcessBuilder,
    private val defaultCwd: () -> File,
) : Tool {
    override val name = NAME
    override val description =
        "Debug a program: breakpoints, stepping, stack frames, scopes and variables. " +
            "start launches an adapter (default preset debugpy, or give adapter as a command line) and stops at the " +
            "first breakpoint or entry; then use continue, next, step_in, step_out, threads, stack, scopes, " +
            "variables, evaluate and stop. Line numbers are 1-based."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("start, continue, next, step_in, step_out, pause, breakpoints, threads, stack, scopes, variables, evaluate or stop."),
            "session" to Schema.string("Session id (default \"main\")."),
            "adapter" to Schema.string("Adapter command line, or a preset name such as debugpy."),
            "program" to Schema.string("Program to debug, for start."),
            "args" to Schema.array(Schema.string("Program argument"), "Arguments for start."),
            "cwd" to Schema.string("Working directory for the adapter and program."),
            "path" to Schema.string("File to set breakpoints in."),
            "lines" to Schema.array(Schema.integer("1-based line"), "Breakpoint lines."),
            "stop_on_entry" to Schema.boolean("Stop at the first line (default false)."),
            "thread" to Schema.integer("Thread id for stepping and stack; defaults to the last stopped thread."),
            "frame" to Schema.integer("Frame id for scopes and evaluate; defaults to the top frame."),
            "ref" to Schema.integer("variablesReference from a scopes reply, for action=variables."),
            "expression" to Schema.string("Expression to evaluate in the selected frame."),
            "timeout_seconds" to Schema.integer("How long to wait for a stop or reply (default 30)."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.string("action")?.lowercase() ?: return ToolResult(false, "Give an action.")
        val id = args.string("session")?.takeIf { it.isNotBlank() } ?: "main"
        val timeout = ((args.int("timeout_seconds") ?: 30) * 1_000L).coerceIn(1_000L, 180_000L)
        if (action == "start") return start(id, args, ctx, timeout)
        val session = sessions[id] ?: return ToolResult(false, "No debug session \"$id\". Use action=start first.")
        return runCatching { control(id, session, action, args, timeout) }
            .getOrElse { ToolResult(false, it.message ?: "The debug adapter failed.") }
    }

    private suspend fun start(id: String, args: JsonObject, ctx: ToolContext, timeout: Long): ToolResult {
        val program = args.string("program")?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Give the program to debug for action=start.")
        val adapter = args.string("adapter")?.takeIf { it.isNotBlank() } ?: "debugpy"
        val command = DapClient.PRESETS[adapter]?.toList() ?: splitCommand(adapter)
        if (command.isEmpty()) return ToolResult(false, "Give adapter as a command line, for example \"python -m debugpy.adapter\".")
        val cwd = args.string("cwd")?.let { path -> File(path).let { if (it.isAbsolute) it else File(ctx.workspace.shellRoot ?: defaultCwd(), path) } }
            ?: ctx.workspace.shellRoot ?: defaultCwd()
        val programFile = File(program).let { if (it.isAbsolute) it else File(cwd, program) }
        sessions.remove(id)?.close()
        val client = DapClient(shell, command, cwd)
        client.start()
        client.initialize(adapter)
        val lines = args.intList("lines")
        args.string("path")?.let { path -> client.setBreakpoints(absolute(path, cwd).path, lines) }
        client.launch(programFile.path, args.stringList("args"), args.flag("stop_on_entry", false))
        client.waitEvent("initialized", 5_000)
        runCatching { client.request("configurationDone", JsonObject(emptyMap()), 5_000) }
        sessions[id] = client
        val stopped = client.waitEvent("stopped", timeout)
        if (stopped == null) {
            val ended = client.waitEvent("terminated", 2_000)
            if (ended != null) {
                sessions.remove(id)?.close()
                return ToolResult(false, "The program finished before reaching a breakpoint.")
            }
            return ToolResult(true, "Session \"$id\" started with $adapter; it is running. Use action=pause or set breakpoints first.")
        }
        return ToolResult(true, describeStop(id, client, stopped))
    }

    private suspend fun control(id: String, client: DapClient, action: String, args: JsonObject, timeout: Long): ToolResult = when (action) {
        "breakpoints" -> {
            val path = args.string("path") ?: return ToolResult(false, "Give the file for breakpoints.")
            val lines = args.intList("lines")
            client.setBreakpoints(File(path).absolutePath, lines)
            ToolResult(true, "Set ${lines.size} breakpoint(s) in $path.")
        }
        "continue", "next", "step_in", "step_out", "pause" -> {
            val thread = args.int("thread") ?: sessions[id]?.lastThread ?: return ToolResult(false, "Give the thread id (see action=threads).")
            val command = when (action) {
                "next" -> "next"; "step_in" -> "stepIn"; "step_out" -> "stepOut"; "pause" -> "pause"; else -> "continue"
            }
            client.step(command, thread)
            if (command == "continue" || command == "pause") {
                val stopped = client.waitEvent("stopped", timeout)
                if (stopped == null) {
                    val ended = client.waitEvent("terminated", 2_000)
                    if (ended != null) { sessions.remove(id)?.close(); return ToolResult(true, "The program finished.") }
                    return ToolResult(true, "Sent $command to thread $thread; no stop yet.")
                }
                ToolResult(true, describeStop(id, client, stopped))
            } else {
                val stopped = client.waitEvent("stopped", timeout)
                if (stopped != null) ToolResult(true, describeStop(id, client, stopped))
                else ToolResult(true, "Sent $command; waiting for the next stop.")
            }
        }
        "threads" -> {
            val body = client.request("threads", JsonObject(emptyMap()), timeout)
            val threads = body?.get("threads")?.jsonArray.orEmpty()
            ToolResult(true, if (threads.isEmpty()) "No threads reported."
            else threads.joinToString("\n") { it.jsonObject.let { t -> "${t["id"]?.jsonPrimitive?.intOrNull ?: "?"}: ${t["name"]?.jsonPrimitive?.contentOrNull ?: ""}" } })
        }
        "stack" -> {
            val thread = args.int("thread") ?: sessions[id]?.lastThread ?: return ToolResult(false, "Give the thread id.")
            val body = client.request("stackTrace", buildJsonObject { put("threadId", thread); put("levels", 20) }, timeout)
            val frames = body?.get("stackFrames")?.jsonArray.orEmpty()
            ToolResult(true, if (frames.isEmpty()) "No stack frames."
            else frames.joinToString("\n") { element ->
                val frame = element.jsonObject
                val path = frame["source"]?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull ?: "?"
                "${frame["id"]?.jsonPrimitive?.intOrNull}: ${frame["name"]?.jsonPrimitive?.contentOrNull} at $path:${frame["line"]?.jsonPrimitive?.intOrNull}"
            })
        }
        "scopes" -> {
            val frame = args.int("frame") ?: sessions[id]?.lastFrame ?: return ToolResult(false, "Give the frame id (see action=stack).")
            val body = client.request("scopes", buildJsonObject { put("frameId", frame) }, timeout)
            val scopes = body?.get("scopes")?.jsonArray.orEmpty()
            ToolResult(true, if (scopes.isEmpty()) "No scopes."
            else scopes.joinToString("\n") { element ->
                val scope = element.jsonObject
                "${scope["name"]?.jsonPrimitive?.contentOrNull ?: "scope"} (ref ${scope["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0})"
            })
        }
        "variables" -> {
            val ref = args.int("ref") ?: return ToolResult(false, "Give the variablesReference from action=scopes.")
            val body = client.request("variables", buildJsonObject { put("variablesReference", ref) }, timeout)
            val variables = body?.get("variables")?.jsonArray.orEmpty()
            ToolResult(true, if (variables.isEmpty()) "No variables."
            else variables.joinToString("\n") { element ->
                val variable = element.jsonObject
                val type = variable["type"]?.jsonPrimitive?.contentOrNull
                "${variable["name"]?.jsonPrimitive?.contentOrNull}: ${variable["value"]?.jsonPrimitive?.contentOrNull}" + (if (type != null) " ($type)" else "")
            })
        }
        "evaluate" -> {
            val expression = args.string("expression") ?: return ToolResult(false, "Give the expression to evaluate.")
            val frame = args.int("frame") ?: sessions[id]?.lastFrame
            val body = client.request("evaluate", buildJsonObject {
                put("expression", expression)
                if (frame != null) put("frameId", frame)
                put("context", "repl")
            }, timeout)
            ToolResult(true, body?.get("result")?.jsonPrimitive?.contentOrNull ?: "(no result)")
        }
        "stop" -> {
            sessions.remove(id)?.close()
            ToolResult(true, "Stopped debug session \"$id\".")
        }
        else -> ToolResult(false, "Unknown action \"$action\".")
    }

    private fun describeStop(id: String, client: DapClient, stopped: JsonObject): String {
        val body = stopped["body"]?.jsonObject
        val thread = body?.get("threadId")?.jsonPrimitive?.intOrNull
        val reason = body?.get("reason")?.jsonPrimitive?.contentOrNull ?: "stopped"
        val description = body?.get("description")?.jsonPrimitive?.contentOrNull
        val frame = body?.get("stackFrames")?.jsonArray?.firstOrNull()?.jsonObject
        val path = frame?.get("source")?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
        val line = frame?.get("line")?.jsonPrimitive?.intOrNull
        val frameId = frame?.get("id")?.jsonPrimitive?.intOrNull
        if (thread != null) client.lastThread = thread
        if (frameId != null) client.lastFrame = frameId
        return buildString {
            append("Stopped: ").append(reason)
            if (description != null) append(" (").append(description).append(')')
            if (path != null) append(" at ").append(path).append(':').append(line ?: "?")
            append("\nthread ").append(thread ?: "?").append(", frame ").append(frameId ?: "?")
            append(". Use stack, scopes, variables or continue.")
        }
    }

    private fun absolute(path: String, cwd: File): File =
        File(path).let { if (it.isAbsolute) it else File(cwd, path) }

    private fun splitCommand(line: String): List<String> =
        Regex(""""([^"]*)"|'([^']*)'|(\S+)""").findAll(line).map { match ->
            match.groupValues[1].ifBlank { match.groupValues[2].ifBlank { match.groupValues[3] } }
        }.toList()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.flag(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()

    companion object {
        const val NAME = "dap"
        private val sessions = ConcurrentHashMap<String, DapClient>()
    }
}
