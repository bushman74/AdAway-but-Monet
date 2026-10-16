package org.adaway.ui.lists

import org.adaway.ui.search.SearchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests of [HostSearch]: the SQL condition each search and its options put on the hosts.
 */
class HostSearchTest {
    private val anyCase = SearchOptions()
    private val matchCase = SearchOptions(matchCase = true)
    private val wholeWord = SearchOptions(wholeWord = true)
    private val both = SearchOptions(matchCase = true, wholeWord = true)

    @Test
    fun searchingNothingKeepsEveryHost() {
        assertNull(HostSearch.of("", anyCase))
        assertNull(HostSearch.of("  ", both))
        assertNull(HostSearch.of(".", wholeWord))
    }

    @Test
    fun findsTheTextAnywhereWhateverItsCase() {
        assertEquals(HostSearch(LIKE, "%ads%"), HostSearch.of(" ads ", anyCase))
    }

    @Test
    fun turnsTheWildcardsIntoThoseOfLike() {
        assertEquals(HostSearch(LIKE, "%a%b_c%"), HostSearch.of("a*b?c", anyCase))
    }

    @Test
    fun keepsTheWildcardsOfLikeAsText() {
        assertEquals(HostSearch(LIKE, "%my\\_host\\%\\\\%"), HostSearch.of("my_host%\\", anyCase))
    }

    @Test
    fun matchesCaseWithGlob() {
        assertEquals(HostSearch(GLOB, "*Ads*?*"), HostSearch.of("Ads*?", matchCase))
    }

    @Test
    fun keepsTheSetsOfGlobAsText() {
        assertEquals(HostSearch(GLOB, "*a[[]b]*"), HostSearch.of("a[b]", matchCase))
    }

    @Test
    fun findsWholeLabels() {
        assertEquals(
            HostSearch(WORD_LIKE, "%.example.com.%"),
            HostSearch.of(".example.com.", wholeWord)
        )
        assertEquals(HostSearch(WORD_GLOB, "*.Ads.*"), HostSearch.of("Ads", both))
    }

    private companion object {
        const val LIKE = "host LIKE ? ESCAPE '\\'"
        const val GLOB = "host GLOB ?"
        const val WORD_LIKE = "('.' || host || '.') LIKE ? ESCAPE '\\'"
        const val WORD_GLOB = "('.' || host || '.') GLOB ?"
    }
}
