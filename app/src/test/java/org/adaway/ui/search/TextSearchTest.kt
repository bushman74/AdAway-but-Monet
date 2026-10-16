package org.adaway.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of [TextSearch]: how a search and its options match the texts held in memory.
 */
class TextSearchTest {
    private val anyCase = SearchOptions()
    private val matchCase = SearchOptions(matchCase = true)
    private val wholeWord = SearchOptions(wholeWord = true)

    @Test
    fun searchingNothingKeepsEveryText() {
        val search = TextSearch(" ", anyCase)
        assertTrue(search.isEmpty)
        assertTrue(search.matches("anything"))
    }

    @Test
    fun findsTheTextAnywhereWhateverItsCase() {
        assertTrue(TextSearch("black", anyCase).matches("StevenBlack hosts"))
        assertTrue(TextSearch("BLACK", anyCase).matches("StevenBlack hosts"))
        assertFalse(TextSearch("white", anyCase).matches("StevenBlack hosts"))
    }

    @Test
    fun matchesCaseWhenAsked() {
        assertTrue(TextSearch("Black", matchCase).matches("StevenBlack hosts"))
        assertFalse(TextSearch("black", matchCase).matches("StevenBlack hosts"))
    }

    @Test
    fun readsTheWildcards() {
        assertTrue(TextSearch("ads*.txt", anyCase).matches("https://example.org/ads-list.txt"))
        assertTrue(TextSearch("hosts?txt", anyCase).matches("https://example.org/hosts.txt"))
        assertFalse(TextSearch("hosts?txt", anyCase).matches("https://example.org/hosts-.txt"))
    }

    @Test
    fun readsEverythingElseAsText() {
        assertFalse(TextSearch("a.c", anyCase).matches("abc"))
        assertTrue(TextSearch("(a+)", anyCase).matches("list (a+)"))
    }

    @Test
    fun findsWholeWords() {
        assertTrue(TextSearch("black", wholeWord).matches("Steven Black hosts"))
        assertFalse(TextSearch("black", wholeWord).matches("StevenBlack hosts"))
        assertTrue(TextSearch("raw.github*", wholeWord).matches("https://raw.githubusercontent.com/x"))
        assertFalse(TextSearch("github", wholeWord).matches("https://raw.githubusercontent.com/x"))
    }
}
