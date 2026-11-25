package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class ChiefVerdictTest {
    @Test fun `parse PASS with reason`() {
        val verdict = ChiefVerdict.parse("PASS\nreport equals the required value")
        assertTrue(verdict.pass)
        assertEquals("report equals the required value", verdict.reason)
    }

    @Test fun `parse FAIL with reason`() {
        val verdict = ChiefVerdict.parse("FAIL\nworker answered SAI, expected 7")
        assertFalse(verdict.pass)
        assertEquals("worker answered SAI, expected 7", verdict.reason)
    }

    @Test fun `parse json shaped verdict`() {
        val verdict = ChiefVerdict.parse("{\"pass\": true, \"reason\": \"all criteria met\"}")
        assertTrue(verdict.pass)
        assertEquals("all criteria met", verdict.reason)
    }

    @Test fun `parse verdict inside prose and code fences`() {
        val verdict = ChiefVerdict.parse("Here is my judgement:\n```\nFAIL\nSAI does not equal 7\n```")
        assertFalse(verdict.pass)
        assertTrue(verdict.reason.contains("SAI"))
    }

    @Test fun `parse PASS without reason still yields a reason`() {
        val verdict = ChiefVerdict.parse("PASS")
        assertTrue(verdict.pass)
        assertTrue(verdict.reason.isNotBlank())
    }

    @Test fun `first line verdict wins over later mentions`() {
        val verdict = ChiefVerdict.parse("PASS\nThe word FAIL appears only in the reason")
        assertTrue(verdict.pass)
    }

    @Test fun `json pass false stays false when reason mentions PASS`() {
        val verdict = ChiefVerdict.parse("{\"pass\": false, \"reason\": \"PASS appears in the quote\"}")
        assertFalse(verdict.pass)
    }

    @Test fun `unparseable output becomes FAIL instead of an error`() {
        val verdict = ChiefVerdict.parse("")
        assertFalse(verdict.pass)
        assertTrue(verdict.reason.contains("parse"))
    }
}
