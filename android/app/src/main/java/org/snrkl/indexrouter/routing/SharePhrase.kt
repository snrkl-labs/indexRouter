package org.snrkl.indexrouter.routing

/**
 * Prepares a spoken phrase for an app that will parse it as language.
 *
 * **Why this exists, and it is not cosmetic.** The Pebble app's transcription
 * ends a phrase with a full stop, and a trailing full stop defeats the
 * receiving parser's *time* reading while leaving its date reading intact.
 * Measured against KashCal:
 *
 * | Sent | Result |
 * |---|---|
 * | `lunch with Sinclair tomorrow at noon.` | Tomorrow, **All day**, with `noon.` left over as text |
 * | `lunch with Sinclair tomorrow at noon`  | Tomorrow, **12:00** |
 *
 * So every spoken appointment would have silently lost its time — an all-day
 * event instead of a lunch — for one character nobody said.
 *
 * Only *trailing* punctuation is removed. Stripping it throughout would damage
 * the phrase, and it is the end of the string that the parser trips on.
 *
 * No Android types: covered by JVM unit tests.
 */
object SharePhrase {

    /** Sentence-final marks a transcriber adds, which no speaker dictated. */
    private const val TRAILING = ".!?,;:… \t"

    fun forParsing(text: String): String = text
        // One line: a receiving parser generally treats a single line as
        // something to parse and several as a description.
        .replace(Regex("\\s*\\R\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trimEnd(*TRAILING.toCharArray())
        .trim()
}
