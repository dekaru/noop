package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZJS-S1: the pure half of the global section search — accent folding, case folding and the
 * substring filter over the [SearchEntry] index. Pure data in / out, so it runs in the plain-JVM
 * suite (no Robolectric): the composable resolves strings, this class owns the matching rules.
 */
class SearchScreenFilterTest {

    private fun entries(vararg titles: String) =
        titles.mapIndexed { i, t -> SearchEntry(Destination.Today, t, if (i % 2 == 0) "Insights" else null) }

    // --- normalizeForSearch ---

    @Test
    fun normalizationStripsDiacritics() {
        assertEquals("seccion", normalizeForSearch("Sección"))
        assertEquals("configure", normalizeForSearch("configuré"))
        assertEquals("grosso", normalizeForSearch("GRossö"))
    }

    @Test
    fun normalizationIsCaseInsensitive() {
        assertEquals(normalizeForSearch("Sleep"), normalizeForSearch("sleep"))
        assertEquals("sleep", normalizeForSearch("SLEEP"))
    }

    @Test
    fun normalizationKeepsPlainTextIntact() {
        assertEquals("workouts", normalizeForSearch("workouts"))
    }

    // --- searchSections: matching ---

    @Test
    fun matchesSubstringCaseInsensitive() {
        val idx = entries("Sleep", "Workouts", "Data sources")
        assertEquals(listOf("Sleep"), searchSections(idx, "slee").map { it.title })
        assertEquals(listOf("Workouts"), searchSections(idx, "WORK").map { it.title })
    }

    @Test
    fun matchesWithoutAccents() {
        val idx = entries("Sección")
        assertTrue(searchSections(idx, "seccion").isNotEmpty())
        assertTrue(searchSections(idx, "sección").isNotEmpty())
    }

    @Test
    fun noMatchYieldsEmptyList() {
        assertTrue(searchSections(entries("Sleep"), "zzz").isEmpty())
    }

    // --- searchSections: empty query returns the full index ---

    @Test
    fun blankQueryReturnsEverything() {
        val idx = entries("Sleep", "Workouts")
        assertEquals(idx, searchSections(idx, ""))
        assertEquals(idx, searchSections(idx, "   "))
    }

    // --- buildSearchIndex ---

    @Test
    fun indexCoversEveryDestinationExactlyOnce() {
        val titles = Destination.entries.associateWith { it.name }
        val idx = buildSearchIndex(titles, emptyMap())
        assertEquals(Destination.entries.toList(), idx.map { it.dest })
    }

    @Test
    fun indexAttachesGroupLabelForGroupedDestinations() {
        val titles = Destination.entries.associateWith { it.name }
        val labels = drawerGroups.associate { it.header to it.header }
        val idx = buildSearchIndex(titles, labels)
        val byDest = idx.associateBy { it.dest }
        assertEquals("Insights", byDest[Destination.InsightsHub]!!.groupLabel)
        // More / Search are deliberately outside every group.
        assertEquals(null, byDest[Destination.More]!!.groupLabel)
        assertEquals(null, byDest[Destination.Search]!!.groupLabel)
    }

    @Test
    fun searchIsNotInAnyDrawerGroup() {
        assertFalse(drawerGroups.flatMap { it.items }.contains(Destination.Search))
    }
}
