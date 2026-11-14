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
class RoutingClassifier(
    private val rules: List<RoutingRule>,
    private val mode: VaultMode = VaultMode(perGesture = true),
) {

    /**
     * @param trigger the gesture that started the recording. The chain is
     *   every rule that gesture sees (see [VaultMode.sees]), as **one list
     *   ordered by priority then id** — a group says which gestures a rule
     *   applies to, not where it sits in the order. First match wins; no match
     *   falls to the default route. Null evaluates every rule, for a
     *   single-group rule set.
     */
    fun classify(transcript: String, trigger: RuleGroup? = null): RoutingDecision {
        val haystack = normalise(transcript)
        val words = haystack.split(' ').filter { it.isNotBlank() }

        val ordered = chainFor(rules, trigger, mode)

        val hit = ordered
            .asSequence()
            .filter { rule -> rule.enabled && rule.keywords.any { it.isNotBlank() } }
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

/**
 * The rules a recording from [trigger] is checked against, in the order they
 * are checked, by priority then id. Which rules those are is [VaultMode.sees].
 * Null means every rule.
 *
 * The one definition of the order, shared by routing and by the rules screen's
 * run-order view, so what is shown is what runs.
 */
fun chainFor(
    rules: List<RoutingRule>,
    trigger: RuleGroup?,
    mode: VaultMode = VaultMode(perGesture = true),
): List<RoutingRule> = rules
    .filter { trigger == null || (trigger.isTrigger && mode.sees(trigger, it)) }
    .sortedWith(compareBy({ it.priority }, { it.id }))
