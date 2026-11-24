package com.androidharness.app.llm

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdentityTest {
    @After fun reset() = ProviderIdentity.update(ProviderIdentity.DEFAULT, emptyList())

    @Test fun `default identity adds no request header`() {
        assertTrue(ProviderIdentity.headers(ProviderIdentity.DEFAULT, emptyList()).isEmpty())
    }

    @Test fun `presets present a coding client user agent`() {
        for (preset in ProviderIdentity.presets.filter { it.id !in setOf(ProviderIdentity.DEFAULT, ProviderIdentity.CUSTOM) }) {
            val header = ProviderIdentity.headers(preset.id, emptyList()).single()
            assertEquals("User-Agent", header.name)
            assertTrue(header.value.isNotBlank())
        }
        assertTrue(ProviderIdentity.presets.map { it.id }.distinct().size == ProviderIdentity.presets.size)
    }

    @Test fun `custom rows skip disabled and blank entries and trim names`() {
        val rows = listOf(
            ProviderHeader(" User-Agent ", "my-agent/1.0"),
            ProviderHeader("X-Client", "desktop"),
            ProviderHeader("X-Off", "no", enabled = false),
            ProviderHeader("X-Blank", "   "),
        )
        assertEquals(listOf("User-Agent" to "my-agent/1.0", "X-Client" to "desktop"),
            ProviderIdentity.headers(ProviderIdentity.CUSTOM, rows).map { it.name to it.value })
    }

    @Test fun `custom rows keep the provider header rules and unknown ids are rejected`() {
        assertTrue(ProviderIdentity.error(ProviderIdentity.CUSTOM, listOf(ProviderHeader("Authorization", "x"))) != null)
        assertNull(ProviderIdentity.error(ProviderIdentity.CUSTOM, listOf(ProviderHeader("User-Agent", "ok"))))
        assertTrue(ProviderIdentity.error("not-a-preset", emptyList()) != null)
    }

    @Test fun `the identity is applied first so a provider row can still win`() {
        ProviderIdentity.update("claude-code", emptyList())
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "model",
            headers = listOf(ProviderHeader("User-Agent", "provider-agent/2.0")))
        val request = ProviderHeaders.apply(
            Request.Builder().url("https://example.com/v1/chat/completions"), config,
        ).build()
        assertEquals("provider-agent/2.0", request.header("User-Agent"))
    }

    @Test fun `the preset user agent reaches a provider without its own header`() {
        ProviderIdentity.update("codex", emptyList())
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "model")
        val request = ProviderHeaders.apply(
            Request.Builder().url("https://example.com/v1/chat/completions"), config,
        ).build()
        assertEquals("codex_cli_rs/0.44.0 (Linux; x86_64)", request.header("User-Agent"))
    }
}
