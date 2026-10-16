package org.adaway.ui.search

/**
 * Finds a search in texts held in memory, such as the names and addresses of the hosts sources.
 *
 * @param query   The text searched, with `*` and `?` as wildcards. Blanks around it are ignored.
 * @param options How it matches.
 */
class TextSearch(query: String, private val options: SearchOptions) {
    private val regex: Regex? = buildRegex(query.trim(), options)

    /**
     * Whether nothing is searched, so every text is kept.
     */
    val isEmpty: Boolean get() = regex == null

    fun matches(text: String): Boolean {
        val pattern = regex ?: return true
        return pattern.containsMatchIn(if (options.matchCase) text else text.lowercase())
    }

    private companion object {
        /**
         * Letters and digits make up words, anything else separates them.
         */
        private const val WORD_CHARACTER = "[\\p{L}\\p{N}]"

        private fun buildRegex(query: String, options: SearchOptions): Regex? {
            if (query.isEmpty()) {
                return null
            }
            // Both sides are compared in lower case rather than with a case insensitive pattern,
            // which the regular expressions of Android and of the JVM do not treat alike.
            val text = if (options.matchCase) query else query.lowercase()
            val pattern = StringBuilder()
            val literal = StringBuilder()
            for (character in text) {
                if (character == '*' || character == '?') {
                    if (literal.isNotEmpty()) {
                        pattern.append(Regex.escape(literal.toString()))
                        literal.clear()
                    }
                    pattern.append(if (character == '*') ".*" else ".")
                } else {
                    literal.append(character)
                }
            }
            if (literal.isNotEmpty()) {
                pattern.append(Regex.escape(literal.toString()))
            }
            if (options.wholeWord) {
                pattern.insert(0, "(?<!$WORD_CHARACTER)(?:")
                pattern.append(")(?!$WORD_CHARACTER)")
            }
            return Regex(pattern.toString())
        }
    }
}
