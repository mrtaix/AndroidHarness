package com.androidharness.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DapFramingTest {
    @Test fun `encode writes the byte length, not the character count`() {
        val text = """{"seq":1,"type":"request","command":"initialize"}"""
        val bytes = DapFraming.encode(text)
        val header = String(bytes, Charsets.US_ASCII).substringBefore("\r\n\r\n")
        assertEquals("Content-Length: ${text.toByteArray().size}", header)
        assertEquals(text, String(bytes, header.length + 4, bytes.size - header.length - 4))
    }

    @Test fun `take returns one message and how much it consumed`() {
        val first = DapFraming.encode("""{"event":"initialized"}""")
        val second = DapFraming.encode("""{"event":"stopped"}""")
        val buffer = first + second
        val (message, consumed) = DapFraming.take(buffer)
        assertEquals("""{"event":"initialized"}""", message)
        assertEquals(first.size, consumed)
        val (next, nextConsumed) = DapFraming.take(buffer.copyOfRange(consumed, buffer.size))
        assertEquals("""{"event":"stopped"}""", next)
        assertEquals(second.size, nextConsumed)
    }

    @Test fun `a partial body yields no message`() {
        val bytes = DapFraming.encode("""{"event":"stopped"}""")
        val (message, consumed) = DapFraming.take(bytes, bytes.size - 5)
        assertNull(message)
        assertEquals(0, consumed)
    }

    @Test fun `unicode bodies are measured in bytes`() {
        val text = """{"output":"日本語"}"""
        val bytes = DapFraming.encode(text)
        assertEquals(text, DapFraming.take(bytes).first)
        assertEquals(bytes.size, DapFraming.take(bytes).second)
    }

    @Test fun `header lookup is case insensitive and tolerates other headers`() {
        assertEquals(12, DapFraming.contentLength("Content-Type: application/json\r\ncontent-length: 12\r\n"))
        assertNull(DapFraming.contentLength("Content-Type: application/json\r\n"))
    }
}
