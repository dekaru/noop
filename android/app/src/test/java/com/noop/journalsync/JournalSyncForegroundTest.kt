package com.noop.journalsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZJS-F8: pure decision for foreground (launch/ON_RESUME) journal syncs.
 */
class JournalSyncForegroundTest {

    private val throttle = JournalSyncScheduler.RESUME_SYNC_THROTTLE_MS

    @Test
    fun `disabled never syncs`() {
        assertFalse(JournalSyncScheduler.shouldSyncOnForeground(0L, 1_000_000L, enabled = false))
    }

    @Test
    fun `first resume (no marker) syncs`() {
        assertTrue(JournalSyncScheduler.shouldSyncOnForeground(0L, 1_000L, enabled = true))
    }

    @Test
    fun `within throttle does not sync (resume right after launch)`() {
        val launchAt = 1_000_000L
        assertFalse(
            JournalSyncScheduler.shouldSyncOnForeground(launchAt, launchAt + throttle - 1, enabled = true),
        )
        // A few seconds after launch: still throttled — no duplicate sync.
        assertFalse(
            JournalSyncScheduler.shouldSyncOnForeground(launchAt, launchAt + 5_000L, enabled = true),
        )
    }

    @Test
    fun `past throttle syncs (return from background)`() {
        val lastAt = 1_000_000L
        assertTrue(
            JournalSyncScheduler.shouldSyncOnForeground(lastAt, lastAt + throttle, enabled = true),
        )
        assertTrue(
            JournalSyncScheduler.shouldSyncOnForeground(lastAt, lastAt + throttle + 60_000L, enabled = true),
        )
    }
}
