package org.snrkl.indexrouter.voice

/**
 * Which recent transcription the keyboard should be handed, if any.
 *
 * Pure logic, no Android types, so the age cutoff — the part that decides
 * whether someone's text field gets this morning's shopping list — is covered
 * by unit tests rather than by trying it on a handset.
 */
object TranscriptBuffer {

    /** A transcript and when the webhook carrying it arrived. */
    data class Entry(val text: String, val receivedAt: Long)

    sealed interface Result {
        /** Commit this. */
        data class Commit(val text: String, val ageMillis: Long) : Result

        /** Nothing has been dictated yet, or the log is empty. */
        data object Empty : Result

        /**
         * There is a transcript, but it is older than the cutoff.
         *
         * Reported rather than committed, and reported *with* its age: a mic
         * key that silently does nothing is indistinguishable from a broken
         * one, and the reason it did nothing is the thing worth knowing.
         */
        data class TooOld(val ageMillis: Long, val cutoffMillis: Long) : Result
    }

    /**
     * @param entries recent transcripts, newest first.
     * @param now current time in millis.
     * @param cutoffMillis how old a transcript may be and still be committed;
     *   zero or less means no cutoff at all.
     */
    fun pick(entries: List<Entry>, now: Long, cutoffMillis: Long): Result {
        val newest = entries.firstOrNull { it.text.isNotBlank() } ?: return Result.Empty
        // A clock adjustment, or a recordedAt from a device whose clock ran
        // ahead, must not produce a negative age that sails past the cutoff.
        val age = (now - newest.receivedAt).coerceAtLeast(0L)
        return when {
            cutoffMillis <= 0L -> Result.Commit(newest.text.trim(), age)
            age <= cutoffMillis -> Result.Commit(newest.text.trim(), age)
            else -> Result.TooOld(age, cutoffMillis)
        }
    }
}
