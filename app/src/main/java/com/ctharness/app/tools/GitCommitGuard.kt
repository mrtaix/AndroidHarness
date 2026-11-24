package com.ctharness.app.tools

/**
 * Answers "is this commit about to publish something it shouldn't?" before
 * the commit runs: oversized blobs and credential shapes that push protection
 * will reject anyway, but only after the branch history is already dirty.
 */
object GitCommitGuard {
    const val MAX_BYTES = 5L * 1024 * 1024
    private const val SCAN_CHARS = 512 * 1024

    private val patterns = listOf(
        "OpenAI-style key" to Regex("""\bsk-[A-Za-z0-9]{20,}"""),
        "GitHub token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        "AWS access key" to Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        "Google API key" to Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
        "Slack token" to Regex("""\bxox[baprs]-[0-9A-Za-z-]{10,}"""),
        "private key block" to Regex("""-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"""),
    )

    /** One changed file as the guard sees it; [text] is null for binary or unreadable content. */
    data class Fileish(val path: String, val bytes: Long, val text: String?)

    fun problems(files: List<Fileish>): List<String> = files.flatMap { file ->
        buildList {
            if (file.bytes > MAX_BYTES) add("${file.path} is ${file.bytes / 1024 / 1024} MB (limit 5 MB)")
            val text = file.text?.take(SCAN_CHARS) ?: return@buildList
            patterns.forEach { (label, pattern) ->
                if (pattern.containsMatchIn(text)) add("${file.path} looks like it contains a $label")
            }
            // Loud uppercase assignments are the usual accidentally staged .env file.
            if (Regex("""(?m)^\s*[A-Z0-9_]*(?:API_?KEY|PASSWORD|SECRET|TOKEN)\s*=\s*\S{8,}""").containsMatchIn(text))
                add("${file.path} has a credential-looking assignment")
        }
    }.distinct()

    /** Text only when it looks like UTF-8; binary files skip the content scan. */
    fun textOf(data: ByteArray): String? {
        if (data.any { it == 0.toByte() }) return null
        return runCatching { data.toString(Charsets.UTF_8) }.getOrNull()
    }
}
