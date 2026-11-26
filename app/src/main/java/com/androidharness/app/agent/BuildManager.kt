package com.androidharness.app.agent

import com.androidharness.app.data.env.BuildHistoryEntry
import com.androidharness.app.data.env.BuildHistoryStore
import com.androidharness.app.tools.AndroidBuildCore
import com.androidharness.app.tools.AndroidBuildOutcome

/** Application-scoped build runner shared by the tool and the settings UI. */
class BuildManager(
    val history: BuildHistoryStore,
    private val coreFactory: () -> AndroidBuildCore,
) {
    suspend fun build(
        project: java.io.File,
        route: String = "auto",
        install: Boolean = false,
        open: Boolean = false,
        crashCheck: Boolean = false,
    ): AndroidBuildOutcome {
        val outcome = coreFactory().execute(
            project = project,
            route = route,
            env = emptyMap(),
            doInstall = install,
            doLaunch = open,
            doCrashCheck = crashCheck,
        )
        outcome.report?.let { report ->
            val id = history.record(report, project.absolutePath)
            if (install && outcome.ok) history.mark(id, BuildHistoryEntry.Status.INSTALLED)
            if (open && outcome.ok) history.mark(id, BuildHistoryEntry.Status.LAUNCHED)
        }
        return outcome
    }
}
