package org.adaway.ui.lists

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests of [sourceLabelsOf], which names the sources shown under each host of the lists screen.
 */
class SourceLabelsTest {
    private val labels = mapOf(
        2 to "AdAway",
        3 to "StevenBlack",
        4 to "peterlowe"
    )

    @Test
    fun namesEverySourceAlphabetically() {
        assertEquals(listOf("AdAway", "peterlowe", "StevenBlack"), sourceLabelsOf("3,4,2", labels))
    }

    @Test
    fun hostOnlyTheUserAddedHasNoLabel() {
        // The user's source (id 1) is not among the labels, so it is never named.
        assertEquals(emptyList<String>(), sourceLabelsOf("1", labels))
    }

    @Test
    fun hostTheUserAddedNamesTheOtherSourcesListingIt() {
        assertEquals(listOf("StevenBlack"), sourceLabelsOf("1,3", labels))
    }

    @Test
    fun repeatedIdsAreNamedOnce() {
        assertEquals(listOf("AdAway"), sourceLabelsOf("2,2", labels))
    }

    @Test
    fun missingIdsGiveNoLabel() {
        assertEquals(emptyList<String>(), sourceLabelsOf(null, labels))
        assertEquals(emptyList<String>(), sourceLabelsOf("", labels))
        assertEquals(emptyList<String>(), sourceLabelsOf("9", labels))
    }
}
