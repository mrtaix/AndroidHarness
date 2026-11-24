package com.androidharness.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChiefToolLogicTest {

    private class FakeDelegate : ChiefDelegate {
        val spawns = mutableListOf<SpawnRequest>()
        val stops = mutableListOf<String>()
        val waits = mutableListOf<Pair<String, Int>>()

        override suspend fun spawn(request: SpawnRequest): String {
            spawns += request
            return "task-${spawns.size}"
        }

        override suspend fun waitFor(delegateId: String, timeoutSeconds: Int): String? {
            waits += delegateId to timeoutSeconds
            return "result from $delegateId"
        }

        override suspend fun stop(delegateId: String) {
            stops += delegateId
        }
    }

    private fun setup(): Triple<ChiefTree, FakeDelegate, ChiefToolLogic> {
        val tree = ChiefTree()
        tree.plan("ship feature")
        val delegate = FakeDelegate()
        return Triple(tree, delegate, ChiefToolLogic(tree, null, delegate))
    }

    @Test fun `chief_assign forwards acceptance criteria into subagent prompt`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "lead", "do work", listOf("tests pass"), listOf("src/a"))
        val request = delegate.spawns.single()
        assertTrue(request.prompt.contains("tests pass"))
        assertEquals("task-1", tree.get(node)?.delegateId)
    }

    @Test fun `assign emits snapshot to listeners`() = runBlocking {
        val tree = ChiefTree()
        val root = tree.plan("ship feature")
        var latest: ChiefSnapshot? = null
        val logic = ChiefToolLogic(tree, null, FakeDelegate(), onChange = { latest = it })
        logic.assign(root.id, "worker", "do work", emptyList(), listOf("src/w"))
        assertEquals(2, latest?.nodes?.size)
    }

    @Test fun `finished delegate report attaches to its node`() = runBlocking {
        val (tree, _, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", emptyList(), listOf("src/w"))
        val attached = logic.attachFinished("task-1", "tests passed")
        assertEquals(node, attached?.id)
        assertEquals(ChiefStatus.REVIEW, tree.get(node)?.status)
        assertEquals("tests passed", tree.get(node)?.report)
    }

    @Test fun `chief_status renders indentation by depth`() = runBlocking {
        val (tree, _, logic) = setup()
        val root = tree.all().first().id
        val lead = logic.assign(root, "lead", "do work", emptyList(), listOf("src/lead"))
        logic.assign(lead, "worker", "do work", emptyList(), listOf("src/worker"))
        val text = logic.status()
        assertTrue(text.contains("  lead"))
        assertTrue(text.contains("    worker"))
    }

    @Test fun `chief_review reject does not create a new subagent`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", emptyList(), listOf("src/w"))
        tree.complete(node, "draft")
        logic.review(node, approved = false, feedback = "fix X")
        assertEquals(1, delegate.spawns.size)
        assertEquals(listOf("fix X"), tree.get(node)?.feedback)
    }

    @Test fun `chief_stop cascades before delegating stop`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val lead = logic.assign(root, "lead", "do work", emptyList(), listOf("src/lead"))
        val worker = logic.assign(lead, "worker", "do work", emptyList(), listOf("src/worker"))
        logic.stop(lead)
        assertTrue(delegate.stops.contains("task-1"))
        assertTrue(delegate.stops.contains("task-2"))
        assertEquals(ChiefStatus.CANCELLED, tree.get(worker)?.status)
    }

    @Test fun `chief_merge refuses unreviewed node`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", emptyList(), listOf("src/w"))
        tree.complete(node, "draft")
        assertNotNull(runCatching { logic.merge(node) }.exceptionOrNull())
        assertTrue(delegate.stops.isEmpty())
    }
}
