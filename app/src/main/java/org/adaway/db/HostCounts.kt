package org.adaway.db

import org.adaway.db.entity.ListType

/**
 * The cached counts of blocked, allowed and redirected hosts.
 *
 * Counting the distinct hosts of a type reads millions of rows, which is far too slow to do every
 * time the home screen is shown. The counts are stored when the host entries are rebuilt, which
 * every update and every applied change goes through, so the screen reads the last stored value
 * immediately and nothing counts the blocked hosts again on its own.
 *
 * A stored count only ever lags: a change to the lists not yet applied shows once it is.
 */
object HostCounts {
    private val TYPES = listOf(ListType.BLOCKED, ListType.ALLOWED, ListType.REDIRECTED)

    /**
     * Store the counts of a rebuild of the host entries. Run it in the transaction of the
     * rebuild, so the counts describe the lists it was built from.
     *
     * @param blockedCount The number of distinct blocked hosts the rebuild imported. The allowed
     * and the redirected hosts are few, so they are counted here.
     */
    @JvmStatic
    fun storeRebuilt(database: AppDatabase, blockedCount: Int) {
        val hostListItemDao = database.hostsListItemDao()
        val metadataDao = database.metadataDao()
        metadataDao.setHostCount(ListType.BLOCKED, blockedCount)
        metadataDao.setHostCount(ListType.ALLOWED, hostListItemDao.countHosts(ListType.ALLOWED.value))
        metadataDao.setHostCount(ListType.REDIRECTED, hostListItemDao.countHosts(ListType.REDIRECTED.value))
    }

    /**
     * Recompute and store every host count. Blocking: run it off the main thread.
     *
     * It reads every blocked host, so it is kept for the rare changes made outside a rebuild,
     * like restoring a backup.
     */
    @JvmStatic
    fun refresh(database: AppDatabase) {
        val hostListItemDao = database.hostsListItemDao()
        val metadataDao = database.metadataDao()
        for (type in TYPES) {
            metadataDao.setHostCount(type, hostListItemDao.countHosts(type.value))
        }
    }

    /**
     * Compute and store the counts that were never stored, such as before the first rebuild.
     * Blocking: run it off the main thread.
     */
    @JvmStatic
    fun fillMissing(database: AppDatabase) {
        val hostListItemDao = database.hostsListItemDao()
        val metadataDao = database.metadataDao()
        for (type in TYPES) {
            if (metadataDao.getHostCount(type) == null) {
                metadataDao.setHostCount(type, hostListItemDao.countHosts(type.value))
            }
        }
    }
}
