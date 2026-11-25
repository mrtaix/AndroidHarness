package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class MissionBridgeTest {
    private val mission = MissionState("m1", "fix login", checkCommand = "make test")

    @Test fun `issue url parses owner repo and number`() {
        val parsed = MissionIssue.parse("https://github.com/Sanuu7/AndroidHarness/issues/125")
        assertEquals("Sanuu7", parsed.owner)
        assertEquals("AndroidHarness", parsed.repo)
        assertEquals(125, parsed.number)
    }

    @Test fun `bad issue url is rejected`() {
        assertNotNull(runCatching { MissionIssue.parse("https://github.com/Sanuu7/AndroidHarness/pull/125") }.exceptionOrNull())
        assertNotNull(runCatching { MissionIssue.parse("not a url") }.exceptionOrNull())
    }

    @Test fun `kickoff carries goal and check command`() {
        val prompt = MissionGoalBridge.kickoff(mission)
        assertTrue(prompt.contains("fix login"))
        assertTrue(prompt.contains("make test"))
    }

    @Test fun `resume carries last reason`() {
        val prompt = MissionGoalBridge.resume(mission, "503 rate limit")
        assertTrue(prompt.contains("fix login"))
        assertTrue(prompt.contains("503 rate limit"))
    }

    @Test fun `idempotency key comes from retry policy`() {
        assertEquals("m1:7", MissionGoalBridge.key(mission, 7))
    }

    @Test fun `issue body becomes goal text`() {
        val goal = MissionIssue.goal("Login fails on cold start", "Steps: open app, press login")
        assertTrue(goal.contains("Login fails on cold start"))
        assertTrue(goal.contains("Steps:"))
    }
}
