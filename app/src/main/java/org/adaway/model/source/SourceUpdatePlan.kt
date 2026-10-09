package org.adaway.model.source

import org.adaway.db.entity.HostsSource
import java.time.ZonedDateTime

/**
 * What a check of the sources found: the ones to retrieve, and the online dates it learned.
 *
 * The retrieval works from it rather than checking every source again. It used to, so after the
 * check had counted through the sources, the retrieval walked through them all once more while
 * the screen still showed the check as finished.
 *
 * @param outdatedSources The sources to retrieve.
 * @param onlineModificationDates The online modification date of each source that has one known,
 * by source id.
 */
class SourceUpdatePlan(
    val outdatedSources: List<HostsSource>,
    val onlineModificationDates: Map<Int, ZonedDateTime>
) {
    fun hasUpdate(): Boolean = outdatedSources.isNotEmpty()
}
