package com.noop.journalsync

import com.noop.ui.JOURNAL_DEVICE_ID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSyncProtocolTest {

    // (T1) tolerant parser: malformed records are discarded and counted; good ones survive.
    @Test
    fun parserDiscardsMalformedAndKeepsGood() {
        val array = JSONArray()
        array.put(answerJson(1))
        array.put(JSONObject().put("id", 2)) // missing day and question
        array.put(JSONObject().put("day", "2026-10-01").put("question", "x")) // missing id
        array.put(answerJson(3).put("day", "01-10-2026")) // bad day format
        array.put(answerJson(4).put("answered_yes", "yes")) // wrong type
        array.put(answerJson(5).put("numeric_value", "12")) // wrong type
        array.put(JSONObject("{\"not\":\"an answer object\"}"))
        val body = JSONObject().put("answers", array).put("next", 5).toString()

        val result = JournalSyncProtocol.parseAnswersPage(body)

        assertTrue(result is JournalSyncProtocol.ParseResult.Ok)
        val page = (result as JournalSyncProtocol.ParseResult.Ok).page
        assertEquals(6, page.discarded)
        // only id 1 survives: id 3 carried an invalid day and is counted as discarded
        assertEquals(listOf(1L), page.answers.map { it.id })
        assertEquals(5L, page.next)
    }

    @Test
    fun wholeResponseShapeFailureIsMalformed() {
        assertTrue(JournalSyncProtocol.parseAnswersPage("not json")
            is JournalSyncProtocol.ParseResult.Malformed)
        assertTrue(JournalSyncProtocol.parseAnswersPage(JSONObject().put("other", 1).toString())
            is JournalSyncProtocol.ParseResult.Malformed)
    }

    // (T2) mapper: numeric implies yes; deviceId is the native journal source; notes carried.
    @Test
    fun mapperNumericImpliesYesAndUsesJournalDeviceId() {
        val answer = answer(9, numericValue = 2.0, answeredYes = false, notes = "two cups")
        val entry = JournalSyncProtocol.toJournalEntry(answer)
        assertTrue(entry.answeredYes)
        assertEquals(2.0, entry.numericValue!!, 0.0)
        assertEquals("two cups", entry.notes)
        assertEquals(JOURNAL_DEVICE_ID, entry.deviceId)
    }

    @Test
    fun answeredYesZeroOneOrBoolAccepted() {
        assertTrue(
            JournalSyncProtocol.parseAnswer(
                answerJson(1).put("answered_yes", 1),
            )!!.answeredYes,
        )
        assertTrue(
            !JournalSyncProtocol.parseAnswer(
                answerJson(2).put("answered_yes", 0),
            )!!.answeredYes,
        )
        assertTrue(
            JournalSyncProtocol.parseAnswer(
                answerJson(3).put("answered_yes", true),
            )!!.answeredYes,
        )
    }

    // (T2 / condition E) the deviceId contract with the native journal table.
    @Test
    fun journalDeviceIdContract() {
        assertEquals("noop-journal", JOURNAL_DEVICE_ID)
    }

    @Test
    fun isIsoDayStrict() {
        assertTrue(JournalSyncProtocol.isIsoDay("2026-10-01"))
        assertTrue(!JournalSyncProtocol.isIsoDay("2026-13-01"))
        assertTrue(!JournalSyncProtocol.isIsoDay("2026-10-32"))
        assertTrue(!JournalSyncProtocol.isIsoDay("20261001"))
        assertTrue(!JournalSyncProtocol.isIsoDay("2026/10/01"))
    }

    @Test
    fun catalogAndAckEncoding() {
        val body = JournalSyncProtocol.encodeCatalog(
            listOf(
                JournalSyncProtocol.CatalogQuestion("Q1", "yes_no", null, "Health"),
                JournalSyncProtocol.CatalogQuestion("Q2", "numeric", "mg"),
            ),
        )
        val root = JSONObject(body)
        val questions = root.getJSONArray("questions")
        assertEquals(2, questions.length())
        assertEquals("Q1", questions.getJSONObject(0).getString("question"))
        assertEquals("yes_no", questions.getJSONObject(0).getString("kind"))
        assertEquals("mg", questions.getJSONObject(1).getString("unit"))

        // JSONArray.map uses opt(): non-Int entries arrive as null and fail the cast,
        // so walk with explicit getInt instead.
        val ids = ArrayList<Long>()
        val ackArray = JSONObject(JournalSyncProtocol.encodeAck(listOf(1L, 2L, 3L))).getJSONArray("ids")
        for (i in 0 until ackArray.length()) ids.add(ackArray.getInt(i).toLong())
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun dayWindowDefaultsCarriedThrough() {
        val page = (JournalSyncProtocol.parseAnswersPage(
            JSONObject()
                .put("answers", JSONArray().put(answerJson(1).put("notes", JSONObject.NULL)))
                .toString(),
        ) as JournalSyncProtocol.ParseResult.Ok).page
        assertNull(page.answers[0].notes)
        assertNull(page.answers[0].numericValue)
    }

    @Test
    fun malformedRecordsIncludeSentinelNulls() {
        // JSONObject.NULL in answered_yes must NOT parse as anything; discard.
        val result = JournalSyncProtocol.parseAnswersPage(
            JSONObject()
                .put(
                    "answers",
                    JSONArray()
                        .put(answerJson(1).put("answered_yes", JSONObject.NULL))
                        .put(answerJson(2).put("numeric_value", JSONObject.NULL))
                        .put(answerJson(3).put("notes", JSONObject.NULL)),
                )
                .toString(),
        ) as JournalSyncProtocol.ParseResult.Ok
        // answered_yes NULL -> discard; numeric/notes NULL -> keep (absent).
        assertEquals(listOf(2L, 3L), result.page.answers.map { it.id })
        assertEquals(1, result.page.discarded)
    }

    private fun answerJson(id: Long): JSONObject =
        JSONObject()
            .put("id", id)
            .put("day", "2026-10-01")
            .put("question", "Did you take magnesium?")
            .put("answered_yes", 1)

    // ---- ZJS-F4: GET /v1/pending tolerant parser ----

    @Test
    fun parsePendingKeepsGoodAndDiscardsMalformed() {
        val array = JSONArray()
        array.put(pendingJson(1))
        array.put(JSONObject().put("ask_id", 2)) // missing day/question
        array.put(pendingJson(3).put("day", "not-a-day"))
        array.put(JSONObject().put("day", "2026-10-02").put("question", "Q")) // missing id
        array.put(pendingJson(5).put("kind", 42)) // bad kind type -> kind null, record kept
        val body = JSONObject().put("asks", array).toString()

        val result = JournalSyncProtocol.parsePendingPage(body)

        assertTrue(result is JournalSyncProtocol.ParseResult.Ok)
        val page = (result as JournalSyncProtocol.ParseResult.Ok).page
        assertEquals(listOf(1L, 5L), page.asks.map { it.askId })
        assertNull(page.asks[1].kind)
        assertEquals("2026-10-01", page.asks[0].day)
        assertEquals("yes_no", page.asks[0].kind)
    }

    @Test
    fun parsePendingWholeShapeFailureIsMalformed() {
        assertTrue(JournalSyncProtocol.parsePendingPage("not json")
            is JournalSyncProtocol.ParseResult.Malformed)
        assertTrue(JournalSyncProtocol.parsePendingPage(JSONObject().put("other", 1).toString())
            is JournalSyncProtocol.ParseResult.Malformed)
        // Empty asks[] is fine (no pending).
        val empty = JournalSyncProtocol.parsePendingPage(
            JSONObject().put("asks", JSONArray()).toString(),
        ) as JournalSyncProtocol.ParseResult.Ok
        assertTrue(empty.page.asks.isEmpty())
    }

    @Test
    fun parsePendingIgnoresExtraFields() {
        val record = pendingJson(9)
            .put("status", "asked")
            .put("asked_at", "2026-10-02T08:00:00Z")
            .put("expires_at", "2026-10-02T20:00:00Z")
        val page = (JournalSyncProtocol.parsePendingPage(
            JSONObject().put("asks", JSONArray().put(record)).toString(),
        ) as JournalSyncProtocol.ParseResult.Ok).page
        assertEquals(1, page.asks.size)
        assertEquals(9L, page.asks[0].askId)
        assertEquals("numeric", page.asks[0].kind)
    }

    private fun pendingJson(askId: Long): JSONObject =
        JSONObject()
            .put("ask_id", askId)
            .put("day", "2026-10-01")
            .put("question", "Did you sleep well?")
            .put("kind", if (askId == 5L) "yes_no" else "numeric")
}
