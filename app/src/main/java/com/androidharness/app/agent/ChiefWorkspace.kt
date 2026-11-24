package com.androidharness.app.agent

import java.io.File

/** Prepares a scratch workspace containing only the paths a chief node may touch. */
object ChiefWorkspace {
    private const val DIR = "chief-worktrees"
    private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    private val SKIPPED = setOf(".git", "node_modules", ".gradle", "__pycache__")

    fun prepare(base: File, nodeId: String, source: File, scope: List<String>): File? {
        require(SAFE_ID.matches(nodeId)) { "unsafe node id: $nodeId" }
        if (scope.isEmpty()) return null
        val root = File(File(base, DIR), nodeId)
        if (root.exists()) root.deleteRecursively()
        check(root.mkdirs()) { "Could not create chief workspace $nodeId" }
        val sourceRoot = source.canonicalFile.toPath()
        scope.forEach { raw ->
            val relative = raw.trim('/')
            val path = sourceRoot.resolve(relative).normalize()
            require(path.startsWith(sourceRoot)) { "scope escapes workspace: $raw" }
            val scoped = path.toFile()
            if (!scoped.exists()) return@forEach
            if (scoped.isFile) {
                val out = File(root, relative)
                out.parentFile?.mkdirs()
                scoped.copyTo(out, overwrite = true)
            } else {
                scoped.walkTopDown()
                    .onEnter { it.name !in SKIPPED }
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = sourceRoot.relativize(file.canonicalFile.toPath()).toString()
                        val out = File(root, rel)
                        out.parentFile?.mkdirs()
                        file.copyTo(out, overwrite = true)
                    }
            }
        }
        return root
    }
}
