package org.adaway.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * A host as the lists screen shows it: once, together with every source that lists it.
 *
 * The same host is often listed by several sources, each in its own row. [item] is the one row the
 * screen toggles, edits and deletes. [sourceIds] names the sources of all of them.
 */
data class ListedHost(
    @Embedded
    val item: HostListItem,
    /**
     * The ids of the sources listing the host, comma separated as SQLite's `group_concat` writes
     * them, in no particular order.
     */
    @ColumnInfo(name = "source_ids")
    val sourceIds: String?
)
