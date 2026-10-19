package app.safetube.core

/**
 * Instant, offline matching against the parent's keyword list.
 * Matches whole words (and simple plurals), case-insensitively, so "witch"
 * catches "Witches Brew" but not "switch".
 */
class KeywordFilter(keywords: List<String>) {
    private val patterns: List<Pair<String, Regex>> = keywords
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .map { kw ->
            val body = kw.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            kw to Regex("(?<![\\p{L}\\p{N}])$body(?:s|es|'s)?(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }

    /** Returns the first keyword found in [text], or null. */
    fun firstMatch(text: String): String? = patterns.firstOrNull { it.second.containsMatchIn(text) }?.first

    val isEmpty: Boolean get() = patterns.isEmpty()

    companion object {
        /** Parses a comma- or newline-separated list as typed by a parent. */
        fun parseList(raw: String): List<String> =
            raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
