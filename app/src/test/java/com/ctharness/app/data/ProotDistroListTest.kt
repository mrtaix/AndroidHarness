package com.ctharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotDistroListTest {
    @Test fun `an empty environment reports nothing`() {
        val text = "No containers are installed.\n\nInstall one with: proot-distro install ubuntu:24.04"
        assertEquals(emptyList<String>(), ProotDistroList.parse(text))
    }

    @Test fun `installed entries are read under their heading only`() {
        val text = """
            Supported distributions:
              ubuntu (24.04)
              debian (12)
            Installed distributions:
              ubuntu (24.04)
              alpine
        """.trimIndent()
        assertEquals(listOf("ubuntu", "alpine"), ProotDistroList.parse(text))
    }

    @Test fun `supported-only output does not count as installed`() {
        val text = """
            Supported distributions:
              ubuntu (24.04)
              debian (12)
        """.trimIndent()
        assertTrue(ProotDistroList.parse(text).isEmpty())
    }
}
