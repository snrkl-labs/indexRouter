package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingClassifierTest {

    private val rules = listOf(
        RoutingRule(id = 1, keywords = listOf("blogidea"), action = ActionType.FILE, folder = "Blog Ideas", priority = 10),
        RoutingRule(id = 2, keywords = listOf("task"), action = ActionType.TASK, priority = 20),
        RoutingRule(id = 3, keywords = listOf("timer"), action = ActionType.TIMER, priority = 30),
        RoutingRule(id = 4, keywords = listOf("blog idea"), action = ActionType.FILE, folder = "Blog Ideas", priority = 15),
    )
    private val classifier = RoutingClassifier(rules)

    @Test
    fun `matches a keyword regardless of case and trailing punctuation`() {
        assertEquals("task", classifier.classify("Task: buy milk.").routedBy)
    }

    @Test
    fun `does not fire on a keyword buried inside a longer word`() {
        // The whole reason CONTAINS_WORD is the default: "multitasking" is not a task.
        assertFalse(classifier.classify("Thinking about multitasking again").matched)
    }

    @Test
    fun `multi-word keywords match as a phrase`() {
        assertEquals("blog idea", classifier.classify("Blog idea about ring routing").routedBy)
    }

    @Test
    fun `priority decides when two keywords are present`() {
        // "blogidea" (10) beats "task" (20) in the same sentence, every time.
        assertEquals("blogidea", classifier.classify("blogidea and also task something").routedBy)
    }

    @Test
    fun `a miss is a decision, not a failure`() {
        val decision = classifier.classify("Just thinking out loud")
        assertFalse(decision.matched)
        assertNull(decision.rule)
        assertEquals(RoutingDecision.DEFAULT_ROUTE, decision.routedBy)
    }

    @Test
    fun `any keyword in a rule matches, and the one that fired is recorded`() {
        // Several ways of saying the same thing, one rule, one destination.
        val synonyms = RoutingClassifier(
            listOf(
                RoutingRule(
                    id = 9,
                    keywords = listOf("blog idea", "blog post idea", "content idea"),
                    action = ActionType.FILE,
                    folder = "blogPostIdeas",
                ),
            ),
        )
        assertEquals("content idea", synonyms.classify("Content idea about routing").routedBy)
        assertEquals("blog post idea", synonyms.classify("Blog post idea for later").routedBy)
        // Which one matched is kept, so the note records what actually fired
        // rather than whichever happened to be listed first.
        assertEquals("blog idea", synonyms.classify("Blog idea number three").matchedKeyword)
    }

    @Test
    fun `disabled rules are skipped`() {
        val off = RoutingClassifier(listOf(rules[1].copy(enabled = false)))
        assertTrue(off.classify("task buy milk").rule == null)
    }
}
