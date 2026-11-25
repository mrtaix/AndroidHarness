package com.androidharness.app.agent

import kotlinx.serialization.Serializable

/** Result of a model-backed check of a node's report against its acceptance criteria. */
@Serializable
data class ChiefVerdict(
    val pass: Boolean,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
    val model: String = "",
) {
    companion object {
        /** Parses the compact PASS/FAIL first line plus a reason. */
        fun parse(text: String): ChiefVerdict {
            val lines = text.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val first = lines.firstOrNull()?.uppercase()
                ?: error("Chief verifier returned no verdict")
            val pass = when (first) {
                "PASS" -> true
                "FAIL" -> false
                else -> error("Chief verifier must answer PASS or FAIL on the first line")
            }
            val reason = lines.drop(1).joinToString("\n").trim()
            if (reason.isEmpty()) error("Chief verifier gave no reason")
            return ChiefVerdict(pass, reason)
        }
    }
}
