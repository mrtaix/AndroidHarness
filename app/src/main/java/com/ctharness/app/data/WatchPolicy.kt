package com.ctharness.app.data

import java.io.File

/** One file as the watcher last saw it; size and mtime catch edits that keep the length. */
data class WatchedFile(val path: String, val size: Long, val modifiedMs: Long)

/**
 * The cheap half of watch mode: decide which files changed since the previous
 * poll and whether that change is worth a test run. Polling keeps it working on
 * SAF trees and inside a proot rootfs, where inotify is not available.
 */
object WatchPolicy {
    val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", "build", ".gradle", ".idea", "dist", "out",
        "__pycache__", ".venv", "venv", ".mypy_cache", ".pytest_cache", ".dart_tool", "target",
        ".harness", "Pods", ".next", ".cxx", ".kotlin",
    )

    const val DEFAULT_MAX_FILES = 4_000
    const val DEFAULT_DEBOUNCE_MS = 1_500L

    fun collect(
        root: File,
        extensions: Set<String> = emptySet(),
        maxFiles: Int = DEFAULT_MAX_FILES,
    ): List<WatchedFile> {
        if (!root.isDirectory) return emptyList()
        val wanted = extensions.map { it.removePrefix(".").lowercase() }.toSet()
        val files = ArrayList<WatchedFile>()
        fun visit(dir: File, depth: Int) {
            if (files.size >= maxFiles || depth > 12) return
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (files.size >= maxFiles) return
                if (entry.isDirectory) {
                    if (entry.name in SKIP_DIRS || entry.name.startsWith(".git")) continue
                    visit(entry, depth + 1)
                } else if (entry.isFile) {
                    if (wanted.isNotEmpty() && entry.extension.lowercase() !in wanted) continue
                    files += WatchedFile(relative(root, entry), entry.length(), entry.lastModified())
                }
            }
        }
        visit(root, 0)
        return files
    }

    fun relative(root: File, file: File): String =
        runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name)

    fun index(files: List<WatchedFile>): Map<String, WatchedFile> = files.associateBy { it.path }

    /** Paths added, removed or rewritten since [previous]; sorted so a message stays stable. */
    fun changed(previous: Map<String, WatchedFile>, current: List<WatchedFile>): List<String> {
        val next = index(current)
        val touched = ArrayList<String>()
        for ((path, file) in next) {
            val before = previous[path]
            if (before == null) { touched += path; continue }
            if (before.size != file.size || before.modifiedMs != file.modifiedMs) touched += path
        }
        for (path in previous.keys) if (path !in next) touched += path
        return touched.sorted()
    }

    /** A change matters when a matching source file moved; deletions count. */
    fun shouldTrigger(changed: List<String>, extensions: Set<String> = emptySet()): Boolean {
        if (changed.isEmpty()) return false
        if (extensions.isEmpty()) return true
        val wanted = extensions.map { it.removePrefix(".").lowercase() }.toSet()
        return changed.any { path ->
            val extension = path.substringAfterLast('.', "").lowercase()
            extension in wanted || !path.contains('.')
        }
    }

    /** Summarises a change list for the status line without dumping every path. */
    fun describe(changed: List<String>, limit: Int = 3): String {
        if (changed.isEmpty()) return "no changes"
        val head = changed.take(limit).joinToString(", ")
        return if (changed.size <= limit) head else "$head and ${changed.size - limit} more"
    }
}
