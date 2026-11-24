package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChiefWorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(): File {
        val root = tmp.newFolder("source")
        File(root, "src/a/A.kt").apply { parentFile.mkdirs(); writeText("a") }
        File(root, "src/b/B.kt").apply { parentFile.mkdirs(); writeText("b") }
        File(root, "README.md").writeText("readme")
        return root
    }

    @Test fun `scope copy keeps only scoped files`() {
        val scratch = ChiefWorkspace.prepare(tmp.root, "chief-1", source(), listOf("src/a"))
        assertTrue(File(scratch, "src/a/A.kt").exists())
        assertFalse(File(scratch, "src/b/B.kt").exists())
        assertFalse(File(scratch, "README.md").exists())
    }

    @Test fun `empty scope is not isolated`() {
        assertNull(ChiefWorkspace.prepare(tmp.root, "chief-1", source(), emptyList()))
    }

    @Test fun `unsafe node id is rejected`() {
        val err = runCatching { ChiefWorkspace.prepare(tmp.root, "../escape", source(), listOf("src/a")) }.exceptionOrNull()
        assertTrue(err?.message.orEmpty().contains("node id"))
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test fun `missing scope path yields empty scratch`() {
        val scratch = ChiefWorkspace.prepare(tmp.root, "chief-2", source(), listOf("src/missing"))!!
        assertTrue(scratch.isDirectory)
        assertEquals(0, scratch.walkTopDown().count() - 1)
    }
}
