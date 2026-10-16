package org.adaway.db.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * The queries listing the hosts of a list, each host once with the ids of every source listing
 * it, read through [HostListItemDao.loadList].
 *
 * They are built rather than declared, as the condition a search puts on the hosts depends on its
 * options. Without a search they have no condition on the hosts at all.
 */
object HostListQueries {
    /**
     * Load one list.
     *
     * With a single min() in a grouped query, SQLite takes the other columns from the row holding
     * the minimum. The user's source has the lowest id, so a host the user added is always shown
     * as the user's own row, which stays editable, even when a source lists it too.
     *
     * @param type      The type of the list.
     * @param condition A condition on the `host` column, or `null` to keep every host.
     * @param argument  The argument the condition binds.
     */
    fun list(type: Int, condition: String?, argument: String?): SupportSQLiteQuery {
        val args = mutableListOf<Any>(type)
        val where = StringBuilder("type = ?")
        appendCondition(where, args, condition, argument)
        return SimpleSQLiteQuery(
            "SELECT id, host, type, enabled, redirection, MIN(source_id) AS source_id, " +
                "GROUP_CONCAT(DISTINCT source_id) AS source_ids FROM hosts_lists " +
                "WHERE $where GROUP BY host ORDER BY host ASC",
            args.toTypedArray()
        )
    }

    /**
     * Load the hosts one source lists.
     *
     * The source, type and host index returns them in host order without a sort. Each host's
     * sources are then looked up by type and host, so the other sources listing it are named too.
     *
     * @param type      The type of the list.
     * @param sourceId  The id of the source.
     * @param condition A condition on the `host` column, or `null` to keep every host.
     * @param argument  The argument the condition binds.
     */
    fun sourceList(type: Int, sourceId: Int, condition: String?, argument: String?): SupportSQLiteQuery {
        val args = mutableListOf<Any>(type, sourceId, type)
        val where = StringBuilder("source_id = ? AND type = ?")
        appendCondition(where, args, condition, argument)
        return SimpleSQLiteQuery(
            "SELECT id, host, type, enabled, redirection, source_id, " +
                "(SELECT GROUP_CONCAT(DISTINCT other.source_id) FROM hosts_lists AS other " +
                "WHERE other.type = ? AND other.host = hosts_lists.host) AS source_ids " +
                "FROM hosts_lists WHERE $where GROUP BY host ORDER BY host ASC",
            args.toTypedArray()
        )
    }

    private fun appendCondition(
        where: StringBuilder,
        args: MutableList<Any>,
        condition: String?,
        argument: String?
    ) {
        if (condition != null && argument != null) {
            where.append(" AND ").append(condition)
            args.add(argument)
        }
    }
}
