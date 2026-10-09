package org.adaway.util

import java.io.File

/**
 * Removes the files of the HTTP cache that hosts source downloads used to keep.
 *
 * That cache was never read back, since every download carries its own conditions, and it shared
 * the top of the cache directory with the cache of DNS over HTTPS, which OkHttp forbids. It is
 * gone, and the cache of DNS over HTTPS has a directory of its own, so the cache files left at the
 * top belong to no cache any more.
 */
object LegacyHttpCache {
    /**
     * The journal of an OkHttp cache and its temporary and backup copies.
     */
    private val JOURNAL_FILES = setOf("journal", "journal.tmp", "journal.bkp")

    /**
     * An entry of an OkHttp cache: the hash of its address, the index of the part, and a suffix
     * while it is written.
     */
    private val ENTRY_FILE = Regex("[0-9a-f]{32}\\.[0-9]+(\\.tmp)?")

    /**
     * Delete the files of the former cache. Only those named as OkHttp names them are touched, at
     * the top of the directory only, so the other files kept there stay. Blocking: run it off the
     * main thread.
     *
     * @param cacheDirectory The application cache directory.
     */
    @JvmStatic
    fun clear(cacheDirectory: File) {
        val files = cacheDirectory.listFiles() ?: return
        for (file in files) {
            if (file.isFile && (file.name in JOURNAL_FILES || ENTRY_FILE.matches(file.name))) {
                file.delete()
            }
        }
    }
}
