package org.adaway.db.entity

/**
 * A row of the host lists, reduced to what updating its source needs: its id, and its host to
 * read the rows of the source page by page.
 */
data class SourceRow(
    val id: Long,
    val host: String
)
