package org.snrkl.indexrouter.action

import org.snrkl.indexrouter.routing.Tags
import java.time.LocalDate

/**
 * Builds the single line inserted into today's daily note.
 *
 * Shape is fixed by the vault's own conventions, not chosen here:
 *  - `- [ ] ` — Tasks-plugin checkbox.
 *  - `#task` — the daily note's Day Plan queries filter on `description includes
 *    #task`; without it a routed task is invisible in the view meant to show it.
 *  - `#index` — marks provenance, so routed tasks can be told apart from
 *    hand-written ones. Configurable; the default is not load-bearing.
 *  - `➕ YYYY-MM-DD` — the Tasks plugin's created-date signifier. The emoji IS
 *    the syntax: without it the date is plain text and `created` queries miss it.
 *
 * Deliberately free of Android types so it is covered by JVM unit tests.
 */
object TaskLineFormatter {

    const val CREATED_SIGNIFIER = "➕" // ➕ U+2795 HEAVY PLUS SIGN

    /** Tags applied to every routed task unless overridden in config. */
    val DEFAULT_TAGS: List<String> = listOf("#task", "#index")

    fun format(
        transcript: String,
        createdOn: LocalDate,
        tags: List<String> = DEFAULT_TAGS,
        includeCreatedDate: Boolean = true,
    ): String {
        val text = transcript.trim().trimEnd('.', ' ')
        val parts = mutableListOf("- [ ]", text)
        parts += Tags.canonical(tags)
        if (includeCreatedDate) parts += "$CREATED_SIGNIFIER $createdOn"
        return parts.joinToString(" ")
    }

}
