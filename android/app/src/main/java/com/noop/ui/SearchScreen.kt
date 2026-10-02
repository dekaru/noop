package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/**
 * ZJS-S1: global section search.
 *
 * One flat index over every [Destination] with its localized title and (where it has one) its
 * [drawerGroups] label. The matching itself is PURE — [normalizeForSearch] + [searchSections] take
 * and return plain data, so the accent-folding / case rules are testable in the plain-JVM suite
 * exactly like [MoreSectionPrefs] — while the composable only resolves strings and renders.
 */

/** Case- and accent-insensitive normalization: NFD decompose, drop combining marks, lowercase. */
internal fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/** One row of the search index: a destination, its localized title, its group's localized label. */
internal data class SearchEntry(
    val dest: Destination,
    val title: String,
    val groupLabel: String?,
)

/**
 * The filter: blank query returns EVERYTHING (the index doubles as a section index); otherwise a
 * case- and accent-insensitive substring match on the normalized localized title.
 */
internal fun searchSections(entries: List<SearchEntry>, query: String): List<SearchEntry> {
    val needle = normalizeForSearch(query).trim()
    if (needle.isEmpty()) return entries
    return entries.filter { normalizeForSearch(it.title).contains(needle) }
}

/** The full index: every destination once, groups in drawer order, groupless destinations last. */
internal fun buildSearchIndex(
    titles: Map<Destination, String>,
    groupLabels: Map<String, String>,
): List<SearchEntry> {
    val seen = mutableSetOf<Destination>()
    val entries = mutableListOf<SearchEntry>()
    drawerGroups.forEach { group ->
        group.items.forEach { dest ->
            if (seen.add(dest)) {
                entries += SearchEntry(dest, titles.getValue(dest), groupLabels[group.header])
            }
        }
    }
    // Destinations deliberately outside [drawerGroups] (contextual / experimental screens) are still
    // searchable — the point of the index is that nothing is more than a query away.
    Destination.entries.forEach { dest ->
        if (seen.add(dest)) {
            entries += SearchEntry(dest, titles.getValue(dest), groupLabel = null)
        }
    }
    return entries
}

/** The search page: an auto-focused field over a live-filtered, tappable list of results. */
@Composable
internal fun SearchScreen(onNavigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Resolve every localized title/group label ONCE per locale change; the filter itself is pure.
    val titles = remember {
        Destination.entries.associateWith { uiString(it.titleRes) }
    }
    val groupLabels = remember {
        drawerGroups.associate { it.header to uiString(it.headerRes) }
    }
    val results = remember(query, titles, groupLabels) {
        searchSections(buildSearchIndex(titles, groupLabels), query)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 28.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("search_field"),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(),
        )
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty()) {
            Text(
                stringResource(R.string.search_no_results, query),
                style = NoopType.body,
                color = Palette.textSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(results, key = { it.dest.route }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                keyboard?.hide()
                                onNavigate(entry.dest.route)
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            entry.dest.icon,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.width(24.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            entry.title,
                            style = NoopType.body,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.groupLabel != null) {
                            Text(entry.groupLabel, style = NoopType.caption, color = Palette.textTertiary)
                        }
                    }
                }
            }
        }
    }
}
