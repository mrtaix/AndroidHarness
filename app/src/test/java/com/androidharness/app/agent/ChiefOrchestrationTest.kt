package com.androidharness.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChiefOrchestrationTest {

    private fun tree(): ChiefTree = ChiefTree()
    private fun assign(tree: ChiefTree, parent: String, name: String, scope: String, role: ChiefRole = ChiefRole.WORKER): String =
        tree.assign(parentId = parent, title = name, prompt = "do $name", acceptance = listOf("done"), scope = listOf(scope), role = role).id

    @Test fun `assign rejects a fourth level`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val lead = assign(tree, root, "lead", "src/lead", ChiefRole.LEAD)
        val worker = assign(tree, lead, "worker", "src/worker")
        val err = runCatching { assign(tree, worker, "deep", "src/deep") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("worker"))
        assertEquals(3, tree.all().size)
    }

    @Test fun `worker cannot delegate`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val lead = tree.assign(root, "lead", "do lead", role = ChiefRole.LEAD, scope = listOf("src/lead")).id
        val worker = tree.assign(lead, "worker", "do work", role = ChiefRole.WORKER, scope = listOf("src/w")).id
        val err = runCatching {
            tree.assign(worker, "deep", "do more", role = ChiefRole.WORKER, scope = listOf("src/deep"))
        }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("worker"))
        assertEquals(3, tree.all().size)
    }

    @Test fun `only chief can create a lead`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val lead = tree.assign(root, "lead", "do lead", role = ChiefRole.LEAD, scope = listOf("src/lead")).id
        val err = runCatching {
            tree.assign(lead, "nested", "do more", role = ChiefRole.LEAD, scope = listOf("src/nested"))
        }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("role"))
    }

    @Test fun `assign rejects ninth live node`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        repeat(7) { assign(tree, root, "n$it", "src/n$it") }
        assertEquals(8, tree.all().size)
        val err = runCatching { assign(tree, root, "n8", "src/n8") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("limit"))
        assertEquals(8, tree.all().size)
    }

    @Test fun `overlapping scopes are rejected while both nodes are active`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val a = assign(tree, root, "a", "src/a")
        val err = runCatching { assign(tree, root, "b", "src/a/b") }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("scope"))
        tree.complete(a, "done")
        tree.review(a, approved = true)
        tree.merge(a)
        val b = assign(tree, root, "b", "src/a/b")
        assertEquals(b, tree.get(b)?.id)
    }

    @Test fun `rejected review resumes the same node with feedback`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val node = assign(tree, root, "worker", "src/w")
        tree.complete(node, "first draft")
        tree.review(node, approved = false, feedback = "fix X")
        val after = tree.get(node)!!
        assertEquals(ChiefStatus.RUNNING, after.status)
        assertEquals(1, after.retries)
        assertEquals(listOf("fix X"), after.feedback)
        assertEquals(2, tree.all().size)
    }

    @Test fun `third rejection fails the node`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val node = assign(tree, root, "worker", "src/w")
        tree.complete(node, "draft")
        tree.review(node, false, "one")
        tree.complete(node, "draft2")
        tree.review(node, false, "two")
        tree.complete(node, "draft3")
        tree.review(node, false, "three")
        assertEquals(ChiefStatus.FAILED, tree.get(node)?.status)
        assertNotNull(runCatching { tree.merge(node) }.exceptionOrNull())
    }

    @Test fun `merge requires approved review`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val node = assign(tree, root, "worker", "src/w")
        tree.complete(node, "done")
        assertNotNull(runCatching { tree.merge(node) }.exceptionOrNull())
        tree.review(node, approved = true)
        assertEquals(ChiefStatus.DONE, tree.merge(node).status)
    }

    @Test fun `stop cascades to descendants`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val lead = assign(tree, root, "lead", "src/lead", ChiefRole.LEAD)
        val worker = assign(tree, lead, "worker", "src/worker")
        tree.stop(lead)
        assertEquals(ChiefStatus.CANCELLED, tree.get(lead)?.status)
        assertEquals(ChiefStatus.CANCELLED, tree.get(worker)?.status)
        assertNotEquals(ChiefStatus.CANCELLED, tree.get(root)?.status)
    }

    @Test fun `budget overrun cancels node`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val node = assign(tree, root, "worker", "src/w")
        tree.markBudgetExceeded(node)
        assertEquals(ChiefStatus.CANCELLED, tree.get(node)?.status)
        assertNotNull(runCatching { tree.merge(node) }.exceptionOrNull())
    }

    @Test fun `parallel assigns keep unique ids and every node`() = runBlocking {
        val tree = ChiefTree()
        val root = tree.plan("ship feature").id
        val jobs = (0 until 7).map { i ->
            launch(Dispatchers.Default) {
                tree.assign(root, "n$i", "do $i", scope = listOf("src/n$i"))
            }
        }
        jobs.joinAll()
        assertEquals(8, tree.all().size)
        assertEquals(8, tree.all().map { it.id }.toSet().size)
    }

    @Test fun `status renders indentation by depth`() {
        val tree = tree()
        val root = tree.plan("ship feature").id
        val lead = assign(tree, root, "lead", "src/lead", ChiefRole.LEAD)
        val worker = assign(tree, lead, "worker", "src/worker")
        val text = tree.status()
        assertTrue(text.contains("ship feature"))
        assertTrue(text.contains("  lead"))
        assertTrue(text.contains("    worker"))
    }
}
