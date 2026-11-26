package com.androidharness.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MissionBuildChainTest {

    private class FakeSideEffects : MissionSideEffects {
        val met = mutableListOf<String>()
        val built = mutableListOf<String>()
        override suspend fun onMet(mission: MissionState): String? { met += mission.id; return null }
        override suspend fun onBuilt(mission: MissionState, apkPath: String?) { built += mission.id }
    }

    @Test fun `a build hint produces an install open chain once`() = runBlocking {
        val se = FakeSideEffects()
        val steps = MissionBuildChain.plan("build:install:open", "/proj")
        assertNotNull(steps)
        assertTrue(steps!!.install)
        assertTrue(steps.open)
        assertTrue(steps.crashCheck)
        assertEquals("/proj", steps.project)
    }

    @Test fun `plain text produces no build chain`() {
        assertNull(MissionBuildChain.plan("fix login", "/proj"))
        assertNull(MissionBuildChain.plan("build", "/proj"))
    }

    @Test fun `chain is idempotent by mission id and turn`() = runBlocking {
        val se = FakeSideEffects()
        val seen = mutableSetOf<String>()
        val key = MissionBuildChain.idempotencyKey("mission-1", 3)
        assertTrue(seen.add(key))
        assertFalse(seen.add(key))
        assertEquals("mission-1:3", key)
    }
}
