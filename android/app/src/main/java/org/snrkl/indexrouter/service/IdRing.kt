package org.snrkl.indexrouter.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out notification ids from a fixed-size ring, so a category of
 * notification replaces its own oldest entry instead of growing without bound.
 *
 * **Why this exists.** Android refuses everything from an app once 50 of its
 * notifications are live, and the refusal is a line in logcat — not an
 * exception, not a return value. An ever-incrementing id therefore does not
 * merely "fill the shade": it reaches the cap and then **silently stops the
 * failure alerts this app is built around never losing.**
 *
 * That is not theoretical. The milestone stream posts five notifications per
 * recording, and one regression run — four recordings — was enough to hit the
 * cap, after which the route notification for a task that had routed perfectly
 * well was thrown away by the framework.
 *
 * Each category gets its own ring, sized so that all of them together stay
 * under the cap even when every one is full. A recording is never lost to this:
 * the routing log row is written regardless of whether a notification survives.
 */
internal class IdRing(private val first: Int, private val size: Int) {

    init {
        require(size > 0) { "a ring needs at least one slot" }
    }

    // Atomic because a burst of recordings routes concurrently.
    private val next = AtomicInteger(0)

    /** The next id, wrapping round to replace this category's oldest. */
    fun take(): Int = first + next.getAndIncrement().mod(size)
}
