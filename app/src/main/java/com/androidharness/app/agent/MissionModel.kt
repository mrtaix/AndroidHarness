package com.androidharness.app.agent

import kotlinx.serialization.Serializable

@Serializable
enum class MissionStatus { QUEUED, RUNNING, WAITING_NETWORK, WAITING_RETRY, NEEDS_USER, PARTIAL, DONE, CANCELLED }

@Serializable
enum class MissionSource { TEXT, ISSUE_URL }

@Serializable
enum class FailureKind { TRANSIENT, RATE_LIMIT, PERMANENT }

@Serializable
data class MissionLimits(
    val turnLimit: Int = 100,
    val maxCostUsd: Double = 2.0,
    val maxWallClockMs: Long = 8 * 60 * 60 * 1000,
    val maxRetriesPerKind: Int = 8,
)

@Serializable
data class MissionCheckpoint(
    val transcriptTail: String = "",
    val diffSummary: String = "",
)

@Serializable
data class MissionState(
    val id: String,
    val goal: String,
    val source: MissionSource = MissionSource.TEXT,
    val status: MissionStatus = MissionStatus.QUEUED,
    val branch: String? = null,
    val sessionId: String? = null,
    val turns: Int = 0,
    val costUsd: Double = 0.0,
    val elapsedMs: Long = 0L,
    val retries: Int = 0,
    val nextRetryAtMs: Long? = null,
    val watchdogRetries: Int = 0,
    val lastReason: String? = null,
    val prUrl: String? = null,
    val checkCommand: String? = null,
    val checkpoint: MissionCheckpoint = MissionCheckpoint(),
    val limits: MissionLimits = MissionLimits(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

sealed interface MissionEvent {
    data object START : MissionEvent
    data object NETWORK_LOST : MissionEvent
    data object NETWORK_RESTORED : MissionEvent
    data object RETRY_TICK : MissionEvent
    data object USER_RESUME : MissionEvent
    data object WATCHDOG : MissionEvent
    data object CANCEL : MissionEvent
    data class FAILURE(val kind: FailureKind, val reason: String) : MissionEvent
    data class BLOCKED(val reason: String) : MissionEvent
    data class TURN_DONE(val elapsedMs: Long, val costUsd: Double) : MissionEvent
    data class MET(val prUrl: String? = null) : MissionEvent
}

/** Pure transition rules for an overnight mission. */
fun MissionState.transition(event: MissionEvent): MissionState = when (event) {
    MissionEvent.START -> if (status == MissionStatus.QUEUED) copy(status = MissionStatus.RUNNING) else this
    MissionEvent.NETWORK_LOST -> if (status == MissionStatus.RUNNING) copy(status = MissionStatus.WAITING_NETWORK) else this
    MissionEvent.NETWORK_RESTORED -> if (status == MissionStatus.WAITING_NETWORK) copy(status = MissionStatus.RUNNING) else this
    MissionEvent.RETRY_TICK -> if (status == MissionStatus.WAITING_RETRY) copy(status = MissionStatus.RUNNING) else this
    MissionEvent.USER_RESUME -> if (status == MissionStatus.NEEDS_USER) copy(status = MissionStatus.RUNNING) else this
    MissionEvent.CANCEL -> if (status == MissionStatus.DONE) this else copy(status = MissionStatus.CANCELLED)
    is MissionEvent.MET -> if (status == MissionStatus.DONE) this else
        copy(status = MissionStatus.DONE, prUrl = event.prUrl ?: prUrl)
    is MissionEvent.BLOCKED -> if (status == MissionStatus.RUNNING)
        copy(status = MissionStatus.NEEDS_USER, lastReason = event.reason) else this
    is MissionEvent.FAILURE -> if (status != MissionStatus.RUNNING) this else when {
        event.kind == FailureKind.PERMANENT -> copy(status = MissionStatus.NEEDS_USER, lastReason = event.reason)
        retries + 1 >= limits.maxRetriesPerKind -> copy(status = MissionStatus.PARTIAL, lastReason = event.reason)
        else -> copy(status = MissionStatus.WAITING_RETRY, retries = retries + 1, lastReason = event.reason)
    }
    is MissionEvent.TURN_DONE -> {
        val nextTurns = turns + 1
        val nextCost = costUsd + event.costUsd
        val nextElapsed = elapsedMs + event.elapsedMs
        if (nextTurns >= limits.turnLimit || nextCost >= limits.maxCostUsd || nextElapsed >= limits.maxWallClockMs) {
            copy(status = MissionStatus.PARTIAL, turns = nextTurns, costUsd = nextCost, elapsedMs = nextElapsed,
                lastReason = "Mission budget reached")
        } else {
            copy(turns = nextTurns, costUsd = nextCost, elapsedMs = nextElapsed)
        }
    }
    MissionEvent.WATCHDOG -> if (status != MissionStatus.RUNNING) this else if (watchdogRetries >= 1) {
        copy(status = MissionStatus.PARTIAL, lastReason = "Watchdog fired twice")
    } else {
        copy(status = MissionStatus.WAITING_RETRY, watchdogRetries = 1, retries = retries + 1,
            lastReason = "No heartbeat, retrying from checkpoint")
    }
}
