package org.snrkl.indexrouter.routing

/**
 * The one place that decides what a tag looks like.
 *
 * A tag is entered, stored and displayed **with** its hash — `#blogPostIdeas` —
 * whether or not the hash was typed. That was a coin toss before: the field
 * accepted either form and said nothing about which was meant, so the same tag
 * could sit in the config two different ways.
 *
 * The two places a tag is written still differ, because Obsidian requires them
 * to, and that is why this is a function rather than a constant:
 *
 *  - **Frontmatter** takes it bare — `tags: [blogPostIdeas]`. A hash inside a
 *    YAML array is not how Obsidian wants a tag, and quoting it to make it
 *    legal YAML would make it a string rather than a tag.
 *  - **Inline in the body** takes the hash, which is what makes it match a
 *    plain-text search as well as a tag search. Frontmatter alone is findable
 *    by `tag:` but not by typing `#blogPostIdeas` into search.
 */
object Tags {

    /** The canonical form: hashed, trimmed, no internal spaces. */
    fun canonical(raw: String): String {
        val body = raw.trim().removePrefix("#").trim().replace(Regex("\\s+"), "-")
        return if (body.isEmpty()) "" else "#$body"
    }

    fun canonical(raw: List<String>): List<String> =
        raw.map(::canonical).filter { it.isNotEmpty() }.distinct()

    /** Bare, for a YAML `tags:` array. */
    fun bare(raw: String): String = canonical(raw).removePrefix("#")

    fun bare(raw: List<String>): List<String> =
        canonical(raw).map { it.removePrefix("#") }

    /** Hashed and space-separated, for a line in the note body. */
    fun inline(raw: List<String>): String = canonical(raw).joinToString(" ")
}
