package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChiefTreeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ChiefTreeStore(File(tmp.root, "tree.json"))

    @Test fun `save then load preserves tree`() {
        val tree = ChiefTree()
        val root = tree.plan("ship feature")
        val node = tree.assign(
            parentId = root.id,
            title = "lead",
            prompt = "do work",
            acceptance = listOf("tests pass"),
            scope = listOf("src/a"),
            budget = ChiefBudget(maxTokens = 1234),
        )
        val store = store()
        store.save(tree.snapshot())
        val loaded = store.load()!!
        val restored = ChiefTree(initial = loaded)
        assertEquals(2, restored.all().size)
        assertEquals(listOf("tests pass"), restored.get(node.id)?.acceptance)
        assertEquals(1234, restored.get(node.id)?.budget?.maxTokens)
        assertEquals(root.id, restored.get(node.id)?.parentId)
    }

    @Test fun `active nodes become interrupted after reload`() {
        val tree = ChiefTree()
        val root = tree.plan("ship feature")
        val node = tree.assign(root.id, "worker", "do work", scope = listOf("src/w"))
        val store = store()
        store.save(tree.snapshot())
        val loaded = store.load()!!
        assertEquals(ChiefStatus.INTERRUPTED, loaded.nodes.first { it.id == root.id }.status)
        assertEquals(ChiefStatus.INTERRUPTED, loaded.nodes.first { it.id == node.id }.status)
    }

    @Test fun `interrupted snapshot is persisted after reload`() {
        val tree = ChiefTree()
        tree.plan("ship feature")
        val store = store()
        store.save(tree.snapshot())
        store.load()
        assertTrue(File(tmp.root, "tree.json").readText().contains("INTERRUPTED"))
    }

    @Test fun `save uses atomic rename`() {
        val tree = ChiefTree()
        tree.plan("ship feature")
        val store = store()
        store.save(tree.snapshot())
        assertFalse(File(tmp.root, "tree.json.next").exists())
        assertTrue(File(tmp.root, "tree.json").exists())
    }
}
