package com.androidharness.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissionManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Starter : MissionRunStarter {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun start(mission: MissionState, prompt: String): String {
            calls += mission.id to prompt
            return "sid-${calls.size}"
        }
    }

    private class SideEffects : MissionSideEffects {
        var calls = 0
        override suspend fun onMet(mission: MissionState): String? {
            calls++
            return "https://github.com/o/r/pull/$calls"
        }
    }

    private fun manager(now: () -> Long = { 1_000L }): Pair<MissionManager, Starter> {
        val starter = Starter()
        return MissionManager(MissionStore(tmp.root), starter, SideEffects(), now = now) to starter
    }

    @Test fun `create saves running mission and starts it`() = runBlocking {
        val (manager, starter) = manager()
        val created = manager.create("fix login", MissionSource.TEXT, "make test", MissionLimits())
        assertEquals(MissionStatus.RUNNING, created.status)
        assertEquals("sid-1", created.sessionId)
        assertEquals(created, manager.list().single())
        assertEquals(1, starter.calls.size)
        assertTrue(starter.calls[0].second.contains("fix login"))
    }

    @Test fun `transient failure waits without calling starter again`() = runBlocking {
        val (manager, starter) = manager()
        val created = manager.create("fix login")
        manager.handleFailure(created.id, 503, "server error", "ApiException")
        val saved = manager.list().single()
        assertEquals(MissionStatus.WAITING_RETRY, saved.status)
        assertEquals(1, saved.retries)
        assertNotNull(saved.nextRetryAtMs)
        assertEquals(1, starter.calls.size)
        manager.retryNow(created.id)
        assertEquals(MissionStatus.RUNNING, manager.list().single().status)
        assertEquals(2, starter.calls.size)
    }

    @Test fun `permanent failure parks for user`() = runBlocking {
        val (manager, _) = manager()
        val created = manager.create("fix login")
        manager.handleFailure(created.id, 401, "unauthorized", "ApiException")
        assertEquals(MissionStatus.NEEDS_USER, manager.list().single().status)
    }

    @Test fun `network loss does not consume retry budget`() = runBlocking {
        val (manager, _) = manager()
        val created = manager.create("fix login")
        manager.networkLost(created.id)
        val waiting = manager.list().single()
        assertEquals(MissionStatus.WAITING_NETWORK, waiting.status)
        assertEquals(0, waiting.retries)
        manager.networkRestored(created.id)
        assertEquals(MissionStatus.RUNNING, manager.list().single().status)
    }

    @Test fun `met calls side effects once`() = runBlocking {
        val (manager, _) = manager()
        val created = manager.create("fix login")
        val effects = manager.sideEffects as SideEffects
        manager.markMet(created.id)
        manager.markMet(created.id)
        assertEquals(1, effects.calls)
        assertEquals("https://github.com/o/r/pull/1", manager.list().single().prUrl)
    }

    @Test fun `watchdog retries once then partial`() = runBlocking {
        val (manager, _) = manager()
        val created = manager.create("fix login")
        manager.watchdog(created.id)
        assertEquals(MissionStatus.WAITING_RETRY, manager.list().single().status)
        manager.retryNow(created.id)
        manager.watchdog(created.id)
        assertEquals(MissionStatus.PARTIAL, manager.list().single().status)
    }

    @Test fun `tick retries due missions and restores network`() = runBlocking {
        var clock = 1_000L
        val (manager, starter) = manager({ clock })
        val created = manager.create("fix login")
        manager.handleFailure(created.id, 503, "server error", "ApiException")
        assertEquals(MissionStatus.WAITING_RETRY, manager.list().single().status)
        clock = 200_000L
        manager.tick(online = true)
        assertEquals(MissionStatus.RUNNING, manager.list().single().status)
        assertEquals(2, starter.calls.size)
        manager.networkLost(created.id)
        manager.tick(online = true)
        assertEquals(MissionStatus.RUNNING, manager.list().single().status)
    }

    @Test fun `resumeAll restarts interrupted missions`() = runBlocking {
        val (manager, starter) = manager()
        val created = manager.create("fix login")
        manager.networkLost(created.id)
        manager.resumeAll()
        assertEquals(MissionStatus.RUNNING, manager.list().single().status)
        assertEquals(2, starter.calls.size)
    }
}
