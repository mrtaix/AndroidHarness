package com.androidharness.app.agent

/** Starts a run for a mission and returns its chat session id. */
interface MissionRunStarter {
    suspend fun start(mission: MissionState, prompt: String): String
}

/** Side effects that must happen at most once when a mission reaches its finish line. */
interface MissionSideEffects {
    suspend fun onMet(mission: MissionState): String?
}

/** Registry of overnight missions. Pure enough to test with fakes. */
class MissionManager(
    val store: MissionStore,
    private val starter: MissionRunStarter,
    val sideEffects: MissionSideEffects,
    private val notifier: (MissionState, String) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    fun list(): List<MissionState> = store.list()

    suspend fun create(
        goal: String,
        source: MissionSource = MissionSource.TEXT,
        checkCommand: String? = null,
        limits: MissionLimits = MissionLimits(),
    ): MissionState {
        val mission = MissionState(
            id = "mission-${java.util.UUID.randomUUID()}",
            goal = goal,
            source = source,
            checkCommand = checkCommand,
            limits = limits,
        )
        val started = mission.transition(MissionEvent.START)
        persist(started)
        val sessionId = starter.start(started, "Goal: $goal")
        val withSession = started.copy(sessionId = sessionId)
        return persist(withSession)
    }

    suspend fun handleFailure(id: String, code: Int?, message: String?, causeName: String?) {
        val kind = MissionRetryPolicy.classify(code, message, causeName)
        val current = store.load(id) ?: return
        val next = current.transition(MissionEvent.FAILURE(kind, message ?: kind.name))
        if (next.status == MissionStatus.WAITING_RETRY) {
            val delay = MissionRetryPolicy.nextDelay(kind, next.retries, null)
            persist(next.copy(nextRetryAtMs = now() + delay))
            notifier(next, "Retrying in ${delay / 1000}s: ${message ?: kind.name}")
        } else {
            persist(next)
            notifier(next, next.lastReason ?: "Mission needs attention")
        }
    }

    suspend fun retryNow(id: String) {
        val current = store.load(id) ?: return
        if (current.status != MissionStatus.WAITING_RETRY) return
        val next = current.transition(MissionEvent.RETRY_TICK).copy(nextRetryAtMs = null)
        persist(next)
        starter.start(next, "Continue mission: ${next.goal}\nLast issue: ${next.lastReason.orEmpty()}")
    }

    fun networkLost(id: String) {
        val current = store.load(id) ?: return
        persist(current.transition(MissionEvent.NETWORK_LOST))
    }

    fun networkRestored(id: String) {
        val current = store.load(id) ?: return
        if (current.status == MissionStatus.WAITING_NETWORK) {
            persist(current.transition(MissionEvent.NETWORK_RESTORED))
        }
    }

    suspend fun watchdog(id: String) {
        val current = store.load(id) ?: return
        val next = current.transition(MissionEvent.WATCHDOG)
        if (next.status == MissionStatus.WAITING_RETRY) {
            persist(next.copy(nextRetryAtMs = now() + 1_000L))
        } else {
            persist(next)
        }
    }

    suspend fun markMet(id: String) {
        val current = store.load(id) ?: return
        if (current.status == MissionStatus.DONE) return
        val next = current.transition(MissionEvent.MET(current.prUrl))
        persist(next)
        val url = sideEffects.onMet(next)
        if (url != null && url != next.prUrl) persist(next.copy(prUrl = url))
        notifier(next, "Mission reached: ${next.goal}")
    }

    suspend fun pause(id: String) {
        val current = store.load(id) ?: return
        persist(current.copy(status = MissionStatus.NEEDS_USER, lastReason = "Paused by user"))
    }

    suspend fun resume(id: String) {
        val current = store.load(id) ?: return
        val next = current.transition(MissionEvent.USER_RESUME).copy(nextRetryAtMs = null)
        persist(next)
        starter.start(next, "Resume mission: ${next.goal}")
    }

    suspend fun cancel(id: String) {
        val current = store.load(id) ?: return
        persist(current.transition(MissionEvent.CANCEL))
    }

    /** One scheduler tick: retry due missions and resume waiting ones when online. */
    suspend fun tick(online: Boolean) {
        val current = now()
        list().forEach { mission ->
            when {
                mission.status == MissionStatus.WAITING_RETRY &&
                    mission.nextRetryAtMs != null && current >= mission.nextRetryAtMs!! -> retryNow(mission.id)
                mission.status == MissionStatus.WAITING_NETWORK && online -> networkRestored(mission.id)
            }
        }
    }

    suspend fun resumeAll() {
        list().filter {
            it.status == MissionStatus.RUNNING || it.status == MissionStatus.WAITING_RETRY ||
                it.status == MissionStatus.WAITING_NETWORK
        }.forEach { mission ->
            val resumed = when (mission.status) {
                MissionStatus.WAITING_NETWORK -> mission.transition(MissionEvent.NETWORK_RESTORED)
                MissionStatus.WAITING_RETRY -> mission.transition(MissionEvent.RETRY_TICK)
                else -> mission
            }.copy(nextRetryAtMs = null)
            persist(resumed)
            starter.start(resumed, "Resume mission after restart: ${mission.goal}")
        }
    }

    private fun persist(mission: MissionState): MissionState = synchronized(lock) {
        val saved = mission.copy(updatedAt = now())
        store.save(saved)
        saved
    }
}
