package com.androidharness.app.lsp

import com.androidharness.app.config.ConfigLanguageServer
import java.io.File

/**
 * Turns the discovered servers into the config the existing `lsp` tool expects.
 * A project that declares its own servers always wins; this only fills the
 * empty case.
 */
object LanguageServerDiscovery {
    fun configs(prefix: File, root: File): List<ConfigLanguageServer> =
        LanguageServerCatalog.detect(prefix, root).map { candidate ->
            ConfigLanguageServer(
                name = "discovered ${candidate.id}",
                plugin = "auto",
                command = candidate.command,
                args = candidate.args,
                extensions = candidate.extensions,
                env = emptyMap(),
                root = root.absolutePath,
            )
        }
}
