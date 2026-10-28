package org.snrkl.indexrouter.routing

/**
 * What to do with a transcript once a rule matches.
 *
 * [wireName] is what goes in the database and the vault config and must not
 * change — hand-edited config files depend on it. [displayName] is what the
 * user reads; the two are deliberately separate so presentation can change freely.
 */
enum class ActionType(val wireName: String, val displayName: String) {
    FILE("file", "File"),
    TASK("task", "Task"),
    TIMER("timer", "Timer"),
    CALENDAR("calendar", "Calendar"),

    /**
     * Spoken to be typed somewhere else, not to be kept.
     *
     * Writes nothing: no note, no task, and no recording either. The text goes
     * to the keyboard's voice queue and nowhere near the vault, which is the
     * whole point — a dictation is not a note.
     */
    DICTATION("dictation", "Dictation"),
    ;

    /** Which fields on the rule editor mean anything for this action. */
    val usesFolder: Boolean get() = this == FILE
    // Tasks carry tags too — a "work task" and a "home task" route want
    // different hashtags, so tags cannot be global to every task rule.
    val usesTags: Boolean get() = this == FILE || this == TASK

    companion object {
        fun fromWire(value: String?): ActionType? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * How a keyword is compared against the transcript.
 *
 * [CONTAINS_WORD] is the default and the only one that behaves sanely on speech:
 * a bare substring match fires "task" inside "multitasking". The other members
 * exist so the deferred phonetic/contacts matching can be added as a new value
 * rather than a schema change.
 */
enum class MatchType(val wireName: String) {
    CONTAINS_WORD("contains_word"),
    STARTS_WITH("starts_with"),
    CONTAINS_SUBSTRING("contains_substring"),
    ;

    companion object {
        fun fromWire(value: String?): MatchType =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: CONTAINS_WORD
    }
}

data class RoutingRule(
    val id: Long = 0,
    /**
     * Every phrase that should fire this rule — "blog idea", "blog post idea",
     * "content idea". Several matching at once is of no consequence: they all
     * route to the same place. Between rules, priority still decides.
     */
    val keywords: List<String>,
    val action: ActionType,
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val matchType: MatchType = MatchType.CONTAINS_WORD,
    /** Lower wins. Makes a two-keyword sentence deterministic rather than a coin toss. */
    val priority: Int = 100,
    val enabled: Boolean = true,
    /** Whether this rule's folder is listed in the generated Map of Content. */
    val includeInMoc: Boolean = true,
    /**
     * Whether the keyword that fired this rule is removed from what is written.
     *
     * Only ever removed from the **start** of the phrase, so a keyword used
     * mid-sentence is left as content. "task call Fred" loses its keyword;
     * "remind me about the task tomorrow" does not.
     */
    val stripKeyword: Boolean = true,
)

/**
 * The outcome of classification. A miss is a first-class result, not an error:
 * it still routes, to the catch-all, and is still logged.
 */
data class RoutingDecision(
    val rule: RoutingRule?,
    val transcript: String,
    /** Which of the rule's keywords matched. Null when nothing matched. */
    val matchedKeyword: String? = null,
) {
    val matched: Boolean get() = rule != null

    /** Value written to the note's `routed-by` frontmatter field. */
    val routedBy: String get() = matchedKeyword ?: rule?.keywords?.firstOrNull() ?: DEFAULT_ROUTE

    companion object {
        const val DEFAULT_ROUTE = "default"
    }
}
