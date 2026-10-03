package com.noop.journalsync

import android.content.Context
import com.noop.ui.JournalCatalogItem
import com.noop.ui.JournalGroup
import com.noop.ui.JournalKind
import com.noop.ui.loadJournalCatalogItems
import com.noop.ui.normJournalKey
import com.noop.ui.saveJournalCatalogItems

/**
 * ZJS-F6: adoption of the server's SSOT catalog into the local journal catalog.
 *
 * Merge rules: the LOCAL item wins on conflict (matched by normJournalKey); the server only
 * ADDS questions missing locally, carrying the server's kind/unit/group. Customs, renames,
 * hidden flags and every other local property are NEVER overwritten or deleted — a locally
 * hidden question that also exists as a server starter simply stays hidden.
 */
object CatalogAdoption {

    private const val APPLIED_HASH_KEY = "noop.journalSync.catalogHash"

    /** Pure merge (JVM-testable). Order: locals keep their sortIndex; server additions append. */
    fun mergeRemoteCatalog(
        remote: List<JournalSyncProtocol.CatalogQuestion>,
        local: List<JournalCatalogItem>,
    ): List<JournalCatalogItem> {
        val byKey = HashMap<String, JournalCatalogItem>()
        for (it in local) byKey[normJournalKey(it.canonical)] = it
        var next = (local.maxOfOrNull { it.sortIndex } ?: -1) + 1
        for (q in remote) {
            val key = normJournalKey(q.question)
            if (byKey.containsKey(key)) continue
            val item = JournalCatalogItem(
                canonical = q.question,
                kind = if (q.kind == "numeric") JournalKind.Numeric(q.unit) else JournalKind.Bool,
                group = JournalGroup.fromKey(q.group ?: "Other"),
                sortIndex = next++,
                hidden = false,
                custom = false,
            )
            byKey[key] = item
        }
        // Preserve the local list's order; additions go after in server order.
        val mergedKeys = local.map { normJournalKey(it.canonical) }.toMutableSet()
        val additions = remote.mapNotNull { q ->
            val key = normJournalKey(q.question)
            if (mergedKeys.contains(key)) {
                null
            } else {
                mergedKeys.add(key)
                byKey[key]
            }
        }
        return local + additions
    }

    /** The last server catalog hash applied to the local store, or null. */
    fun appliedHash(context: Context): String? =
        context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
            .getString(APPLIED_HASH_KEY, null)

    /** True when this server hash was already merged into the local store. */
    fun alreadyApplied(context: Context, hash: String?): Boolean {
        if (hash.isNullOrBlank()) return false
        return appliedHash(context) == hash
    }

    /**
     * Adopt the remote catalog into prefs: merge, persist, remember the applied hash.
     * Callers gate on JournalSyncSettings.enabled (never adopt when sync is off).
     */
    fun applyRemoteCatalog(context: Context, remote: JournalSyncProtocol.RemoteCatalog) {
        if (remote.questions.isEmpty()) return
        val current = loadJournalCatalogItems(context)
        val merged = mergeRemoteCatalog(remote.questions, current)
        saveJournalCatalogItems(context, merged)
        remote.hash?.let {
            context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
                .edit().putString(APPLIED_HASH_KEY, it).apply()
        }
    }
}
