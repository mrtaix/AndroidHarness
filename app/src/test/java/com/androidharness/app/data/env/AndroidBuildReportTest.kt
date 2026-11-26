package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test

class AndroidBuildReportTest {
    private val sample = """
        {"route":"script","package":"com.example.demo","apk":"build/demo.apk","size_bytes":12345,
         "sha256":"abc123","min_sdk":26,"target_sdk":35,"version_code":7,"version_name":"1.2",
         "keystore":"debug.keystore","built_at":"2026-11-26T00:00:00Z","incremental":true,
         "checks":["aapt2","javac","d8","apksigner-verify"]}
    """.trimIndent()

    @Test fun `parses a complete report`() {
        val r = AndroidBuildReportParser.parse(sample)!!
        assertEquals("com.example.demo", r.packageName)
        assertEquals("script", r.route)
        assertEquals(12345L, r.sizeBytes)
        assertEquals("abc123", r.sha256)
        assertEquals(26, r.minSdk)
        assertEquals(35, r.targetSdk)
        assertEquals(7, r.versionCode)
        assertEquals("1.2", r.versionName)
        assertTrue(r.incremental)
        assertEquals(4, r.checks.size)
    }

    @Test fun `incomplete or malformed reports return null`() {
        assertNull(AndroidBuildReportParser.parse("{}"))
        assertNull(AndroidBuildReportParser.parse("not json"))
        assertNull(AndroidBuildReportParser.parse("""{"route":"script","package":"x"}"""))
    }

    @Test fun `unknown fields are tolerated`() {
        val json = sample.replace("\"route\":", "\"extra\":1,\"route\":")
        assertNotNull(AndroidBuildReportParser.parse(json))
    }

    @Test fun `summary is vietnamese and never leaks a passphrase`() {
        val r = AndroidBuildReportParser.parse(sample)!!
        val summary = AndroidBuildReportParser.summary(r)
        assertTrue(summary.contains("com.example.demo"))
        assertTrue(summary.contains("abc123".take(8)))
        assertTrue(summary.contains("script"))
        assertFalse(summary.contains("KS_PASS"))
        assertFalse(summary.contains("android")) // keystore name only, not passphrase
    }

    @Test fun `checks summary lists steps`() {
        val r = AndroidBuildReportParser.parse(sample)!!
        val summary = AndroidBuildReportParser.summary(r)
        assertTrue(summary.contains("aapt2"))
        assertTrue(summary.contains("d8"))
    }
}
