package org.adaway.ui.search

/**
 * How a search matches its text. In every mode `*` stands for any text, `?` for any one
 * character, and the text may be found anywhere unless it must be a whole word.
 *
 * @param matchCase Whether upper and lower case letters differ.
 * @param wholeWord Whether the text must be a whole word: whole labels of a host, between its
 *                  dots, or whole words of a name or an address, between characters that are
 *                  neither letters nor digits.
 */
data class SearchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false
)
