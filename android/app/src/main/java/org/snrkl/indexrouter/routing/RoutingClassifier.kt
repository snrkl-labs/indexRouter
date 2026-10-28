package org.snrkl.indexrouter.routing

/**
 * Matches a transcript against the routing table.
 *
 * Deliberately dumb and deterministic — the whole reason the webhook path was
 * chosen over MCP was to keep this decision out of a probabilistic model. Rules
 * are evaluated in priority order and the first hit wins, so a sentence
 * containing two keywords always routes the same way.
 *
 * No Android types: covered by JVM unit tests.
 */
class RoutingClassifier(private val rules: List<RoutingRule>) {

    fun classify(transcript: String): RoutingDecision {
        val haystack = normalise(transcript)
        val words = haystack.split(' ').filter { it.isNotBlank() }

        val hit = rules
            .asSequence()
            .filter { rule -> rule.enabled && rule.keywords.any { it.isNotBlank() } }
            .sortedWith(compareBy({ it.priority }, { it.id }))
            .mapNotNull { rule ->
                // Any of a rule's keywords matching is a match; which one it was
                // is kept only so the note can record what fired.
                rule.keywords.firstOrNull { matches(it, rule.matchType, haystack, words) }
                    ?.let { rule to it }
            }
            .firstOrNull()

        return RoutingDecision(
            rule = hit?.first,
            transcript = transcript,
            matchedKeyword = hit?.second,
        )
    }

    private fun matches(keyword: String, matchType: MatchType, haystack: String, words: List<String>): Boolean {
        val needle = normalise(keyword)
        if (needle.isBlank()) return false
        return when (matchType) {
            // Multi-word keywords ("blog idea") cannot be a word-list lookup.
            MatchType.CONTAINS_WORD ->
                if (needle.contains(' ')) haystack.containsPhrase(needle) else words.contains(needle)
            MatchType.STARTS_WITH -> haystack.startsWith(needle)
            MatchType.CONTAINS_SUBSTRING -> haystack.contains(needle)
        }
    }

    private fun String.containsPhrase(phrase: String): Boolean =
        " $this ".contains(" $phrase ")

    /**
     * Lower-cases and strips the punctuation the transcriber sprinkles in, so
     * "Task: buy milk." and "task buy milk" match the same rule. Apostrophes are
     * kept — "don't" is one word, not two.
     */
    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it == '\'' ) it else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()
}
