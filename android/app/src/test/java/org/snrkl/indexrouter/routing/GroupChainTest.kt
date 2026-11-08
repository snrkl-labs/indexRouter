package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain a gesture evaluates: common plus its own rules, one priority order. */
class GroupChainTest {

    private val single = RuleGroup.SINGLE_CLICK_HOLD
    private val double = RuleGroup.DOUBLE_CLICK_HOLD

    private fun rule(id: Long, keyword: String, action: ActionType, group: RuleGroup, priority: Int,
                     match: MatchType = MatchType.CONTAINS_WORD) =
        RoutingRule(id = id, keywords = listOf(keyword), action = action, group = group, priority = priority, matchType = match)

    @Test
    fun `priority decides across common and gesture rules, not the group`() {
        val rules = listOf(
            rule(1, "timer", ActionType.TIMER, RuleGroup.COMMON, priority = 20),
            rule(2, "task", ActionType.TASK, double, priority = 10),
        )
        // "task, set a timer" matches both; the task rule has the lower number.
        val decision = RoutingClassifier(rules).classify("task, set a timer", double)
        assertEquals(2L, decision.rule?.id)
    }

    @Test
    fun `a common rule with a lower number wins`() {
        val rules = listOf(
            rule(1, "timer", ActionType.TIMER, RuleGroup.COMMON, priority = 5),
            rule(2, "task", ActionType.TASK, double, priority = 10),
        )
        assertEquals(1L, RoutingClassifier(rules).classify("task, set a timer", double).rule?.id)
    }

    @Test
    fun `a starts-with common rule leaves a phrase it does not open`() {
        val rules = listOf(
            rule(1, "timer", ActionType.TIMER, RuleGroup.COMMON, priority = 5, match = MatchType.STARTS_WITH),
            rule(2, "task", ActionType.TASK, double, priority = 10),
        )
        assertEquals(2L, RoutingClassifier(rules).classify("task, set a timer", double).rule?.id)
        assertEquals(1L, RoutingClassifier(rules).classify("timer ten minutes", double).rule?.id)
    }

    @Test
    fun `the other gesture's rules are never evaluated`() {
        val rules = listOf(rule(1, "blog", ActionType.FILE, single, priority = 1))
        val decision = RoutingClassifier(rules).classify("blog idea", double)
        // Falls to double-click-hold's default route, not to the other vault's rule.
        assertNull(decision.rule)
    }

    @Test
    fun `common rules apply to both gestures`() {
        val rules = listOf(rule(1, "alarm", ActionType.TIMER, RuleGroup.COMMON, priority = 1))
        assertEquals(1L, RoutingClassifier(rules).classify("alarm five minutes", single).rule?.id)
        assertEquals(1L, RoutingClassifier(rules).classify("alarm five minutes", double).rule?.id)
    }

    @Test
    fun `the carried-over vault's rules run for neither gesture until assigned`() {
        val rules = listOf(rule(1, "task", ActionType.TASK, RuleGroup.CARRIED_OVER, priority = 1))
        assertNull(RoutingClassifier(rules).classify("task milk", single).rule)
        assertNull(RoutingClassifier(rules).classify("task milk", double).rule)
    }

    @Test
    fun `groups allow only their own types`() {
        assertTrue(RuleGroup.COMMON.allows(ActionType.TIMER))
        assertTrue(!RuleGroup.COMMON.allows(ActionType.FILE))
        assertTrue(single.allows(ActionType.TASK))
        assertTrue(!single.allows(ActionType.DICTATION))
        assertTrue(RuleGroup.CARRIED_OVER.allows(ActionType.FILE))
    }

    @Test
    fun `trigger header is read strictly`() {
        assertEquals(TriggerHeader.Gesture(double), TriggerHeader.parse("double-click-hold"))
        assertEquals(TriggerHeader.Gesture(single), TriggerHeader.parse(" single-click-hold "))
        assertEquals(TriggerHeader.TestEvent, TriggerHeader.parse("test-event"))
        assertEquals(TriggerHeader.Missing, TriggerHeader.parse(null))
        assertEquals(TriggerHeader.Missing, TriggerHeader.parse("  "))
        // Not a gesture, even though it is a group id: never guess a vault.
        assertTrue(TriggerHeader.parse("common") is TriggerHeader.Unknown)
        assertTrue(TriggerHeader.parse("carried-over") is TriggerHeader.Unknown)
        assertTrue(TriggerHeader.parse("triple-click") is TriggerHeader.Unknown)
    }
}
