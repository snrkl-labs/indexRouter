package org.snrkl.indexrouter.ui.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns a group's colour seed into colours that work in either theme.
 *
 * A seed is never drawn as it is. It is taken apart into lightness, chroma and
 * hue (OKLCH, which is perceptually even, so a fixed lightness means the same
 * contrast whatever the hue), and rebuilt at a lightness chosen for the theme,
 * with its chroma capped so nothing is shouty, and its hue pulled a little
 * towards the theme's primary so the groups sit inside whatever palette dynamic
 * colour produced. This is the same idea as Material's tonal palettes and its
 * harmonise step, done in OKLCH rather than HCT so it needs no dependency.
 *
 * Because the lightness is fixed by the theme, not by the seed, any seed the
 * user picks comes out readable: the contrast is guaranteed by construction
 * and checked in the unit tests.
 *
 * No Android types: covered by JVM unit tests. Colours are ARGB ints.
 */
object Tonal {

    data class Lch(val l: Double, val c: Double, val h: Double)

    /** The three colours a group needs in one theme. */
    data class GroupTones(val container: Int, val onContainer: Int, val accent: Int)

    // Lightness per role and theme, and the chroma ceilings that keep it muted.
    private const val LIGHT_CONTAINER_L = 0.92
    private const val LIGHT_ON_L = 0.32
    private const val LIGHT_ACCENT_L = 0.56
    private const val DARK_CONTAINER_L = 0.33
    private const val DARK_ON_L = 0.92
    private const val DARK_ACCENT_L = 0.76
    private const val CONTAINER_C = 0.065
    private const val ON_C = 0.09
    private const val ACCENT_C = 0.13

    /** How far a hue may be turned towards the primary, in degrees. */
    private const val MAX_HARMONISE = 15.0

    /** How close to the error hue harmonising may bring a group's hue, in degrees. */
    const val ERROR_CLEARANCE = 25.0

    /**
     * @param error the theme's error colour. Harmonising never moves a hue into
     *   [ERROR_CLEARANCE] of it: a red wallpaper must not turn a rose group into
     *   something that reads as a failure. A seed the user picked inside that
     *   band is left where they put it — that is their choice, not a drift.
     */
    fun tones(seed: Int, primary: Int, dark: Boolean, error: Int? = null): GroupTones {
        val s = toLch(seed)
        var h = harmonise(s.h, toLch(primary).h)
        if (error != null) {
            val e = toLch(error).h
            if (hueDistance(s.h, e) >= ERROR_CLEARANCE && hueDistance(h, e) < ERROR_CLEARANCE) {
                // Back off along the way it came until it clears the band.
                val sign = if (((e - s.h + 540.0) % 360.0) - 180.0 > 0) -1 else 1
                h = (e + sign * ERROR_CLEARANCE + 360.0) % 360.0
            }
        }
        fun make(l: Double, cap: Double) = fromLch(Lch(l, min(s.c, cap), h))
        return if (dark) {
            GroupTones(make(DARK_CONTAINER_L, CONTAINER_C), make(DARK_ON_L, ON_C), make(DARK_ACCENT_L, ACCENT_C))
        } else {
            GroupTones(make(LIGHT_CONTAINER_L, CONTAINER_C), make(LIGHT_ON_L, ON_C), make(LIGHT_ACCENT_L, ACCENT_C))
        }
    }

    /** Turns [hue] towards [toward] by half the gap, at most [MAX_HARMONISE] degrees. */
    fun harmonise(hue: Double, toward: Double): Double {
        val diff = ((toward - hue + 540.0) % 360.0) - 180.0
        val step = min(abs(diff) / 2.0, MAX_HARMONISE) * if (diff < 0) -1 else 1
        return (hue + step + 360.0) % 360.0
    }

    /** Shortest angular distance between two hues, in degrees. */
    fun hueDistance(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    // ---- conversions ---------------------------------------------------------

    fun toLch(argb: Int): Lch {
        val r = linear(((argb shr 16) and 0xFF) / 255.0)
        val g = linear(((argb shr 8) and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = (Math.toDegrees(atan2(okB, okA)) + 360.0) % 360.0
        return Lch(okL, hypot(okA, okB), hue)
    }

    /**
     * Back to sRGB. A colour outside the screen's gamut loses chroma until it
     * fits, keeping its lightness and hue — the two things that carry meaning
     * here — rather than being clipped channel by channel.
     */
    fun fromLch(lch: Lch): Int {
        var lo = 0.0
        var hi = lch.c
        var best = rgbOrNull(lch.l, 0.0, lch.h)!!
        rgbOrNull(lch.l, hi, lch.h)?.let { return pack(it) }
        repeat(24) {
            val mid = (lo + hi) / 2
            val rgb = rgbOrNull(lch.l, mid, lch.h)
            if (rgb != null) { best = rgb; lo = mid } else hi = mid
        }
        return pack(best)
    }

    private fun rgbOrNull(l: Double, c: Double, h: Double): DoubleArray? {
        val a = c * cos(Math.toRadians(h))
        val b = c * sin(Math.toRadians(h))
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        val g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        val bl = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        val eps = 1e-6
        if (listOf(r, g, bl).any { it < -eps || it > 1 + eps }) return null
        return doubleArrayOf(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), bl.coerceIn(0.0, 1.0))
    }

    private fun pack(linearRgb: DoubleArray): Int {
        val (r, g, b) = linearRgb.map { (gamma(it) * 255.0 + 0.5).toInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    /** WCAG relative luminance. */
    fun luminance(argb: Int): Double {
        val r = linear(((argb shr 16) and 0xFF) / 255.0)
        val g = linear(((argb shr 8) and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
