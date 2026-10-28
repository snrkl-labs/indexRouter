package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {

    @Test
    fun `a tag is canonical with its hash, typed or not`() {
        // The whole point: the field used to accept both and say nothing about
        // which was meant, so the same tag could be stored two ways.
        assertEquals("#blogPostIdeas", Tags.canonical("blogPostIdeas"))
        assertEquals("#blogPostIdeas", Tags.canonical("#blogPostIdeas"))
        assertEquals("#blogPostIdeas", Tags.canonical("  #blogPostIdeas  "))
    }

    @Test
    fun `a double hash is not created from one already there`() {
        assertEquals("#task", Tags.canonical("#task"))
    }

    @Test
    fun `spaces inside a tag become hyphens, since a tag cannot hold one`() {
        // Otherwise "#blog idea" is the tag "#blog" followed by loose text, and
        // the half after the space silently stops being part of it.
        assertEquals("#blog-idea", Tags.canonical("blog idea"))
        assertEquals("#blog-idea", Tags.canonical("# blog   idea"))
    }

    @Test
    fun `an empty or hash-only entry yields nothing rather than a bare hash`() {
        assertEquals("", Tags.canonical(""))
        assertEquals("", Tags.canonical("   "))
        assertEquals("", Tags.canonical("#"))
        assertEquals(emptyList<String>(), Tags.canonical(listOf("", "#", "  ")))
    }

    @Test
    fun `duplicates collapse however they were typed`() {
        assertEquals(
            listOf("#task", "#index"),
            Tags.canonical(listOf("task", "#task", "index", "  #index")),
        )
    }

    @Test
    fun `frontmatter takes them bare, because a hash in a YAML array is not a tag`() {
        assertEquals(listOf("task", "index"), Tags.bare(listOf("#task", "index")))
        assertEquals("blogPostIdeas", Tags.bare("#blogPostIdeas"))
    }

    @Test
    fun `the body takes them hashed, which is what a plain-text search matches`() {
        // Frontmatter alone is findable by a tag: search but not by typing
        // "#blogPostIdeas" into search, and both ways should find a routed note.
        assertEquals("#task #index", Tags.inline(listOf("task", "#index")))
        assertEquals("", Tags.inline(emptyList()))
    }
}
