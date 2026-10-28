package org.snrkl.indexrouter.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LogMarkerTest {

    @Test
    fun `the marker reads exactly as specified`() {
        assertEquals(
            "----- cleared on 2026-09-23-17:42:11 -----",
            LogMarker.text(LocalDateTime.of(2026, 9, 23, 17, 42, 11)),
        )
    }

    @Test
    fun `it says nothing about why the log was cleared`() {
        // Deliberate: the log records that content went and when, not whether
        // a person did it or the retention rule did. Differentiating them would
        // turn the log into a record of habits, which is the opposite of why
        // the retention exists.
        val manual = LogMarker.text(LocalDateTime.of(2026, 9, 23, 9, 0, 0))
        val automatic = LogMarker.text(LocalDateTime.of(2026, 9, 23, 9, 0, 0))
        assertEquals(manual, automatic)
    }

    @Test
    fun `single-digit parts are padded, so markers align and sort`() {
        assertEquals(
            "----- cleared on 2026-01-02-03:04:05 -----",
            LogMarker.text(LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
        )
    }

    @Test
    fun `a marker is recognisable from its text alone`() {
        assertTrue(LogMarker.looksLikeMarker(LogMarker.text(LocalDateTime.now())))
        assertFalse(LogMarker.looksLikeMarker("Task buy milk"))
        assertFalse(LogMarker.looksLikeMarker("----- cleared on"))
    }

    @Test
    fun `the marker carries no message content`() {
        // The reason it survives the pruning that removes everything around it.
        val text = LogMarker.text(LocalDateTime.of(2026, 9, 23, 17, 42, 11))
        assertEquals("----- cleared on 2026-09-23-17:42:11 -----", text)
        assertTrue(text.none { it.isLetter() && it !in "clearedon" })
    }
}
