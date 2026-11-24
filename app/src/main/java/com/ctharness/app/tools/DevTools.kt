package com.ctharness.app.tools

import com.ctharness.app.data.ProotManager
import com.ctharness.app.data.WatchService
import com.ctharness.app.data.env.ShellTierRouter
import com.ctharness.app.testing.TestReports
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Watch mode as a tool: start it, stop it, or ask what the last run did. */
class WatchTool(
    private val service: () -> WatchService?,
    private val router: ShellTierRouter,
) : Tool {
    override val name = NAME
    override val description =
        "Run a command whenever the workspace changes, and report the last result. " +
            "Actions: start (path, command, extensions), stop, status, run_once. " +
            "The poll loop stays inside the app process, so it ends when the app is killed."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("start, stop, status or run_once."),
            "path" to Schema.string("Directory to watch, relative to the workspace; default the root."),
            "command" to Schema.string("Command to run on change, for example ./gradlew test or pytest -q."),
            "extensions" to Schema.array(Schema.string("File extension such as kt or py"), "Only watch these extensions."),
            "timeout_seconds" to Schema.integer("How long one run may take (default 300)."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val watch = service() ?: return ToolResult(false, "Watch mode is not available in this build.")
        val root = root(ctx, args.string("path"))
        return when (val action = args.string("action")?.lowercase()) {
            "start" -> {
                val command = args.string("command") ?: return ToolResult(false, "Give the command to run on change.")
                val extensions = args.stringList("extensions").map { it.removePrefix(".") }.toSet()
                runCatching {
                    watch.start(root, command, extensions, timeoutMs = (args.int("timeout_seconds") ?: 300) * 1_000)
                }.fold(
                    onSuccess = { message -> ToolResult(true, message) },
                    onFailure = { ToolResult(false, it.message ?: "Could not start watch mode.") },
                )
            }
            "stop" -> ToolResult(true, if (watch.stop()) "Watch stopped." else "Watch was not running.")
            "status" -> {
                val state = watch.state.value
                val body = buildString {
                    appendLine(state.line())
                    if (state.lastResult.isNotBlank()) {
                        appendLine()
                        appendLine("Last output:")
                        append(state.lastResult.take(3_000))
                    }
                }
                ToolResult(true, body.trimEnd())
            }
            "run_once" -> {
                val command = args.string("command") ?: return ToolResult(false, "Give the command to run.")
                ToolResult(true, watch.runOnce(root, command, (args.int("timeout_seconds") ?: 300) * 1_000))
            }
            else -> ToolResult(false, "Unknown action \"$action\". Use start, stop, status or run_once.")
        }
    }

    private fun root(ctx: ToolContext, path: String?): File {
        val base = ctx.workspace.shellRoot ?: File(".")
        if (path.isNullOrBlank() || path == ".") return base
        val candidate = File(path)
        return (if (candidate.isAbsolute) candidate else File(base, path)).canonicalFile
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    companion object { const val NAME = "watch" }
}

/** Test Explorer: runs a runner and turns its report into cases a chat can act on. */
class TestRunnerTool(
    private val router: ShellTierRouter,
) : Tool {
    override val name = NAME
    override val description =
        "Run the project's tests and list the results. Pass the command the project uses " +
            "(./gradlew test, pytest -q, npm test). JUnit XML and pytest output are parsed into cases; " +
            "failures come first with their file and line when the report has them."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string("Test command to run. Defaults to a guess from the project files."),
            "framework" to Schema.string("gradle, pytest, npm or auto (default)."),
            "path" to Schema.string("Directory to run in; default the workspace root."),
            "only" to Schema.string("Rerun one case: substring of its name or path."),
            "timeout_seconds" to Schema.integer("How long the run may take (default 900)."),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val root = args.string("path")?.let { path ->
            val file = File(path)
            if (file.isAbsolute) file else File(ctx.workspace.shellRoot ?: File("."), path)
        } ?: ctx.workspace.shellRoot ?: File(".")
        val framework = (args.string("framework") ?: "auto").lowercase().let {
            if (it == "auto") guess(root) else it
        }
        val command = args.string("command") ?: defaultCommand(framework)
            ?: return ToolResult(false, "Give the test command for this project.")
        val timeout = ((args.int("timeout_seconds") ?: 900) * 1_000)
        val result = router.run(command, root, timeoutMs = timeout, maxOutput = 60_000)
        val output = result.rawOutput + (if (result.rawStderr.isBlank()) "" else "\n" + result.rawStderr)
        val report = findReport(root, framework) ?: output
        val summary = TestReports.parse(report, framework)
        val only = args.string("only")
        val filtered = if (only.isNullOrBlank()) summary else summary.copy(
            cases = summary.cases.filter { it.label.contains(only, ignoreCase = true) || (it.file?.contains(only, ignoreCase = true) == true) },
        )
        val header = buildString {
            append("exit ").append(result.exitCode)
            if (result.timedOut) append(" (timed out)")
            result.note?.let { append(" · ").append(it) }
            append('\n')
            if (filtered.cases.isEmpty() && only != null) append("No case matched \"$only\".\n")
        }
        return ToolResult(
            ok = result.exitCode == 0 && filtered.failed == 0,
            output = header + filtered.render(),
        )
    }

    /** Gradle drops XML reports under build/test-results; pytest keeps its own in the console. */
    private fun findReport(root: File, framework: String): String? {
        if (framework != "gradle") return null
        val dir = listOf("app/build/test-results/testDebugUnitTest", "app/build/test-results/test", "build/test-results/test")
            .map { File(root, it) }
            .firstOrNull { it.isDirectory } ?: return null
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.take(40).toList()
        if (files.isEmpty()) return null
        return buildString {
            append("<testsuites>")
            files.forEach { file -> runCatching { append(file.readText()) } }
            append("</testsuites>")
        }
    }

    private fun guess(root: File): String = TestReports.detectFramework(
        listOf("build.gradle.kts", "build.gradle", "pyproject.toml", "pytest.ini", "package.json", "Cargo.toml", "go.mod")
            .filter { File(root, it).exists() },
    )

    private fun defaultCommand(framework: String): String? = when (framework) {
        "gradle" -> "./gradlew test --console=plain"
        "pytest" -> "pytest -q"
        "npm" -> "npm test"
        "cargo" -> "cargo test"
        "go" -> "go test ./..."
        else -> null
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    companion object { const val NAME = "tests" }
}

/** Ubuntu through proot-distro, installed from the same card that lists it. */
class ProotTool(
    private val manager: () -> ProotManager?,
    private val defaultCwd: () -> File,
) : Tool {
    override val name = NAME
    override val description =
        "Run commands inside an Ubuntu (or other proot-distro) container: apt, node, python and any Linux tool. " +
            "Actions: status, install (distro, about 400 MB on first use), run (distro, command). " +
            "The workspace and shared storage are bound into the container."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("status, install or run."),
            "distro" to Schema.string("Distribution name such as ubuntu, ubuntu:24.04 or debian."),
            "command" to Schema.string("Command to run inside the container for action=run."),
            "timeout_seconds" to Schema.integer("Run timeout (default 300); install allows up to 1800."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val proot = manager() ?: return ToolResult(false, "The proot container manager is not available in this build.")
        val cwd = ctx.workspace.shellRoot ?: defaultCwd()
        return when (val action = args.string("action")?.lowercase()) {
            "status" -> {
                val state = proot.status(cwd)
                val body = if (state.distros.isEmpty()) {
                    "No container installed.\n\n" + state.raw.lineSequence().filter { it.isNotBlank() }.take(6).joinToString("\n")
                } else {
                    "Installed: " + state.distros.joinToString(", ")
                }
                ToolResult(true, body)
            }
            "install" -> {
                val distro = args.string("distro")?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(false, "Name the distribution, for example ubuntu.")
                ToolResult(true, proot.install(distro, cwd))
            }
            "run" -> {
                val distro = args.string("distro")?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(false, "Name the distribution, for example ubuntu.")
                val command = args.string("command") ?: return ToolResult(false, "Give the command to run.")
                val timeout = ((args.int("timeout_seconds") ?: 300) * 1_000)
                val result = proot.run(distro, command, cwd, timeoutMs = timeout)
                ToolResult(result.startsWith("exit 0"), result)
            }
            else -> ToolResult(false, "Unknown action \"$action\". Use status, install or run.")
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    companion object { const val NAME = "proot" }
}
