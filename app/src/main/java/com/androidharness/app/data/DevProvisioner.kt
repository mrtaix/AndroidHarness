package com.androidharness.app.data

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The downloads the advanced features need, with one switchboard behind them:
 * tmux for panes, Pyright and tsserver for completion, debugpy for the
 * debugger, and an Ubuntu container for everything else. The app never hides
 * a missing download behind a failure: the settings card lists it and offers
 * the install button.
 */
class DevProvisioner(
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: ShellTierRouter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class Status { READY, MISSING, INSTALLING, FAILED }

    data class Payload(
        val id: String,
        val label: String,
        val status: Status,
        val detail: String = "",
        val action: String = "",
    )

    data class State(val loading: Boolean = true, val payloads: Map<String, Payload> = emptyMap()) {
        fun ordered(): List<Payload> = ids.mapNotNull { payloads[it] }
        fun missing(): List<Payload> = ordered().filter { it.status == Status.MISSING || it.status == Status.FAILED }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun refresh() {
        if (_state.value.payloads.values.any { it.status == Status.INSTALLING }) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            val payloads = LinkedHashMap<String, Payload>()
            payloads["clangd"] = binary("clangd", "clangd", "C/C++ completion")
            payloads["pyright"] = binary("pyright", "pyright-langserver", "Python completion")
            payloads["typescript"] = binary("typescript", "typescript-language-server", "TypeScript and JavaScript completion")
            payloads["tmux"] = binary("tmux", "tmux", "Panes inside one terminal")
            payloads["debugpy"] = pythonModule()
            payloads["ubuntu"] = distro()
            _state.update { it.copy(loading = false, payloads = payloads) }
        }
    }

    fun install(id: String) {
        val current = _state.value.payloads[id] ?: return
        if (current.status == Status.INSTALLING) return
        _state.update { state ->
            state.copy(payloads = state.payloads + (id to current.copy(status = Status.INSTALLING, detail = "Starting…")))
        }
        scope.launch {
            val outcome = runCatching { perform(id) }
            _state.update { state ->
                val done = outcome.getOrNull()
                val failed = outcome.exceptionOrNull()
                state.copy(payloads = state.payloads + (id to current.copy(
                    status = if (done != null && done.first) Status.READY else Status.FAILED,
                    detail = done?.second ?: (failed?.message ?: "Install failed"),
                )))
            }
            refresh()
        }
    }

    private suspend fun perform(id: String): Pair<Boolean, String> = when (id) {
        "clangd" -> installPackage("clang")
        "tmux" -> installPackage("tmux")
        "pyright" -> command("npm install -g pyright", "Pyright", 900_000)
        "typescript" -> command("npm install -g typescript typescript-language-server", "TypeScript language server", 900_000)
        "debugpy" -> command("python -m pip install --user debugpy", "debugpy", 900_000)
        "ubuntu" -> {
            // ProotManager already runs proot-distro through the shell router, which
            // is the tier that has the tool; installing from the app prefix would
            // report "command not found" (exit 127).
            val output = ProotManager(router).install("ubuntu", File("."))
            (output.startsWith("Installed") || output.contains("already installed", ignoreCase = true)) to output
        }
        else -> false to "Unknown payload \"$id\""
    }

    private suspend fun installPackage(name: String): Pair<Boolean, String> {
        var last = "Installing $name…"
        val result = linuxEnv.install(listOf(name), requester = "Developer tools") { status ->
            last = status.currentPackage ?: status.phase.name.lowercase()
        }
        return result.fold(
            onSuccess = { true to "$name is installed." },
            onFailure = { false to (it.message ?: last) },
        )
    }

    private fun command(line: String, label: String, timeoutMs: Int): Pair<Boolean, String> {
        if (!linuxEnv.isReady) return false to "The Linux environment is not installed yet."
        val process = runCatching { linuxEnv.shellProcessBuilder(line).redirectErrorStream(true).start() }
            .getOrElse { return false to (it.message ?: "Could not start $label") }
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { text -> if (output.length < 8_000) output.appendLine(text) } }
        }
        reader.isDaemon = true
        reader.start()
        val finished = runCatching { process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            return false to "$label timed out after ${timeoutMs / 60_000} min."
        }
        val exit = process.exitValue()
        val tail = output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n")
        return if (exit == 0) true to "$label installed."
        else false to "$label failed (exit $exit).\n$tail"
    }

    private fun binary(id: String, command: String, label: String): Payload {
        val bin = File(linuxEnv.prefix, "bin/$command")
        val ready = bin.isFile || File(linuxEnv.prefix, "bin/$command.js").isFile
        return if (ready) Payload(id, label, Status.READY, "Installed in the Linux environment.")
        else Payload(id, label, Status.MISSING, "Not installed yet.", action = "Install")
    }

    private fun pythonModule(): Payload {
        if (!linuxEnv.isReady) return Payload("debugpy", "Python debugger (debugpy)", Status.MISSING, "The Linux environment is not installed.")
        val probe = runCatching {
            val process = linuxEnv.shellProcessBuilder("python -c \"import debugpy;print(debugpy.__version__)\"")
                .redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val ok = process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
            ok to text
        }.getOrNull()
        return if (probe?.first == true) Payload("debugpy", "Python debugger (debugpy)", Status.READY, "Installed (${probe.second.take(20)}).")
        else Payload("debugpy", "Python debugger (debugpy)", Status.MISSING, "Not installed yet.", action = "Install")
    }

    private suspend fun distro(): Payload {
        if (!linuxEnv.isReady) return Payload("ubuntu", "Ubuntu container (proot)", Status.MISSING, "The Linux environment is not installed.")
        // ProotManager runs through the shell router, so the check must use the
        // same tier; the app's own prefix is a different install and may not have
        // proot-distro at all.
        val text = runCatching {
            val result = router.run("proot-distro list", File("."), timeoutMs = 60_000, maxOutput = 8_000)
            result.rawOutput + "\n" + result.rawStderr
        }.getOrDefault("")
        val names = ProotDistroList.parse(text)
        return when {
            names.isNotEmpty() -> Payload("ubuntu", "Ubuntu container (proot)", Status.READY, "Installed: " + names.joinToString(", "))
            text.contains("command not found", ignoreCase = true) ->
                Payload("ubuntu", "Ubuntu container (proot)", Status.MISSING,
                    "proot-distro will be installed first (about 400 MB in total).", action = "Install")
            else -> Payload("ubuntu", "Ubuntu container (proot)", Status.MISSING, "No container installed yet (about 400 MB).", action = "Install")
        }
    }

    companion object {
        val ids = listOf("tmux", "pyright", "typescript", "clangd", "debugpy", "ubuntu")
    }
}
