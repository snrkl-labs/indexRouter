package org.snrkl.indexrouter.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NoteRewriterTest {

    private val date = LocalDate.of(2026, 8, 21)
    private val note = """
        ---
        created: 2026-08-21
        source: pebble-index-01
        routed-by: blogidea
        tags: [blogPostIdeas]
        ---

        Remember to book the car in for a service.

        ![[2026-08-21 1338 ring_ABC.m4a]]
    """.trimIndent()

    @Test
    fun `the classifier's original answer is kept, not erased`() {
        val out = NoteRewriter.rewriteForReroute(note, becomingTask = false, createdOn = date, tags = emptyList())
        assertTrue(out.contains("routed-by: manual"))
        // Without this, there is no record of what the classifier got wrong.
        assertTrue(out.contains("rerouted-from: blogidea"))
    }

    @Test
    fun `a note becoming a task gains a task line`() {
        val out = NoteRewriter.rewriteForReroute(
            note, becomingTask = true, createdOn = date, tags = listOf("#task", "#index"),
        )
        assertTrue(out.contains("- [ ] Remember to book the car in for a service #task #index ➕ 2026-08-21"))
        // The recording embed is left alone.
        assertTrue(out.contains("![[2026-08-21 1338 ring_ABC.m4a]]"))
    }

    @Test
    fun `an item already holding a task line is not double-wrapped`() {
        val task = "---\nrouted-by: task\n---\n\n- [ ] buy milk #task ➕ 2026-08-21\n"
        val out = NoteRewriter.rewriteForReroute(task, becomingTask = true, createdOn = date, tags = listOf("#task"))
        assertEquals(1, Regex("- \\[ \\]").findAll(out).count())
    }

    @Test
    fun `re-routing twice does not stack rerouted-from entries`() {
        val once = NoteRewriter.rewriteForReroute(note, false, date, emptyList())
        val twice = NoteRewriter.rewriteForReroute(once, false, date, emptyList())
        assertEquals(1, Regex("rerouted-from:").findAll(twice).count())
        assertTrue(twice.contains("rerouted-from: blogidea"))
    }

    @Test
    fun `a note with no frontmatter still gets one`() {
        val out = NoteRewriter.rewriteForReroute("Just some text\n", false, date, emptyList())
        assertTrue(out.contains("Just some text"))
    }
}
