package com.androidharness.app.agent

/** Parses the id from the background-task tool result. */
object ChiefTaskId {
    private val PATTERN = Regex("task_id=([A-Za-z0-9_-]+)")

    fun from(output: String): String? = PATTERN.find(output)?.groupValues?.get(1)
}
