package org.adaway.ui.lists

/**
 * Turn the source ids SQLite's `group_concat` wrote for a host into the names shown under it.
 *
 * Ids missing from [labels] are dropped. The user's own source is never in it, so a host that only
 * the user added gets no label and keeps its usual look.
 *
 * @param sourceIds The comma separated source ids, `null` when there are none.
 * @param labels The source names by source id.
 * @return The names, sorted alphabetically.
 */
fun sourceLabelsOf(sourceIds: String?, labels: Map<Int, String>): List<String> {
    if (sourceIds.isNullOrEmpty()) {
        return emptyList()
    }
    return sourceIds.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .distinct()
        .mapNotNull { labels[it] }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
}
