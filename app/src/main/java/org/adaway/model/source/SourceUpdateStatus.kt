package org.adaway.model.source

import java.time.Period
import java.time.ZonedDateTime

/**
 * The update status of a hosts source.
 *
 * A source is either up to date or outdated, with no third state. Classifying it here rather than
 * with a pair of SQL predicates keeps the two states exhaustive by construction: previously a
 * source with either modification date unset matched neither predicate and was counted nowhere.
 */
object SourceUpdateStatus {
    /**
     * Tell whether a source is up to date.
     *
     * @param localModificationDate The date the source was last installed, or {@code null} when it
     * was never installed.
     * @param onlineModificationDate The date the source was last modified online, or {@code null}
     * when the server does not report one.
     * @return {@code true} when the source is up to date, {@code false} when it is outdated.
     */
    @JvmStatic
    fun isUpToDate(
        localModificationDate: ZonedDateTime?,
        onlineModificationDate: ZonedDateTime?
    ): Boolean {
        // Never installed: there is nothing on the device yet, so it is outdated.
        if (localModificationDate == null) {
            return false
        }
        // Installed, and no newer version is known to exist.
        if (onlineModificationDate == null) {
            return true
        }
        return !onlineModificationDate.isAfter(localModificationDate)
    }

    /**
     * Tell whether a source has to be retrieved again.
     *
     * A source whose online date is unknown is refreshed once a week. The date is unknown both
     * when the server does not report one and when the source could not be reached at all, and
     * the two cannot be told apart from the answer; treating unknown as "modified just now" would
     * turn every failed check into a reason to download every source again, which is how a failing
     * connection used to produce an update that never ended.
     *
     * @param localModificationDate The date the source was last installed, or `null` when it was
     * never installed.
     * @param onlineModificationDate The date the source was last modified online, or `null` when
     * it is unknown.
     * @param now The current date.
     * @return `true` when the source has to be retrieved, `false` when it can be left alone.
     */
    @JvmStatic
    fun needsRetrieval(
        localModificationDate: ZonedDateTime?,
        onlineModificationDate: ZonedDateTime?,
        now: ZonedDateTime
    ): Boolean {
        // Never installed: there is nothing on the device yet.
        if (localModificationDate == null) {
            return true
        }
        if (onlineModificationDate != null) {
            return onlineModificationDate.isAfter(localModificationDate)
        }
        return localModificationDate.isBefore(now.minus(UNKNOWN_DATE_REFRESH_INTERVAL))
    }

    /**
     * How often a source whose online date is unknown is retrieved again.
     */
    private val UNKNOWN_DATE_REFRESH_INTERVAL: Period = Period.ofWeeks(1)
}
