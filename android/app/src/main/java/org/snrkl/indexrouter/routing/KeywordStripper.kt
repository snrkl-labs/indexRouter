package org.snrkl.indexrouter.routing

/**
 * Removes the keyword that fired a rule from the front of what was said.
 *
 * "Task buy milk" becomes "buy milk": the keyword told the app where to send
 * this, and repeating it back is noise in a task, a timer label, a calendar
 * title or a dictation committed into a text field.
 *
 * Whichever of a rule's keywords actually fired is the one removed, not the
 * first one listed — a rule matching both "blog idea" and "content idea" must
 * drop the phrase the speaker used.
 *
 * Lives here rather than inside the routing pipeline because two very different
 * callers need the same answer: the pipeline, when it hands text to another
 * app, and the voice input method, which reads the **raw** transcript back out
 * of the routing log and must not commit the keyword into someone's email.
 *
 * No Android types: covered by JVM unit tests.
 */
object KeywordStripper {

    fun strip(transcript: String, keyword: String?): String {
        if (keyword.isNullOrBlank()) return transcript.trim()
        val trimmed = transcript.trim()
        val pattern = Regex("^\\s*${Regex.escape(keyword)}\\b[\\s,:.\\-]*", RegexOption.IGNORE_CASE)
        // Falling back to the whole transcript matters: someone whose entire
        // recording was the keyword should not end up with an empty string
        // silently committed or filed.
        return pattern.replace(trimmed, "").ifBlank { trimmed }
    }
}
