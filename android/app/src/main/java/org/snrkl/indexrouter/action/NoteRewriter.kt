package org.snrkl.indexrouter.action

import java.time.LocalDate

/**
 * Rewrites a note's frontmatter and body when the user re-routes it by hand.
 *
 * Re-routing is the user overruling the classifier, so the record has to say so:
 * `routed-by` becomes `manual`, and the keyword the classifier originally picked
 * is preserved in `rerouted-from` rather than being erased. Losing that would
 * throw away the only evidence of what the classifier keeps getting wrong, which
 * is the reason for having re-route at all.
 *
 * No Android types: covered by JVM unit tests.
 */
object NoteRewriter {

    fun rewriteForReroute(contents: String, becomingTask: Boolean, createdOn: LocalDate, tags: List<String>): String {
        val text = contents.replace("\r\n", "\n")
        val body = frontmatterRange(text)?.let { (start, end) ->
            val front = text.substring(start, end)
            val rest = text.substring(end)
            rewriteFrontmatter(front) + rest
        } ?: text

        return if (becomingTask) ensureTaskLine(body, createdOn, tags) else body
    }

    private fun rewriteFrontmatter(front: String): String {
        val original = Regex("(?m)^routed-by:\\s*(.+)$").find(front)?.groupValues?.get(1)?.trim()
        var out = front
        out = if (original != null) {
            Regex("(?m)^routed-by:.*$").replace(out, "routed-by: manual")
        } else {
            out.trimEnd('\n') + "\nrouted-by: manual\n"
        }
        // Keep the classifier's original answer, and do not stack entries if the
        // same item is re-routed twice.
        if (original != null && original != "manual" && !out.contains("rerouted-from:")) {
            out = Regex("(?m)^routed-by: manual$").replace(out, "routed-by: manual\nrerouted-from: $original")
        }
        return out
    }

    /** A note becoming a task needs to actually contain a task line. */
    private fun ensureTaskLine(body: String, createdOn: LocalDate, tags: List<String>): String {
        val lines = body.split("\n")
        if (lines.any { it.trimStart().startsWith("- [ ]") || it.trimStart().startsWith("- [x]") }) return body

        val frontEnd = lines.indexOfFirst { it.trim() == "---" }.let { first ->
            if (first != 0) -1 else lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        }
        val firstProse = lines.withIndex()
            .firstOrNull { (i, l) -> i > frontEnd && l.isNotBlank() && !l.startsWith("![[") }
            ?: return body

        val line = TaskLineFormatter.format(firstProse.value, createdOn, tags)
        return lines.toMutableList().also { it[firstProse.index] = line }.joinToString("\n")
    }

    private fun frontmatterRange(text: String): Pair<Int, Int>? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        return if (end < 0) null else 0 to (end + 4)
    }
}
