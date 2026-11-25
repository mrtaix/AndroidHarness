package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class MissionModelTest {
    private fun mission() = MissionState(
        id = "m1",
        goal = "fix login",
        source = MissionSource.TEXT,
        limits = MissionLimits(turnLimit = 100, maxCostUsd = 2.0, maxWallClockMs = 8 * 60 * 60 * 1000),
    )

    @Test fun `start moves queued mission to running`() {
        assertEquals(MissionStatus.RUNNING, mission().transition(MissionEvent.START).status)
    }

    @Test fun `network loss does not consume retry budget`() {
        val running = mission().transition(MissionEvent.START)
        val waiting = running.transition(MissionEvent.NETWORK_LOST)
        assertEquals(MissionStatus.WAITING_NETWORK, waiting.status)
        assertEquals(0, waiting.retries)
        assertEquals(MissionStatus.RUNNING,
            waiting.transition(MissionEvent.NETWORK_RESTORED).status)
    }

    @Test fun `transient failure waits and resume increments retry`() {
        val running = mission().transition(MissionEvent.START)
        val waiting = running.transition(MissionEvent.FAILURE(FailureKind.TRANSIENT, "timeout"))
        assertEquals(MissionStatus.WAITING_RETRY, waiting.status)
        assertEquals(1, waiting.retries)
        assertEquals(MissionStatus.RUNNING, waiting.transition(MissionEvent.RETRY_TICK).status)
    }

    @Test fun `permanent failure parks for user`() {
        val waiting = mission().transition(MissionEvent.START)
            .transition(MissionEvent.FAILURE(FailureKind.PERMANENT, "401"))
        assertEquals(MissionStatus.NEEDS_USER, waiting.status)
        assertEquals("401", waiting.lastReason)
        assertEquals(MissionStatus.RUNNING, waiting.transition(MissionEvent.USER_RESUME).status)
    }

    @Test fun `turn and cost ceilings end as partial`() {
        val byTurn = mission().copy(turns = 99).transition(MissionEvent.START)
            .transition(MissionEvent.TURN_DONE(elapsedMs = 1000, costUsd = 0.1))
        assertEquals(MissionStatus.PARTIAL, byTurn.status)
        val byCost = mission().copy(costUsd = 2.5).transition(MissionEvent.START)
            .transition(MissionEvent.TURN_DONE(elapsedMs = 1000, costUsd = 0.1))
        assertEquals(MissionStatus.PARTIAL, byCost.status)
    }

    @Test fun `watchdog retries once then partial`() {
        val once = mission().transition(MissionEvent.START).transition(MissionEvent.WATCHDOG)
        assertEquals(MissionStatus.WAITING_RETRY, once.status)
        assertEquals(1, once.watchdogRetries)
        val twice = once.transition(MissionEvent.RETRY_TICK).transition(MissionEvent.WATCHDOG)
        assertEquals(MissionStatus.PARTIAL, twice.status)
    }

    @Test fun `met is idempotent and records pr`() {
        val done = mission().transition(MissionEvent.START)
            .transition(MissionEvent.MET("https://github.com/o/r/pull/1"))
        assertEquals(MissionStatus.DONE, done.status)
        assertEquals("https://github.com/o/r/pull/1", done.prUrl)
        assertEquals(done, done.transition(MissionEvent.MET("other")))
    }
}
