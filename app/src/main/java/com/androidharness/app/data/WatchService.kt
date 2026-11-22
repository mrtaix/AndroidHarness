package com.androidharness.app.data

import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watch mode: polls the workspace, and after the changes settle runs one test
 * command and keeps its result. Polling (rather than inotify) is deliberate: the
 * same code works on a SAF tree and in a proot rootfs, and it costs one stat per
 * file per second on a typical project.
 */
class WatchService(private val router: ShellTierRouter) {
    data class State(
        val running: Boolean = false,
        val root: String = "",
        val command: String = "",
        val extensions: List<String> = emptyList(),
        val runCount: Int = 0,
        val busy: Boolean = false,
        val lastChange: String = "",
        val lastResult: String = "",
        val lastExit: Int? = null,
        val lastRunAt: Long = 0L,
    ) {
        fun line(): String = when {
            !running -> "Watch is off."
            busy -> "Watch is running \"$command\" (run ${runCount + 1})…"
            runCount == 0 -> "Watching $root for ${extensions.ifEmpty { listOf("*") }.joinToString(",")}; no run yet."
            else -> "Watching $root · ${lastChange.ifBlank { "no recent change" }} · last run " +
                "${lastExit ?: "?"} at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastRunAt))}"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun start(
        root: File,
        command: String,
        extensions: Set<String> = emptySet(),
        debounceMs: Long = WatchPolicy.DEFAULT_DEBOUNCE_MS,
        timeoutMs: Int = 300_000,
        maxFiles: Int = WatchPolicy.DEFAULT_MAX_FILES,
    ): String {
        require(root.isDirectory) { "Watch needs an existing workspace folder: ${root.path}" }
        require(command.isNotBlank()) { "Watch needs a command, for example ./gradlew test or pytest -q" }
        stop()
        val wanted = extensions.map { it.removePrefix(".").lowercase() }.toSet()
        job = scope.launch {
            var previous = WatchPolicy.index(WatchPolicy.collect(root, wanted, maxFiles))
            while (isActive) {
                delay(1_000)
                val current = WatchPolicy.collect(root, wanted, maxFiles)
                var changed = WatchPolicy.changed(previous, current)
                previous = WatchPolicy.index(current)
                if (changed.isEmpty()) continue
                _state.update { it.copy(lastChange = WatchPolicy.describe(changed)) }
                // Let a burst of saves settle before paying for a test run.
                var quiet = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - quiet < debounceMs) {
                    delay(250)
                    val again = WatchPolicy.collect(root, wanted, maxFiles)
                    val more = WatchPolicy.changed(previous, again)
                    if (more.isNotEmpty()) {
                        changed = more
                        previous = WatchPolicy.index(again)
                        quiet = System.currentTimeMillis()
                        _state.update { it.copy(lastChange = WatchPolicy.describe(more)) }
                    }
                }
                if (!isActive) break
                if (!WatchPolicy.shouldTrigger(changed, wanted)) continue
                _state.update { it.copy(busy = true, runCount = it.runCount + 1) }
                val result = runCatching { router.run(command, root, timeoutMs, maxOutput = 12_000) }
                _state.update { state ->
                    val failed = result.isFailure
                    state.copy(
                        busy = false,
                        lastRunAt = System.currentTimeMillis(),
                        lastExit = if (failed) -1 else result.getOrNull()?.exitCode,
                        lastResult = if (failed) {
                            "Command failed to start: " + (result.exceptionOrNull()?.message ?: "unknown error")
                        } else {
                            val run = result.getOrNull()
                            val body = (run?.rawOutput.orEmpty().trim() + "\n" + run?.rawStderr.orEmpty().trim()).trim()
                            body.lineSequence().filter { it.isNotBlank() }.toList().takeLast(12).joinToString("\n").ifBlank { "(no output)" }
                        },
                    )
                }
            }
        }
        _state.value = State(running = true, root = root.path, command = command, extensions = wanted.sorted())
        return "Watching ${root.path} for ${wanted.ifEmpty { setOf("*") }.joinToString(",")}. " +
            "Changes run: $command (starts after ${debounceMs}ms quiet)."
    }

    @Synchronized
    fun stop(): Boolean {
        val was = job != null
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, busy = false) }
        return was
    }

    /** Runs the configured command once, outside the poll loop. */
    suspend fun runOnce(root: File, command: String, timeoutMs: Int = 300_000): String {
        val result = router.run(command, root, timeoutMs, maxOutput = 12_000)
        val body = (result.rawOutput.trim() + "\n" + result.rawStderr.trim()).trim()
        _state.update { it.copy(lastRunAt = System.currentTimeMillis(), lastExit = result.exitCode, lastResult = body.take(4_000)) }
        return "exit ${result.exitCode}" + (result.note?.let { " · $it" } ?: "") + "\n" + body
    }
}
