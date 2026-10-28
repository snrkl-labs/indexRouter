package org.snrkl.indexrouter.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class LogRetentionTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `the cutoff is the retention behind now`() {
        assertEquals(now - TimeUnit.HOURS.toMillis(2), LogRetention.cutoff(now, 2))
        assertEquals(now - TimeUnit.HOURS.toMillis(24), LogRetention.cutoff(now, 24))
    }

    @Test
    fun `zero means keep until cleared, so nothing is pruned`() {
        // Not "prune everything", which is what a zero cutoff would mean if it
        // were computed rather than special-cased — and would silently empty
        // the log of the one route whose words live nowhere else.
        assertNull(LogRetention.cutoff(now, 0))
        assertNull(LogRetention.cutoff(now, -1))
    }

    @Test
    fun `two hours is the default`() {
        assertEquals(2, LogRetention.DEFAULT_HOURS)
    }

    @Test
    fun `it describes itself in words rather than a count of hours`() {
        assertEquals("Keep until cleared", LogRetention.describe(0))
        assertEquals("1 hour", LogRetention.describe(1))
        assertEquals("2 hours", LogRetention.describe(2))
        assertEquals("1 day", LogRetention.describe(24))
        assertEquals("3 days", LogRetention.describe(72))
    }
}
