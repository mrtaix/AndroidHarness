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
        private val VERDICT = Regex("\\b(PASS|FAIL)\\b", RegexOption.IGNORE_CASE)
        private val VERDICT_LINE = Regex("^\\s*(PASS|FAIL)\\b", RegexOption.IGNORE_CASE)
        private val JSON_PASS = Regex("\"pass\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        private val JSON_REASON = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

        /**
         * Accepts the compact first-line form, a JSON object, or prose containing
         * a PASS/FAIL word. Never throws: an unreadable answer is a FAIL verdict.
         */
        fun parse(text: String): ChiefVerdict {
            val raw = text.trim()
            if (raw.isEmpty()) return ChiefVerdict(false, "Could not parse verdict: empty output")
            JSON_PASS.find(raw)?.let { match ->
                val pass = match.groupValues[1].equals("true", ignoreCase = true)
                val reason = JSON_REASON.find(raw)?.groupValues?.get(1)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Verifier gave no reason"
                return ChiefVerdict(pass, reason.take(300))
            }
            val firstLine = raw.trimStart('`').lineSequence().map { it.trim().trim('`', '"') }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val verdict = VERDICT_LINE.find(firstLine) ?: VERDICT.find(raw)
                ?: return ChiefVerdict(false, "Could not parse verdict: ${raw.take(200)}")
            val pass = verdict.groupValues[1].equals("PASS", ignoreCase = true)
            val rest = raw.removeRange(verdict.range)
            var reason = rest.replace("```", " ").replace(Regex("[\\r\\n]+"), " ")
                .replace(Regex("^[{}\\\"'`\\s:,]+|[{}\\\"'`\\s:,]+$"), "").trim()
            if (reason.isEmpty()) reason = "Verifier answered ${verdict.value} without a reason"
            return ChiefVerdict(pass, reason.take(300))
        }
    }
}
