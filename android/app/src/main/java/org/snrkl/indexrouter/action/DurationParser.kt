package org.snrkl.indexrouter.action

/**
 * Pulls a timer length out of spoken text: "set a timer for ten minutes",
 * "timer 90 seconds", "timer for an hour and a half".
 *
 * The transcriber emits number words as often as digits, and which one you get
 * for the same utterance is not stable, so both are handled. When nothing usable
 * is found the caller is told so explicitly — firing a guessed default length
 * would be worse than reporting that the timer never started.
 *
 * No Android types: covered by JVM unit tests.
 */
object DurationParser {

    private val WORD_NUMBERS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fourty" to 40, // common transcriber misspelling
        "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "a" to 1, "an" to 1,
    )

    private val UNIT_SECONDS = mapOf(
        "second" to 1L, "seconds" to 1L, "sec" to 1L, "secs" to 1L,
        "minute" to 60L, "minutes" to 60L, "min" to 60L, "mins" to 60L,
        "hour" to 3600L, "hours" to 3600L, "hr" to 3600L, "hrs" to 3600L,
    )

    /** Total seconds, or null when the text carries no recognisable length. */
    fun parseSeconds(text: String): Long? {
        val words = text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

        var total = 0L
        var matchedAUnit = false
        var pending: Int? = null
        var halfPending = false
        var lastUnit: Long? = null

        words.forEach { word ->
            when {
                word == "half" -> {
                    halfPending = true
                    // "an hour and a half": the "a" is an article, not a count.
                    pending = null
                }

                UNIT_SECONDS.containsKey(word) -> {
                    val unit = UNIT_SECONDS.getValue(word)
                    when {
                        // "half an hour" — the "an" must not win over the "half".
                        halfPending -> total += unit / 2
                        pending != null -> total += pending!! * unit
                        else -> return@forEach
                    }
                    matchedAUnit = true
                    lastUnit = unit
                    pending = null
                    halfPending = false
                }

                else -> {
                    val number = word.toIntOrNull() ?: WORD_NUMBERS[word] ?: return@forEach
                    val carried = pending
                    pending = when {
                        // "forty five" is one number, not two.
                        carried != null && carried >= 20 && carried % 10 == 0 && number < 10 -> carried + number
                        else -> number
                    }
                }
            }
        }

        // Trailing remainder in the next unit down: "1 hour 30" is 90 minutes,
        // "2 minutes 30" is 150 seconds.
        val trailing = pending
        val unit = lastUnit
        if (matchedAUnit && trailing != null && unit != null && unit > 1L) {
            total += trailing * (unit / 60).coerceAtLeast(1L)
        }
        // "an hour and a half".
        if (matchedAUnit && halfPending && unit != null) {
            total += unit / 2
        }

        // "timer for 10" with no unit at all — minutes is the only sane reading.
        if (!matchedAUnit) {
            val bare = pending ?: return null
            return if (bare > 0) bare * 60L else null
        }
        return total.takeIf { it > 0 }
    }

    /** Human label for the timer, e.g. "10 min" — shown by the clock app. */
    fun describe(seconds: Long): String = when {
        seconds % 3600L == 0L -> "${seconds / 3600} hr"
        seconds % 60L == 0L -> "${seconds / 60} min"
        else -> "$seconds sec"
    }
}
