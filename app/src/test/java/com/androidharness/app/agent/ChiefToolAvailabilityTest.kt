package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class ChiefToolAvailabilityTest {

    @Test fun `flag off exposes no chief tools`() {
        assertEquals(emptySet<String>(), ChiefToolAvailability.toolNames(enabled = false, depth = 0))
    }

    @Test fun `flag on exposes seven tools at root`() {
        assertEquals(7, ChiefToolAvailability.toolNames(enabled = true, depth = 0).size)
    }

    @Test fun `depth three keeps only status`() {
        assertEquals(setOf("chief_status"), ChiefToolAvailability.toolNames(enabled = true, depth = 3))
    }

    @Test fun `subagent never sees chief tools`() {
        assertFalse(ChiefToolAvailability.visibleToSubagent("chief_assign"))
        assertFalse(ChiefToolAvailability.visibleToSubagent("chief_status"))
        assertTrue(ChiefToolAvailability.visibleToSubagent("shell"))
    }

    @Test fun `nested delegation allowed under limit`() {
        assertTrue(ChiefToolAvailability.canDelegate(enabled = true, depth = 2))
        assertFalse(ChiefToolAvailability.canDelegate(enabled = true, depth = 3))
        assertFalse(ChiefToolAvailability.canDelegate(enabled = false, depth = 0))
    }
}
