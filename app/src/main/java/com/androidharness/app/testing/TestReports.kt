package com.androidharness.app.testing

/**
 * A sealed set rather than an enum: the value only ever appears in reports, and
 * this keeps the parser compilable by a minimal JVM compiler for its unit test.
 */
sealed class TestStatus(val label: String) {
    data object PASSED : TestStatus("passed")
    data object FAILED : TestStatus("failed")
    data object ERROR : TestStatus("error")
    data object SKIPPED : TestStatus("skipped")
}

/** One test as the runner reported it; [file] and [line] are filled when the report carries them. */
data class TestCaseResult(
    val suite: String,
    val name: String,
    val status: TestStatus,
    val durationMs: Long = 0L,
    val message: String? = null,
    val file: String? = null,
    val line: Int? = null,
) {
    val label: String get() = if (suite.isBlank()) name else "$suite › $name"
}

data class TestRunSummary(
    val framework: String,
    val cases: List<TestCaseResult>,
    val rawTail: String = "",
) {
    val passed: Int get() = cases.count { it.status == TestStatus.PASSED }
    val failed: Int get() = cases.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
    val skipped: Int get() = cases.count { it.status == TestStatus.SKIPPED }
    val durationMs: Long get() = cases.sumOf { it.durationMs }

    fun failures(limit: Int = 20): List<TestCaseResult> = cases
        .filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
        .take(limit)

    fun render(limit: Int = 40): String {
        if (cases.isEmpty()) {
            return "No test cases parsed ($framework)." + if (rawTail.isBlank()) "" else "\n\n" + rawTail
        }
        val lines = StringBuilder()
        lines.append("$framework: $passed passed, $failed failed, $skipped skipped")
        if (durationMs > 0) lines.append(" · ${durationMs} ms")
        lines.append('\n')
        cases.take(limit).forEach { case ->
            val mark = when (case.status) {
                TestStatus.PASSED -> "ok"
                TestStatus.SKIPPED -> "skip"
                TestStatus.FAILED, TestStatus.ERROR -> "FAIL"
            }
            lines.append("[$mark] ").append(case.label)
            if (case.durationMs > 0) lines.append(" (").append(case.durationMs).append(" ms)")
            case.file?.let { file ->
                lines.append(" — ").append(file)
                case.line?.let { lines.append(':').append(it) }
            }
            lines.append('\n')
            case.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { lines.append("    ").append(it).append('\n') }
        }
        if (cases.size > limit) lines.append("… ").append(cases.size - limit).append(" more\n")
        if (rawTail.isNotBlank()) lines.append('\n').append(rawTail)
        return lines.toString().trimEnd()
    }
}

/**
 * Turns the output of a test run into cases. JUnit XML is preferred (Gradle can
 * write it), pytest's terminal report is parsed as a fallback, and an
 * unrecognised runner still reports its exit code and output tail.
 *
 * The XML reader is a small scanner rather than a DOM parser: reports are
 * machine written, the shape is flat, and this keeps the object usable from a
 * plain JVM unit test without a platform XML stack.
 */
object TestReports {
    private val pytestLine = Regex("""(?m)^(\S+?::[^\s]+)(?:\s+)(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)""")
    private val pytestFailed = Regex("""(?m)^(?:FAILED|ERROR)\s+(\S+?::[^\s]+)""")
    private val junitSummary = Regex("""(?m)^Tests run:\s*(\d+),\s*Failures:\s*(\d+)""")
    private val testcaseTag = Regex("""<testcase\b""", RegexOption.IGNORE_CASE)
    private val attributePattern = Regex("""([A-Za-z_][A-Za-z0-9_.-]*)\s*=\s*"([^"]*)"""")

    private val IGNORE = RegexOption.IGNORE_CASE

    /** Cheap stack check when a project has no saved framework: markers beat guesses. */
    fun detectFramework(filenames: Collection<String>): String {
        val names = filenames.map { it.substringAfterLast('/') }.toSet()
        return when {
            names.any { it == "build.gradle.kts" || it == "build.gradle" || it == "settings.gradle.kts" } -> "gradle"
            names.any { it == "pyproject.toml" || it == "pytest.ini" || it == "tox.ini" || it == "setup.cfg" } -> "pytest"
            names.any { it == "package.json" } -> "npm"
            names.any { it == "Cargo.toml" } -> "cargo"
            names.any { it == "go.mod" } -> "go"
            else -> "unknown"
        }
    }

    fun parse(output: String, framework: String): TestRunSummary = when (framework) {
        "pytest" -> TestRunSummary(framework, parsePytest(output), tail(output))
        "gradle" -> TestRunSummary(framework, parseJUnitXml(output), tail(output))
        else -> TestRunSummary(framework, parsePytest(output) + parseJUnitXml(output), tail(output))
    }

    fun parseJUnitXml(xml: String): List<TestCaseResult> {
        if (!xml.contains("<testcase", ignoreCase = true)) return emptyList()
        val cases = ArrayList<TestCaseResult>()
        var cursor = 0
        while (true) {
            val match = testcaseTag.find(xml, cursor) ?: break
            val start = match.range.first
            val nextCase = testcaseTag.find(xml, start + 1)?.range?.first
            val suiteEnd = xml.indexOf("</testsuite", start + 1, ignoreCase = true)
            val end = listOfNotNull(nextCase, suiteEnd.takeIf { it > 0 }).minOrNull() ?: xml.length
            val chunk = xml.substring(start, end)
            cursor = end.coerceAtLeast(start + 1)
            val headerEnd = chunk.indexOf('>').let { if (it < 0) chunk.length else it }
            val attributes = parseAttributes(chunk.substring(0, headerEnd))
            val status = when {
                hasTag(chunk, "failure") -> TestStatus.FAILED
                hasTag(chunk, "error") -> TestStatus.ERROR
                hasTag(chunk, "skipped") -> TestStatus.SKIPPED
                else -> TestStatus.PASSED
            }
            cases += TestCaseResult(
                suite = attributes["classname"].orEmpty().ifBlank { suiteName(xml, start) },
                name = attributes["name"]?.takeIf { it.isNotBlank() } ?: "test ${cases.size}",
                status = status,
                durationMs = ((attributes["time"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                message = if (status == TestStatus.PASSED || status == TestStatus.SKIPPED) null else failureMessage(chunk),
                file = attributes["file"]?.takeIf { it.isNotBlank() },
                line = attributes["line"]?.toIntOrNull(),
            )
        }
        return cases
    }

    fun parsePytest(output: String): List<TestCaseResult> {
        val results = LinkedHashMap<String, TestCaseResult>()
        pytestLine.findAll(output).forEach { match ->
            val id = match.groupValues[1]
            val status = when (match.groupValues[2].uppercase()) {
                "PASSED" -> TestStatus.PASSED
                "SKIPPED", "XFAIL" -> TestStatus.SKIPPED
                "ERROR" -> TestStatus.ERROR
                else -> TestStatus.FAILED
            }
            val file = id.substringBefore("::").takeIf { it.isNotBlank() }
            val name = id.substringAfter("::")
            results[id] = TestCaseResult(suite = file ?: "", name = name, status = status, file = file)
        }
        pytestFailed.findAll(output).forEach { match ->
            val id = match.groupValues[1]
            if (results[id] == null) {
                val file = id.substringBefore("::")
                results[id] = TestCaseResult(file, id.substringAfter("::"), TestStatus.FAILED, file = file)
            }
        }
        return results.values.toList()
    }

    fun tail(output: String, lines: Int = 12): String =
        output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines).joinToString("\n")

    fun junitCounts(output: String): Pair<Int, Int>? {
        val match = junitSummary.find(output) ?: return null
        return match.groupValues[1].toIntOrNull()?.let { run -> run to (match.groupValues[2].toIntOrNull() ?: 0) }
    }

    private fun parseAttributes(text: String): Map<String, String> =
        attributePattern.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private fun hasTag(chunk: String, tag: String): Boolean =
        Regex("""<$tag[\s/>]""", IGNORE).containsMatchIn(chunk)

    private fun failureMessage(chunk: String): String? {
        val tagStart = listOf("failure", "error")
            .mapNotNull { tag -> Regex("<$tag\\b[^>]*>", IGNORE).find(chunk)?.range }
            .minByOrNull { it.first }
        if (tagStart == null) return null
        val attributes = parseAttributes(chunk.substring(tagStart.first, tagStart.last + 1))
        attributes["message"]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val bodyStart = tagStart.last + 1
        val bodyEnd = chunk.indexOf('<', bodyStart).let { if (it < 0) chunk.length else it }
        val body = chunk.substring(bodyStart, minOf(bodyEnd, bodyStart + 2_000))
            .replace(Regex("<[^>]+>"), " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .trim()
        return body.takeIf { it.isNotEmpty() }
    }

    private fun suiteName(xml: String, before: Int): String {
        val head = xml.substring(0, before.coerceIn(0, xml.length))
        val match = Regex("""<testsuite\b([^>]*)>""", IGNORE).findAll(head).lastOrNull() ?: return ""
        return parseAttributes(match.groupValues[1])["name"].orEmpty()
    }
}
