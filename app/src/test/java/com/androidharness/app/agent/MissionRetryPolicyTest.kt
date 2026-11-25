package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MissionRetryPolicyTest {
    @Test fun `network and server errors are transient`() {
        assertEquals(FailureKind.TRANSIENT, MissionRetryPolicy.classify(null, "connection reset", "IOException"))
        assertEquals(FailureKind.TRANSIENT, MissionRetryPolicy.classify(503, null, "ApiException"))
        assertEquals(FailureKind.TRANSIENT, MissionRetryPolicy.classify(null, "temporarily overloaded", null))
    }

    @Test fun `auth and bad request are permanent`() {
        assertEquals(FailureKind.PERMANENT, MissionRetryPolicy.classify(400, null, "ApiException"))
        assertEquals(FailureKind.PERMANENT, MissionRetryPolicy.classify(401, null, "ApiException"))
        assertEquals(FailureKind.PERMANENT, MissionRetryPolicy.classify(404, null, "ApiException"))
    }

    @Test fun `rate limit gets its own kind and respects retry after`() {
        assertEquals(FailureKind.RATE_LIMIT, MissionRetryPolicy.classify(429, null, "ApiException"))
        assertEquals(120_000L, MissionRetryPolicy.nextDelay(FailureKind.RATE_LIMIT, 1, 120))
        assertEquals(3_600_000L, MissionRetryPolicy.nextDelay(FailureKind.RATE_LIMIT, 1, 7200))
    }

    @Test fun `transient backoff stays in jitter band and caps at thirty minutes`() {
        val first = MissionRetryPolicy.nextDelay(FailureKind.TRANSIENT, 1, null)
        assertTrue(first in 700..1300)
        val capped = MissionRetryPolicy.nextDelay(FailureKind.TRANSIENT, 99, null)
        assertTrue(capped in (30 * 60 * 1000 * 0.7).toLong()..(30 * 60 * 1000 * 1.3).toLong())
        assertEquals(0L, MissionRetryPolicy.nextDelay(FailureKind.PERMANENT, 1, null))
    }

    @Test fun `idempotency key is stable`() {
        assertEquals("m1:3", MissionRetryPolicy.key("m1", 3))
    }

    @Test fun `IOException cause alone is transient`() {
        assertEquals(FailureKind.TRANSIENT, MissionRetryPolicy.classifyFrom(IOException("offline")))
    }
}
