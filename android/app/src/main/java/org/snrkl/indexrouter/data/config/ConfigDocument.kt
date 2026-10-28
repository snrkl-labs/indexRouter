package org.snrkl.indexrouter.data.config

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode

/**
 * The vault-side representation of everything the app knows, minus the shared
 * secret, and the config backup across a reinstall.
 *
 * Format version `00.04.00` (see [ConfigVersion]): the settings and routing
 * table moved out of the YAML frontmatter and into a fenced `yaml` block in the
 * note body. Obsidian renders frontmatter through its properties editor, which
 * is the least hand-editable surface it has — and hand-editing this file is the
 * entire reason it lives in the vault. A fenced block is plain text.
 */
@Serializable
data class ConfigDocument(
    /**
     * Bumped when the meaning of a field changes. A v1 file is not upgraded in
     * place — it is ignored with a notification, and the app starts from bundled
     * defaults, because v1's `folder` values are vault-root paths whose meaning
     * has changed and quietly reinterpreting them would write to the wrong place.
     */
    val version: String = ConfigVersion.CURRENT.text,
    val settings: SettingsBlock = SettingsBlock(),
    val routing: List<RuleBlock> = emptyList(),
) {
    fun payload() = ConfigPayload(settings = settings, routing = routing)
}

/**
 * What actually goes in the body's fenced block: the part worth hand-editing.
 *
 * Separate from [ConfigDocument] because the version belongs in the
 * frontmatter, where a reader — human or machine — sees it before parsing
 * anything else.
 */
@Serializable
data class ConfigPayload(
    val settings: SettingsBlock = SettingsBlock(),
    val routing: List<RuleBlock> = emptyList(),
)

/**
 * `XX.YY.ZZ`, matching the versioning used elsewhere in the vault (the daily
 * note template carries `templateVersion: 00.04.00`).
 *
 * Earlier config files carried a bare integer — `version: 3`. Those are mapped
 * onto `00.0N.00` so one comparison covers both shapes, rather than two code
 * paths that can disagree about which file is too old.
 */
data class ConfigVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ConfigVersion> {

    val text: String get() = "%02d.%02d.%02d".format(major, minor, patch)

    override fun compareTo(other: ConfigVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = text

    companion object {
        /** What this build writes. */
        val CURRENT = ConfigVersion(0, 4, 0)

        /**
         * Oldest format still readable. `00.02.00` and later are read and
         * widened; `00.01.00` is refused, because its `folder` values were
         * vault-root paths whose meaning has since narrowed and no amount of
         * reading can tell which kind a given value is.
         */
        val OLDEST_READABLE = ConfigVersion(0, 2, 0)

        /**
         * Parses either shape: `00.04.00`, a bare integer `3` from a file
         * written before this scheme, or a short `0.4` — a hand-edit is allowed
         * to be sloppy about trailing zeroes. Null when it is not a version at
         * all, which the caller treats as "too old to trust".
         */
        fun parse(raw: String?): ConfigVersion? {
            val trimmed = raw?.trim()?.trim('"', '\'') ?: return null
            if (trimmed.isEmpty()) return null
            // A bare integer is a pre-00.03.00 file: version 3 becomes 00.03.00.
            trimmed.toIntOrNull()?.let { return ConfigVersion(0, it, 0) }
            val parts = trimmed.split('.')
            if (parts.size !in 2..3) return null
            val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
            return ConfigVersion(numbers[0], numbers[1], numbers.getOrElse(2) { 0 })
        }
    }
}

@Serializable
data class SettingsBlock(
    /** The one path the user picks. Everything the app writes lives under it. */
    @SerialName("base-dir") val baseDir: String = "_RingRing",
    /** Reserved subfolder for task files. Always present. */
    @SerialName("task-dir") val taskDir: String = "_Tasks",
    /** Reserved subfolder for recordings that matched no rule. Always present. */
    @SerialName("fallback-dir") val fallbackDir: String = "_Inbox",
    /** Recordings. Not a routing-rule folder — every action's audio lands here. */
    @SerialName("audio-dir") val audioDir: String = "_Audio",
    @SerialName("task-tags") val taskTags: List<String> = listOf("#task", "#index"),
    @SerialName("task-created-date") val taskCreatedDate: Boolean = true,
    @SerialName("server-port") val serverPort: Int = 8787,
    /** Timestamped copies kept per MoC file before a rebuild overwrites it. */
    @SerialName("moc-backup-retention") val mocBackupRetention: Int = 5,
    /**
     * How deeply the inbox and audio folders are filed by date: none, month or
     * day. Both take a file per recording and never lose one, so left flat they
     * become unusable to browse.
     */
    @SerialName("date-filing") val dateFiling: String = "day",
    /** Which routes announce themselves. See [NotifyBlock]. */
    val notifications: NotifyBlock = NotifyBlock(),
    /** What the keyboard's mic key can reach. See [VoiceBlock]. */
    val voice: VoiceBlock = VoiceBlock(),
    /** How a calendar route hands over. See [CalendarBlock]. */
    val calendar: CalendarBlock = CalendarBlock(),
    /** What the app keeps and what it says out loud. See [PrivacyBlock]. */
    val privacy: PrivacyBlock = PrivacyBlock(),
)

/**
 * How long the app keeps what was said, and whether it repeats it.
 *
 * The routing log is the only place a **dictation**'s words exist — that route
 * writes nothing else — so retention here is the sole control over the most
 * sensitive content the app handles. Everything else it logs also exists as a
 * note or a task in the vault, where it is meant to be.
 */
@Serializable
data class PrivacyBlock(
    /** Hours. Zero keeps the log until it is cleared by hand. */
    @SerialName("log-retention-hours") val logRetentionHours: Int = 2,
    /**
     * Whether notifications and the milestone stream quote what was said. Off
     * by default: a notification carrying the text puts it on the lock screen.
     */
    @SerialName("detailed-content-logging") val detailedContentLogging: Boolean = false,
)

/**
 * How the calendar route reaches a calendar.
 *
 * `share-to` sends the spoken phrase as plain text to [sharePackage], whose own
 * language parser turns it into a title, a date and a time — which
 * `ACTION_INSERT` cannot do, since it takes only structured extras and leaves
 * the event in the next default slot.
 *
 * The package is named rather than left to a chooser deliberately:
 * `ACTION_SEND` `text/plain` is claimed by every messaging app, and a share
 * sheet of chat apps in front of a private voice note is one tap from sending a
 * thought to a contact.
 */
@Serializable
data class CalendarBlock(
    val mode: String = CalendarMode.DEFAULT.wireName,
    @SerialName("share-package") val sharePackage: String = "org.onekash.kashcal",
)

/**
 * The transcriptions the voice input method offers the keyboard.
 *
 * [cutoffMinutes] is the setting that matters: without it, tapping the mic key
 * hours after a recording would quietly commit that morning's words into
 * whatever field happens to be focused. Zero disables the cutoff entirely,
 * which is a choice rather than an accident and so is allowed.
 */
@Serializable
data class VoiceBlock(
    @SerialName("buffer-size") val bufferSize: Int = 10,
    @SerialName("cutoff-minutes") val cutoffMinutes: Int = 10,
)

/**
 * Which routes raise a notification when they fire.
 *
 * Each route has its own notification channel, because a sound is a property of
 * a channel on Android 8+ and not of an individual notification — so "a
 * different alert per route" *is* "a channel per route", with the sound chosen
 * in Android's own settings rather than in this app.
 *
 * The successful routes default off: a notification per voice note is noise
 * when the whole point of the app is that a note files itself without anyone
 * touching the phone. [fallback] defaults on because that is the existing
 * behaviour and it is a route that needs attention. Failures are not
 * configurable at all — a lost recording must never pass quietly.
 */
@Serializable
data class NotifyBlock(
    val file: Boolean = false,
    val task: Boolean = false,
    val timer: Boolean = false,
    val calendar: Boolean = false,
    /** A recording that matched nothing. On by default — it wants a decision. */
    val fallback: Boolean = true,
    /**
     * Every pipeline milestone as its own notification. Verbose by design and
     * on its own low-importance channel, for working out why a webhook did not
     * do what was expected.
     */
    /**
     * A dictation notifies by default, unlike the other routes.
     *
     * It is the one route that leaves nothing behind, so without a notification
     * there is no sign it happened at all — and seeing what was actually
     * transcribed *before* pasting it is worth more here than anywhere else.
     */
    val dictation: Boolean = true,
    @SerialName("debug-stream") val debugStream: Boolean = false,
) {
    fun enabledFor(action: ActionType): Boolean = when (action) {
        ActionType.FILE -> file
        ActionType.TASK -> task
        ActionType.TIMER -> timer
        ActionType.CALENDAR -> calendar
        ActionType.DICTATION -> dictation
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RuleBlock(
    /**
     * Every phrase that fires this rule. A `00.02.00` file carries `keyword:` as
     * a single string; it is widened to a one-item list on read, so upgrading
     * costs nothing — unlike the v1 folder change, this one has no ambiguity to
     * guess at.
     */
    val keywords: List<String> = emptyList(),
    /**
     * Pre-00.03.00 compatibility only: read and folded into [keywords], never
     * written. `EncodeDefault.NEVER` is what keeps it out of the file — with it
     * emitted, every rule in a hand-editable config carried a `keyword: null`
     * line meaning nothing.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val keyword: String? = null,
    val action: String,
    /**
     * A single folder name, resolved as `${baseDir}/${folder}`. In v1 this was an
     * arbitrary vault path; narrowing it is why v1 files are not auto-upgraded.
     */
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("match-type") val matchType: String = "contains_word",
    val priority: Int = 100,
    val enabled: Boolean = true,
    @SerialName("include-in-moc") val includeInMoc: Boolean = true,
    /**
     * Whether the keyword is removed from the front of what was said.
     *
     * Defaults to on, which is what every example of the behaviour asked for.
     * It only ever strips from the **start**, so a keyword used mid-sentence is
     * left as content.
     */
    @SerialName("strip-keyword") val stripKeyword: Boolean = true,
) {
    /** The keywords, whichever format the file used. */
    fun allKeywords(): List<String> =
        (keywords + listOfNotNull(keyword)).map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
