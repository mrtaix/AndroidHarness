package com.androidharness.app.agent

import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Outer retry policy for missions, separate from per-stream RetryPolicy. */
object MissionRetryPolicy {
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30 * 60 * 1_000L
    private const val MAX_RETRY_AFTER_SECONDS = 60 * 60

    private val TRANSIENT_WORDS = Regex(
        "timeout|timed out|temporar|try again|connection|network|offline|reset|unavailable|overloaded|5\\d\\d",
        RegexOption.IGNORE_CASE,
    )

    fun classify(code: Int?, message: String?, causeName: String?): FailureKind = when {
        code == 429 -> FailureKind.RATE_LIMIT
        code == 400 || code == 401 || code == 403 || code == 404 -> FailureKind.PERMANENT
        code == 408 || (code != null && code in 500..599) -> FailureKind.TRANSIENT
        message?.contains("rate limit", ignoreCase = true) == true -> FailureKind.RATE_LIMIT
        causeName == "IOException" || (message != null && TRANSIENT_WORDS.containsMatchIn(message)) -> FailureKind.TRANSIENT
        else -> FailureKind.PERMANENT
    }

    fun classifyFrom(cause: Throwable): FailureKind =
        if (cause is IOException) FailureKind.TRANSIENT
        else classify(null, cause.message, cause::class.simpleName)

    fun nextDelay(kind: FailureKind, attempt: Int, retryAfterSeconds: Long?): Long {
        if (kind == FailureKind.PERMANENT) return 0L
        val base = transientDelay(attempt)
        if (kind != FailureKind.RATE_LIMIT) return base
        val asked = retryAfterSeconds?.takeIf { it > 0 }
            ?.let { min(it, MAX_RETRY_AFTER_SECONDS.toLong()) * 1_000L } ?: 0L
        return max(asked, base)
    }

    fun key(missionId: String, turn: Int): String = "$missionId:$turn"

    private fun transientDelay(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 20)
        val base = min(BASE_DELAY_MS shl exp, MAX_DELAY_MS).toLong()
        val jitter = (base * 0.3 * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }
}
