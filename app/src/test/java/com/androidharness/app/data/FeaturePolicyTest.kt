package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturePolicyTest {
    private val full = FeatureSignals(ramGb = 8, batteryPercent = 90, charging = true,
        installed = FeatureKey.entries.toSet())

    @Test fun `off always wins and on always runs`() {
        FeatureKey.entries.forEach { key ->
            assertFalse(FeaturePolicy.enabled(key, FeatureSwitch.OFF, full))
            assertTrue(FeaturePolicy.enabled(key, FeatureSwitch.ON, full))
        }
    }

    @Test fun `automatic keeps download-backed features off until installed`() {
        val fresh = full.copy(installed = emptySet())
        assertFalse(FeaturePolicy.enabled(FeatureKey.PROOT, FeatureSwitch.AUTO, fresh))
        assertFalse(FeaturePolicy.enabled(FeatureKey.LSP, FeatureSwitch.AUTO, fresh))
        assertFalse(FeaturePolicy.enabled(FeatureKey.DAP, FeatureSwitch.AUTO, fresh))
        assertTrue(FeaturePolicy.enabled(FeatureKey.PROOT, FeatureSwitch.AUTO, full))
    }

    @Test fun `automatic pauses heavy work on a low battery`() {
        val low = full.copy(batteryPercent = 12, charging = false)
        assertFalse(FeaturePolicy.enabled(FeatureKey.WATCH_MODE, FeatureSwitch.AUTO, low))
        assertFalse(FeaturePolicy.enabled(FeatureKey.LSP, FeatureSwitch.AUTO, low))
        // A forced-on switch is the user's decision, not the policy's.
        assertTrue(FeaturePolicy.enabled(FeatureKey.LSP, FeatureSwitch.ON, low))
    }

    @Test fun `watch mode runs while charging even on a low battery until the floor`() {
        assertTrue(FeaturePolicy.enabled(FeatureKey.WATCH_MODE, FeatureSwitch.AUTO, full.copy(batteryPercent = 18, charging = true)))
        assertFalse(FeaturePolicy.enabled(FeatureKey.WATCH_MODE, FeatureSwitch.AUTO, full.copy(batteryPercent = 14, charging = true)))
    }

    @Test fun `terminal sessions scale with memory and stop in the background on a low battery`() {
        assertEquals(4, FeaturePolicy.sessionLimit(FeatureSwitch.AUTO, full.copy(ramGb = 8)))
        assertEquals(1, FeaturePolicy.sessionLimit(FeatureSwitch.AUTO, full.copy(ramGb = 4)))
        assertEquals(0, FeaturePolicy.sessionLimit(FeatureSwitch.OFF, full))
        assertFalse(FeaturePolicy.enabled(FeatureKey.TERMINALS, FeatureSwitch.AUTO,
            full.copy(batteryPercent = 10), background = true))
        assertTrue(FeaturePolicy.enabled(FeatureKey.TERMINALS, FeatureSwitch.AUTO,
            full.copy(batteryPercent = 10), background = false))
    }

    @Test fun `status explains why automatic is idle`() {
        val missing = full.copy(installed = emptySet())
        assertEquals("Auto · needs download", FeaturePolicy.status(FeatureKey.LSP, FeatureSwitch.AUTO, missing))
        assertEquals("Auto · paused, battery 9%", FeaturePolicy.status(FeatureKey.WATCH_MODE, FeatureSwitch.AUTO,
            full.copy(batteryPercent = 9, charging = false)))
        assertEquals("Auto · running", FeaturePolicy.status(FeatureKey.HTTP_CLIENT, FeatureSwitch.AUTO, full))
        assertEquals("Off", FeaturePolicy.status(FeatureKey.DAP, FeatureSwitch.OFF, full))
    }

    @Test fun `only known ids parse from storage`() {
        assertEquals(FeatureKey.HTTP_CLIENT, FeatureKey.parse("http_client"))
        assertEquals(null, FeatureKey.parse("nope"))
        assertEquals(FeatureSwitch.AUTO, FeatureSwitch.parse(null))
        assertEquals(FeatureSwitch.OFF, FeatureSwitch.parse("off"))
    }
}
