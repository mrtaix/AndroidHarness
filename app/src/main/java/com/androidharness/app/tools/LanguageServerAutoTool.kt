package com.androidharness.app.tools

import com.androidharness.app.lsp.LanguageServerDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The `lsp` tool when the project does not declare servers: it looks at the
 * installed tarball binaries and the files in the workspace, starts the server
 * that fits, and delegates to the normal path. Fails with an actionable message
 * when nothing is installed, instead of disappearing from the tool list.
 */
class LanguageServerAutoTool(
    private val prefix: () -> File,
    private val shell: (String) -> ProcessBuilder,
) : Tool {
    override val name = NAME
    override val description =
        "Ask the project's language servers about code: diagnostics, definition, references, hover, " +
            "completion or symbols. Servers are discovered from the Linux environment (clangd, pyright, " +
            "typescript-language-server, gopls, rust-analyzer, bash-language-server). Line and column are 1-based. " +
            "Install a missing server from Settings → Advanced features."
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(
        mapOf(
            "operation" to Schema.string("diagnostics, definition, references, hover, completion or symbols."),
            "path" to Schema.string("File path relative to the project."),
            "line" to Schema.integer("1-based line, for definition, references, hover and completion."),
            "column" to Schema.integer("1-based column, for definition, references, hover and completion."),
        ),
        required = listOf("operation", "path"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val root = ctx.workspace.shellRoot?.canonicalFile
            ?: return@withContext ToolResult(false, "Language servers need a project folder on this device.")
        val servers = runCatching { LanguageServerDiscovery.configs(prefix(), root) }.getOrElse { emptyList() }
        if (servers.isEmpty()) {
            return@withContext ToolResult(false,
                "No language server is installed for this project yet. Open Settings → Advanced features and " +
                    "install one (clangd, Pyright, or the TypeScript server); then call lsp again.")
        }
        LanguageServerTool(servers, shell).execute(args, ctx)
    }

    companion object {
        const val NAME = "lsp"
    }
}
