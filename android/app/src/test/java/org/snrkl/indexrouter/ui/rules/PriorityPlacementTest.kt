package org.snrkl.indexrouter.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.RuleGroup

class PriorityPlacementTest {

    private fun r(id: Long, p: Int, g: RuleGroup, a: ActionType) =
        RoutingRule(id = id, keywords = listOf("k$id"), action = a, priority = p, group = g)

    @Test
    fun `a number between neighbours, or past the end`() {
        assertEquals(15, PriorityPlacement.between(10, 20))
        assertEquals(30, PriorityPlacement.between(20, null))
        assertEquals(0, PriorityPlacement.between(null, 10))
        assertNull(PriorityPlacement.between(10, 11))
    }

    @Test
    fun `with no gap, everything is renumbered without reordering anything else`() {
        val t1 = r(2, 10, RuleGroup.DOUBLE_CLICK_HOLD, ActionType.TASK)
        val t2 = r(3, 11, RuleGroup.DOUBLE_CLICK_HOLD, ActionType.FILE)
        val common = r(1, 20, RuleGroup.COMMON, ActionType.TIMER)
        val other = r(5, 15, RuleGroup.SINGLE_CLICK_HOLD, ActionType.FILE)
        val moved = r(4, 30, RuleGroup.DOUBLE_CLICK_HOLD, ActionType.FILE)
        val out = PriorityPlacement.renumberAll(listOf(common, t1, t2, other, moved), moved, above = 10, below = 11)
        val byId = listOf(common, t1, t2, other, moved).associate { it.id to it.priority } +
            out.associate { it.id to it.priority }
        // Overall order: t1, moved, t2, other, common — every pair not involving
        // the moved rule keeps its relative order.
        assertEquals(listOf(2L, 4L, 3L, 5L, 1L), byId.entries.sortedBy { it.value }.map { it.key })
    }
}
