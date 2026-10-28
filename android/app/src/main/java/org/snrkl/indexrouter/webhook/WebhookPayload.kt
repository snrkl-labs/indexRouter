package org.snrkl.indexrouter.webhook

/**
 * One voice note as delivered by the Pebble app.
 *
 * Field names mirror the multipart form exactly (`client`, `recordedAt`,
 * `transcription`) of a captured payload. Everything is
 * nullable except the transcript, because a firmware or app update may drop or
 * rename a field and losing a note to a parse failure is the worst outcome here.
 */
data class WebhookPayload(
    val transcript: String,
    val recordedAtMillis: Long?,
    val client: String?,
    /** `x-index-trigger`, e.g. "double-click-hold" — lets one endpoint serve both patterns. */
    val trigger: String?,
    /** `user-agent`, e.g. "CoreApp/1.8.0.6-1-g4546d7b2f" — version sanity-checking. */
    val userAgent: String?,
    val audio: AudioClip?,
) {
    data class AudioClip(
        /** Original name, e.g. ring_EA22…-…m4a. */
        val fileName: String,
        val bytes: ByteArray,
    ) {
        // ByteArray in a data class needs these; identity comparison is meaningless.
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioClip && fileName == other.fileName && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }
}
