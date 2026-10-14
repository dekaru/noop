package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZJS-F9: the "from last night" prefill must not clone synced answers into today's rows.
 */
class JournalPrefillTest {

    private val yesterday = mapOf("Alcohol" to false, "Read before bed" to true)

    @Test
    fun `sync active with yesterday rows shows banner only, no upsert`() {
        val decision = shouldPrefillFromYesterday(
            answers = emptyMap(),
            dayOffset = 0L,
            yesterdayAnswers = yesterday,
            syncedQuestions = yesterday.keys,
        )
        assertEquals(PrefillDecision.BannerOnly, decision)
    }

    @Test
    fun `sync inactive with yesterday rows upserts as today (original behaviour)`() {
        val decision = shouldPrefillFromYesterday(
            answers = emptyMap(),
            dayOffset = 0L,
            yesterdayAnswers = yesterday,
            syncedQuestions = emptySet(),
        )
        assertEquals(
            PrefillDecision.PrefillLocal(answers = yesterday, persist = yesterday),
            decision,
        )
    }

    @Test
    fun `banner reload never supplies answers and real today answers stop prefill`() {
        repeat(3) {
            assertEquals(
                PrefillDecision.BannerOnly,
                shouldPrefillFromYesterday(emptyMap(), 0L, yesterday, yesterday.keys),
            )
        }
        assertEquals(
            PrefillDecision.NoPrefill,
            shouldPrefillFromYesterday(mapOf("Alcohol" to true), 0L, yesterday, yesterday.keys),
        )
    }

    @Test
    fun `no yesterday rows never prefills`() {
        assertEquals(
            PrefillDecision.NoPrefill,
            shouldPrefillFromYesterday(emptyMap(), 0L, emptyMap(), emptySet()),
        )
    }

    @Test
    fun `non-today day offset never prefills`() {
        for (offset in listOf(-1L, 1L, -3L)) {
            val decision = shouldPrefillFromYesterday(
                answers = emptyMap(),
                dayOffset = offset,
                yesterdayAnswers = yesterday,
                syncedQuestions = emptySet(),
            )
            assertEquals(PrefillDecision.NoPrefill, decision)
        }
    }

    @Test
    fun `today already has answers never prefills`() {
        val decision = shouldPrefillFromYesterday(
            answers = mapOf("Alcohol" to true),
            dayOffset = 0L,
            yesterdayAnswers = yesterday,
            syncedQuestions = emptySet(),
        )
        assertEquals(PrefillDecision.NoPrefill, decision)
    }
}
