package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarModeTest {

    @Test
    fun `wire names round-trip, and are what a hand-edited config would write`() {
        assertEquals(CalendarMode.INSERT, CalendarMode.fromWire("insert"))
        assertEquals(CalendarMode.SHARE_TO, CalendarMode.fromWire("share-to"))
        assertEquals(CalendarMode.SHARE_TO, CalendarMode.fromWire("  Share-To  "))
    }

    @Test
    fun `anything unrecognised falls to the default rather than failing the config`() {
        // A typo in a hand-edit should cost the setting, not the whole file.
        assertEquals(CalendarMode.DEFAULT, CalendarMode.fromWire("nonsense"))
        assertEquals(CalendarMode.DEFAULT, CalendarMode.fromWire(null))
        assertEquals(CalendarMode.DEFAULT, CalendarMode.fromWire(""))
    }

    @Test
    fun `sending the phrase is the default, because the parsing is the point`() {
        assertEquals(CalendarMode.SHARE_TO, CalendarMode.DEFAULT)
    }
}
