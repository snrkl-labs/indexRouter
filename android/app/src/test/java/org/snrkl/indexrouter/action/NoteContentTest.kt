package org.snrkl.indexrouter.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NoteContentTest {

    @Test
    fun `tags are written bare in the frontmatter and hashed in the body`() {
        // Both, deliberately: frontmatter is findable by a tag: search, the
        // inline line is findable by typing the tag into search.
        val note = NoteContent.build(
            transcript = "blog idea about folder schemas",
            routedBy = "blog idea",
            tags = listOf("blogPostIdeas", "#ideas"),
            createdOn = LocalDate.of(2026, 9, 18),
            recordedAt = null,
            audioFileName = null,
            trigger = null,
        )
        assertTrue(note.contains("tags: [blogPostIdeas, ideas]"))
        assertTrue(note.contains("#blogPostIdeas #ideas"))
        // And the hashed line is in the body, below the frontmatter fence.
        assertTrue(note.indexOf("#blogPostIdeas") > note.lastIndexOf("---"))
    }

    @Test
    fun `a note with no tags gains no stray hash line`() {
        val note = NoteContent.build(
            transcript = "something",
            routedBy = "default",
            tags = emptyList(),
            createdOn = LocalDate.of(2026, 9, 18),
            recordedAt = null,
            audioFileName = null,
            trigger = null,
        )
        assertTrue(note.contains("tags: []"))
        assertFalse(note.lines().any { it.trim() == "#" })
    }

    private val recorded = LocalDateTime.of(2026, 8, 18, 19, 7, 11)

    @Test
    fun `frontmatter carries the Dataview contract`() {
        val note = NoteContent.build(
            transcript = "Blog idea about ring routing.",
            routedBy = "blogidea",
            tags = listOf("#blogPostIdeas"),
            createdOn = LocalDate.of(2026, 8, 18),
            recordedAt = recorded,
            audioFileName = "ring_ABC.m4a",
            trigger = "double-click-hold",
        )
        assertTrue(note.startsWith("---\n"))
        assertTrue(note.contains("created: 2026-08-18"))
        assertTrue(note.contains("source: pebble-index-01"))
        assertTrue(note.contains("routed-by: blogidea"))
        // Tags are frontmatter, hash-stripped — Metadata Menu's convention.
        assertTrue(note.contains("tags: [blogPostIdeas]"))
        assertTrue(note.contains("recorded-at: 2026-08-18T19:07:11"))
        assertTrue(note.contains("audio: \"[[ring_ABC.m4a]]\""))
        assertTrue(note.contains("![[ring_ABC.m4a]]"))
    }

    @Test
    fun `catch-all notes still carry full traceability`() {
        val note = NoteContent.build(
            transcript = "Just thinking out loud",
            routedBy = "default",
            tags = emptyList(),
            createdOn = LocalDate.of(2026, 8, 18),
            recordedAt = null,
            audioFileName = null,
            trigger = null,
        )
        assertTrue(note.contains("routed-by: default"))
        assertTrue(note.contains("tags: []"))
    }

    @Test
    fun `filename is sortable and strips characters a filesystem rejects`() {
        assertEquals(
            "2026-08-18 1907 Blog idea about ring routing",
            NoteContent.fileName("Blog idea about ring/routing?", recorded),
        )
    }

    @Test
    fun `an empty transcript still yields a usable filename`() {
        assertEquals("2026-08-18 1907", NoteContent.fileName("   ", recorded))
    }
}
