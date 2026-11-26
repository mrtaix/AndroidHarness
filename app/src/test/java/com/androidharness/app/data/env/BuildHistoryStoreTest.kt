package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildHistoryStoreTest {
    private val report = AndroidBuildReport(
        route = "script", packageName = "com.example.demo", apk = "build/demo.apk",
        sizeBytes = 12345, sha256 = "abc123def456", minSdk = 26, targetSdk = 35,
        versionCode = 7, versionName = "1.2", keystore = "debug.keystore",
        builtAt = "2026-11-27T00:00:00Z", incremental = true, checks = listOf("aapt2", "d8"),
    )

    private fun store(): Pair<BuildHistoryStore, File> {
        val dir = Files.createTempDirectory("ah-build-history").toFile()
        return BuildHistoryStore(File(dir, "build-history.json")) to dir
    }

    @Test fun `record then list returns newest first and survives reload`() {
        val (store, dir) = store()
        store.record(report.copy(packageName = "a", builtAt = "2026-11-27T00:00:01Z"))
        store.record(report.copy(packageName = "b", builtAt = "2026-11-27T00:00:02Z"))
        val reloaded = BuildHistoryStore(File(dir, "build-history.json"))
        val list = reloaded.list()
        assertEquals(2, list.size)
        assertEquals("b", list.first().report.packageName)
    }

    @Test fun `history is capped at twenty entries`() {
        val (store, _) = store()
        repeat(25) { store.record(report.copy(packageName = "p$it", builtAt = "2026-11-27T00:00:%02d Z".format(it))) }
        assertEquals(20, store.list().size)
        assertEquals("p24", store.list().first().report.packageName)
    }

    @Test fun `status of an entry can be updated and is persisted`() {
        val (store, dir) = store()
        val id = store.record(report)
        store.mark(id, BuildHistoryEntry.Status.INSTALLED)
        val reloaded = BuildHistoryStore(File(dir, "build-history.json"))
        assertEquals(BuildHistoryEntry.Status.INSTALLED, reloaded.list().first { it.id == id }.status)
    }

    @Test fun `clear removes everything`() {
        val (store, _) = store()
        store.record(report)
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test fun `corrupt file is treated as empty`() {
        val dir = Files.createTempDirectory("ah-build-history2").toFile()
        val f = File(dir, "build-history.json")
        f.writeText("not json {{{")
        assertTrue(BuildHistoryStore(f).list().isEmpty())
    }
}
