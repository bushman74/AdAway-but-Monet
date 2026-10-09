package org.adaway.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.adaway.db.entity.ListType
import org.adaway.db.entity.Metadata
import java.util.UUID

@Dao
interface MetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(metadata: Metadata)

    @Query("SELECT `value` FROM `metadata` WHERE `key` = :key LIMIT 1")
    fun get(key: String): String?

    @Query("SELECT `value` FROM `metadata` WHERE `key` = :key LIMIT 1")
    fun observe(key: String): LiveData<String?>

    /**
     * Observe a cached host counter.
     * The counters are expensive to compute over millions of rows, so the home screen reads the
     * last computed value and is updated when a new one is stored.
     */
    fun observeHostCount(type: ListType): LiveData<String?> = observe(hostCountKey(type))

    /**
     * Get a cached host counter.
     *
     * @return The count, or `null` when it was never computed.
     */
    fun getHostCount(type: ListType): Int? = get(hostCountKey(type))?.toIntOrNull()

    fun setHostCount(type: ListType, count: Int) {
        put(Metadata().apply {
            key = hostCountKey(type)
            value = count.toString()
        })
    }

    /**
     * Record that the host entries were rebuilt.
     * Must run in the same transaction as the rebuild, so the recorded revision and the entries it
     * describes can never disagree.
     */
    fun markHostEntriesRebuilt() {
        put(Metadata().apply {
            key = HOST_ENTRIES_REVISION
            value = UUID.randomUUID().toString()
        })
    }

    /**
     * Get the revision of the host entries.
     *
     * @return The revision, or an empty string when the entries were never rebuilt.
     */
    fun getHostEntriesRevision(): String = get(HOST_ENTRIES_REVISION).orEmpty()

    companion object {
        private fun hostCountKey(type: ListType): String = "hostCount." + type.name

        private const val HOST_ENTRIES_REVISION = "hostEntriesRevision"
    }
}
