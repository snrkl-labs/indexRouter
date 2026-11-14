package org.snrkl.indexrouter.ui.rules

import org.snrkl.indexrouter.routing.RoutingRule

/**
 * Where a dragged rule's priority goes, in the one number space every group
 * shares.
 *
 * No Android types: covered by JVM unit tests.
 */
internal object PriorityPlacement {

    const val STEP = 10

    /**
     * A whole number strictly between [above] and [below] — the dragged rule's
     * new neighbours in its own group — or null when there is none. At either
     * end of the group it steps past the neighbour by [STEP].
     */
    fun between(above: Int?, below: Int?): Int? = when {
        above == null && below == null -> null
        above == null -> below!! - STEP
        below == null -> above + STEP
        below - above >= 2 -> above + (below - above) / 2
        else -> null
    }

    /**
     * Every rule renumbered 10, 20, 30… in the overall order, with [moved]
     * placed just after [above] (or just before [below]). Ties in the overall
     * order are broken by id, as the classifier breaks them.
     */
    fun renumberAll(all: List<RoutingRule>, moved: RoutingRule, above: Int?, below: Int?): List<RoutingRule> {
        val others = all.filter { it.id != moved.id }.sortedWith(compareBy({ it.priority }, { it.id }))
        val anchorAbove = above?.let { p -> others.lastOrNull { it.priority == p && it.group == moved.group } ?: others.lastOrNull { it.priority == p } }
        val anchorBelow = below?.let { p -> others.firstOrNull { it.priority == p && it.group == moved.group } ?: others.firstOrNull { it.priority == p } }
        val index = when {
            anchorAbove != null -> others.indexOf(anchorAbove) + 1
            anchorBelow != null -> others.indexOf(anchorBelow)
            else -> others.size
        }
        val order = others.toMutableList().apply { add(index, moved) }
        return order.mapIndexedNotNull { i, rule ->
            val priority = (i + 1) * STEP
            if (rule.priority != priority || rule.id == moved.id) rule.copy(priority = priority) else null
        }
    }
}
