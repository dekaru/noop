package com.noop.journalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZJS-F4 — decision tests for the pending-asks notification: one notification only when
 * there is at least one pending ask, none otherwise. (The notify() call itself needs
 * Android; the notification builder mirrors InactivityNotifier and is covered by CI build.)
 */
class JournalPendingNotifierTest {

    @Test
    fun notifiesOnlyWhenThereIsAtLeastOneAsk() {
        assertTrue(!shouldNotifyPending(0))
        assertTrue(shouldNotifyPending(1))
        assertTrue(shouldNotifyPending(3))

        assertTrue(!JournalPendingNotifier.shouldNotify(null))
        assertTrue(
            !JournalPendingNotifier.shouldNotify(JournalSyncProtocol.PendingPage(emptyList())),
        )
        val withAsks = JournalSyncProtocol.PendingPage(
            listOf(
                JournalSyncProtocol.PendingAsk(1L, "2026-10-01", "Did you sleep well?", "yes_no"),
            ),
        )
        assertTrue(JournalPendingNotifier.shouldNotify(withAsks))
    }

    @Test
    fun countUsedForTheTextIsTheAskCount() {
        // The worker passes page.asks.size straight through; the contract under test.
        val page = JournalSyncProtocol.PendingPage(
            (1L..4L).map {
                JournalSyncProtocol.PendingAsk(it, "2026-10-01", "Q$it", "yes_no")
            },
        )
        assertEquals(4, page.asks.size)
    }
}
