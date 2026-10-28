package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordStripperTest {

    @Test
    fun `the keyword that fired is removed from the front`() {
        assertEquals("buy milk", KeywordStripper.strip("Task buy milk", "task"))
        assertEquals("buy the tickets", KeywordStripper.strip("Dictation buy the tickets", "dictation"))
    }

    @Test
    fun `matching ignores case and eats the punctuation that follows`() {
        assertEquals("buy milk", KeywordStripper.strip("TASK: buy milk", "task"))
        assertEquals("buy milk", KeywordStripper.strip("task,  buy milk", "task"))
        assertEquals("buy milk", KeywordStripper.strip("Task - buy milk", "task"))
    }

    @Test
    fun `a multi-word keyword is removed whole`() {
        assertEquals(
            "about folder schemas",
            KeywordStripper.strip("blog post idea about folder schemas", "blog post idea"),
        )
    }

    @Test
    fun `only a whole word matches, so a keyword inside another word survives`() {
        // "task" must not fire inside "multitasking", and must not chew it up.
        assertEquals(
            "multitasking is a myth",
            KeywordStripper.strip("multitasking is a myth", "task"),
        )
    }

    @Test
    fun `it is only removed from the front, not wherever it appears`() {
        assertEquals(
            "buy milk then do the task",
            KeywordStripper.strip("Task buy milk then do the task", "task"),
        )
    }

    @Test
    fun `a recording that was only the keyword keeps its text rather than vanishing`() {
        // An empty string silently filed, or committed into a text field, is
        // worse than the keyword being left in.
        assertEquals("task", KeywordStripper.strip("task", "task"))
        assertEquals("Dictation.", KeywordStripper.strip("Dictation.", "dictation"))
    }

    @Test
    fun `no keyword means nothing is removed`() {
        assertEquals("rambling with no match", KeywordStripper.strip("rambling with no match", null))
        assertEquals("rambling with no match", KeywordStripper.strip("  rambling with no match  ", ""))
    }
}
