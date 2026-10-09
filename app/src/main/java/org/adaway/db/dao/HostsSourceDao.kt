package org.adaway.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.adaway.db.entity.HostsSource
import java.time.ZonedDateTime
import java.util.Optional

@Dao
interface HostsSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(source: HostsSource)

    @Update
    fun update(source: HostsSource)

    @Delete
    fun delete(source: HostsSource)

    @get:Query("SELECT * FROM hosts_sources WHERE enabled = 1 AND id != 1 ORDER BY url ASC")
    val enabled: List<HostsSource>

    fun toggleEnabled(source: HostsSource) {
        val id = source.id
        val enabled = !source.isEnabled
        source.isEnabled = enabled
        setSourceEnabled(id, enabled)
        setSourceItemsEnabled(id, enabled)
    }

    @Query("UPDATE hosts_sources SET enabled = :enabled WHERE id =:id")
    fun setSourceEnabled(id: Int, enabled: Boolean)

    @Query("UPDATE hosts_lists SET enabled = :enabled WHERE source_id =:id")
    fun setSourceItemsEnabled(id: Int, enabled: Boolean)

    @Query("SELECT * FROM hosts_sources WHERE id = :id")
    fun getById(id: Int): Optional<HostsSource>

    /**
     * Tell whether a source is enabled.
     *
     * @return Whether it is, or `null` when there is no such source.
     */
    @Query("SELECT enabled FROM hosts_sources WHERE id = :id")
    fun isSourceEnabled(id: Int): Boolean?

    @Query("SELECT * FROM hosts_sources WHERE url = :url")
    fun getByUrl(url: String): Optional<HostsSource>

    /**
     * Change what the source editor edits, and only that, so an update that ran while the editor
     * was open keeps the dates and size it recorded.
     */
    @Query("UPDATE hosts_sources SET label = :label, url = :url, allowEnabled = :allowEnabled, redirectEnabled = :redirectEnabled WHERE id = :id")
    fun updateDefinition(
        id: Int,
        label: String,
        url: String,
        allowEnabled: Boolean,
        redirectEnabled: Boolean
    )

    @get:Query("SELECT * FROM hosts_sources WHERE id != 1 ORDER BY label ASC")
    val all: List<HostsSource>

    @Query("SELECT * FROM hosts_sources WHERE id != 1 ORDER BY label ASC")
    fun loadAll(): LiveData<List<HostsSource>>

    /**
     * Load the sources listing at least one host of a type, other than the user's own, by name.
     * Each check is a single lookup in the source, type and host index.
     */
    @Query("SELECT * FROM hosts_sources WHERE id != 1 AND EXISTS (SELECT 1 FROM hosts_lists WHERE hosts_lists.source_id = hosts_sources.id AND hosts_lists.type = :type) ORDER BY label COLLATE NOCASE ASC")
    fun loadListingSources(type: Int): LiveData<List<HostsSource>>

    @Query("UPDATE hosts_sources SET last_modified_online = :dateTime WHERE id = :id")
    fun updateOnlineModificationDate(id: Int, dateTime: ZonedDateTime?)

    /**
     * Record when a source was installed and, when it is known, when it was last modified online.
     * The online date is unknown both for a source that reports none and for one that could not be
     * reached, so it must stay nullable.
     */
    @Query("UPDATE hosts_sources SET last_modified_local = :localModificationDate, last_modified_online = :onlineModificationDate WHERE id = :id")
    fun updateModificationDates(
        id: Int,
        localModificationDate: ZonedDateTime,
        onlineModificationDate: ZonedDateTime?
    )

    @Query("UPDATE hosts_sources SET entityTag = :entityTag WHERE id = :id")
    fun updateEntityTag(id: Int, entityTag: String)

    @Query("UPDATE hosts_sources SET size = (SELECT count(id) FROM hosts_lists WHERE source_id = :id) WHERE id = :id")
    fun updateSize(id: Int)

    @Query("SELECT count(id) FROM hosts_sources WHERE enabled = 1 AND last_modified_online > last_modified_local")
    fun countOutdated(): LiveData<Int>

    @Query("SELECT count(id) FROM hosts_sources WHERE enabled = 1 AND last_modified_online <= last_modified_local")
    fun countUpToDate(): LiveData<Int>

    /**
     * Record why the last retrieval of a source failed, or `null` once it succeeds.
     */
    @Query("UPDATE hosts_sources SET last_update_error = :error WHERE id = :id")
    fun updateLastUpdateError(id: Int, error: String?)

    @Query("UPDATE hosts_sources SET last_modified_local = NULL, last_modified_online = NULL, entityTag = NULL, size = 0, last_update_error = NULL WHERE id = :id")
    fun clearProperties(id: Int)
}
