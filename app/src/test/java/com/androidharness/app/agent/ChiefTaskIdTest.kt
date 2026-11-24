package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class ChiefTaskIdTest {
    @Test fun `task id stops before the sentence period`() {
        val output = "Started Worker in the background. " +
            "task_id=chief-fab6ea4a-1c2d. Continue independent work or the conversation."
        assertEquals("chief-fab6ea4a-1c2d", ChiefTaskId.from(output))
    }

    @Test fun `task id from bare output`() {
        assertEquals("chief-1", ChiefTaskId.from("task_id=chief-1"))
    }

    @Test fun `no task id returns null`() {
        assertNull(ChiefTaskId.from("Started Worker in the background."))
    }
}
