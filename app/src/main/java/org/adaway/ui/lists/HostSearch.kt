package org.adaway.ui.lists

import org.adaway.ui.search.SearchOptions

/**
 * The condition a search puts on the hosts of a list, as SQL on the `host` column with the one
 * argument it binds.
 *
 * SQLite's LIKE ignores the case of ASCII letters, which make up host names, and GLOB does not,
 * so they serve the two ways of matching case. A whole word is a whole label of the host: the
 * host is wrapped in dots so that its first and last labels are surrounded by dots as well.
 * Every condition reads the host of each row once, as the search did before options existed.
 */
data class HostSearch(val sql: String, val argument: String) {
    companion object {
        /**
         * The condition for a search, or `null` when nothing is searched and every host is kept.
         *
         * @param query   The text searched, with `*` and `?` as wildcards. Blanks around it are
         *                ignored, as host names hold none.
         * @param options How it matches.
         */
        fun of(query: String, options: SearchOptions): HostSearch? {
            var text = query.trim()
            if (options.wholeWord) {
                // Dots only separate labels, which the whole word search already places.
                text = text.trim('.')
            }
            if (text.isEmpty()) {
                return null
            }
            val host = if (options.wholeWord) "('.' || host || '.')" else "host"
            val edge = if (options.wholeWord) "." else ""
            return if (options.matchCase) {
                HostSearch("$host GLOB ?", "*$edge${globPattern(text)}$edge*")
            } else {
                HostSearch("$host LIKE ? ESCAPE '\\'", "%$edge${likePattern(text)}$edge%")
            }
        }

        /**
         * GLOB already reads `*` and `?` as wildcards. Only `[`, which would open a set of
         * characters, is made literal by enclosing it in a set of its own.
         */
        private fun globPattern(text: String): String = text.replace("[", "[[]")

        /**
         * LIKE reads `%` and `_` as wildcards, which host names may hold as text, so they are
         * escaped before the search's own wildcards are turned into them.
         */
        private fun likePattern(text: String): String = buildString(text.length) {
            for (character in text) {
                when (character) {
                    '\\', '%', '_' -> append('\\').append(character)
                    '*' -> append('%')
                    '?' -> append('_')
                    else -> append(character)
                }
            }
        }
    }
}
