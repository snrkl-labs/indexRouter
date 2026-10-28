package org.snrkl.indexrouter.moc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MocContentTest {

    @Test
    fun `a child map links back up to the root index`() {
        val body = MocContent.childBody("blogPostIdeas", "_RingRing/blogPostIdeas", "_RingRing MoC.md")
        assertTrue(body.contains("Up: [[_RingRing MoC]]"))
        assertTrue(body.contains("FROM \"_RingRing/blogPostIdeas\""))
        // The listing is a query, so it stays right as recordings arrive without
        // the app rewriting anything.
        assertTrue(body.contains("```dataview"))
        // A map must not list itself.
        assertTrue(body.contains("WHERE type != \"moc\""))
    }

    @Test
    fun `the root index links down to every child`() {
        val body = MocContent.rootBody(
            "_RingRing", "_RingRing",
            listOf("tasks" to "3 items", "blogPostIdeas" to "1 item"),
        )
        assertTrue(body.contains("[[tasks MoC|tasks]]"))
        assertTrue(body.contains("[[blogPostIdeas MoC|blogPostIdeas]]"))
        assertTrue(body.contains("3 items"))
    }

    @Test
    fun `map filenames are folder-qualified so wikilinks stay unambiguous`() {
        assertEquals("tasks MoC.md", MocContent.mocFileName("tasks"))
    }

    @Test
    fun `backups are timestamped, not single-slot`() {
        assertEquals("tasks MoC.md.20260821134500.bak", MocContent.backupName("tasks MoC.md", "20260821134500"))
    }

    @Test
    fun `pruning removes the oldest beyond the retention count`() {
        val existing = listOf(
            "tasks MoC.md.20260818120000.bak",
            "tasks MoC.md.20260819120000.bak",
            "tasks MoC.md.20260820120000.bak",
            "tasks MoC.md.20260821120000.bak",
            "other MoC.md.20260821120000.bak",
        )
        val pruned = MocContent.backupsToPrune(existing, "tasks MoC.md", keep = 2)
        assertEquals(
            listOf("tasks MoC.md.20260818120000.bak", "tasks MoC.md.20260819120000.bak"),
            pruned,
        )
        // Retention is per map, so another map's backups are untouched.
        assertTrue(pruned.none { it.startsWith("other") })
    }

    @Test
    fun `nothing is pruned while under the limit`() {
        assertTrue(MocContent.backupsToPrune(listOf("tasks MoC.md.20260821120000.bak"), "tasks MoC.md", 5).isEmpty())
    }

    @Test
    fun `the daily-note query is scoped to the app's folder, not matched by tag`() {
        // Daily notes written before v0.2.0 still hold inline task lines with the
        // same tag; a tag-matched query would list those twice in the very
        // section meant to replace them.
        val query = MocContent.dailyNoteTaskQuery("_RingRing")
        assertTrue(query.contains("FROM \"_RingRing\""))
        assertTrue(query.contains("file.day = this.file.day"))
        assertTrue(!query.contains("#index"))
    }

    @Test
    fun `only the task folder's pro-forma carries the daily-note block`() {
        val tasks = MocContent.proForma("tasks", "_RingRing/tasks", "_RingRing", isTaskFolder = true)
        assertTrue(tasks.contains("for the daily note"))
        val other = MocContent.proForma("blogPostIdeas", "_RingRing/blogPostIdeas", "_RingRing", isTaskFolder = false)
        assertTrue(!other.contains("for the daily note"))
    }

    @Test
    fun `the pro-forma nests its fences so the block can be copied whole`() {
        val text = MocContent.proForma("blogPostIdeas", "_RingRing/blogPostIdeas", "_RingRing", isTaskFolder = false)
        assertTrue(text.contains("````"))
        assertTrue(text.contains("```dataview"))
        assertTrue(text.contains("TASK"))
    }
}
