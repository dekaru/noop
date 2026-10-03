package com.noop.journalsync

import com.noop.ui.JournalKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZJS-F6: parse of the server SSOT catalog body + merge into the local catalog. */
class CatalogAdoptionTest {

    // -- parsing --

    private fun catalogBody(vararg questions: Triple<String, String, Pair<String, String>?>,
                            hash: String? = "abc123"): String {
        val array = JSONArray()
        for ((q, kind, meta) in questions) {
            val o = JSONObject().put("question", q).put("kind", kind)
            meta?.let {
                o.put("unit", it.first)
                o.put("group", it.second)
            }
            array.put(o)
        }
        val root = JSONObject().put("questions", array)
        hash?.let { root.put("catalog_hash", it) }
        return root.toString()
    }

    @Test
    fun parseCatalogOkWithHash() {
        val result = JournalSyncProtocol.parseCatalog(
            catalogBody(
                Triple("Alpha?", "yes_no", null),
                Triple("Beta?", "numeric", "mg" to "Supplements"),
            ),
        )
        assertTrue(result is JournalSyncProtocol.CatalogParseResult.Ok)
        val catalog = (result as JournalSyncProtocol.CatalogParseResult.Ok).catalog
        assertEquals(2, catalog.questions.size)
        assertEquals("abc123", catalog.hash)
        assertEquals("numeric", catalog.questions[1].kind)
        assertEquals("mg", catalog.questions[1].unit)
    }

    @Test
    fun parseCatalogMalformedShape() {
        assertTrue(JournalSyncProtocol.parseCatalog("not json")
            is JournalSyncProtocol.CatalogParseResult.Malformed)
        assertTrue(JournalSyncProtocol.parseCatalog(JSONObject().put("other", 1).toString())
            is JournalSyncProtocol.CatalogParseResult.Malformed)
    }

    @Test
    fun parseCatalogTolerantPerRecord() {
        val array = JSONArray()
        array.put(JSONObject().put("question", "Good?").put("kind", "yes_no"))
        array.put(JSONObject().put("question", "Bad kind?").put("kind", "bogus"))
        array.put(JSONObject().put("kind", "yes_no")) // missing question
        array.put(JSONObject().put("question", "").put("kind", "yes_no"))
        val body = JSONObject().put("questions", array).put("catalog_hash", "h").toString()
        val result = JournalSyncProtocol.parseCatalog(body)
        val catalog = (result as JournalSyncProtocol.CatalogParseResult.Ok).catalog
        assertEquals(listOf("Good?"), catalog.questions.map { it.question })
        assertEquals("h", catalog.hash)
    }

    @Test
    fun parseCatalogNullAndMissingHash() {
        val result = JournalSyncProtocol.parseCatalog(catalogBody(hash = null))
        val catalog = (result as JournalSyncProtocol.CatalogParseResult.Ok).catalog
        assertNull(catalog.hash)
    }

    // -- merge --

    private fun local(vararg canonicals: String, hidden: Boolean = false, custom: Boolean = false) =
        canonicals.mapIndexed { i, q ->
            com.noop.ui.JournalCatalogItem(
                canonical = q, kind = JournalKind.Bool,
                group = com.noop.ui.JournalGroup.Other,
                sortIndex = i, hidden = hidden, custom = custom,
            )
        }

    private fun remote(vararg questions: String) =
        questions.map { JournalSyncProtocol.CatalogQuestion(question = it, kind = "yes_no") }

    @Test
    fun localWinsOnConflict() {
        val localItems = listOf(
            com.noop.ui.JournalCatalogItem(
                canonical = "Did you take magnesium?",
                displayName = "Magnesio",
                kind = JournalKind.Numeric("mg"),
                group = com.noop.ui.JournalGroup.Supplements,
                sortIndex = 0,
                custom = true,
            ),
        )
        val merged = CatalogAdoption.mergeRemoteCatalog(
            remote("Did you take magnesium?"),
            localItems,
        )
        assertEquals(1, merged.size)
        assertEquals("Magnesio", merged[0].displayName)
        assertEquals(JournalKind.Numeric("mg"), merged[0].kind)
        assertEquals(com.noop.ui.JournalGroup.Supplements, merged[0].group)
        assertTrue(merged[0].custom)
    }

    @Test
    fun newServerQuestionAddedWithServerKindUnitGroup() {
        val merged = CatalogAdoption.mergeRemoteCatalog(
            listOf(
                JournalSyncProtocol.CatalogQuestion("Resting heart rate?", "numeric", "bpm", "Health"),
                JournalSyncProtocol.CatalogQuestion("New yes/no?", "yes_no"),
            ),
            local("Did you take magnesium?"),
        )
        assertEquals(3, merged.size)
        val rhr = merged.first { it.canonical == "Resting heart rate?" }
        assertEquals(JournalKind.Numeric("bpm"), rhr.kind)
        assertEquals(com.noop.ui.JournalGroup.Health, rhr.group)
        assertFalse(rhr.custom)
        assertFalse(rhr.hidden)
    }

    @Test
    fun customNeverDeletedAndHiddenRespected() {
        val localItems = local("My custom?", custom = true) +
            local("Did you view a screen in bed?", hidden = true)
        val merged = CatalogAdoption.mergeRemoteCatalog(
            remote("My custom?", "Did you view a screen in bed?", "Brand new?"),
            localItems,
        )
        // local custom survives, hidden local stays hidden, server addition lands
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.canonical == "My custom?" && it.custom })
        assertTrue(merged.any { it.canonical == "Did you view a screen in bed?" && it.hidden })
        assertTrue(merged.any { it.canonical == "Brand new?" && !it.hidden })
    }

    @Test
    fun normKeyMatchNoDuplicate() {
        val merged = CatalogAdoption.mergeRemoteCatalog(
            remote("Did you take magnesium?"),
            local("  did you take MAGNESIUM? "),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun emptyRemoteIsNoOp() {
        val localItems = local("A?")
        assertTrue(CatalogAdoption.mergeRemoteCatalog(emptyList(), localItems) === localItems ||
            CatalogAdoption.mergeRemoteCatalog(emptyList(), localItems) == localItems)
    }
}
