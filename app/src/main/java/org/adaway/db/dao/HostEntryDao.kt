package org.adaway.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.adaway.db.entity.HostEntry
import org.adaway.db.entity.HostListItem
import org.adaway.db.entity.ListType
import org.adaway.db.entity.ListType.REDIRECTED
import java.util.Locale
import java.util.regex.Pattern

@Dao
interface HostEntryDao {
    /**
     * Remove every entry but the enabled blocked hosts, as blocked entries.
     *
     * The redirected entries go too: they are added again once the blocked hosts are complete,
     * as a full rebuild would. Each entry is checked against the index of the lists alone. So are
     * entries holding capitals, left by versions that kept them: the entries are in lower case.
     * A host listed with capitals has no row matching its entry, so its entry is removed here and
     * added again by [importBlocked], which costs little as such hosts are rare.
     */
    @Query("DELETE FROM `host_entries` WHERE `type` != 0 OR `redirection` IS NOT NULL OR `host` != LOWER(`host`) OR NOT EXISTS (SELECT 1 FROM `hosts_lists` WHERE `hosts_lists`.`type` = 0 AND `hosts_lists`.`enabled` = 1 AND `hosts_lists`.`host` = `host_entries`.`host`)")
    fun removeUnblocked()

    /**
     * Add every enabled blocked host not yet an entry, once each, in lower case.
     *
     * Grouped by host rather than made distinct over every column: the index of the lists then
     * answers it alone, already in host order, and a host is imported once even if two of its
     * rows were ever to differ. A blocked entry has no redirection.
     *
     * Host names do not depend on case. The VPN looks each requested host up in lower case, so a
     * host listed with capitals was only blocked by the hosts file of the root method.
     */
    @Query("INSERT OR IGNORE INTO `host_entries` SELECT LOWER(`host`), 0, NULL FROM `hosts_lists` WHERE `type` = 0 AND `enabled` = 1 GROUP BY `host`")
    fun importBlocked()

    @get:Query("SELECT host FROM hosts_lists WHERE type = 1 AND enabled = 1")
    val enabledAllowedHosts: List<String>

    @Query("DELETE FROM `host_entries` WHERE `host` LIKE :hostPattern")
    fun allowHost(hostPattern: String)

    @Query("DELETE FROM `host_entries` WHERE `host` IN (:hosts)")
    fun allowHosts(hosts: List<String>)

    @get:Query("SELECT * FROM hosts_lists WHERE type = 2 AND enabled = 1 ORDER BY host ASC, source_id DESC")
    val enabledRedirectedHosts: List<HostListItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun redirectHost(redirection: HostEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun redirectHosts(redirections: List<HostEntry>)

    /**
     * Rebuild the entries from the enabled lists.
     *
     * @return The number of distinct hosts the enabled lists block, before the allowed and the
     * redirected hosts are applied, as the home screen shows it.
     */
    fun sync(): Int {
        // Rebuilt in place rather than from nothing: the entries still blocked are kept as they
        // are, and only the others are written. Rewriting all of them, every time the lists
        // changed even a little, wrote four times as much to the storage.
        removeUnblocked()
        importBlocked()
        // The entries are now exactly the blocked hosts, once each, so counting them counts the
        // distinct blocked hosts without reading the lists again.
        val blockedCount = count
        applyAllowList()
        applyRedirectList()
        return blockedCount
    }

    /**
     * Remove the allowed hosts from the entries.
     *
     * Hosts without wildcard are removed by exact match so the unique index on `host` is used.
     * Only the few entries carrying a wildcard fall back to `LIKE`, which cannot use that index
     * and therefore scans the whole table once per pattern.
     */
    private fun applyAllowList() {
        val (wildcards, exacts) = enabledAllowedHosts.partition { host ->
            host.indexOf('*') != -1 || host.indexOf('?') != -1
        }
        exacts.map { it.lowercase(Locale.ROOT) }.chunked(DELETE_CHUNK_SIZE).forEach(::allowHosts)
        for (allowedHost in wildcards) {
            val hostPattern = A_CHAR_PATTERN.matcher(
                ANY_CHAR_PATTERN.matcher(allowedHost).replaceAll("%")
            ).replaceAll("_")
            allowHost(hostPattern)
        }
    }

    private fun applyRedirectList() {
        enabledRedirectedHosts.asSequence()
            .map { redirectedHost ->
                HostEntry().apply {
                    host = redirectedHost.host.lowercase(Locale.ROOT)
                    type = REDIRECTED
                    redirection = redirectedHost.redirection
                }
            }
            .chunked(INSERT_CHUNK_SIZE)
            .forEach(::redirectHosts)
    }

    @get:Query("SELECT * FROM `host_entries` ORDER BY `host`")
    val all: List<HostEntry>

    @get:Query("SELECT COUNT(*) FROM `host_entries`")
    val count: Int

    /**
     * Read a page of entries, ordered by host, starting strictly after the given host.
     * The host column is unique, so consecutive pages neither overlap nor leave gaps.
     */
    @Query("SELECT * FROM `host_entries` WHERE `host` > :afterHost ORDER BY `host` LIMIT :limit")
    fun getEntriesAfter(afterHost: String, limit: Int): List<HostEntry>

    /**
     * Get the type a host is listed with, or `null` when it is not listed at all.
     *
     * Most hosts are not listed: the table only holds the blocked and the redirected ones, so the
     * query returns no row for anything else. The return type must stay nullable, otherwise Room
     * throws on the empty result.
     */
    @Query("SELECT `type` FROM `host_entries` WHERE `host` == :host LIMIT 1")
    fun getTypeOfHost(host: String): ListType?

    /**
     * Get the type to apply to a host, defaulting to [ListType.ALLOWED] when it is not listed.
     * Use it to decide what to do with a request, not to tell whether the host is listed.
     */
    @Query("SELECT IFNULL((SELECT `type` FROM `host_entries` WHERE `host` == :host LIMIT 1), 1)")
    fun getTypeForHost(host: String): ListType

    @Query("SELECT * FROM `host_entries` WHERE `host` == :host LIMIT 1")
    fun getEntry(host: String): HostEntry?

    companion object {
        /**
         * The number of host names bound to a single statement.
         * Kept well below the SQLite bound parameter limit (999 on older Android versions).
         */
        private const val DELETE_CHUNK_SIZE = 500

        /**
         * The number of redirections inserted per transaction.
         */
        private const val INSERT_CHUNK_SIZE = 1000

        private val ANY_CHAR_PATTERN: Pattern = Pattern.compile("\\*")
        private val A_CHAR_PATTERN: Pattern = Pattern.compile("\\?")
    }
}
