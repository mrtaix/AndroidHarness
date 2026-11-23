package com.androidharness.app.data

import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.data.env.ShellRunResult
import java.io.File
import java.io.StringBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Ubuntu on demand through `proot-distro`, which the Linux environment already
 * ships. A container lives in the prefix, so it survives app updates and needs
 * no Android root: proot maps the guest root user onto the app uid.
 */
class ProotManager(
    private val router: ShellTierRouter,
    private val linuxEnv: com.androidharness.app.data.env.LinuxEnvironmentManager
) {
    data class State(val distros: List<String>, val raw: String)

    suspend fun status(cwd: File): State {
        val result = router.run("proot-distro list", cwd, timeoutMs = 60_000, maxOutput = 8_000)
        val text = (result.rawOutput + "\n" + result.rawStderr).trim()
        return State(ProotDistroList.parse(text), text)
    }

    /** Downloads and unpacks a distribution; the first run is a few hundred MB. */
    suspend fun install(distro: String, cwd: File, timeoutMs: Int = 1_500_000): String {
        require(distro.matches(Regex("[a-z0-9][a-z0-9.:-]{1,40}"))) {
            "Distribution names look like ubuntu, ubuntu:24.04 or debian; got \"$distro\""
        }
        // Use the app's shell environment which has the correct PATH to proot-distro
        val result = runCatching {
            val builder = linuxEnv.shellProcessBuilder("proot-distro install ${ProotCommand.quote(distro)}")
            builder.directory(cwd)
            builder.redirectErrorStream(true)
            builder.environment().putAll(linuxEnv.processEnv())
            builder.start().also { proc ->
                val stdout = StringBuffer()
                val stderr = StringBuffer()
                val out = thread { gobble(proc.inputStream, stdout, 12_000) }
                val err = thread { gobble(proc.errorStream, stderr, 12_000) }
                listOf(out, err).forEach { it.isDaemon = true; it.start() }
                val exitCode = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                    .getOrElse { proc.destroyForcibly(); false }
                ShellRunResult(
                    exitCode = proc.exitValue(),
                    timedOut = !exitCode,
                    rawOutput = stdout.toString(),
                    rawStderr = stderr.toString(),
                    tier = ExecutionTier.APP_LINUX,
                    note = null
                )
            }
        }
        val result = result.getOrElse { ShellRunResult(-1, false, "", it.message.orEmpty(), ExecutionTier.APP_LINUX, "Failed to start proot-distro") }
        val text = (result.rawOutput + "\n" + result.rawStderr).trim()
        return if (result.exitCode == 0 || text.contains("already exists", ignoreCase = true)) {
            "Installed $distro.\n" + text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).joinToString("\n")
        } else {
            "Install of $distro failed (exit ${result.exitCode}" + (if (result.timedOut) ", timed out" else "") + ").\n$text"
        }
    }

    /** Runs one command inside the container, with the workspace and shared storage bound in. */
    suspend fun run(
        distro: String,
        command: String,
        cwd: File,
        binds: List<String> = emptyList(),
        timeoutMs: Int = 300_000,
        maxOutput: Int = 24_000,
    ): String {
        require(command.isNotBlank()) { "Give the command to run inside $distro." }
        val rootfs = containerRootfs(distro, cwd)
        val line = if (rootfs != null) directCommand(rootfs, command, cwd, binds) else distroLogin(distro, command, cwd, binds)
        val result = router.run(line, cwd, timeoutMs, maxOutput)
        val output = (result.rawOutput.trimEnd() + if (result.rawStderr.isNotBlank()) "\n" + result.rawStderr.trimEnd() else "")
        return buildString {
            append("exit ").append(result.exitCode)
            result.note?.let { append(" · ").append(it) }
            append(" · ").append(distro).append('\n')
            append(output.ifBlank { "(no output)" })
        }
    }

    /**
     * proot-distro sanitises the environment, which on this device strips the
     * library path proot itself needs; calling proot directly keeps the harness
     * environment intact. The rootfs layout is `<prefix>/var/lib/proot-distro/
     * containers/<name>/rootfs`.
     */
    private fun directCommand(rootfs: File, command: String, cwd: File, binds: List<String>): String {
        val allBinds = bindList(cwd, binds)
        val tmp = System.getenv("HARNESS_SCRATCH") ?: rootfs.parentFile?.absolutePath ?: "/tmp"
        return "PROOT_TMP_DIR=" + ProotCommand.quote(tmp) + " PROOT_NO_SECCOMP=1 " +
            ProotCommand.shell(
                proot = "proot",
                rootfs = rootfs.absolutePath,
                workdir = "/root",
                binds = allBinds,
                uidZero = true,
                commandLine = command,
            )
    }

    private fun distroLogin(distro: String, command: String, cwd: File, binds: List<String>): String {
        val allBinds = bindList(cwd, binds).joinToString(" ") { "--bind ${ProotCommand.quote(it)}" }
        return "proot-distro login ${ProotCommand.quote(distro)} $allBinds -- " +
            ProotCommand.quote("/bin/bash") + " " + ProotCommand.quote("-lc") + " " + ProotCommand.quote(command)
    }

    private fun bindList(cwd: File, binds: List<String>): List<String> =
        (listOf(cwd.path, "/storage/emulated/0") + binds).distinct().filter { File(it).exists() }

    /** The container root proot needs, when proot-distro already unpacked one. */
    fun containerRootfs(distro: String, cwd: File): File? {
        val name = distro.substringBefore(':')
        val candidates = listOf(
            File(System.getenv("PREFIX").orEmpty(), "var/lib/proot-distro/containers/$name/rootfs"),
            File(cwd, ".proot-distro/$name/rootfs"),
        )
        return candidates.firstOrNull { File(it, "etc/os-release").isFile || File(it, "bin").isDirectory }
    }
}
