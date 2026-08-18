package org.adaway.model.source

import java.time.Duration

/**
 * What a single update run is allowed to spend before it gives up.
 *
 * An update walks every enabled source over the network, and each source can cost the whole
 * connect and read timeout before failing. Twenty sources on a failing connection therefore take
 * far longer than the time the system allows a background task to run, and a run killed part way
 * through is started again from the beginning, forever. A run that watches its own budget stops
 * itself, reports why, and leaves the remaining sources for the next one.
 *
 * @param total The time the whole run may take.
 * @param maxConsecutiveFailures How many sources may fail in a row before the run gives up. A
 * connection that is failing fails for every source, so there is nothing to gain from walking the
 * rest of the list.
 * @param elapsedMillis Reads the time, in milliseconds. Replaced by the tests.
 */
class UpdateBudget @JvmOverloads constructor(
    private val total: Duration = DEFAULT_TOTAL,
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    private val elapsedMillis: () -> Long = System::currentTimeMillis
) {
    private val startedAt: Long = elapsedMillis()
    private var consecutiveFailures: Int = 0

    /**
     * Whether the run has spent its time.
     */
    fun timedOut(): Boolean = elapsedMillis() - startedAt >= total.toMillis()

    /**
     * Whether enough sources failed in a row to call the connection unusable.
     */
    fun failing(): Boolean = consecutiveFailures >= maxConsecutiveFailures

    /**
     * Whether the run should stop before starting another source.
     */
    fun exhausted(): Boolean = timedOut() || failing()

    fun recordSuccess() {
        consecutiveFailures = 0
    }

    fun recordFailure() {
        consecutiveFailures++
    }

    companion object {
        /**
         * The time a run may take.
         *
         * Comfortably inside the ten minutes the system allows a background task, so a run that is
         * going nowhere ends on its own terms rather than being killed and started over.
         */
        @JvmField
        val DEFAULT_TOTAL: Duration = Duration.ofMinutes(7)

        /**
         * How many sources may fail in a row before the run gives up.
         */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 4
    }
}
