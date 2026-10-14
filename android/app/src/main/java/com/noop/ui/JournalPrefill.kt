package com.noop.ui

/**
 * ZJS-F9: outcome of the "from last night" prefill when opening today's journal.
 *
 * [PrefillLocal] persists [PrefillLocal.persist] as real rows for today; [BannerOnly] only shows
 * an informational banner without selecting yesterday's answers or writing anything.
 */
sealed interface PrefillDecision {
    data object NoPrefill : PrefillDecision

    data class PrefillLocal(
        val answers: Map<String, Boolean>,
        val persist: Map<String, Boolean>,
    ) : PrefillDecision

    data object BannerOnly : PrefillDecision
}

/**
 * ZJS-F9: yesterday's answers that came from the journal sync must never be cloned as today's rows.
 * The sync marks no per-question origin, so callers pass every yesterday question as synced while the
 * sync is enabled; with sync off, nothing is synced and the original upsert prefill applies.
 */
fun shouldPrefillFromYesterday(
    answers: Map<String, Boolean>,
    dayOffset: Long,
    yesterdayAnswers: Map<String, Boolean>,
    syncedQuestions: Set<String>,
): PrefillDecision {
    if (dayOffset != 0L || answers.isNotEmpty() || yesterdayAnswers.isEmpty()) {
        return PrefillDecision.NoPrefill
    }
    val localOnly = yesterdayAnswers.filterKeys { it !in syncedQuestions }
    return if (localOnly.isEmpty()) {
        PrefillDecision.BannerOnly
    } else {
        PrefillDecision.PrefillLocal(answers = yesterdayAnswers, persist = localOnly)
    }
}
