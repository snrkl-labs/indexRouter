package org.snrkl.indexrouter.data.db

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The line written into the log when it is cleared.
 *
 * Deliberately identical whether the clearing was manual or the retention rule
 * doing its work: the log should not record *why* content went, only that it
 * did and when. Differentiating them would make the log itself a record of the
 * user's habits, which is the opposite of the point.
 *
 * Holds no message content, which is why a marker survives the pruning that
 * removes everything around it.
 *
 * No Android types: covered by JVM unit tests.
 */
object LogMarker {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss")

    fun text(at: LocalDateTime): String = "----- cleared on ${STAMP.format(at)} -----"

    /** Recognises a marker from its text, for anything reading rows back. */
    fun looksLikeMarker(text: String): Boolean =
        text.startsWith("----- cleared on ") && text.endsWith(" -----")
}
