package com.ctharness.app.lsp

import java.io.File

/** A language server this app knows how to start; [markers] say when it is relevant. */
data class ServerCandidate(
    val id: String,
    val command: String,
    val args: List<String> = emptyList(),
    val extensions: Map<String, String>,
    val markers: List<String> = emptyList(),
)

/**
 * Finds the language servers already installed in the Linux prefix and checks
 * whether the project actually uses that language, so a plain clone gets
 * completion and diagnostics without a hand-written server entry.
 */
object LanguageServerCatalog {
    val SERVERS: List<ServerCandidate> = listOf(
        ServerCandidate(
            id = "kotlin", command = "kotlin-language-server",
            extensions = mapOf("kt" to "kotlin", "kts" to "kotlin"),
            markers = listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle"),
        ),
        ServerCandidate(
            id = "pyright", command = "pyright-langserver", args = listOf("--stdio"),
            extensions = mapOf("py" to "python", "pyi" to "python"),
            markers = listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "tox.ini"),
        ),
        ServerCandidate(
            id = "typescript", command = "typescript-language-server", args = listOf("--stdio"),
            extensions = mapOf("ts" to "typescript", "tsx" to "typescriptreact", "js" to "javascript", "jsx" to "javascriptreact",
                "mjs" to "javascript", "cjs" to "javascript", "json" to "json", "vue" to "vue", "svelte" to "svelte"),
            markers = listOf("package.json", "tsconfig.json", "jsconfig.json"),
        ),
        ServerCandidate(
            id = "clangd", command = "clangd", args = listOf("--background-index"),
            extensions = mapOf("c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp"),
            markers = listOf("compile_commands.json", "CMakeLists.txt", "meson.build", "Makefile"),
        ),
        ServerCandidate(
            id = "gopls", command = "gopls",
            extensions = mapOf("go" to "go", "mod" to "go.mod"),
            markers = listOf("go.mod", "go.sum"),
        ),
        ServerCandidate(
            id = "rust", command = "rust-analyzer",
            extensions = mapOf("rs" to "rust"),
            markers = listOf("Cargo.toml", "Cargo.lock"),
        ),
        ServerCandidate(
            id = "bash", command = "bash-language-server", args = listOf("start"),
            extensions = mapOf("sh" to "shellscript", "bash" to "shellscript", "zsh" to "shellscript"),
            markers = listOf(".shellcheckrc", "Makefile"),
        ),
    )

    private val NODE_WRAPPERS = setOf(".js", ".cjs", ".mjs")
    private val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", ".gradle", ".idea", "dist", "out", "__pycache__",
        ".venv", "venv", ".mypy_cache", ".pytest_cache", "target", ".harness",
    )
    private const val SCAN_LIMIT = 600

    /** Executables in the prefix's `bin`, which is how this app installs packages. */
    fun executable(prefix: File, command: String): File? {
        val bin = File(prefix, "bin")
        val direct = File(bin, command)
        if (direct.isFile) return direct
        for (suffix in NODE_WRAPPERS) {
            val wrapper = File(bin, command + suffix)
            if (wrapper.isFile) return wrapper
        }
        val lib = File(prefix, "lib/$command/bin/$command")
        return lib.takeIf { it.isFile }
    }

    fun relevant(candidate: ServerCandidate, root: File): Boolean {
        if (candidate.markers.any { File(root, it).exists() }) return true
        val wanted = candidate.extensions.keys.map { it.lowercase() }.toSet()
        if (wanted.isEmpty()) return true
        var seen = 0
        return root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .any { file ->
                if (!file.isFile) return@any false
                seen++
                if (seen > SCAN_LIMIT) return@any true
                file.extension.lowercase() in wanted
            }
    }

    /**
     * Servers worth starting for [root]: the binary exists in [prefix] and the
     * project looks like it uses that language. Empty when nothing is installed,
     * which keeps the tool out of the registry instead of failing on every call.
     */
    fun detect(prefix: File, root: File): List<ServerCandidate> =
        SERVERS.filter { executable(prefix, it.command) != null && relevant(it, root) }
}
