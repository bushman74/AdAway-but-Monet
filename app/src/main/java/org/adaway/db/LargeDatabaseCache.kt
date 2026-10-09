package org.adaway.db

import timber.log.Timber

/**
 * Gives the database a larger page cache while hosts sources are updated and the hosts to block
 * are rebuilt.
 *
 * Both read and change millions of rows spread over large indexes, which the default cache of
 * about 2 MB cannot hold, so the same pages are read and written again and again. The larger
 * cache only lasts as long as that work: the process can live on, running the VPN for instance,
 * and SQLite keeps what its cache holds until told to give it back.
 *
 * The setting belongs to the database connection, so it is changed in a transaction, which runs
 * on the connection every write and every transaction uses. Updates and rebuilds can overlap, so
 * the cache is enlarged by the first to start and restored by the last to end.
 */
object LargeDatabaseCache {
    /**
     * The size of the larger cache, in KiB. Larger ones gained nothing more on a benchmark of 20
     * sources and 4.3 million hosts.
     */
    private const val CACHE_SIZE_KIB = 64 * 1024

    private val lock = Any()
    private var users = 0
    private var previousCacheSize: Long? = null

    /**
     * Enlarge the cache, unless it is already. Call it outside any transaction, and pair it with
     * [release] in a `finally` block. Failing to change the cache is not an error: the work only
     * runs slower.
     */
    @JvmStatic
    fun acquire(database: AppDatabase) {
        synchronized(lock) {
            users++
            if (users > 1) {
                return
            }
            try {
                database.runInTransaction(Runnable {
                    val db = database.openHelper.writableDatabase
                    db.query("PRAGMA cache_size").use { cursor ->
                        previousCacheSize = if (cursor.moveToFirst()) cursor.getLong(0) else null
                    }
                    if (previousCacheSize != null) {
                        db.execSQL("PRAGMA cache_size = -$CACHE_SIZE_KIB")
                    }
                })
            } catch (exception: RuntimeException) {
                Timber.w(exception, "Failed to enlarge the database cache.")
                previousCacheSize = null
            }
        }
    }

    /**
     * Restore the cache once the last work that enlarged it ends, and give its memory back.
     */
    @JvmStatic
    fun release(database: AppDatabase) {
        synchronized(lock) {
            users--
            if (users > 0) {
                return
            }
            val previous = previousCacheSize ?: return
            previousCacheSize = null
            try {
                database.runInTransaction(Runnable {
                    val db = database.openHelper.writableDatabase
                    db.execSQL("PRAGMA cache_size = $previous")
                    db.execSQL("PRAGMA shrink_memory")
                })
            } catch (exception: RuntimeException) {
                Timber.w(exception, "Failed to restore the database cache.")
            }
        }
    }
}
