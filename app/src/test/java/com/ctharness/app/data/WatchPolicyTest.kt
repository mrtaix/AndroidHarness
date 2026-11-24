package com.ctharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `collect skips build folders and filters extensions`() {
        val root = temp.newFolder("repo")
        java.io.File(root, "README.md").writeText("x")
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Main.kt").writeText("fun main() {}")
        java.io.File(root, "build").mkdirs()
        java.io.File(root, "build/Generated.kt").writeText("generated")
        val files = WatchPolicy.collect(root, setOf("kt"))
        assertEquals(listOf("src/Main.kt"), files.map { it.path })
    }

    @Test fun `changed reports edits, additions and deletions`() {
        val before = listOf(WatchedFile("a.kt", 10, 1_000), WatchedFile("b.kt", 20, 2_000))
        val previous = WatchPolicy.index(before)
        val after = listOf(WatchedFile("a.kt", 11, 1_100), WatchedFile("c.kt", 5, 3_000))
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), WatchPolicy.changed(previous, after))
        assertEquals(emptyList<String>(), WatchPolicy.changed(WatchPolicy.index(after), after))
    }

    @Test fun `shouldTrigger respects the extension filter`() {
        assertTrue(WatchPolicy.shouldTrigger(listOf("src/A.kt"), setOf("kt")))
        assertFalse(WatchPolicy.shouldTrigger(listOf("src/A.kt"), setOf("py")))
        assertTrue(WatchPolicy.shouldTrigger(listOf("Makefile"), setOf("py")))
        assertFalse(WatchPolicy.shouldTrigger(emptyList(), setOf("kt")))
    }

    @Test fun `describe keeps the message short`() {
        assertEquals("no changes", WatchPolicy.describe(emptyList()))
        assertEquals("a, b", WatchPolicy.describe(listOf("a", "b")))
        assertEquals("a, b, c and 2 more", WatchPolicy.describe(listOf("a", "b", "c", "d", "e")))
    }
}
