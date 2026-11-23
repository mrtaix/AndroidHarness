package com.androidharness.app.llm

import org.junit.Assert.*
import org.junit.Test

class OpenCodeFreeCatalogTest {
    private val catalog = """
        {"object":"list","data":[
          {"id":"big-pickle","name":"Big Pickle","capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},"limit":{"context":200000}},
          {"id":"deepseek-v4.1-flash-free","name":"DeepSeek V4.1 Flash Free","capabilities":{"tools":{"supported":true},"reasoning":{"supported":true,"effort":["low","high"]}},"limit":{"context":200000}},
          {"id":"claude-opus-5-5","name":"Claude Opus 5.5","capabilities":{"tools":{"supported":true}},"limit":{"context":200000}},
          {"id":"jev-1.13","name":"Jev","capabilities":{"tools":{"supported":true}}}
        ]}
    """.trimIndent()

    @Test fun `parses only keyless free models that support tools`() {
        val entries = OpenCodeFreeProvider.parseCatalog(catalog)
        assertEquals(listOf("big-pickle", "deepseek-v4.1-flash-free"), entries.map { it.id })
        assertEquals("DeepSeek V4.1 Flash Free", entries[1].displayName)
        assertEquals(200000L, entries[0].contextTokens)
        assertTrue(entries[0].reasoning == true)
        assertTrue(entries.all { it.note?.contains("keyless") == true })
    }

    @Test fun `rejects a catalog with no free models`() {
        val e = runCatching { OpenCodeFreeProvider.parseCatalog("""{"data":[{"id":"paid-only"}]}""") }.exceptionOrNull()
        assertNotNull(e)
    }

    @Test fun `injects CLI identity headers and never a global preset`() {
        val headers = OpenCodeFreeProvider.headers("session-1")
        assertEquals(OpenCodeFreeProvider.CLI_UA, headers.first { it.name == "User-Agent" }.value)
        assertEquals("cli", headers.first { it.name == "x-opencode-client" }.value)
        assertEquals("session-1", headers.first { it.name == "x-opencode-session" }.value)
        assertFalse(ProviderHeaders.editable(OpenCodeFreeProvider.config))
    }
}
