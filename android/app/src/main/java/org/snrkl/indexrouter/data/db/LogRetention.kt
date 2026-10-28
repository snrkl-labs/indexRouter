package org.snrkl.indexrouter.data.db

import java.util.concurrent.TimeUnit

/**
 * How long a routing log row lives.
 *
 * The log is the only place a **dictation**'s words exist at all — that route
 * writes nothing else, by design — so this is the sole control over the most
 * sensitive content the app handles. Everything else it records also exists as
 * a note or a task in the vault, where it is meant to be.
 *
 * Two hours by default: long enough to answer "where did that go?", short
 * enough that a phone picked up tomorrow holds nothing worth reading.
 *
 * No Android types: covered by JVM unit tests.
 */
object LogRetention {

    const val KEEP_UNTIL_CLEARED = 0
    const val DEFAULT_HOURS = 2

    /**
     * The cutoff before which rows should go, or null when nothing should be
     * pruned automatically.
     */
    fun cutoff(now: Long, retentionHours: Int): Long? {
        if (retentionHours <= KEEP_UNTIL_CLEARED) return null
        return now - TimeUnit.HOURS.toMillis(retentionHours.toLong())
    }

    fun describe(retentionHours: Int): String = when {
        retentionHours <= KEEP_UNTIL_CLEARED -> "Keep until cleared"
        retentionHours == 1 -> "1 hour"
        retentionHours < 24 -> "$retentionHours hours"
        retentionHours % 24 == 0 -> "${retentionHours / 24} day${if (retentionHours == 24) "" else "s"}"
        else -> "$retentionHours hours"
    }
}
