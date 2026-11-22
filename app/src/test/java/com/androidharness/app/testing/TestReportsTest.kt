package com.androidharness.app.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestReportsTest {
    @Test fun `junit xml reports failures, errors and skips`() {
        val xml = """
            <testsuites>
              <testsuite name="com.example.CartTest">
                <testcase name="adds items" classname="com.example.CartTest" time="0.012"/>
                <testcase name="rejects empty" classname="com.example.CartTest" time="0.5" file="src/test/kotlin/CartTest.kt" line="42">
                  <failure message="expected 3 but was 4">stack</failure>
                </testcase>
                <testcase name="talks to db" classname="com.example.CartTest"><error message="boom"/></testcase>
                <testcase name="ignored on ci" classname="com.example.CartTest"><skipped/></testcase>
              </testsuite>
            </testsuites>
        """.trimIndent()
        val cases = TestReports.parseJUnitXml(xml)
        assertEquals(4, cases.size)
        assertEquals(TestStatus.PASSED, cases[0].status)
        assertEquals(12L, cases[0].durationMs)
        assertEquals(TestStatus.FAILED, cases[1].status)
        assertEquals("expected 3 but was 4", cases[1].message)
        assertEquals("src/test/kotlin/CartTest.kt", cases[1].file)
        assertEquals(42, cases[1].line)
        assertEquals(TestStatus.ERROR, cases[2].status)
        assertEquals(TestStatus.SKIPPED, cases[3].status)
        val summary = TestRunSummary("gradle", cases)
        assertEquals(1, summary.passed)
        assertEquals(2, summary.failed)
        assertEquals(1, summary.skipped)
        assertTrue(summary.render().contains("FAIL"))
        assertEquals(
            listOf("com.example.CartTest › rejects empty", "com.example.CartTest › talks to db"),
            summary.failures().map { it.label },
        )
    }

    @Test fun `broken xml still yields case names`() {
        val xml = """<testsuite name="s"><testcase name="a" classname="c" time="0.1"><testcase name="b" classname="c""""
        val cases = TestReports.parseJUnitXml(xml)
        assertEquals(listOf("a", "b"), cases.map { it.name })
    }

    @Test fun `pytest terminal output becomes cases`() {
        val output = """
            tests/test_cart.py::test_adds PASSED                                      [ 25%]
            tests/test_cart.py::test_rejects FAILED                                   [ 50%]
            tests/test_cart.py::test_skips SKIPPED (windows)                           [ 75%]
            =================================== FAILURES ===================================
            FAILED tests/test_cart.py::test_rejects - AssertionError: expected 3
            =========================== short test summary ================================
            FAILED tests/test_cart.py::test_rejects - AssertionError: expected 3
            1 failed, 1 passed, 1 skipped in 0.42s
        """.trimIndent()
        val cases = TestReports.parsePytest(output)
        assertEquals(3, cases.size)
        assertEquals(TestStatus.FAILED, cases.first { it.name == "test_rejects" }.status)
        assertEquals("tests/test_cart.py", cases.first { it.name == "test_rejects" }.file)
        assertEquals(1, TestRunSummary("pytest", cases).failed)
    }

    @Test fun `framework detection prefers project markers`() {
        assertEquals("gradle", TestReports.detectFramework(listOf("app/src/main/AndroidManifest.xml", "build.gradle.kts")))
        assertEquals("pytest", TestReports.detectFramework(listOf("pyproject.toml", "src/main.py")))
        assertEquals("npm", TestReports.detectFramework(listOf("package.json")))
        assertEquals("unknown", TestReports.detectFramework(listOf("README.md")))
    }

    @Test fun `gradle console summary is read when xml is absent`() {
        val output = "Tests run: 17,  Failures: 2, Skips: 1"
        assertEquals(17 to 2, TestReports.junitCounts(output))
        assertNull(TestReports.junitCounts("nothing here"))
        assertTrue(TestRunSummary("gradle", emptyList(), TestReports.tail(output)).render().contains("Tests run"))
    }
}
