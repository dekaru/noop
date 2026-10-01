package com.noop.journalsync

import com.noop.data.JournalEntry
import com.noop.ui.JOURNAL_DEVICE_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared JVM fakes for the runner tests (no Robolectric needed: pure logic under test). */

/** Fake API scripted with a queue of page results per call kind. */
class FakeApi : JournalSyncApi {
    val catalogCalls = mutableListOf<List<JournalSyncProtocol.CatalogQuestion>>()
    val ackCalls = mutableListOf<List<Long>>()
    var catalogResult: ApiResult<Unit> = ApiResult.Ok(null)
    val answerResults = ArrayDeque<ApiResult<JournalSyncProtocol.Page>>()

    override suspend fun postCatalog(questions: List<JournalSyncProtocol.CatalogQuestion>): ApiResult<Unit> {
        catalogCalls.add(questions)
        return catalogResult
    }

    override suspend fun getAnswers(after: Long?): ApiResult<JournalSyncProtocol.Page> =
        if (answerResults.isEmpty()) ApiResult.Ok(JournalSyncProtocol.Page(emptyList(), 0, null))
        else answerResults.removeFirst()

    override suspend fun postAck(ids: List<Long>): ApiResult<Unit> {
        ackCalls.add(ids)
        return ApiResult.Ok(null)
    }
}

/** Fake store with the real PK (deviceId, day, question); can be told to throw. */
class FakeStore : JournalStore {
    val rows = LinkedHashMap<Triple<String, String, String>, JournalEntry>()
    var upsertCalls = 0
    var failNextUpsert: Exception? = null

    override suspend fun upsert(rows: List<JournalEntry>) {
        upsertCalls++
        failNextUpsert?.let { throw it }
        for (row in rows) {
            this.rows[Triple(row.deviceId, row.day, row.question)] = row
        }
    }
}

fun pageOf(vararg answers: JournalSyncProtocol.Answer, next: Long? = null): ApiResult.Ok<JournalSyncProtocol.Page> =
    ApiResult.Ok(JournalSyncProtocol.Page(answers.toList(), 0, next))

fun answer(
    id: Long,
    day: String = "2026-10-01",
    question: String = "Did you take magnesium?",
    answeredYes: Boolean = true,
    numericValue: Double? = null,
    notes: String? = null,
) = JournalSyncProtocol.Answer(id, day, question, answeredYes, numericValue, notes)

class JournalSyncRunnerTest {

    // (T3) idempotency: the same page run twice yields the same rows, no duplicates.
    @Test
    fun resyncSamePageDoesNotDuplicate() = runBlocking {
        val api = FakeApi()
        val store = FakeStore()
        val runner = JournalSyncRunner(api, store)
        api.answerResults.addLast(pageOf(answer(1), answer(2, question = "Did you feel stressed?")))

        val first = runner.run()
        assertTrue(first is SyncOutcome.Success)
        assertEquals(2, store.rows.size)

        // Server re-delivers the same rows (e.g. a reset ack): upsert, not insert.
        api.answerResults.addLast(pageOf(answer(1), answer(2, question = "Did you feel stressed?")))
        val second = runner.run()
        assertEquals(SyncOutcome.Success(2, 0), second)
        assertEquals(2, store.rows.size)
        assertEquals(2, store.upsertCalls) // both runs wrote one batch each
        Unit
    }

    // (T4) a pre-existing row from another deviceId stays untouched.
    @Test
    fun otherDeviceRowsStayIntact() = runBlocking {
        val api = FakeApi()
        val store = FakeStore()
        store.upsert(
            listOf(
                JournalEntry("my-whoop", "2026-10-01", "Did you take magnesium?", answeredYes = true),
            ),
        )
        val runner = JournalSyncRunner(api, store)
        api.answerResults.addLast(pageOf(answer(1)))

        runner.run()

        assertEquals(2, store.rows.size)
        val whoopRow = store.rows[Triple("my-whoop", "2026-10-01", "Did you take magnesium?")]
        assertTrue(whoopRow != null)
        assertEquals("my-whoop", whoopRow?.deviceId)
        Unit
    }

    // (T5, condition A) no ack if upsert throws; ack with the right ids after a good upsert.
    @Test
    fun noAckWhenUpsertThrows() = runBlocking {
        val api = FakeApi()
        val store = FakeStore().apply { failNextUpsert = IllegalStateException("disk full") }
        val runner = JournalSyncRunner(api, store)
        api.answerResults.addLast(pageOf(answer(7), answer(8)))

        val outcome = runner.run()

        assertTrue(outcome is SyncOutcome.Retry)
        assertTrue(api.ackCalls.isEmpty())
        // Now let the upsert succeed: ack carries exactly the delivered ids.
        val healthy = JournalSyncRunner(api, FakeStore())
        api.answerResults.addLast(pageOf(answer(7), answer(8)))
        healthy.run()
        assertEquals(listOf(listOf(7L, 8L)), api.ackCalls)
        Unit
    }

    // (T6) 401/403 => AuthFailed outcome, no ack.
    @Test
    fun authFailedOutcomeNoAck() = runBlocking {
        for (code in intArrayOf(401, 403)) {
            val api = FakeApi()
            val store = FakeStore()
            val runner = JournalSyncRunner(api, store)
            api.answerResults.addLast(ApiResult.AuthFailed(code))

            val outcome = runner.run()

            assertTrue("code $code", outcome is SyncOutcome.AuthFailed)
            assertEquals(code, (outcome as SyncOutcome.AuthFailed).code)
            assertTrue(api.ackCalls.isEmpty())
            assertTrue(store.upsertCalls == 0)
        }
        Unit
    }

    // (T7) 5xx and IO-style failures map to Retry.
    @Test
    fun retryableFailures() = runBlocking {
        for (result in listOf(ApiResult.RetryableFailure(500, null), ApiResult.RetryableFailure(429, null))) {
            val api = FakeApi()
            api.answerResults.addLast(result)
            val outcome = JournalSyncRunner(api, FakeStore()).run()
            assertTrue("$result", outcome is SyncOutcome.Retry)
        }
        Unit
    }

    // (T8) empty page terminates the loop; page cap stops an endless server.
    @Test
    fun emptyPageTerminatesAndPageCapStops() = runBlocking {
        val api = FakeApi()
        val runner = JournalSyncRunner(api, FakeStore())
        api.answerResults.addLast(pageOf(answer(1)))
        api.answerResults.addLast(pageOf(answer(2)))
        api.answerResults.addLast(ApiResult.Ok(JournalSyncProtocol.Page(emptyList(), 0, null)))

        val outcome = runner.run()
        assertEquals(SyncOutcome.Success(2, 0), outcome)
        // two data pages acked, one each; the empty page acks nothing
        assertEquals(2, api.ackCalls.size)
        assertEquals(listOf(1L), api.ackCalls[0])
        assertEquals(listOf(2L), api.ackCalls[1])

        // Cap: a server that always returns the same rows must stop at MAX_PAGES.
        val loopApi = FakeApi()
        val loopRunner = JournalSyncRunner(loopApi, FakeStore())
        repeat(JournalSyncRunner.MAX_PAGES) { loopApi.answerResults.addLast(pageOf(answer(1))) }
        val capped = loopRunner.run()
        assertTrue(capped is SyncOutcome.Retry)
        assertEquals(JournalSyncRunner.MAX_PAGES, loopApi.ackCalls.size)
        Unit
    }

    // (T9) a failing postCatalog does not abort the sync.
    @Test
    fun catalogFailureDoesNotAbortSync() = runBlocking {
        val api = FakeApi()
        val store = FakeStore()
        val runner = JournalSyncRunner(api, store)
        api.catalogResult = ApiResult.RetryableFailure(500, null)
        api.answerResults.addLast(pageOf(answer(1)))

        val outcome = runner.run()

        assertEquals(SyncOutcome.Success(1, 0), outcome)
        assertEquals(1, store.rows.size)
        Unit
    }

    // T10-lite: AuthFailed propagates even when only the catalog call is rejected.
    @Test
    fun catalogAuthFailureAborts() = runBlocking {
        val api = FakeApi()
        api.catalogResult = ApiResult.AuthFailed(401)
        val outcome = JournalSyncRunner(api, FakeStore()).run()
        assertTrue(outcome is SyncOutcome.AuthFailed)
        Unit
    }
}
