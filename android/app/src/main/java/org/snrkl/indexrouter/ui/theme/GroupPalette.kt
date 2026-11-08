package org.snrkl.indexrouter.ui.theme

import org.snrkl.indexrouter.routing.RuleGroup

/**
 * Group colour seeds: the defaults, and the swatches offered in the picker.
 *
 * Seeds only — what is drawn is derived from them per theme by [Tonal]. The
 * default red sits at the red edge of what [Tonal.ERROR_CLEARANCE] allows,
 * reading as red without being the error hue: the error role already means
 * "something failed" on this screen (failures, the swipe-to-delete reveal,
 * validation), and a group that reads as an error would be worse than no
 * colour at all.
 *
 * No Android types: covered by JVM unit tests.
 */
object GroupPalette {

    const val MUTED_PURPLE = 0xFF7E6A9E.toInt()
    const val ROSE = 0xFFAE4974.toInt()
    const val MUTED_BLUE = 0xFF5A7BA8.toInt()

    fun defaultSeed(group: RuleGroup): Int = when (group) {
        RuleGroup.COMMON -> MUTED_PURPLE
        RuleGroup.SINGLE_CLICK_HOLD -> ROSE
        RuleGroup.DOUBLE_CLICK_HOLD -> MUTED_BLUE
        RuleGroup.CARRIED_OVER -> 0xFF7A7A7A.toInt()
    }

    /** The curated set. Any custom hex is accepted too; derivation keeps it readable. */
    val SWATCHES: List<Int> = listOf(
        MUTED_PURPLE,
        ROSE,
        MUTED_BLUE,
        0xFF4F8A7A.toInt(), // teal
        0xFF6E8B4E.toInt(), // moss
        0xFFB08440.toInt(), // ochre
        0xFF8A6E5A.toInt(), // clay
        0xFF5E6E8A.toInt(), // slate
    )

    fun parseHex(text: String): Int? {
        val clean = text.trim().removePrefix("#")
        if (clean.length != 6 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
        return (0xFF000000.toInt()) or clean.toInt(16)
    }

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
