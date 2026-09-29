package com.adnote.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    private val policy = SyncPolicy(minIntervalMs = 60_000L)

    @Test
    fun runsWhenEverythingIsReady() {
        assertTrue(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }

    @Test
    fun offlineNeverRuns() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = false, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }

    @Test
    fun respectsMinInterval() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 50_000L, now = 100_000L))
        assertTrue(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 40_000L, now = 100_000L))
    }

    @Test
    fun nothingToSyncOrDisabledOrUnconfigured() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = false, lastRunAt = 0L, now = 100_000L))
        assertFalse(policy.shouldRun(enabled = false, configured = true, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
        assertFalse(policy.shouldRun(enabled = true, configured = false, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }
}
