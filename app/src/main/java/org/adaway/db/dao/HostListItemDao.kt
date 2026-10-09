package org.adaway.db.dao

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListedHost
import java.util.Optional

@Dao
interface HostListItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg item: HostListItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(items: List<HostListItem>)

    @Update
    fun update(item: HostListItem)

    @Delete
    fun delete(item: HostListItem)

    @Query("DELETE FROM hosts_lists WHERE source_id = 1 AND host = :host")
    fun deleteUserFromHost(host: String)

    /**
     * Load one list, each host once, with the ids of every source that lists it.
     *
     * With a single min() in a grouped query, SQLite takes the other columns from the row holding
     * the minimum. The user's source has the lowest id, so a host the user added is always shown
     * as the user's own row, which stays editable, even when a source lists it too.
     */
    @Query("SELECT id, host, type, enabled, redirection, MIN(source_id) AS source_id, GROUP_CONCAT(DISTINCT source_id) AS source_ids FROM hosts_lists WHERE type = :type AND host LIKE :query GROUP BY host ORDER BY host ASC")
    fun loadList(type: Int, query: String): PagingSource<Int, ListedHost>

    /**
     * Load the hosts one source lists, each once, with the ids of every source that lists it.
     *
     * The source, type and host index returns them in host order without a sort. Each host's
     * sources are then looked up by type and host, so the other sources listing it are named too.
     */
    @Query("SELECT id, host, type, enabled, redirection, source_id, (SELECT GROUP_CONCAT(DISTINCT other.source_id) FROM hosts_lists AS other WHERE other.type = :type AND other.host = hosts_lists.host) AS source_ids FROM hosts_lists WHERE source_id = :sourceId AND type = :type AND host LIKE :query GROUP BY host ORDER BY host ASC")
    fun loadSourceList(type: Int, sourceId: Int, query: String): PagingSource<Int, ListedHost>

    @get:Query("SELECT * FROM hosts_lists WHERE source_id = 1")
    val userList: List<HostListItem>

    @Query("SELECT id FROM hosts_lists WHERE host = :host AND source_id = 1 LIMIT 1")
    fun getHostId(host: String): Optional<Int>

    /**
     * Count the distinct enabled hosts of a type.
     * Expensive over millions of rows, so its result is cached rather than read on every display.
     */
    @Query("SELECT COUNT(DISTINCT host) FROM hosts_lists WHERE type = :type AND enabled = 1")
    fun countHosts(type: Int): Int

    @Query("SELECT COUNT(DISTINCT host) FROM hosts_lists WHERE type = 0 AND enabled = 1")
    fun getBlockedHostCount(): LiveData<Int>

    @Query("SELECT COUNT(DISTINCT host) FROM hosts_lists WHERE type = 1 AND enabled = 1")
    fun getAllowedHostCount(): LiveData<Int>

    @Query("SELECT COUNT(DISTINCT host) FROM hosts_lists WHERE type = 2 AND enabled = 1")
    fun getRedirectHostCount(): LiveData<Int>

    @Query("DELETE FROM hosts_lists WHERE source_id = :sourceId")
    fun clearSourceHosts(sourceId: Int)
}
