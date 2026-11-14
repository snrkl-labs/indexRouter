package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rules a gesture sees, in one vault and with a vault per gesture. */
class VaultModeTest {

    private val single = RuleGroup.SINGLE_CLICK_HOLD
    private val double = RuleGroup.DOUBLE_CLICK_HOLD
    private val one = VaultMode(perGesture = false, home = single)
    private val perGesture = VaultMode(perGesture = true)

    private fun rule(
        id: Long,
        keyword: String,
        action: ActionType,
        group: RuleGroup,
        priority: Int = 10,
        gesture: RuleGroup? = null,
    ) = RoutingRule(id = id, keywords = listOf(keyword), action = action, group = group, priority = priority, gesture = gesture)

    @Test
    fun `in one vault both gestures file into the home vault`() {
        assertEquals(single, one.vaultFor(single))
        assertEquals(single, one.vaultFor(double))
        assertEquals(listOf(single), one.vaultGroups)
    }

    @Test
    fun `with a vault per gesture each files into its own`() {
        assertEquals(single, perGesture.vaultFor(single))
        assertEquals(double, perGesture.vaultFor(double))
        assertEquals(RuleGroup.TRIGGERS, perGesture.vaultGroups)
    }

    @Test
    fun `in one vault a rule for either gesture answers both`() {
        val rules = listOf(rule(1, "idea", ActionType.FILE, single))
        assertEquals(1L, RoutingClassifier(rules, one).classify("idea for later", single).rule?.id)
        assertEquals(1L, RoutingClassifier(rules, one).classify("idea for later", double).rule?.id)
    }

    @Test
    fun `in one vault the same word can file differently per gesture`() {
        val rules = listOf(
            rule(1, "idea", ActionType.FILE, single, gesture = single),
            rule(2, "idea", ActionType.FILE, single, gesture = double),
        )
        assertEquals(1L, RoutingClassifier(rules, one).classify("idea for later", single).rule?.id)
        assertEquals(2L, RoutingClassifier(rules, one).classify("idea for later", double).rule?.id)
        assertFalse(one.overlap(rules[0], rules[1]))
    }

    @Test
    fun `a rule limited to one gesture falls through for the other`() {
        val rules = listOf(rule(1, "blog", ActionType.FILE, single, gesture = double))
        assertNull(RoutingClassifier(rules, one).classify("blog post", single).rule)
    }

    @Test
    fun `a common rule can be limited to a gesture in either mode`() {
        val timer = rule(1, "timer", ActionType.TIMER, RuleGroup.COMMON, gesture = double)
        for (mode in listOf(one, perGesture)) {
            assertNull(RoutingClassifier(listOf(timer), mode).classify("timer five minutes", single).rule)
            assertEquals(1L, RoutingClassifier(listOf(timer), mode).classify("timer five minutes", double).rule?.id)
        }
    }

    @Test
    fun `in one vault a rule left in the other gesture's group is not used`() {
        val stray = rule(1, "task", ActionType.TASK, double)
        assertFalse(one.inUse(stray))
        assertNull(RoutingClassifier(listOf(stray), one).classify("task call Fred", double).rule)
    }

    @Test
    fun `with a vault per gesture the other vault's rules are never seen`() {
        val rules = listOf(rule(1, "blog", ActionType.FILE, single))
        assertNull(RoutingClassifier(rules, perGesture).classify("blog idea", double).rule)
    }

    @Test
    fun `either-gesture and limited rules with one word overlap`() {
        val any = rule(1, "task", ActionType.TASK, single)
        val limited = rule(2, "task", ActionType.TASK, single, gesture = double)
        assertTrue(one.overlap(any, limited))
    }

    @Test
    fun `two vaults' rules never overlap, but common rules overlap both`() {
        val a = rule(1, "task", ActionType.TASK, single)
        val b = rule(2, "task", ActionType.TASK, double)
        val c = rule(3, "timer", ActionType.TIMER, RuleGroup.COMMON)
        assertFalse(perGesture.overlap(a, b))
        assertTrue(perGesture.overlap(a, c))
        assertTrue(perGesture.overlap(b, c))
    }

    @Test
    fun `in one vault a type decides the group`() {
        assertEquals(RuleGroup.COMMON, one.groupFor(ActionType.TIMER))
        assertEquals(single, one.groupFor(ActionType.FILE))
        assertEquals(single, VaultMode(home = single).groupFor(ActionType.TASK, double))
    }

    @Test
    fun `run order in one vault mixes common and vault rules by priority`() {
        val rules = listOf(
            rule(1, "task", ActionType.TASK, single, priority = 30),
            rule(2, "timer", ActionType.TIMER, RuleGroup.COMMON, priority = 20),
            rule(3, "idea", ActionType.FILE, single, priority = 10, gesture = single),
        )
        assertEquals(listOf(3L, 2L, 1L), chainFor(rules, single, one).map { it.id })
        assertEquals(listOf(2L, 1L), chainFor(rules, double, one).map { it.id })
    }
}
