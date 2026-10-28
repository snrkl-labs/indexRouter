package org.snrkl.indexrouter.action

import org.snrkl.indexrouter.routing.Tags
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the note bodies written into the vault.
 *
 * The frontmatter shape is a contract, not a preference: `created` as a bare ISO
 * date is what Dataview compares against a daily note's own date, and `routed-by`
 * is what makes "show me everything that came in via rule X" answerable from
 * inside Obsidian.
 *
 * Tags are written **twice, deliberately**: bare in the frontmatter, matching
 * the Metadata Menu convention used across the vault, and hashed inline in the
 * body. Frontmatter alone is findable by a `tag:` search but not by typing
 * `#blogPostIdeas` into search, and both ways of looking should find a routed
 * note.
 *
 * No Android types: covered by JVM unit tests.
 */
object NoteContent {

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun build(
        transcript: String,
        routedBy: String,
        tags: List<String>,
        createdOn: LocalDate,
        recordedAt: LocalDateTime?,
        audioFileName: String?,
        trigger: String?,
    ): String = buildString {
        appendLine("---")
        appendLine("created: $createdOn")
        appendLine("source: pebble-index-01")
        appendLine("routed-by: $routedBy")
        appendLine("tags: [${Tags.bare(tags).joinToString(", ")}]")
        recordedAt?.let { appendLine("recorded-at: ${ISO_LOCAL.format(it)}") }
        trigger?.let { appendLine("trigger: $it") }
        audioFileName?.let { appendLine("audio: \"[[$it]]\"") }
        appendLine("---")
        appendLine()
        appendLine(transcript.trim())
        // Hashed and inline as well as in the frontmatter, so a plain-text
        // search for the tag finds this note too.
        Tags.inline(tags).takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(it)
        }
        if (audioFileName != null) {
            appendLine()
            // Embedded, not linked: the recording is the ground truth when the
            // transcriber mangles a proper noun.
            appendLine("![[$audioFileName]]")
        }
    }

    /**
     * Filename: sortable timestamp plus the opening words, so the vault reads as
     * a chronological capture log and each note is still recognisable.
     */
    fun fileName(transcript: String, at: LocalDateTime, maxWords: Int = 8): String {
        val words = transcript.trim()
            .split(Regex("\\s+"))
            .take(maxWords)
            .joinToString(" ")
        val safe = words
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '\'') it else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()
            .trimEnd('.', '-')
            .take(60)
        val stamp = FILE_STAMP.format(at)
        return if (safe.isBlank()) stamp else "$stamp $safe"
    }

    fun toLocalDateTime(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
        epochMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
}
