package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MissionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = MissionStore(tmp.root)

    private fun mission(id: String) = MissionState(
        id = id,
        goal = "fix login",
        source = MissionSource.ISSUE_URL,
        status = MissionStatus.RUNNING,
        branch = "mission/$id",
        sessionId = "sid-$id",
        turns = 3,
        costUsd = 0.42,
        elapsedMs = 120_000,
        retries = 1,
        watchdogRetries = 1,
        lastReason = "503",
        prUrl = "https://github.com/o/r/pull/1",
        checkCommand = "make test",
        checkpoint = MissionCheckpoint("tail", "diff"),
        limits = MissionLimits(90, 1.5, 60_000, 6),
    )

    @Test fun `save then load preserves mission`() {
        val store = store()
        val saved = mission("m1")
        store.save(saved)
        val loaded = store.load("m1")!!
        assertEquals(saved, loaded)
    }

    @Test fun `list returns saved missions sorted`() {
        val store = store()
        store.save(mission("m2"))
        store.save(mission("m1"))
        assertEquals(listOf("m1", "m2"), store.list().map { it.id })
    }

    @Test fun `delete removes one mission`() {
        val store = store()
        store.save(mission("m1"))
        store.delete("m1")
        assertNull(store.load("m1"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun `save uses atomic rename`() {
        val store = store()
        store.save(mission("m1"))
        assertFalse(File(tmp.root, "m1.json.tmp").exists())
        assertTrue(File(tmp.root, "m1.json").exists())
    }

    @Test fun `corrupt file is ignored`() {
        File(tmp.root, "bad.json").writeText("{not json")
        assertTrue(store().list().isEmpty())
    }
}
