package com.androidharness.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ChiefScopeTest {
    private val fileScope = listOf(".harness/chief-scope-test.txt")

    @Test fun `empty scope allows everything`() {
        assertTrue(ChiefScope.allows(emptyList(), "AGENTS.md"))
        assertTrue(ChiefScope.allows(emptyList(), "/storage/emulated/0/HARNESS/AGENTS.md"))
    }

    @Test fun `file scope allows only that exact file`() {
        assertTrue(ChiefScope.allows(fileScope, ".harness/chief-scope-test.txt"))
        assertFalse(ChiefScope.allows(fileScope, "AGENTS.md"))
        assertFalse(ChiefScope.allows(fileScope, ".harness/chief-scope-outside.txt"))
    }

    @Test fun `absolute paths denied but root allowed for read-only tools`() {
        assertFalse(ChiefScope.allows(fileScope, "/storage/emulated/0/HARNESS/AGENTS.md"))
        assertTrue(ChiefScope.allows(fileScope, "."))
        assertFalse(ChiefScope.allows(fileScope, "C:/work/AGENTS.md"))
    }

    @Test fun `directory scope allows descendants`() {
        val scope = listOf("src/")
        assertTrue(ChiefScope.allows(scope, "src/a/A.kt"))
        assertFalse(ChiefScope.allows(scope, "README.md"))
    }

    @Test fun `root path is allowed only when tool is read-only`() {
        val rootArg = buildJsonObject { put("path", ".") }
        assertTrue(ChiefScope.allows(fileScope, rootArg, allowRoot = true))
        assertFalse(ChiefScope.allows(fileScope, rootArg, allowRoot = false))
        assertTrue(ChiefScope.allows(fileScope, "."))
        assertTrue(ChiefScope.allows(listOf("verify-pass"), "."))
    }

    @Test fun `bare directory name is a valid path scope`() {
        assertTrue(ChiefScope.isValid(listOf("verify-pass")))
        assertTrue(ChiefScope.allows(listOf("verify-pass"), "verify-pass/a.txt"))
        assertFalse(ChiefScope.allows(listOf("verify-pass"), "other/a.txt"))
    }

    @Test fun `invalid scope entries are rejected`() {
        assertFalse(ChiefScope.isValid(listOf("../escape")))
        assertFalse(ChiefScope.isValid(listOf("/absolute/path")))
        assertFalse(ChiefScope.isValid(listOf("")))
        assertFalse(ChiefScope.isValid(listOf(".")))
    }

    @Test fun `scoped workers are denied the shell tool entirely`() {
        assertEquals("Shell is disabled for scoped chief workers; use scoped file tools.",
            ChiefScope.deniesTool(listOf(".harness/x"), "shell"))
        assertNull(ChiefScope.deniesTool(listOf(".harness/x"), "read_file"))
        assertNull(ChiefScope.deniesTool(emptyList(), "shell"))
    }

    @Test fun `shell command path outside scope is denied`() {
        val scope = listOf(".harness/chief-scope-bypass.txt")
        val cmd = "printf BYPASS_SHELL_OK > .harness/chief-scope-bypass-shell.txt"
        assertFalse(ChiefScope.allows(scope, buildJsonObject { put("command", cmd) }))
        assertTrue(ChiefScope.allows(scope, buildJsonObject { put("command", "printf ok > .harness/chief-scope-bypass.txt") }))
    }

    @Test fun `scope checks all path-like arguments`() {
        val allowed: JsonObject = buildJsonObject { put("path", ".harness/chief-scope-test.txt") }
        val denied: JsonObject = buildJsonObject { put("path", "AGENTS.md") }
        assertTrue(ChiefScope.allows(fileScope, allowed))
        assertFalse(ChiefScope.allows(fileScope, denied))
    }
}
