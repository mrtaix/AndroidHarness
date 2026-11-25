package com.androidharness.app.agent

/** Builds the prompts that connect a mission to the existing goal loop. */
object MissionGoalBridge {
    fun kickoff(mission: MissionState): String = buildString {
        append("Goal: ").append(mission.goal.trim()).append('\n')
        mission.checkCommand?.takeIf { it.isNotBlank() }?.let {
            append("Check command: ").append(it.trim()).append('\n')
        }
        append("Keep working until the check shows the goal is reached.")
    }

    fun resume(mission: MissionState, reason: String?): String = buildString {
        append("Continue mission: ").append(mission.goal.trim()).append('\n')
        reason?.takeIf { it.isNotBlank() }?.let { append("Last issue: ").append(it.trim()).append('\n') }
        append("Start from the saved checkpoint; do not repeat completed work.")
    }

    fun key(mission: MissionState, turn: Int): String = MissionRetryPolicy.key(mission.id, turn)
}
