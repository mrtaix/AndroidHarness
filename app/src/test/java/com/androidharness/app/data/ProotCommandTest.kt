package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {
    @Test fun `argv carries the rootfs, workdir, binds and command`() {
        val argv = ProotCommand.argv("/p/proot", "/p/ubuntu", "/root", listOf("/dev", "/sdcard"), true, listOf("/bin/bash", "-l"))
        assertEquals("/p/proot", argv.first())
        assertTrue(argv.contains("-0"))
        assertEquals("/p/ubuntu", argv[argv.indexOf("-r") + 1])
        assertEquals("/root", argv[argv.indexOf("-w") + 1])
        assertEquals(2, argv.count { it == "-b" })
        assertEquals(listOf("/bin/bash", "-l"), argv.takeLast(2))
    }

    @Test fun `guest root bind and uid flag are optional`() {
        val argv = ProotCommand.argv("/p/proot", "/r", "", emptyList(), uidZero = false, command = listOf("ls"))
        assertFalse(argv.contains("-0"))
        assertFalse(argv.contains("-w"))
        assertEquals(listOf("/p/proot", "-r", "/r", "ls"), argv)
        val rooted = ProotCommand.argv("/p/proot", "/r", "/root", emptyList(), command = listOf("ls"), guestRoot = "/data/home")
        assertEquals("/data/home:/root", rooted[rooted.indexOf("-b") + 1])
    }

    @Test fun `shell form quotes spaces and single quotes`() {
        val line = ProotCommand.shell("/p/proot", "/data/ubuntu 24", commandLine = "echo 'hi there'")
        assertTrue(line.startsWith("'/p/proot'"))
        assertTrue(line.contains("'/data/ubuntu 24'"))
        assertTrue(line.contains("'echo '\\''hi there'\\'''"))
    }

    @Test fun `rootfs markers are recognised`() {
        assertTrue(ProotCommand.looksLikeRootfs("./usr/lib"))
        assertTrue(ProotCommand.looksLikeRootfs("bin/sh"))
        assertFalse(ProotCommand.looksLikeRootfs("./etc/passwd"))
    }
}
