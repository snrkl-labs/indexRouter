package org.snrkl.indexrouter.data.prefs

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.snrkl.indexrouter.action.DateFiling
import org.snrkl.indexrouter.data.config.LayoutBlock
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.routing.Tags
import java.time.LocalDateTime

/**
 * One gesture's vault: where it is, and how it is filed.
 *
 * The where — root path or tree grant, and the base folder — is the gesture
 * mapping, and lives only in the app. No vault file may name another vault,
 * and this is the setting that names both.
 *
 * The how — reserved folder names, date filing, map retention, task tags — is
 * the vault's **layout**. It is mirrored into that vault's own config file, so
 * a reinstall restores it from the vault it describes. See [toLayout].
 *
 * Every path the app writes for this gesture resolves under [baseDir], which
 * may have several segments (`Inbox/_RingRing`). Rule folders and reserved
 * folder names stay single names beneath it.
 */
class VaultProfile internal constructor(
    private val prefs: SharedPreferences,
    val group: RuleGroup,
) {
    init {
        require(group.hasVault) { "The common group has no vault" }
    }

    private fun key(name: String) = "${group.wireName}.$name"

    var storageMode: StorageMode
        get() = StorageMode.entries.firstOrNull { it.name == prefs.getString(key(KEY_STORAGE_MODE), null) }
            ?: StorageMode.FILES
        set(value) = prefs.edit { putString(key(KEY_STORAGE_MODE), value.name) }

    var vaultRootPath: String?
        get() = prefs.getString(key(KEY_VAULT_ROOT), null)
        set(value) = prefs.edit { putString(key(KEY_VAULT_ROOT), value) }

    var vaultTreeUri: Uri?
        get() = prefs.getString(key(KEY_TREE_URI), null)?.let(Uri::parse)
        set(value) = prefs.edit { putString(key(KEY_TREE_URI), value?.toString()) }

    /** Whether a vault has been chosen for this gesture at all. */
    val isAssigned: Boolean
        get() = when (storageMode) {
            StorageMode.FILES -> !vaultRootPath.isNullOrBlank()
            StorageMode.SAF -> vaultTreeUri != null
        }

    /** Forgets the vault. The gesture's deliveries then fail visibly, never fall over. */
    fun unassign() = prefs.edit {
        remove(key(KEY_VAULT_ROOT))
        remove(key(KEY_TREE_URI))
        remove(key(KEY_STORAGE_MODE))
        remove(key(KEY_BASE_CONFIRMED))
    }

    var lastSyncedAt: Long
        get() = prefs.getLong(key(KEY_LAST_SYNCED), 0L)
        set(value) = prefs.edit { putLong(key(KEY_LAST_SYNCED), value) }

    var usingFallbackConfig: Boolean
        get() = prefs.getBoolean(key(KEY_FALLBACK), false)
        set(value) = prefs.edit { putBoolean(key(KEY_FALLBACK), value) }

    /**
     * The common rules in this vault's file are behind the app's: a common
     * edit was made while this vault could not be reached. Cleared by the next
     * successful write to it.
     */
    var commonStale: Boolean
        get() = prefs.getBoolean(key(KEY_COMMON_STALE), false)
        set(value) = prefs.edit { putBoolean(key(KEY_COMMON_STALE), value) }

    /**
     * Why the last read or write of this vault's config file failed, or null
     * if it succeeded. Kept so the status card can say why, not only that.
     */
    var lastSyncError: String?
        get() = prefs.getString(key(KEY_SYNC_ERROR), null)
        set(value) = prefs.edit { if (value == null) remove(key(KEY_SYNC_ERROR)) else putString(key(KEY_SYNC_ERROR), value) }

    // ---- layout --------------------------------------------------------------

    /** Vault-relative root for everything this gesture writes. May have several segments. */
    var baseDir: String
        get() = prefs.getString(key(KEY_BASE_DIR), null) ?: LayoutBlock().baseDir
        set(value) = prefs.edit { putString(key(KEY_BASE_DIR), value.trim('/')) }

    /**
     * False until the base folder has been chosen deliberately. The default is a
     * guess at a name in someone else's vault, so it is offered on first setup
     * rather than silently adopted.
     */
    var baseDirConfirmed: Boolean
        get() = prefs.getBoolean(key(KEY_BASE_CONFIRMED), false)
        set(value) = prefs.edit { putBoolean(key(KEY_BASE_CONFIRMED), value) }

    var taskDirName: String
        get() = prefs.getString(key(KEY_TASK_DIR), null) ?: LayoutBlock().taskDir
        set(value) = prefs.edit { putString(key(KEY_TASK_DIR), value.trim('/')) }

    var fallbackDirName: String
        get() = prefs.getString(key(KEY_FALLBACK_DIR), null) ?: LayoutBlock().fallbackDir
        set(value) = prefs.edit { putString(key(KEY_FALLBACK_DIR), value.trim('/')) }

    var audioDirName: String
        get() = prefs.getString(key(KEY_AUDIO_DIR), null) ?: LayoutBlock().audioDir
        set(value) = prefs.edit { putString(key(KEY_AUDIO_DIR), value.trim('/')) }

    var taskTags: List<String>
        get() = Tags.canonical(
            prefs.getString(key(KEY_TASK_TAGS), null)?.split(',')?.filter { it.isNotBlank() }
                ?: LayoutBlock().taskTags,
        )
        set(value) = prefs.edit { putString(key(KEY_TASK_TAGS), Tags.canonical(value).joinToString(",")) }

    var taskCreatedDate: Boolean
        get() = prefs.getBoolean(key(KEY_TASK_CREATED), LayoutBlock().taskCreatedDate)
        set(value) = prefs.edit { putBoolean(key(KEY_TASK_CREATED), value) }

    var mocBackupRetention: Int
        get() = prefs.getInt(key(KEY_MOC_RETENTION), LayoutBlock().mocBackupRetention)
        set(value) = prefs.edit { putInt(key(KEY_MOC_RETENTION), value.coerceAtLeast(1)) }

    var dateFiling: DateFiling.Depth
        get() = DateFiling.Depth.fromWire(prefs.getString(key(KEY_DATE_FILING), null))
        set(value) = prefs.edit { putString(key(KEY_DATE_FILING), value.wireName) }

    // ---- resolved paths --------------------------------------------------------

    fun taskPath(): String = under(taskDirName)
    fun fallbackPath(): String = under(fallbackDirName)
    fun audioPath(): String = under(audioDirName)

    /** The dated path a recording or unmatched note is filed into. */
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

    /** Every folder the app itself needs in this vault, regardless of what rules exist. */
    fun reservedPaths(): List<String> = listOf(baseDir.trim('/'), taskPath(), fallbackPath(), audioPath())

    fun toLayout() = LayoutBlock(
        baseDir = baseDir,
        taskDir = taskDirName,
        fallbackDir = fallbackDirName,
        audioDir = audioDirName,
        taskTags = taskTags,
        taskCreatedDate = taskCreatedDate,
        mocBackupRetention = mocBackupRetention,
        dateFiling = dateFiling.wireName,
    )

    fun apply(layout: LayoutBlock) {
        baseDir = layout.baseDir
        taskDirName = layout.taskDir
        fallbackDirName = layout.fallbackDir
        audioDirName = layout.audioDir
        taskTags = layout.taskTags
        taskCreatedDate = layout.taskCreatedDate
        mocBackupRetention = layout.mocBackupRetention
        dateFiling = DateFiling.Depth.fromWire(layout.dateFiling)
    }

    /**
     * Replaces reserved folder names that are still the pre-v0.5.0 defaults.
     *
     * `tasks`, `inbox` and `Audio` were defaults, not decisions, and a config
     * file written back then still carries them. A name that differs from the
     * old default was chosen deliberately and is left alone.
     */
    fun upgradeReservedFolderNames(): List<Pair<String, String>> {
        if (prefs.getBoolean(key(KEY_RESERVED_UPGRADED), false)) return emptyList()
        val renames = mutableListOf<Pair<String, String>>()
        if (taskDirName == LEGACY_TASK_DIR) {
            taskDirName = LayoutBlock().taskDir
            renames += LEGACY_TASK_DIR to taskDirName
        }
        if (fallbackDirName == LEGACY_FALLBACK_DIR) {
            fallbackDirName = LayoutBlock().fallbackDir
            renames += LEGACY_FALLBACK_DIR to fallbackDirName
        }
        if (audioDirName == LEGACY_AUDIO_DIR) {
            audioDirName = LayoutBlock().audioDir
            renames += LEGACY_AUDIO_DIR to audioDirName
        }
        prefs.edit { putBoolean(key(KEY_RESERVED_UPGRADED), true) }
        return renames
    }

    internal companion object {
        const val CONFIG_FILE = "indexrouter-config.md"

        const val KEY_STORAGE_MODE = "storage_mode"
        const val KEY_VAULT_ROOT = "vault_root_path"
        const val KEY_TREE_URI = "vault_tree_uri"
        const val KEY_LAST_SYNCED = "last_synced_at"
        const val KEY_FALLBACK = "using_fallback"
        const val KEY_COMMON_STALE = "common_stale"
        const val KEY_SYNC_ERROR = "last_sync_error"
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

        /** Keys that were global when the app had one vault, moved under a gesture by the migration. */
        val LEGACY_KEYS = listOf(
            KEY_STORAGE_MODE, KEY_VAULT_ROOT, KEY_TREE_URI, KEY_LAST_SYNCED, KEY_FALLBACK,
            KEY_BASE_DIR, KEY_BASE_CONFIRMED, KEY_TASK_DIR, KEY_FALLBACK_DIR, KEY_AUDIO_DIR,
            KEY_TASK_TAGS, KEY_TASK_CREATED, KEY_MOC_RETENTION, KEY_DATE_FILING, KEY_RESERVED_UPGRADED,
        )

        const val LEGACY_TASK_DIR = "tasks"
        const val LEGACY_FALLBACK_DIR = "inbox"
        const val LEGACY_AUDIO_DIR = "Audio"
    }
}

/**
 * Copies every setting of [from] to [to], then clears [from]. Used once, when
 * the carried-over vault is given to a gesture.
 */
internal fun SharedPreferences.moveProfile(from: RuleGroup, to: RuleGroup) {
    val all = this.all
    edit {
        VaultProfile.LEGACY_KEYS.forEach { key ->
            val source = "${from.wireName}.$key"
            val target = "${to.wireName}.$key"
            when (val value = all[source]) {
                is String -> putString(target, value)
                is Boolean -> putBoolean(target, value)
                is Int -> putInt(target, value)
                is Long -> putLong(target, value)
                else -> remove(target)
            }
            remove(source)
        }
    }
}
