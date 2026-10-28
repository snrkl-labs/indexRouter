package org.snrkl.indexrouter.data.prefs

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.snrkl.indexrouter.action.DateFiling
import org.snrkl.indexrouter.data.config.NotifyBlock
import org.snrkl.indexrouter.data.config.CalendarBlock
import org.snrkl.indexrouter.data.config.PrivacyBlock
import org.snrkl.indexrouter.data.db.LogRetention
import org.snrkl.indexrouter.data.config.SettingsBlock
import org.snrkl.indexrouter.data.config.VoiceBlock
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.routing.Tags
import java.time.LocalDateTime

enum class StorageMode { FILES, SAF }

/**
 * Device-local settings.
 *
 * Everything except the vault grant is mirrored into the vault config file, so a
 * reinstall restores it. The tree URI cannot be: Android revokes persisted URI
 * permissions on uninstall.
 *
 * Every vault path the app writes resolves under [baseDir] — see [taskPath],
 * [fallbackPath], [audioPath], [rulePath]. Nothing outside that folder is ever
 * written, which is what removed the daily-note sync contention.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var storageMode: StorageMode
        get() = StorageMode.entries.firstOrNull { it.name == prefs.getString(KEY_STORAGE_MODE, null) }
            ?: StorageMode.FILES
        set(value) = prefs.edit { putString(KEY_STORAGE_MODE, value.name) }

    var vaultRootPath: String?
        get() = prefs.getString(KEY_VAULT_ROOT, null)
        set(value) = prefs.edit { putString(KEY_VAULT_ROOT, value) }

    var vaultTreeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        set(value) = prefs.edit { putString(KEY_TREE_URI, value?.toString()) }

    var receiverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECEIVER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_RECEIVER_ENABLED, value) }

    var lastSyncedAt: Long
        get() = prefs.getLong(KEY_LAST_SYNCED, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNCED, value) }

    var usingFallbackConfig: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, false)
        set(value) = prefs.edit { putBoolean(KEY_FALLBACK, value) }

    // ---- the folder schema -------------------------------------------------

    /** Vault-relative root for everything this app writes. */
    var baseDir: String
        get() = prefs.getString(KEY_BASE_DIR, null) ?: SettingsBlock().baseDir
        set(value) = prefs.edit { putString(KEY_BASE_DIR, value.trim('/')) }

    /**
     * False until the base folder has been chosen deliberately. The default is a
     * guess at a name in someone else's vault, so it is offered on first setup
     * rather than silently adopted.
     */
    var baseDirConfirmed: Boolean
        get() = prefs.getBoolean(KEY_BASE_CONFIRMED, false)
        set(value) = prefs.edit { putBoolean(KEY_BASE_CONFIRMED, value) }

    var taskDirName: String
        get() = prefs.getString(KEY_TASK_DIR, null) ?: SettingsBlock().taskDir
        set(value) = prefs.edit { putString(KEY_TASK_DIR, value.trim('/')) }

    var fallbackDirName: String
        get() = prefs.getString(KEY_FALLBACK_DIR, null) ?: SettingsBlock().fallbackDir
        set(value) = prefs.edit { putString(KEY_FALLBACK_DIR, value.trim('/')) }

    var audioDirName: String
        get() = prefs.getString(KEY_AUDIO_DIR, null) ?: SettingsBlock().audioDir
        set(value) = prefs.edit { putString(KEY_AUDIO_DIR, value.trim('/')) }

    var taskTags: List<String>
        get() = Tags.canonical(
            prefs.getString(KEY_TASK_TAGS, null)?.split(',')?.filter { it.isNotBlank() }
                ?: SettingsBlock().taskTags,
        )
        // Canonicalised on read as well as write: a config file carried over
        // from before this rule holds whichever form was typed at the time.
        set(value) = prefs.edit { putString(KEY_TASK_TAGS, Tags.canonical(value).joinToString(",")) }

    var taskCreatedDate: Boolean
        get() = prefs.getBoolean(KEY_TASK_CREATED, SettingsBlock().taskCreatedDate)
        set(value) = prefs.edit { putBoolean(KEY_TASK_CREATED, value) }

    var mocBackupRetention: Int
        get() = prefs.getInt(KEY_MOC_RETENTION, SettingsBlock().mocBackupRetention)
        set(value) = prefs.edit { putInt(KEY_MOC_RETENTION, value.coerceAtLeast(1)) }

    var dateFiling: DateFiling.Depth
        get() = DateFiling.Depth.fromWire(prefs.getString(KEY_DATE_FILING, null))
        set(value) = prefs.edit { putString(KEY_DATE_FILING, value.wireName) }

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, SettingsBlock().serverPort)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    // ---- notifications -----------------------------------------------------

    /**
     * Whether a route announces itself when it fires.
     *
     * Defaults are off for the routes that succeed quietly: the app exists so a
     * voice note files itself without anyone touching the phone, and a
     * notification per recording undoes that. The sound each enabled route
     * makes is a property of its channel, chosen in Android's settings — see
     * [org.snrkl.indexrouter.data.config.NotifyBlock].
     */
    fun routeNotify(action: ActionType): Boolean =
        prefs.getBoolean(routeKey(action), NotifyBlock().enabledFor(action))

    fun setRouteNotify(action: ActionType, enabled: Boolean) =
        prefs.edit { putBoolean(routeKey(action), enabled) }

    /** A recording that matched nothing. On by default: it wants a decision. */
    var notifyFallback: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_FALLBACK, NotifyBlock().fallback)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_FALLBACK, value) }

    /** Every pipeline milestone as its own notification. Off by default. */
    var debugStream: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_STREAM, NotifyBlock().debugStream)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_STREAM, value) }

    private fun routeKey(action: ActionType) = "notify_${action.wireName}"

    // ---- the log, and what leaves the app ----------------------------------

    /**
     * How long a routing log row is kept, in hours. Zero keeps it until cleared.
     *
     * The log is the only place a dictation's words exist at all, so this is
     * the sole control over the most sensitive content the app handles.
     */
    var logRetentionHours: Int
        get() = prefs.getInt(KEY_LOG_RETENTION, LogRetention.DEFAULT_HOURS)
        set(value) = prefs.edit { putInt(KEY_LOG_RETENTION, value.coerceAtLeast(0)) }

    /**
     * Whether notifications and the milestone stream quote what was said.
     *
     * Off by default: a notification carrying the text puts it on the lock
     * screen, where it is readable by whoever picks the phone up. With it off
     * they say what happened and nothing about what was said.
     */
    var detailedContentLogging: Boolean
        get() = prefs.getBoolean(KEY_DETAILED_CONTENT, false)
        set(value) = prefs.edit { putBoolean(KEY_DETAILED_CONTENT, value) }

    // ---- calendar ----------------------------------------------------------

    var calendarMode: CalendarMode
        get() = CalendarMode.fromWire(prefs.getString(KEY_CALENDAR_MODE, null))
        set(value) = prefs.edit { putString(KEY_CALENDAR_MODE, value.wireName) }

    /** The app whose language parser the phrase is handed to. */
    var calendarSharePackage: String
        get() = prefs.getString(KEY_CALENDAR_PACKAGE, null)?.takeIf { it.isNotBlank() }
            ?: CalendarBlock().sharePackage
        set(value) = prefs.edit { putString(KEY_CALENDAR_PACKAGE, value.trim()) }

    // ---- voice input --------------------------------------------------------

    /**
     * How many recent transcriptions the keyboard can reach.
     *
     * More than one, deliberately: a double-dictation or a stale capture must
     * not leave the wrong text as the only thing available. Only the newest is
     * committed today, but the buffer is what makes picking among them possible
     * without a schema change.
     */
    var voiceBufferSize: Int
        get() = prefs.getInt(KEY_VOICE_BUFFER, VoiceBlock().bufferSize)
        set(value) = prefs.edit { putInt(KEY_VOICE_BUFFER, value.coerceIn(1, 50)) }

    /**
     * How old a transcription may be and still be committed, in minutes.
     *
     * This is the setting doing the real work: tapping the mic key hours later
     * should commit nothing rather than quietly commit something from that
     * morning into whatever field happens to be focused.
     */
    var voiceCutoffMinutes: Int
        get() = prefs.getInt(KEY_VOICE_CUTOFF, VoiceBlock().cutoffMinutes)
        set(value) = prefs.edit { putInt(KEY_VOICE_CUTOFF, value.coerceAtLeast(0)) }

    private fun notifyBlock() = NotifyBlock(
        file = routeNotify(ActionType.FILE),
        task = routeNotify(ActionType.TASK),
        timer = routeNotify(ActionType.TIMER),
        calendar = routeNotify(ActionType.CALENDAR),
        fallback = notifyFallback,
        dictation = routeNotify(ActionType.DICTATION),
        debugStream = debugStream,
    )

    // ---- resolved paths ----------------------------------------------------

    fun taskPath(): String = under(taskDirName)
    fun fallbackPath(): String = under(fallbackDirName)
    fun audioPath(): String = under(audioDirName)

    /** The dated path a recording or unmatched note is filed into today. */
    fun fallbackPathFor(at: LocalDateTime): String = DateFiling.folderFor(fallbackPath(), at, dateFiling)
    fun audioPathFor(at: LocalDateTime): String = DateFiling.folderFor(audioPath(), at, dateFiling)
    fun configPath(): String = under(CONFIG_FILE)

    /** A rule's folder is a single name under the base dir, never a vault path. */
    fun rulePath(folder: String?): String =
        folder?.trim('/')?.takeIf { it.isNotBlank() }?.let { under(it) } ?: fallbackPath()

    private fun under(segment: String): String {
        val root = baseDir.trim('/')
        val leaf = segment.trim('/')
        return if (root.isBlank()) leaf else "$root/$leaf"
    }

    /** Every folder the app itself needs, regardless of what rules exist. */
    fun reservedPaths(): List<String> = listOf(baseDir.trim('/'), taskPath(), fallbackPath(), audioPath())

    fun toBlock() = SettingsBlock(
        baseDir = baseDir,
        taskDir = taskDirName,
        fallbackDir = fallbackDirName,
        audioDir = audioDirName,
        taskTags = taskTags,
        taskCreatedDate = taskCreatedDate,
        serverPort = serverPort,
        mocBackupRetention = mocBackupRetention,
        dateFiling = dateFiling.wireName,
        notifications = notifyBlock(),
        voice = VoiceBlock(bufferSize = voiceBufferSize, cutoffMinutes = voiceCutoffMinutes),
        calendar = CalendarBlock(mode = calendarMode.wireName, sharePackage = calendarSharePackage),
        privacy = PrivacyBlock(
            logRetentionHours = logRetentionHours,
            detailedContentLogging = detailedContentLogging,
        ),
    )

    /**
     * Replaces reserved folder names that are still the pre-v0.5.0 defaults.
     *
     * `tasks`, `inbox` and `Audio` were defaults, not decisions — but they
     * persist in an upgraded install's settings and in its config file, so the
     * app kept honouring them and filing into `tasks`. A name that differs from
     * the old default was chosen deliberately and is left alone.
     *
     * Returns the renames applied, so the caller can say what happened: a
     * rename nobody asked for must be visible, and the folders themselves are
     * the user's to move.
     */
    fun upgradeReservedFolderNames(): List<Pair<String, String>> {
        if (prefs.getBoolean(KEY_RESERVED_UPGRADED, false)) return emptyList()

        val renames = mutableListOf<Pair<String, String>>()
        if (taskDirName == LEGACY_TASK_DIR) {
            taskDirName = SettingsBlock().taskDir
            renames += LEGACY_TASK_DIR to taskDirName
        }
        if (fallbackDirName == LEGACY_FALLBACK_DIR) {
            fallbackDirName = SettingsBlock().fallbackDir
            renames += LEGACY_FALLBACK_DIR to fallbackDirName
        }
        if (audioDirName == LEGACY_AUDIO_DIR) {
            audioDirName = SettingsBlock().audioDir
            renames += LEGACY_AUDIO_DIR to audioDirName
        }
        prefs.edit { putBoolean(KEY_RESERVED_UPGRADED, true) }
        return renames
    }

    fun apply(block: SettingsBlock) {
        baseDir = block.baseDir
        taskDirName = block.taskDir
        fallbackDirName = block.fallbackDir
        audioDirName = block.audioDir
        taskTags = block.taskTags
        taskCreatedDate = block.taskCreatedDate
        serverPort = block.serverPort
        mocBackupRetention = block.mocBackupRetention
        dateFiling = DateFiling.Depth.fromWire(block.dateFiling)
        setRouteNotify(ActionType.FILE, block.notifications.file)
        setRouteNotify(ActionType.TASK, block.notifications.task)
        setRouteNotify(ActionType.TIMER, block.notifications.timer)
        setRouteNotify(ActionType.CALENDAR, block.notifications.calendar)
        setRouteNotify(ActionType.DICTATION, block.notifications.dictation)
        notifyFallback = block.notifications.fallback
        debugStream = block.notifications.debugStream
        voiceBufferSize = block.voice.bufferSize
        voiceCutoffMinutes = block.voice.cutoffMinutes
        calendarMode = CalendarMode.fromWire(block.calendar.mode)
        calendarSharePackage = block.calendar.sharePackage
        logRetentionHours = block.privacy.logRetentionHours
        detailedContentLogging = block.privacy.detailedContentLogging
    }

    private companion object {
        const val CONFIG_FILE = "indexrouter-config.md"
        const val KEY_TREE_URI = "vault_tree_uri"
        const val KEY_STORAGE_MODE = "storage_mode"
        const val KEY_VAULT_ROOT = "vault_root_path"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val KEY_FALLBACK = "using_fallback"
        const val KEY_BASE_DIR = "base_dir"
        const val KEY_BASE_CONFIRMED = "base_dir_confirmed"
        const val KEY_TASK_DIR = "task_dir"
        const val KEY_FALLBACK_DIR = "fallback_dir"
        const val KEY_AUDIO_DIR = "audio_dir"
        const val KEY_TASK_TAGS = "task_tags"
        const val KEY_TASK_CREATED = "task_created_date"
        const val KEY_MOC_RETENTION = "moc_backup_retention"
        const val KEY_DATE_FILING = "date_filing"
        const val KEY_RESERVED_UPGRADED = "reserved_folders_upgraded"
        const val LEGACY_TASK_DIR = "tasks"
        const val LEGACY_FALLBACK_DIR = "inbox"
        const val LEGACY_AUDIO_DIR = "Audio"
        const val KEY_LOG_RETENTION = "log_retention_hours"
        const val KEY_DETAILED_CONTENT = "detailed_content_logging"
        const val KEY_CALENDAR_MODE = "calendar_mode"
        const val KEY_CALENDAR_PACKAGE = "calendar_share_package"
        const val KEY_VOICE_BUFFER = "voice_buffer_size"
        const val KEY_VOICE_CUTOFF = "voice_cutoff_minutes"
        const val KEY_NOTIFY_FALLBACK = "notify_fallback"
        const val KEY_DEBUG_STREAM = "notify_debug_stream"
        const val KEY_PORT = "server_port"
        const val KEY_RECEIVER_ENABLED = "receiver_enabled"
    }
}
