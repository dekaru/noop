package com.noop.journalsync

import com.noop.data.JournalEntry
import com.noop.ui.JOURNAL_DEVICE_ID
import com.noop.ui.JournalKind
import com.noop.ui.STARTER_JOURNAL_QUESTIONS
import com.noop.ui.STARTER_JOURNAL_GROUPS
import com.noop.ui.JournalGroup

/**
 * Pure sync logic, fully injected (api + store + catalog + clock): JVM-testable.
 *
 * Condition A of Jorge's verdict: the ack is sent ONLY after a successful upsert commit.
 * If the upsert throws, no ack is sent and the outcome is [SyncOutcome.Retry] (the server
 * re-delivers on the next run; the Room upsert makes the write idempotent). If the ack
 * itself fails after a good upsert, the outcome is also [SyncOutcome.Retry] for the same
 * reason.
 */
interface JournalStore {
    /** Single-commit write of a batch (Room @Upsert under the hood = idempotent). */
    suspend fun upsert(rows: List<JournalEntry>)
}

sealed class SyncOutcome {
    data class Success(val synced: Int, val discarded: Int) : SyncOutcome()
    data class AuthFailed(val code: Int) : SyncOutcome()
    data class Retry(val message: String) : SyncOutcome()
    data class Fatal(val message: String) : SyncOutcome()
}

class JournalSyncRunner(
    private val api: JournalSyncApi,
    private val store: JournalStore,
    private val catalogProvider: suspend () -> List<JournalSyncProtocol.CatalogQuestion> = { defaultCatalog() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Hard stop so a server that keeps returning the same rows can never loop forever. */
        const val MAX_PAGES = 20
        const val JOURNAL_SYNC_DEVICE_ID = JOURNAL_DEVICE_ID

        /** Starters, kind yes_no by default, with their native groups. */
        fun defaultCatalog(): List<JournalSyncProtocol.CatalogQuestion> =
            STARTER_JOURNAL_QUESTIONS.map { q ->
                JournalSyncProtocol.CatalogQuestion(
                    question = q,
                    kind = "yes_no",
                    unit = null,
                    group = (STARTER_JOURNAL_GROUPS[q] ?: JournalGroup.Other).name,
                )
            }

        /**
         * Catalog including numeric items, built from already-resolved v2 items
         * (the caller reads them with loadJournalCatalogItems(context); this keeps the
         * runner free of Android types).
         */
        fun catalogFromItems(items: List<com.noop.ui.JournalCatalogItem>): List<JournalSyncProtocol.CatalogQuestion> =
            items.filterNot { it.hidden }.map { item ->
                JournalSyncProtocol.CatalogQuestion(
                    question = item.canonical,
                    kind = when (val kind = item.kind) {
                        is JournalKind.Bool -> "yes_no"
                        is JournalKind.Numeric -> "numeric"
                    },
                    unit = (item.kind as? JournalKind.Numeric)?.unit,
                    group = item.group.name,
                )
            }
    }

    suspend fun run(): SyncOutcome {
        // (a) catalog refresh: failure is non-fatal, log and continue (spec §3.4 step 1).
        val catalog = runCatching { catalogProvider() }.getOrElse { emptyList() }
        if (catalog.isNotEmpty()) {
            when (val result = api.postCatalog(catalog)) {
                is ApiResult.AuthFailed -> return SyncOutcome.AuthFailed(result.code)
                is ApiResult.RetryableFailure, is ApiResult.Fatal -> {
                    // Catalog refresh failed but answers may still sync; keep going.
                }
                is ApiResult.Ok -> Unit
            }
        }

        var synced = 0
        var discarded = 0
        var after: Long? = null

        // (b) page loop: pull -> parse -> upsert ONE call -> ONLY THEN ack.
        for (pageNo in 0 until MAX_PAGES) {
            when (val pulled = api.getAnswers(after)) {
                is ApiResult.AuthFailed -> return SyncOutcome.AuthFailed(pulled.code)
                is ApiResult.RetryableFailure -> return SyncOutcome.Retry("getAnswers failed")
                is ApiResult.Fatal -> return SyncOutcome.Fatal("getAnswers fatal")
                is ApiResult.Ok -> {
                    val page = pulled.page
                        ?: return SyncOutcome.Fatal("missing page in Ok result")
                    discarded += page.discarded
                    if (page.answers.isEmpty()) {
                        // Empty page terminates the loop (server delivers pending-ack until empty).
                        return SyncOutcome.Success(synced, discarded)
                    }
                    val entries = JournalSyncProtocol.toJournalEntries(page.answers)
                    // Commit FIRST; any exception escapes as Retry with no ack sent.
                    try {
                        store.upsert(entries)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        return SyncOutcome.Retry("upsert failed: ${error.javaClass.simpleName}")
                    }
                    // Commit succeeded — now (and only now) ack the valid ids.
                    val ids = page.answers.map { it.id }
                    when (val ack = api.postAck(ids)) {
                        is ApiResult.Ok -> Unit
                        else -> return SyncOutcome.Retry("ack failed after upsert")
                    }
                    synced += ids.size
                    after = page.next
                }
            }
        }
        return SyncOutcome.Retry("page limit $MAX_PAGES reached")
    }
}
