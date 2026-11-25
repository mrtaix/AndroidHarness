package com.androidharness.app.agent

/** Reference to a GitHub issue used as a mission source. */
data class IssueRef(val owner: String, val repo: String, val number: Int)

object MissionIssue {
    private val URL = Regex(
        "^https?://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/issues/(\\d+)(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(url: String): IssueRef {
        val match = URL.matchEntire(url.trim()) ?: error("Expected a GitHub issue URL")
        return IssueRef(
            owner = match.groupValues[1],
            repo = match.groupValues[2],
            number = match.groupValues[3].toInt(),
        )
    }

    fun goal(title: String, body: String): String = buildString {
        append(title.trim())
        if (body.isNotBlank()) append("\n\n").append(body.trim())
    }
}
