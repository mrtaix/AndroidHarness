package com.androidharness.app.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanguageServerCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    private fun prefix(vararg bins: String): java.io.File {
        val prefix = temp.newFolder("prefix")
        val bin = java.io.File(prefix, "bin").apply { mkdirs() }
        bins.forEach { java.io.File(bin, it).writeText("#!/bin/sh\n") }
        return prefix
    }

    @Test fun `only installed and relevant servers are detected`() {
        val prefix = prefix("pyright-langserver", "clangd")
        val root = temp.newFolder("project")
        java.io.File(root, "pyproject.toml").writeText("[project]\nname='x'\n")
        val detected = LanguageServerCatalog.detect(prefix, root).map { it.id }
        assertEquals(listOf("pyright"), detected)
    }

    @Test fun `a source file is enough when no marker exists`() {
        val prefix = prefix("typescript-language-server")
        val root = temp.newFolder("hastscript")
        java.io.File(root, "main.ts").writeText("export const x = 1")
        assertEquals(listOf("typescript"), LanguageServerCatalog.detect(prefix, root).map { it.id })
    }

    @Test fun `node wrappers and nested binaries count as installed`() {
        val prefix = prefix("pyright-langserver.js")
        assertTrue(LanguageServerCatalog.executable(prefix, "pyright-langserver")!!.name.endsWith(".js"))
        val nested = temp.newFolder("nested")
        java.io.File(nested, "lib/gopls/bin").mkdirs()
        java.io.File(nested, "lib/gopls/bin/gopls").writeText("x")
        assertEquals("gopls", LanguageServerCatalog.executable(nested, "gopls")!!.name)
        assertNull(LanguageServerCatalog.executable(prefix, "kotlin-language-server"))
    }

    @Test fun `nothing installed means no servers`() {
        val empty = temp.newFolder("empty")
        val root = temp.newFolder("anything")
        assertTrue(LanguageServerCatalog.detect(empty, root).isEmpty())
        assertFalse(LanguageServerCatalog.relevant(
            ServerCandidate("kotlin", "kotlin-language-server", extensions = mapOf("kt" to "kotlin"), markers = listOf("build.gradle.kts")),
            root,
        ))
    }
}
