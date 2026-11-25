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

    @Test fun `unparseable verdict is rejected`() {
        assertNotNull(runCatching { ChiefVerdict.parse("looks fine to me") }.exceptionOrNull())
    }
}
