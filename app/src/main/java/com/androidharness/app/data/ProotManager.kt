package com.androidharness.app.data

import com.androidharness.app.data.env.ShellTierRouter
import java.io.File

/**
 * Ubuntu on demand through `proot-distro`, which the Linux environment already
 * ships. A container lives in the prefix, so it survives app updates and needs
 * no Android root: proot maps the guest root user onto the app uid.
 */
class ProotManager(private val router: ShellTierRouter) {
    data class State(val distros: List<String>, val raw: String)

    suspend fun status(cwd: File): State {
        val text = proot("proot-distro list", cwd, 60_000)
        return State(ProotDistroList.parse(text), text)
    }

    /**
     * proot-distro resolves its containers from $TERMUX__PREFIX. Only the
     * privileged shell tier sets that to the deployed prefix that actually
     * holds the containers; the app tier points it at its own (empty) prefix
     * and its 0700 directory is unreadable to the app uid. So prefer the shell
     * tier and fall back only when there is no privileged runner at all.
     */
    private suspend fun proot(command: String, cwd: File, timeoutMs: Int): String {
        val privileged = router.runPrivilegedNow(command, cwd, timeoutMs, 12_000)
        if (privileged != null) return privileged.rawOutput + "\n" + privileged.rawStderr
        val local = router.run(command, cwd, timeoutMs, 12_000)
        return local.rawOutput + "\n" + local.rawStderr
    }

    /** Downloads and unpacks a distribution; the first run is a few hundred MB. */
    suspend fun install(distro: String, cwd: File, timeoutMs: Int = 1_500_000): String {
        require(distro.matches(Regex("[a-z0-9][a-z0-9.:-]{1,40}"))) {
            "Distribution names look like ubuntu, ubuntu:24.04 or debian; got \"$distro\""
        }
        val text = proot("proot-distro install ${ProotCommand.quote(distro)}", cwd, timeoutMs).trim()
        val already = text.contains("already exists", ignoreCase = true)
        val ok = text.contains("Installed", ignoreCase = true) || text.contains("Unpacking", ignoreCase = true)
        return if (ok || already || text.isBlank()) {
            if (already) "Installed $distro (container already present)."
            else "Installed $distro.\n" + text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).joinToString("\n")
        } else {
            "Install of $distro failed.\n$text"
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
        // Run on the privileged shell tier: proot-distro needs TERMUX__PREFIX
        // pointing at the deployed prefix that holds the container. Use a direct
        // proot invocation when the rootfs is reachable (keeps the harness env),
        // otherwise fall back to proot-distro login.
        val rootfs = containerRootfs(distro, cwd)
        val line = if (rootfs != null) directCommand(rootfs, command, cwd, binds) else distroLogin(distro, command, cwd, binds)
        val privileged = router.runPrivilegedNow(line, cwd, timeoutMs, maxOutput)
        val result = privileged ?: router.run(line, cwd, timeoutMs, maxOutput)
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
        val rel = "var/lib/proot-distro/containers/$name/rootfs"
        // `proot-distro install` runs through the shell router: under the
        // privileged tier its PREFIX is the deployed copy, not this app's own
        // prefix. Check both so a container created by either tier is found.
        val deployed = "/data/local/tmp/androidharness/${appPackage()}/linux"
        val candidates = listOf(
            File(System.getenv("PREFIX").orEmpty(), rel),
            File(deployed, rel),
            File(cwd, ".proot-distro/$name/rootfs"),
        )
        return candidates.firstOrNull { File(it, "etc/os-release").isFile || File(it, "bin").isDirectory }
    }

    private fun appPackage(): String =
        System.getProperty("harness.package") ?: "com.androidharness.app.release"
}
