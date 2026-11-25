package com.androidharness.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChiefToolLogicTest {

    private class FakeDelegate : ChiefDelegate {
        val spawns = mutableListOf<SpawnRequest>()
        val stops = mutableListOf<String>()
        val waits = mutableListOf<Pair<String, Int>>()
        val resumes = mutableListOf<Pair<String, String>>()
        var verdict = ChiefVerdict(true, "ok")

        override suspend fun spawn(request: SpawnRequest): String {
            spawns += request
            return "task-${spawns.size}"
        }

        override suspend fun waitFor(delegateId: String, timeoutSeconds: Int): String? {
            waits += delegateId to timeoutSeconds
            return "result from $delegateId"
        }

        override suspend fun verify(node: ChiefNode): ChiefVerdict = verdict

        override suspend fun resume(node: ChiefNode, feedback: String): String {
            resumes += node.id to feedback
            return "task-resumed-${resumes.size}"
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

    @Test fun `assign forwards role and scope into the node`() = runBlocking {
        val (tree, _, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "lead", "do lead", emptyList(), listOf("src/lead"), role = "LEAD")
        assertEquals(ChiefRole.LEAD, tree.get(node)?.role)
        assertEquals(listOf("src/lead"), tree.get(node)?.scope)
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
        val lead = logic.assign(root, "lead", "do work", emptyList(), listOf("src/lead"), role = "LEAD")
        logic.assign(lead, "worker", "do work", emptyList(), listOf("src/worker"))
        val text = logic.status()
        assertTrue(text.contains("  lead"))
        assertTrue(text.contains("    worker"))
    }

    @Test fun `status for a node includes its descendants`() = runBlocking {
        val (tree, _, logic) = setup()
        val root = tree.all().first().id
        val lead = logic.assign(root, "lead", "do lead", emptyList(), listOf("src/lead"), role = "LEAD")
        logic.assign(lead, "worker", "do work", emptyList(), listOf("src/worker"))
        val text = logic.status(lead)
        assertTrue(text.contains("lead"))
        assertTrue(text.contains("  worker"))
    }

    @Test fun `approve with acceptance requires a PASS verification`() = runBlocking {
        val (tree, _, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", listOf("result must be 7"), listOf("src/w"))
        tree.complete(node, "SAI")
        val err = runCatching { logic.review(node, approved = true, evidence = "claims it matches") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("chief_verify"))
        assertEquals(ChiefStatus.REVIEW, tree.get(node)?.status)
    }

    @Test fun `verify stores verdict and allows approval only on PASS`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", listOf("result must be 7"), listOf("src/w"))
        tree.complete(node, "SAI")
        delegate.verdict = ChiefVerdict(false, "SAI is not 7")
        assertEquals(false, logic.verify(node).pass)
        assertEquals(ChiefStatus.REVIEW, tree.get(node)?.status)
        assertNotNull(runCatching { logic.review(node, approved = true) }.exceptionOrNull())
        delegate.verdict = ChiefVerdict(true, "7 confirmed")
        logic.verify(node)
        logic.review(node, approved = true)
        assertEquals(ChiefStatus.APPROVED, tree.get(node)?.status)
        assertEquals("7 confirmed", tree.get(node)?.verification?.reason)
        assertTrue(logic.status(node).contains("Verification: PASS"))
    }

    @Test fun `reject clears verification before resume`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", listOf("result must be 7"), listOf("src/w"))
        tree.complete(node, "7")
        logic.verify(node)
        tree.complete(node, "7 again")
        logic.review(node, approved = false, feedback = "try again")
        assertNull(tree.get(node)?.verification)
    }

    @Test fun `chief_review reject resumes the same node with feedback`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val node = logic.assign(root, "worker", "do work", emptyList(), listOf("src/w"))
        tree.complete(node, "draft")
        logic.review(node, approved = false, feedback = "fix X")
        assertEquals(1, delegate.spawns.size)
        assertEquals(listOf(node to "fix X"), delegate.resumes)
        assertEquals("task-resumed-1", tree.get(node)?.delegateId)
        assertEquals(ChiefStatus.RUNNING, tree.get(node)?.status)
        assertEquals(listOf("fix X"), tree.get(node)?.feedback)
    }

    @Test fun `chief_stop cascades before delegating stop`() = runBlocking {
        val (tree, delegate, logic) = setup()
        val root = tree.all().first().id
        val lead = logic.assign(root, "lead", "do work", emptyList(), listOf("src/lead"), role = "LEAD")
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
