package com.ctharness.app.data

/**
 * Builds the `proot` command line for a rootfs. Kept free of Android types so
 * the argument shape (bind order, quoting, uid flag) can be unit tested.
 */
object ProotCommand {
    /** Paths a guest shell expects to see; a missing host path is skipped by proot. */
    val DEFAULT_BINDS = listOf("/dev", "/proc", "/sys", "/storage", "/sys/fs/selinux")

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * argv form, for [ProcessBuilder]. [uidZero] maps the guest root user to the
     * app uid, which is what lets `apt` write into the rootfs without real root.
     */
    fun argv(
        proot: String,
        rootfs: String,
        workdir: String = "/root",
        binds: List<String> = DEFAULT_BINDS,
        uidZero: Boolean = true,
        command: List<String> = listOf("/bin/bash", "-l"),
        guestRoot: String? = null,
    ): List<String> {
        val argv = ArrayList<String>()
        argv += proot
        if (uidZero) argv += listOf("-0")
        argv += listOf("-r", rootfs)
        if (workdir.isNotBlank()) argv += listOf("-w", workdir)
        guestRoot?.let { argv += listOf("-b", "$it:/root") }
        binds.filter { it.isNotBlank() }.forEach { argv += listOf("-b", it) }
        argv += command
        return argv
    }

    /** One shell string, quoted for `sh -c`, so it can go through the shell tier. */
    fun shell(
        proot: String,
        rootfs: String,
        workdir: String = "/root",
        binds: List<String> = DEFAULT_BINDS,
        uidZero: Boolean = true,
        commandLine: String = "bash -l",
        guestRoot: String? = null,
    ): String = argv(proot, rootfs, workdir, binds, uidZero, listOf("/bin/sh", "-lc", commandLine), guestRoot)
        .joinToString(" ") { quote(it) }

    /** Rootfs tarballs carry a top-level directory; the manager strips it so paths stay predictable. */
    fun stripComponents(tarball: String): Int = 1

    fun looksLikeRootfs(entry: String): Boolean =
        entry.startsWith("./bin/") || entry.startsWith("bin/") || entry.startsWith("./usr/") || entry.startsWith("usr/")
}
