package com.androidharness.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AndroidBuildToolTest {
    private val reportJson = """
        {"route":"script","package":"com.example.demo","apk":"build/demo.apk","size_bytes":12345,
         "sha256":"abc123def456","min_sdk":26,"target_sdk":35,"version_code":7,"version_name":"1.2",
         "keystore":"debug.keystore","built_at":"2026-11-26T00:00:00Z","incremental":true,"checks":["aapt2","d8"]}
    """.trimIndent()

    private class FakeRunner(val result: BuildStepResult) : BuildRunner {
        val commands = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun run(command: String, cwd: File, env: Map<String, String>): BuildStepResult {
            commands += command to env
            return result
        }
    }

    @Test fun `build command uses the skill script and forwards environment`() = runBlocking {
        val runner = FakeRunner(BuildStepResult(0, "APK: build/demo.apk", ""))
        val tool = reportTool(runner, reportJson)
        val outcome = tool.execute(project = File("/proj"), route = "script",
            env = mapOf("PACKAGE" to "com.example.demo", "KEYSTORE" to "/keys/release.keystore"))
        assertTrue(outcome.ok)
        assertNotNull(outcome.report)
        assertEquals("com.example.demo", outcome.report!!.packageName)
        assertTrue(runner.commands.first().first.contains("build-apk.sh"))
        assertEquals("/keys/release.keystore", runner.commands.first().second["KEYSTORE"])
        assertFalse(runner.commands.first().second.containsKey("KS_PASS"))
    }

    @Test fun `gradle route invokes gradlew`() = runBlocking {
        val runner = FakeRunner(BuildStepResult(0, "BUILD SUCCESSFUL", ""))
        val tool = AndroidBuildCore(runner, scriptPath = "/script/build-apk.sh")
        val outcome = tool.execute(project = File("/proj"), route = "gradle", env = emptyMap())
        assertFalse(outcome.ok) // no APK present in the fake project
        assertTrue(runner.commands.first().first.contains("gradlew"))
    }

    @Test fun `route auto picks gradle only when a gradle file exists`() {
        assertEquals("gradle", AndroidBuildCore.pickRoute(hasGradle = true, requested = "auto"))
        assertEquals("script", AndroidBuildCore.pickRoute(hasGradle = false, requested = "auto"))
        assertEquals("script", AndroidBuildCore.pickRoute(hasGradle = true, requested = "script"))
        assertEquals("gradle", AndroidBuildCore.pickRoute(hasGradle = false, requested = "gradle"))
    }

    @Test fun `install restriction is turned into a guided message`() {
        val guide = AndroidBuildCore.installGuidance(BuildStepResult(1, "Failure [INSTALL_FAILED_USER_RESTRICTED]", ""))
        assertFalse(guide.ok)
        assertTrue(guide.summary.contains("Install via USB"))
        assertTrue(AndroidBuildCore.installGuidance(BuildStepResult(1, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", "")).ok.not())
    }

    @Test fun `a failing build is reported and never installed`() = runBlocking {
        val runner = FakeRunner(BuildStepResult(1, "error: aapt2 failed", "some stderr"))
        val tool = AndroidBuildCore(runner, scriptPath = "/script/build-apk.sh", reportJson = { reportJson })
        val outcome = tool.execute(project = File("/proj"), route = "script", env = emptyMap(), doInstall = true)
        assertFalse(outcome.ok)
        assertEquals(1, runner.commands.size) // only the build ran
        assertTrue(outcome.summary.contains("aapt2"))
    }

    @Test fun `install launch and crash check run in order after a successful build`() = runBlocking {
        val runner = FakeRunner(BuildStepResult(0, "ok", ""))
        val tool = AndroidBuildCore(runner, scriptPath = "/script/build-apk.sh", reportJson = { reportJson })
        tool.execute(project = File("/proj"), route = "script", env = emptyMap(),
            doInstall = true, doLaunch = true, doCrashCheck = true,
            installCommand = { "INSTALL" }, launchCommand = { "LAUNCH" }, crashCommand = { "LOG" })
        val cmds = runner.commands.map { it.first }
        assertEquals(listOf("INSTALL", "LAUNCH", "LOG"), cmds.subList(cmds.size - 3, cmds.size))
    }

    @Test fun `error summary keeps file and line and drops notices`() {
        val summary = AndroidBuildCore.errorSummary("src/Main.java:12: error: cannot find symbol\nnotice: x")
        assertTrue(summary.contains("src/Main.java:12"))
        assertFalse(summary.contains("notice"))
    }

    @Test fun `missing report is a failure even when the script exits zero`() = runBlocking {
        val runner = FakeRunner(BuildStepResult(0, "ok", ""))
        val tool = AndroidBuildCore(runner, scriptPath = "/script/build-apk.sh", reportJson = { null })
        val outcome = tool.execute(project = File("/proj"), route = "script", env = emptyMap())
        assertFalse(outcome.ok)
        assertTrue(outcome.summary.contains("report") || outcome.summary.contains("báo cáo"))
    }

    private fun reportTool(runner: FakeRunner, json: String) = AndroidBuildCore(
        runner, scriptPath = "/script/build-apk.sh", reportJson = { json })
}
