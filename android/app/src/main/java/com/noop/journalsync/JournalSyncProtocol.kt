package com.noop.journalsync

import com.noop.data.JournalEntry
import com.noop.ui.JOURNAL_DEVICE_ID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure models + tolerant parser for the journal-sync server responses
 * (spec zhoop-journal-sync-SPEC.md §4; server: /root/infra/services/journal-sync).
 *
 * `GET /v1/answers` -> {"answers":[{id,day,question,answered_yes,numeric_value,notes}], "next":<id>}
 *
 * A malformed record (missing id/day/question, wrong types, day not YYYY-MM-DD) is DISCARDED
 * and counted; the remaining records still parse. No Android types here — JVM-testable.
 */
object JournalSyncProtocol {

    /** One parsed answer as delivered by the server. */
    data class Answer(
        val id: Long,
        val day: String,
        val question: String,
        val answeredYes: Boolean,
        val numericValue: Double?,
        val notes: String?,
    )

    /** Result of parsing one page: the good records plus how many were discarded. */
    data class Page(
        val answers: List<Answer>,
        val discarded: Int,
        val next: Long?,
    )

    sealed interface ParseResult {
        data class Ok(val page: Page) : ParseResult
        data class Malformed(val reason: String) : ParseResult
    }

    /**
     * Parses a `GET /v1/answers` body. Whole-response shape problems (non-JSON, missing or
     * non-array "answers") are [ParseResult.Malformed]; per-record problems only discard
     * that record (tolerant parse, spec §3.4).
     */
    fun parseAnswersPage(body: String): ParseResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return ParseResult.Malformed("response is not a JSON object")
        val array = root.optJSONArray("answers")
            ?: return ParseResult.Malformed("missing answers[]")
        val out = ArrayList<Answer>()
        var discarded = 0
        for (i in 0 until array.length()) {
            val record = array.optJSONObject(i)
            val parsed = record?.let { parseAnswer(it) }
            if (parsed == null) {
                discarded++
            } else {
                out.add(parsed)
            }
        }
        // `next` is only a pagination marker on this server (ack + re-request drives delivery),
        // but keep it so the runner can pass it back verbatim.
        val next = root.optLong("next", -1L).takeIf { it >= 0 }
        return ParseResult.Ok(Page(out, discarded, next))
    }

    /** Parses one answer record; null = discard. answered_yes arrives as 0/1 or bool. */
    fun parseAnswer(record: JSONObject): Answer? {
        val id = record.optLong("id", -1L)
        if (id <= 0) return null
        val day = record.optString("day", "")
        if (!isIsoDay(day)) return null
        val question = record.optString("question", "")
        if (question.isBlank()) return null
        // JSONObject.NULL (explicit null in the wire) must read as absent, not as a sentinel
        // object: opt() returns the NULL marker, so gate each nullable field with isNull().
        val answeredYes = when (val raw = record.opt("answered_yes")) {
            is Boolean -> raw
            is Int -> raw == 1
            is Long -> raw == 1L
            else -> return null
        }
        val numericValue = if (record.isNull("numeric_value")) null else
            when (val raw = record.opt("numeric_value")) {
                is Double -> raw.takeUnless { it.isNaN() || it.isInfinite() }
                is Int -> raw.toDouble()
                is Long -> raw.toDouble()
                else -> return null
            }
        val notes = if (record.isNull("notes")) null else
            when (val raw = record.opt("notes")) {
                is String -> raw
                else -> return null
            }
        return Answer(id, day, question, answeredYes, numericValue, notes)
    }

    /**
     * Maps a parsed answer to a Room row. Convention: a numeric log implies yes
     * (mirrors the server and the native journal, Entities.kt numericValue doc).
     */
    fun toJournalEntry(answer: Answer): JournalEntry = JournalEntry(
        deviceId = JOURNAL_DEVICE_ID,
        day = answer.day,
        question = answer.question,
        answeredYes = answer.answeredYes || answer.numericValue != null,
        notes = answer.notes,
        numericValue = answer.numericValue,
    )

    fun toJournalEntries(answers: List<Answer>): List<JournalEntry> = answers.map(::toJournalEntry)

    /** Strict YYYY-MM-DD calendar check (mirrors the server's strptime contract). */
    fun isIsoDay(value: String): Boolean {
        if (value.length != 10) return false
        if (value[4] != '-' || value[7] != '-') return false
        for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)) {
            if (!value[i].isDigit()) return false
        }
        val month = value.substring(5, 7).toIntOrNull() ?: return false
        val day = value.substring(8, 10).toIntOrNull() ?: return false
        return month in 1..12 && day in 1..31
    }

    /** One pending (unanswered) ask as delivered by `GET /v1/pending`. */
    data class PendingAsk(
        val askId: Long,
        val day: String,
        val question: String,
        val kind: String?,
    )

    /** Parsed `GET /v1/pending` body. */
    data class PendingPage(val asks: List<PendingAsk>)

    /**
     * Tolerant parser for `GET /v1/pending` -> {"asks":[{ask_id,day,question,status,asked_at,
     * expires_at,kind}]}. A malformed record only discards itself; whole-shape problems
     * (non-JSON, missing "asks") are Malformed, mirroring [parseAnswersPage].
     */
    sealed interface PendingParseResult {
        data class Ok(val page: PendingPage) : PendingParseResult
        data class Malformed(val reason: String) : PendingParseResult
    }

    fun parsePendingPage(body: String): PendingParseResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return PendingParseResult.Malformed("response is not a JSON object")
        val array = root.optJSONArray("asks")
            ?: return PendingParseResult.Malformed("missing asks[]")
        val out = ArrayList<PendingAsk>()
        for (i in 0 until array.length()) {
            val record = array.optJSONObject(i)?.let { parsePendingAsk(it) }
            if (record != null) out.add(record)
        }
        return PendingParseResult.Ok(PendingPage(out))
    }

    /** One pending ask; null = discard (bad id/day/question). Extra fields are ignored. */
    fun parsePendingAsk(record: JSONObject): PendingAsk? {
        val askId = record.optLong("ask_id", -1L)
        if (askId <= 0) return null
        val day = record.optString("day", "")
        if (!isIsoDay(day)) return null
        val question = record.optString("question", "")
        if (question.isBlank()) return null
        val kind = if (record.isNull("kind")) null else
            when (val raw = record.opt("kind")) {
                is String -> raw
                else -> null
            }
        return PendingAsk(askId, day, question, kind)
    }

    /** Catalog payload for `POST /v1/catalog`: {"questions":[{question,kind,unit?,group?}]}. */
    fun encodeCatalog(questions: List<CatalogQuestion>): String {
        val array = JSONArray()
        for (q in questions) {
            val item = JSONObject()
            item.put("question", q.question)
            item.put("kind", q.kind)
            q.unit?.let { item.put("unit", it) }
            q.group?.let { item.put("group", it) }
            array.put(item)
        }
        return JSONObject().put("questions", array).toString()
    }

    fun encodeAck(ids: List<Long>): String {
        val array = JSONArray()
        for (id in ids) array.put(id)
        return JSONObject().put("ids", array).toString()
    }

    /** One catalog entry as the server expects it (kind: yes_no | numeric). */
    data class CatalogQuestion(
        val question: String,
        val kind: String,
        val unit: String? = null,
        val group: String? = null,
    )

    /** ZJS-F6: the server's full SSOT catalog plus its stable content hash. */
    data class RemoteCatalog(
        val questions: List<CatalogQuestion>,
        val hash: String?,
    )

    sealed interface CatalogParseResult {
        data class Ok(val catalog: RemoteCatalog) : CatalogParseResult
        data class Malformed(val reason: String) : CatalogParseResult
    }

    /**
     * Tolerant parser for the catalog payload returned by GET/POST /v1/catalog
     * (ZJS-F6): {"questions":[{question,kind,unit?,group?}], "catalog_hash":"..."}.
     * Whole-shape problems (non-JSON, missing/non-array "questions") are Malformed;
     * a malformed record only discards itself, mirroring [parseAnswersPage].
     */
    fun parseCatalog(body: String): CatalogParseResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return CatalogParseResult.Malformed("response is not a JSON object")
        val array = root.optJSONArray("questions")
            ?: return CatalogParseResult.Malformed("missing questions[]")
        val out = ArrayList<CatalogQuestion>()
        for (i in 0 until array.length()) {
            val record = array.optJSONObject(i)?.let { parseCatalogQuestion(it) }
            if (record != null) out.add(record)
        }
        val hash = if (root.isNull("catalog_hash")) null else
            when (val raw = root.opt("catalog_hash")) {
                is String -> raw.takeIf { it.isNotBlank() }
                else -> null
            }
        return CatalogParseResult.Ok(RemoteCatalog(out, hash))
    }

    /** One catalog record; null = discard (blank question / bad kind). */
    fun parseCatalogQuestion(record: JSONObject): CatalogQuestion? {
        val question = record.optString("question", "")
        if (question.isBlank()) return null
        val kind = record.optString("kind", "")
        if (kind != "yes_no" && kind != "numeric") return null
        val unit = if (record.isNull("unit")) null else
            (record.opt("unit") as? String)
        val group = if (record.isNull("group")) null else
            (record.opt("group") as? String)
        return CatalogQuestion(question, kind, unit, group)
    }
}
