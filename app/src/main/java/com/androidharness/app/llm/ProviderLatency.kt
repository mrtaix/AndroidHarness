package com.androidharness.app.llm

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a provider takes to produce its first byte for a tiny prompt.
 * The same measurement the transcript records per answer, shown on demand so
 * a slow relay is visible before a long task is started.
 */
object ProviderLatency {
    private const val PROMPT = "hi"

    suspend fun measure(config: ProviderConfig, apiKey: String, timeoutMs: Long = 15_000): Long? {
        val provider = ProviderFactory.create(config)
        val options = RequestOptions(maxOutputTokens = 32, thinking = ThinkingLevel.OFF, cacheKey = "latency-probe")
        val started = System.nanoTime()
        return withTimeoutOrNull(timeoutMs) {
            val first = provider.streamChat(config, apiKey, "", listOf(ChatMessage(Role.USER, PROMPT)), emptyList(), options)
                .first { event -> event !is StreamEvent.ThinkingConfigured && event !is StreamEvent.Retrying &&
                    event !is StreamEvent.ProviderChanged && event !is StreamEvent.ResponseState }
            when (first) {
                is StreamEvent.Failure -> null
                else -> ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            }
        }
    }
}
