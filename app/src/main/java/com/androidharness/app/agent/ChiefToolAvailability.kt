package com.androidharness.app.agent

/** Pure policy for which chief tools a run may see. */
object ChiefToolAvailability {
    const val DEPTH_LIMIT = 3

    val ALL = setOf(
        "chief_plan", "chief_assign", "chief_status", "chief_wait",
        "chief_review", "chief_merge", "chief_stop",
    )

    fun toolNames(enabled: Boolean, depth: Int): Set<String> = when {
        !enabled -> emptySet()
        depth >= DEPTH_LIMIT -> setOf("chief_status")
        else -> ALL
    }

    fun canDelegate(enabled: Boolean, depth: Int): Boolean =
        enabled && depth < DEPTH_LIMIT

    /** Subordinates never receive orchestration tools, even at a shallower depth. */
    fun visibleToSubagent(name: String): Boolean = !name.startsWith("chief_")
}
