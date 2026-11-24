package com.ctharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpSendToolTest {
    private val tool = HttpSendTool()

    @Test fun `variables are replaced by name and unknown ones stay visible`() {
        assertEquals("https://api.example.com/v1/users?page=2",
            tool.substitute("https://{{host}}/v1/users?page={{page}}", mapOf("host" to "api.example.com", "page" to "2")))
        assertEquals("https://x.test/{{missing}}", tool.substitute("https://x.test/{{missing}}", emptyMap()))
    }

    @Test fun `spaces inside the braces are tolerated and substituted once`() {
        assertEquals("Bearer abc", tool.substitute("Bearer {{ token }}", mapOf("token" to "abc")))
        assertEquals("a-a", tool.substitute("{{x}}-{{x}}", mapOf("x" to "a")))
    }

    @Test fun `a brace that is not a variable name is left alone`() {
        assertEquals("{\"a\":1}", tool.substitute("{\"a\":1}", emptyMap()))
        assertEquals("{{a b}}", tool.substitute("{{a b}}", mapOf("a" to "x")))
    }
}
