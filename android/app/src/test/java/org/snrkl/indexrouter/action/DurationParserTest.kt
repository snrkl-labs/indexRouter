package org.snrkl.indexrouter.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    @Test
    fun `digits with a unit`() {
        assertEquals(600L, DurationParser.parseSeconds("timer 10 minutes"))
        assertEquals(90L, DurationParser.parseSeconds("Timer for 90 seconds."))
        assertEquals(7200L, DurationParser.parseSeconds("set a timer for 2 hours"))
    }

    @Test
    fun `spoken number words`() {
        assertEquals(600L, DurationParser.parseSeconds("timer for ten minutes"))
        assertEquals(2700L, DurationParser.parseSeconds("timer forty five minutes"))
    }

    @Test
    fun `bare number falls back to minutes`() {
        assertEquals(300L, DurationParser.parseSeconds("timer 5"))
    }

    @Test
    fun `half an hour`() {
        assertEquals(1800L, DurationParser.parseSeconds("timer for half an hour"))
    }

    @Test
    fun `no length means no timer, never a guessed default`() {
        assertNull(DurationParser.parseSeconds("timer please"))
        assertNull(DurationParser.parseSeconds("remind me later"))
    }

    @Test
    fun `an hour and a half`() {
        assertEquals(5400L, DurationParser.parseSeconds("timer for an hour and a half"))
    }

    @Test
    fun `trailing remainder lands in the next unit down`() {
        assertEquals(5400L, DurationParser.parseSeconds("timer 1 hour 30"))
        assertEquals(150L, DurationParser.parseSeconds("timer 2 minutes 30"))
    }

    @Test
    fun `describe produces the label the clock app shows`() {
        assertEquals("10 min", DurationParser.describe(600))
        assertEquals("2 hr", DurationParser.describe(7200))
        assertEquals("90 sec", DurationParser.describe(90))
    }
}
