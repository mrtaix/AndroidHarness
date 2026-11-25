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

    @Test fun `absolute and root paths are denied under scope`() {
        assertFalse(ChiefScope.allows(fileScope, "/storage/emulated/0/HARNESS/AGENTS.md"))
        assertFalse(ChiefScope.allows(fileScope, "."))
        assertFalse(ChiefScope.allows(fileScope, "C:/work/AGENTS.md"))
    }

    @Test fun `directory scope allows descendants`() {
        val scope = listOf("src/")
        assertTrue(ChiefScope.allows(scope, "src/a/A.kt"))
        assertFalse(ChiefScope.allows(scope, "README.md"))
    }

    @Test fun `scope checks all path-like arguments`() {
        val allowed: JsonObject = buildJsonObject { put("path", ".harness/chief-scope-test.txt") }
        val denied: JsonObject = buildJsonObject { put("path", "AGENTS.md") }
        assertTrue(ChiefScope.allows(fileScope, allowed))
        assertFalse(ChiefScope.allows(fileScope, denied))
    }
}
