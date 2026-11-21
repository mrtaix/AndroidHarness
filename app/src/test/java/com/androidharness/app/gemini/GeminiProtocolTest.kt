package com.androidharness.app.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolTest {
    @Test fun `provider ids round-trip and only gemini ids match`() {
        val id = GeminiProtocol.providerId("abc-123")
        assertEquals("gemini:abc-123", id)
        assertTrue(GeminiProtocol.isProvider(id))
        assertEquals("abc-123", GeminiProtocol.accountId(id))
        assertFalse(GeminiProtocol.isProvider("chatgpt:abc-123"))
        assertFalse(GeminiProtocol.isProvider("harness"))
    }

    @Test fun `proof key matches the RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GeminiProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `verifiers are url safe and long enough`() {
        repeat(20) {
            val verifier = GeminiProtocol.verifier()
            assertTrue(verifier.length in 43..128)
            assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        }
        assertFalse(GeminiProtocol.verifier() == GeminiProtocol.verifier())
    }

    @Test fun `authorize url asks for offline code with a forced consent screen`() {
        val url = GeminiProtocol.authorizeUrl("http://127.0.0.1:5555/oauth2callback", "state-1", GeminiProtocol.verifier())
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id="))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        assertTrue(url.contains("state=state-1"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("cloud-platform"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5555%2Foauth2callback"))
    }

    @Test fun `the fallback project id is a stable uuid per account`() {
        val first = GeminiProtocol.stableProject("person@example.com")
        assertEquals(first, GeminiProtocol.stableProject("person@example.com"))
        assertTrue(first != GeminiProtocol.stableProject("other@example.com"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first))
    }

    @Test fun `the wire identity matches the antigravity cli`() {
        assertTrue(GeminiProtocol.USER_AGENT.startsWith("antigravity/"))
        assertTrue(GeminiProtocol.SCOPES.any { it.endsWith("/auth/aicode") })
        assertTrue(GeminiProtocol.SCOPES.any { it.endsWith("/auth/experimentsandconfigs") })
        assertTrue(GeminiProtocol.CODE_ASSIST.endsWith("daily-cloudcode-pa.googleapis.com"))
    }

    @Test fun `account model list is stable and non-empty`() {
        assertTrue(GeminiProtocol.ACCOUNT_MODELS.isNotEmpty())
        assertTrue(GeminiProtocol.ACCOUNT_MODELS.all { it.startsWith("gemini-") })
    }
}
