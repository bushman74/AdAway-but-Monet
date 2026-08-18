package org.adaway.model.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZonedDateTime;

/**
 * Tests of {@link SourceUpdateStatus}.
 *
 * The counters on the home screen split the enabled sources into up to date and outdated, so the
 * two states must cover every combination of modification dates. The cases where one or both dates
 * are unset are the ones the previous SQL predicates silently dropped.
 */
public class SourceUpdateStatusTest {
    private static final ZonedDateTime OLD = ZonedDateTime.parse("2026-01-01T00:00:00Z");
    private static final ZonedDateTime RECENT = ZonedDateTime.parse("2026-07-01T00:00:00Z");

    @Test
    public void neverInstalledSourceIsOutdated() {
        assertFalse(SourceUpdateStatus.isUpToDate(null, RECENT));
    }

    @Test
    public void neverInstalledSourceWithoutOnlineDateIsOutdated() {
        assertFalse(SourceUpdateStatus.isUpToDate(null, null));
    }

    @Test
    public void installedSourceWithoutOnlineDateIsUpToDate() {
        assertTrue(SourceUpdateStatus.isUpToDate(OLD, null));
    }

    @Test
    public void newerOnlineVersionIsOutdated() {
        assertFalse(SourceUpdateStatus.isUpToDate(OLD, RECENT));
    }

    @Test
    public void olderOnlineVersionIsUpToDate() {
        assertTrue(SourceUpdateStatus.isUpToDate(RECENT, OLD));
    }

    @Test
    public void sameDateIsUpToDate() {
        assertTrue(SourceUpdateStatus.isUpToDate(RECENT, RECENT));
    }

    /**
     * The property the home screen counters rely on: over any set of sources, the number counted
     * as up to date plus the number counted as outdated equals the size of the set. This is what
     * the previous pair of SQL predicates failed to guarantee.
     */
    @Test
    public void statesPartitionEverySourceSet() {
        ZonedDateTime[] dates = {null, OLD, RECENT};
        int total = 0;
        int upToDate = 0;
        int outdated = 0;
        for (ZonedDateTime local : dates) {
            for (ZonedDateTime online : dates) {
                total++;
                if (SourceUpdateStatus.isUpToDate(local, online)) {
                    upToDate++;
                } else {
                    outdated++;
                }
            }
        }
        assertEquals(9, total);
        assertEquals(total, upToDate + outdated);
        // Both states must actually occur, so the partition is not trivially one sided.
        assertTrue(upToDate > 0);
        assertTrue(outdated > 0);
    }

    @Test
    public void neverInstalledSourceHasToBeRetrieved() {
        assertTrue(SourceUpdateStatus.needsRetrieval(null, RECENT, RECENT));
        assertTrue(SourceUpdateStatus.needsRetrieval(null, null, RECENT));
    }

    @Test
    public void sourceModifiedSinceItWasInstalledHasToBeRetrieved() {
        assertTrue(SourceUpdateStatus.needsRetrieval(OLD, RECENT, RECENT));
    }

    @Test
    public void sourceInstalledAfterItWasModifiedIsLeftAlone() {
        assertFalse(SourceUpdateStatus.needsRetrieval(RECENT, OLD, RECENT));
    }

    /**
     * An online date is unknown both when the server reports none and when the source could not be
     * reached. Either way the source is not retrieved again until it goes stale, so a run that
     * cannot reach anything does not decide that everything needs downloading.
     */
    @Test
    public void sourceWithoutOnlineDateIsLeftAloneUntilItGoesStale() {
        ZonedDateTime now = ZonedDateTime.parse("2026-07-01T00:00:00Z");

        assertFalse(SourceUpdateStatus.needsRetrieval(now.minusDays(6), null, now));
        assertTrue(SourceUpdateStatus.needsRetrieval(now.minusDays(8), null, now));
    }
}
