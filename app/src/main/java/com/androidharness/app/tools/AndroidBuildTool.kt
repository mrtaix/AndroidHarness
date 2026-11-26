package com.androidharness.app.tools

import com.androidharness.app.data.env.AndroidBuildReport
import com.androidharness.app.data.env.AndroidBuildReportParser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Result of one shell step inside a build chain. Mirrors ShellRunResult's essentials. */
data class BuildStepResult(val exitCode: Int, val output: String, val stderr: String = "") {
    val ok: Boolean get() = exitCode == 0
}

/** Minimal seam so the build chain can be tested without Android/router plumbing. */
interface BuildRunner {
    suspend fun run(command: String, cwd: File, env: Map<String, String>): BuildStepResult
}

/** Outcome of one orchestrated build. */
data class AndroidBuildOutcome(
    val ok: Boolean,
    val summary: String,
    val report: AndroidBuildReport? = null,
)

/**
 * Build → verify → install → launch → logcat, with routing, env forwarding and
 * guided install failures. Pure enough for JVM tests; [AndroidBuildTool] binds
 * it to the app's Tool interface and [ShellTierRouter].
 */
class AndroidBuildCore(
    private val runner: BuildRunner,
    private val scriptPath: String,
    private val reportJson: (File) -> String? = { File(it, "build/build-report.json").run { if (isFile) readText() else null } },
) {
    suspend fun execute(
        project: File,
        route: String,
        env: Map<String, String>,
        doInstall: Boolean = false,
        doLaunch: Boolean = false,
        doCrashCheck: Boolean = false,
        installCommand: (String) -> String = { "pm install -r -g '$it'" },
        launchCommand: (String) -> String = { "monkey -p '$it' -c android.intent.category.LAUNCHER 1" },
        crashCommand: (String) -> String = { "logcat -d -b crash -t 200 | grep -i '$it' || true" },
    ): AndroidBuildOutcome {
        if (route == "gradle") {
            val r = runner.run("sh ./gradlew assembleDebug", project, env)
            if (!r.ok) return AndroidBuildOutcome(false, errorSummary(r.output + "\n" + r.stderr))
            // Gradle writes no build-report.json; derive the same outcome from
            // the first debug APK so install/open/crash work on both routes.
            val apk = findGradleApk(project)
                ?: return AndroidBuildOutcome(false, "Gradle build xong nhưng không tìm thấy APK trong build/outputs/apk/.")
            val pkg = gradlePackage(project) ?: "unknown"
            val report = AndroidBuildReport("gradle", pkg, apk, apk.length.toLong(), "", 0, 0, 0, "", "", "", false, emptyList())
            return finish(project, env, report, doInstall, doLaunch, doCrashCheck,
                installCommand, launchCommand, crashCommand)
        }
        val result = try {
            runner.run("sh '$scriptPath' '${project.absolutePath}'", project, env)
        } catch (e: CancellationException) {
            return AndroidBuildOutcome(false, "Build đã bị hủy.")
        }
        if (!result.ok) return AndroidBuildOutcome(false, errorSummary(result.output + "\n" + result.stderr))

        val text = reportJson(project)
        val report = text?.let { AndroidBuildReportParser.parse(it) }
            ?: return AndroidBuildOutcome(false, "Build không tạo được báo cáo build/build-report.json; hãy thử lại với NO_CACHE=1.")

        return finish(project, env, report, doInstall, doLaunch, doCrashCheck,
            installCommand, launchCommand, crashCommand)
    }

    private suspend fun finish(
        project: File,
        env: Map<String, String>,
        report: AndroidBuildReport,
        doInstall: Boolean,
        doLaunch: Boolean,
        doCrashCheck: Boolean,
        installCommand: (String) -> String,
        launchCommand: (String) -> String,
        crashCommand: (String) -> String,
    ): AndroidBuildOutcome {
        val sb = StringBuilder(AndroidBuildReportParser.summary(report))
        val apk = File(project, report.apk).absolutePath
        if (doInstall) {
            val installed = runner.run(installCommand(apk), project, env)
            if (!installed.ok) {
                sb.append('\n').append(installGuidance(installed).summary)
                return AndroidBuildOutcome(false, sb.toString(), report)
            }
            sb.append("\nCài đặt: thành công")
        }
        if (doLaunch) {
            runner.run(launchCommand(report.packageName), project, env)
            sb.append("\nĐã mở ứng dụng")
        }
        if (doCrashCheck) {
            val crashes = runner.run(crashCommand(report.packageName), project, env).output.trim()
            sb.append(if (crashes.isBlank()) "\nKhông có crash mới" else "\n$crashes")
        }
        return AndroidBuildOutcome(true, sb.toString(), report)
    }

    companion object {
        fun findGradleApk(project: File): String? =
            File(project, "build/outputs/apk/debug").listFiles().orEmpty()
                .firstOrNull { it.isFile && it.name.endsWith(".apk") && !it.name.contains("unsigned") }
                ?.relativeTo(project)?.invariantSeparatorsPath

        /** Reads `package` from the manifest when available; never a secret. */
        fun gradlePackage(project: File): String? = runCatching {
            Regex("""package="([^"]+)"""").find(File(project, "src/main/AndroidManifest.xml").readText())?.groupValues?.get(1)
        }.getOrNull()
        /** One way to pick a route: a Gradle file wins unless the caller pins a route. */
        fun pickRoute(hasGradle: Boolean, requested: String): String = when (requested) {
            "gradle" -> "gradle"
            "script" -> "script"
            else -> if (hasGradle) "gradle" else "script"
        }

        fun errorSummary(raw: String): String {
            val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            val errors = lines.filter { it.contains("error:", ignoreCase = true) || it.contains("FAILED") }.take(5).toList()
            val all = lines.toList()
            val head = errors.ifEmpty { all.subList(maxOf(0, all.size - 5), all.size) }
            return "Build thất bại:\n" + head.joinToString("\n") + "\nXem chi tiết: build/build-error.log"
        }

        fun installGuidance(result: BuildStepResult): AndroidBuildOutcome {
            val text = result.output + "\n" + result.stderr
            return if (text.contains("INSTALL_FAILED_USER_RESTRICTED", ignoreCase = true)) {
                AndroidBuildOutcome(false,
                    "Cài đặt bị chặn bởi MIUI. Bật Developer options → Install via USB (hoặc cho phép popup cài đặt), rồi thử lại.")
            } else {
                AndroidBuildOutcome(false, "Cài đặt thất bại: " + errorSummary(text))
            }
        }
    }
}

/** Tool binding. Wires a [BuildRunner] to the app's ShellTierRouter in HarnessApp. */
class AndroidBuildTool(
    private val core: AndroidBuildCore,
) : Tool {
    override val name = NAME
    override val description =
        "Build an Android APK, then optionally verify, install, launch and check for crashes. " +
            "Picks the Gradle route when the project has a Gradle build file, otherwise the aapt2/javac/d8 route. " +
            "Incremental builds reuse the cache when inputs did not change."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "project" to Schema.string("Project directory, relative to the workspace root."),
            "route" to Schema.string("auto, gradle or script. Default auto."),
            "keystore" to Schema.string("Optional keystore path. Passwords are never accepted here."),
            "install" to Schema.boolean("Install the APK with pm install after building."),
            "open" to Schema.boolean("Launch the app after installing."),
            "check_crash" to Schema.boolean("Read the crash log for the package after launching."),
            "timeout_seconds" to Schema.integer("Build timeout in seconds (default 900)."),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val project = args["project"]?.jsonPrimitive?.contentOrNull?.let { File(it) }
            ?: ctx.workspace.shellRoot ?: File(".")
        val hasGradle = File(project, "build.gradle.kts").isFile || File(project, "build.gradle").isFile
        val route = AndroidBuildCore.pickRoute(hasGradle, args["route"]?.jsonPrimitive?.contentOrNull ?: "auto")
        val env = mutableMapOf<String, String>()
        args["keystore"]?.jsonPrimitive?.contentOrNull?.let { env["KEYSTORE"] = it }
        val doInstall = args["install"]?.jsonPrimitive?.booleanOrNull == true
        val outcome = core.execute(project, route, env,
            doInstall = doInstall,
            doLaunch = args["open"]?.jsonPrimitive?.booleanOrNull == true && doInstall,
            doCrashCheck = args["check_crash"]?.jsonPrimitive?.booleanOrNull == true)
        return ToolResult(ok = outcome.ok, output = outcome.summary)
    }

    companion object { const val NAME = "android_build" }
}
