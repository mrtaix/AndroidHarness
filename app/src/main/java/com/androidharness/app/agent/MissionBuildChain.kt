package com.androidharness.app.agent

/**
 * Turns a mission's checkCommand hint into an optional build→verify→install→
 * open→logcat chain. No new field: a checkCommand starting with `build` opts in.
 * The default chain verifies and builds only; `install`, `open` and `crash`
 * tokens opt into the remaining steps.
 */
object MissionBuildChain {
    data class Plan(
        val project: String,
        val install: Boolean,
        val open: Boolean,
        val crashCheck: Boolean,
    )

    fun plan(checkCommand: String?, project: String): Plan? {
        val text = checkCommand?.trim()?.lowercase() ?: return null
        // A bare "build" is a plain goal, not an opt-in chain; require `build:...`.
        if (!text.startsWith("build:")) return null
        val tokens = text.split(':', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Plan(
            project = project,
            install = "install" in tokens,
            open = "open" in tokens || "install" in tokens,
            crashCheck = "crash" in tokens || "open" in tokens,
        )
    }

    /** Side effects must not repeat across a resume/retry of the same turn. */
    fun idempotencyKey(missionId: String, turn: Int): String = "$missionId:$turn"
}
