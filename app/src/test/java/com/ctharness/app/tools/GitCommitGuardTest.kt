package com.ctharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCommitGuardTest {
    private fun text(path: String, body: String) = GitCommitGuard.Fileish(path, body.length.toLong(), body)

    @Test fun `clean files pass`() {
        assertEquals(emptyList<String>(), GitCommitGuard.problems(listOf(text("Main.kt", "fun main() = println(\"hi\")"))))
    }

    @Test fun `credential shapes are named before they reach the remote`() {
        val cases = mapOf(
            "sk-abcdefghijklmnopqrstuvwx" to "OpenAI-style key",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789" to "GitHub token",
            "AKIAIOSFODNN7EXAMPLE" to "AWS access key",
            "AIzaSyA1234567890abcdefghijklmnopqrstuv" to "Google API key",
            "-----BEGIN OPENSSH PRIVATE KEY-----" to "private key block",
        )
        cases.forEach { (secret, label) ->
            val problems = GitCommitGuard.problems(listOf(text("config.txt", "value = $secret")))
            assertTrue("$label missing for $secret: $problems", problems.any { it.contains(label) })
        }
    }

    @Test fun `an env style assignment is flagged`() {
        assertTrue(GitCommitGuard.problems(listOf(text(".env", "SERVICE_API_KEY=abcdef123456\n")))
            .any { it.contains("credential-looking") })
    }

    @Test fun `oversized files are refused and binary skips the text scan`() {
        val big = GitCommitGuard.Fileish("assets/raw.bin", GitCommitGuard.MAX_BYTES + 1, null)
        assertTrue(GitCommitGuard.problems(listOf(big)).single().contains("limit 5 MB"))
        assertNull(GitCommitGuard.textOf(byteArrayOf(1, 0, 2)))
        assertEquals("hi", GitCommitGuard.textOf("hi".toByteArray()))
    }

    @Test fun `normal prose is not a false positive`() {
        assertEquals(emptyList<String>(), GitCommitGuard.problems(listOf(
            text("README.md", "Set the API key in Settings, never in the repository."),
            text("notes.txt", "sk- is a short prefix, not a key"),
        )))
    }
}
