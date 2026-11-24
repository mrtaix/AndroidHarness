package com.ctharness.app.debug

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Debug Adapter Protocol framing: `Content-Length: N\r\n\r\n` followed by N
 * bytes of JSON. Same header shape as LSP but a different message set, so the
 * parser lives here instead of being shared.
 */
object DapFraming {
    private val headerEnd = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return header + body
    }

    fun contentLength(headers: String): Int? {
        for (line in headers.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(colon + 1).trim().toIntOrNull()
            }
        }
        return null
    }

    fun headerBytes(): ByteArray = headerEnd.copyOf()

    /** Splits the first complete message out of [buffer]; consumed bytes tells the caller how much to drop. */
    fun take(buffer: ByteArray, length: Int = buffer.size): Pair<String?, Int> {
        val end = indexOf(buffer, headerEnd, length) ?: return null to 0
        val headers = String(buffer, 0, end, Charsets.US_ASCII)
        val size = contentLength(headers) ?: return null to end + headerEnd.size
        val bodyStart = end + headerEnd.size
        if (length < bodyStart + size) return null to 0
        return String(buffer, bodyStart, size, Charsets.UTF_8) to (bodyStart + size)
    }

    private fun indexOf(buffer: ByteArray, needle: ByteArray, limit: Int): Int? {
        if (needle.isEmpty()) return 0
        val last = minOf(limit, buffer.size) - needle.size
        for (index in 0..last) {
            var match = true
            for (offset in needle.indices) {
                if (buffer[index + offset] != needle[offset]) { match = false; break }
            }
            if (match) return index
        }
        return null
    }

    /** Blocking read used by the session's reader coroutine. */
    fun read(input: InputStream): String? {
        val headers = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            headers.append(byte.toChar())
            if (headers.length >= 4 && headers.endsWith("\r\n\r\n")) break
            if (headers.length > 8_192) return null
        }
        val size = contentLength(headers.toString()) ?: return null
        val body = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(body, read, size - read)
            if (count < 0) return null
            read += count
        }
        return String(body, Charsets.UTF_8)
    }

    fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        return out.toByteArray()
    }
}
