package org.snrkl.indexrouter.action

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class DateFilingTest {

    private val at = LocalDateTime.of(2026, 8, 5, 14, 3)

    @Test
    fun `day filing mirrors the vault's own daily note layout`() {
        assertEquals("_Inbox/2026/08/05", DateFiling.folderFor("_Inbox", at, DateFiling.Depth.DAY))
    }

    @Test
    fun `month filing stops a level higher`() {
        assertEquals("_Audio/2026/08", DateFiling.folderFor("_Audio", at, DateFiling.Depth.MONTH))
    }

    @Test
    fun `none leaves the folder flat`() {
        assertEquals("_Audio", DateFiling.folderFor("_Audio", at, DateFiling.Depth.NONE))
    }

    @Test
    fun `single digit months and days keep their leading zero`() {
        // Otherwise 2026/8/5 and 2026/08/05 become different folders.
        assertEquals("x/2026/01/02", DateFiling.folderFor("x", LocalDateTime.of(2026, 1, 2, 0, 0), DateFiling.Depth.DAY))
    }

    @Test
    fun `an unknown depth falls back to day rather than throwing`() {
        assertEquals(DateFiling.Depth.DAY, DateFiling.Depth.fromWire("nonsense"))
        assertEquals(DateFiling.Depth.MONTH, DateFiling.Depth.fromWire("month"))
    }
}
