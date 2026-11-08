package org.snrkl.indexrouter.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonalTest {

    /** A spread of primaries, as dynamic colour might produce, plus the fallback. */
    private val primaries = listOf(
        0xFF6750A4, 0xFFD0BCFF, 0xFF006A6A, 0xFF8C1D18, 0xFF3A6A00, 0xFF0061A4, 0xFFB3261E, 0xFF7D5260,
    ).map { it.toInt() }

    private val anything = GroupPalette.SWATCHES + listOf(
        0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF000000, 0xFFFFFFFF, 0xFFFF00FF,
    ).map { it.toInt() }

    @Test
    fun `any seed reads at 4_5 to 1 or better, in either theme, whatever the primary`() {
        for (seed in anything) for (primary in primaries) for (dark in listOf(false, true)) {
            val t = Tonal.tones(seed, primary, dark)
            val ratio = Tonal.contrast(t.container, t.onContainer)
            assertTrue("seed ${GroupPalette.toHex(seed)} dark=$dark ratio=$ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `nothing comes out saturated`() {
        for (seed in anything) for (dark in listOf(false, true)) {
            val t = Tonal.tones(seed, primaries[0], dark)
            assertTrue(Tonal.toLch(t.container).c <= 0.07)
            assertTrue(Tonal.toLch(t.accent).c <= 0.135)
        }
    }

    @Test
    fun `the default red is kept well away from the error hue`() {
        // M3's baseline error roles; dynamic schemes keep error near this hue.
        for (primary in primaries) for (dark in listOf(false, true)) {
            val error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
            val t = Tonal.tones(GroupPalette.ROSE, primary, dark, error)
            for (c in listOf(t.accent, t.container, t.onContainer)) {
                val d = Tonal.hueDistance(Tonal.toLch(c).h, Tonal.toLch(error).h)
                assertTrue("primary=${GroupPalette.toHex(primary)} dark=$dark d=$d", d >= Tonal.ERROR_CLEARANCE - 1)
            }
        }
    }

    @Test
    fun `harmonising moves a hue at most fifteen degrees, towards the primary`() {
        assertEquals(15.0, Tonal.harmonise(0.0, 90.0), 1e-9)
        assertEquals(5.0, Tonal.harmonise(0.0, 10.0), 1e-9)
        assertEquals(345.0, Tonal.harmonise(0.0, 270.0), 1e-9)
    }

    @Test
    fun `round trip through OKLCH is faithful`() {
        for (seed in GroupPalette.SWATCHES) {
            val back = Tonal.fromLch(Tonal.toLch(seed))
            for (shift in listOf(16, 8, 0)) {
                assertTrue(kotlin.math.abs(((back shr shift) and 0xFF) - ((seed shr shift) and 0xFF)) <= 1)
            }
        }
    }

    @Test
    fun `hex parsing accepts what people type`() {
        assertEquals(0xFF5A7BA8.toInt(), GroupPalette.parseHex("#5a7ba8"))
        assertEquals(0xFF5A7BA8.toInt(), GroupPalette.parseHex(" 5A7BA8 "))
        assertEquals(null, GroupPalette.parseHex("#12345"))
        assertEquals(null, GroupPalette.parseHex("zzzzzz"))
    }
}
