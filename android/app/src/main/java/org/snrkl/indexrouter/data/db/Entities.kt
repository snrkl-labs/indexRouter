package org.snrkl.indexrouter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The routing table, authoritative at runtime.
 *
 * The vault YAML is a durable mirror written through on every edit, not a source
 * polled per webhook — reading a file on the way to routing a voice note would
 * add latency to the one path that must not be slow.
 */
/**
 * A rule and the phrases that fire it.
 *
 * The old single `keyword` column was uniquely indexed, which made SQLite the
 * only thing enforcing that two rules cannot claim the same word — and it
 * enforces by throwing, which crashed the app. With a set per rule, uniqueness
 * spans the union of every rule's keywords, which no single-column index can
 * express, so it is checked in the repository before writing instead.
 */
@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Comma-separated, stored lower-cased; matching normalises both sides anyway. */
    val keywords: String,
    val action: String,
    val folder: String? = null,
    /** Comma-separated. A rule has a handful of tags; a join table would be ceremony. */
    val tags: String = "",
    @ColumnInfo(defaultValue = "contains_word") val matchType: String = "contains_word",
    @ColumnInfo(defaultValue = "100") val priority: Int = 100,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Whether this rule's folder appears in the generated Map of Content. */
    @ColumnInfo(defaultValue = "1") val includeInMoc: Boolean = true,
    /**
     * Whether the keyword that fired this rule is removed from what gets
     * written or handed on.
     *
     * Per rule rather than per action type, which is how it used to be decided:
     * "strip for tasks but not notes" was a global judgement that could not be
     * right for everyone's rules. The strip itself stays anchored to the start
     * of the phrase, so it only fires when the keyword actually opens what was
     * said.
     */
    @ColumnInfo(defaultValue = "1") val stripKeyword: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One row per webhook, written whatever the outcome.
 *
 * Timer and calendar actions leave no trace in the vault at all, so for those
 * this table is the only evidence the note ever existed.
 */
@Entity(tableName = "routing_log", indices = [Index(value = ["receivedAt"])])
data class RoutingLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receivedAt: Long,
    /** From the payload's `recordedAt` (epoch ms) — when it was actually spoken. */
    val recordedAt: Long? = null,
    val client: String? = null,
    /** `x-index-trigger`, e.g. double-click-hold. */
    val trigger: String? = null,
    val userAgent: String? = null,
    val transcript: String,
    /**
     * Always "pebble-app" today. The column exists so local re-transcription can
     * land later without a migration.
     */
    @ColumnInfo(defaultValue = "pebble-app") val transcriptSource: String = "pebble-app",
    val matchedRuleId: Long? = null,
    val matchedKeyword: String? = null,
    val action: String? = null,
    /** SUCCESS | FALLBACK | FAILED */
    val outcome: String,
    /** Vault-relative path of whatever was written, for jumping straight to it. */
    val targetPath: String? = null,
    val audioPath: String? = null,
    val audioBytes: Int = 0,
    /**
     * True when the user reclassified this item by hand rather than the classifier
     * matching it. Kept separate from matchedKeyword/action rather than
     * overwriting them, so "what does the classifier keep getting wrong" stays
     * answerable — which is the whole point of allowing re-route.
     */
    @ColumnInfo(defaultValue = "0") val manuallyRouted: Boolean = false,
    /**
     * How long this recording took to handle, end to end. Kept so a stall can be
     * quantified after it happened rather than only while watching logcat.
     */
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    /**
     * Whether the keyword was removed on the way out.
     *
     * Recorded because the log keeps the **raw** transcript — that is what
     * traceability means — while the voice input method reads text back out of
     * it to commit into a text field. Without this it could not tell whether
     * the rule wanted the keyword removed, and would either commit "Dictation
     * buy the tickets" or strip a keyword the rule was told to keep.
     */
    @ColumnInfo(defaultValue = "0") val keywordStripped: Boolean = false,
    /**
     * A marker row, written when the log is cleared — by hand or by the
     * retention rule, which are deliberately indistinguishable.
     *
     * It holds no message content, which is why it survives the pruning that
     * removes everything around it: a log that silently empties with no trace
     * is the thing the marker exists to prevent.
     */
    @ColumnInfo(defaultValue = "0") val isMarker: Boolean = false,
    val errorMessage: String? = null,
)
