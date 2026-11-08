package org.snrkl.indexrouter.data.prefs

import android.content.Context
import androidx.core.content.edit
import org.snrkl.indexrouter.data.config.NotifyBlock
import org.snrkl.indexrouter.data.config.CalendarBlock
import org.snrkl.indexrouter.data.config.PrivacyBlock
import org.snrkl.indexrouter.data.db.LogRetention
import org.snrkl.indexrouter.data.config.SettingsBlock
import org.snrkl.indexrouter.data.config.VoiceBlock
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.routing.RuleGroup

enum class StorageMode { FILES, SAF }

/**
 * Settings that belong to the device, not to either vault: port, privacy,
 * notifications, voice input, calendar hand-off, and the group names and
 * colours.
 *
 * None of these is written to a vault file. Two vaults could each hold a copy,
 * and two copies can disagree; and the group names describe both vaults, which
 * neither vault's file may do. Each gesture's vault, and how it is filed, is a
 * [VaultProfile] — see [profile].
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * One vault per ring gesture. Cached: each is a thin view over the same
     * preferences file, keyed by the gesture's wire name.
     */
    private val profiles: Map<RuleGroup, VaultProfile> =
        RuleGroup.entries.filter { it.hasVault }.associateWith { VaultProfile(prefs, it) }

    fun profile(group: RuleGroup): VaultProfile =
        profiles[group] ?: throw IllegalArgumentException("${group.wireName} has no vault")

    /** Both gestures' vaults, in the order they are drawn. */
    val vaultProfiles: List<VaultProfile> get() = RuleGroup.TRIGGERS.map { profile(it) }

    /** The carried-over vault, waiting to be given to a gesture. Null once it has been, or if there was none. */
    val carriedOver: VaultProfile?
        get() = profile(RuleGroup.CARRIED_OVER).takeIf { it.isAssigned }

    /**
     * Carries an older install's single vault over as a profile of its own,
     * once. The vault settings used to be global keys; they are copied under
     * the carried-over prefix and the old keys removed. No gesture is given
     * the vault — that is the user's decision, not an inference.
     *
     * @return true when there was an old vault to carry over.
     */
    fun migrateLegacyVault(): Boolean {
        val group = RuleGroup.CARRIED_OVER
        if (prefs.getBoolean(KEY_PROFILES_MIGRATED, false)) return false
        val all = prefs.all
        val hadVault = VaultProfile.LEGACY_KEYS.any { it in all }
        prefs.edit {
            if (hadVault) {
                VaultProfile.LEGACY_KEYS.forEach { legacy ->
                    when (val value = all[legacy]) {
                        is String -> putString("${group.wireName}.$legacy", value)
                        is Boolean -> putBoolean("${group.wireName}.$legacy", value)
                        is Int -> putInt("${group.wireName}.$legacy", value)
                        is Long -> putLong("${group.wireName}.$legacy", value)
                        else -> Unit
                    }
                    remove(legacy)
                }
            }
            putBoolean(KEY_PROFILES_MIGRATED, true)
        }
        return hadVault
    }

    /** Gives the carried-over vault, settings and all, to [gesture]. */
    fun moveCarriedOverTo(gesture: RuleGroup) {
        require(gesture.isTrigger)
        prefs.moveProfile(RuleGroup.CARRIED_OVER, gesture)
    }

    var receiverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECEIVER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_RECEIVER_ENABLED, value) }

    // ---- groups ----------------------------------------------------------------

    /** The name a person reads. The wire id never changes; this does. */
    fun groupName(group: RuleGroup): String =
        prefs.getString("$KEY_GROUP_NAME${group.wireName}", null)?.takeIf { it.isNotBlank() }
            ?: group.defaultName

    fun setGroupName(group: RuleGroup, name: String) = prefs.edit {
        val clean = name.trim().take(MAX_GROUP_NAME)
        if (clean.isEmpty()) remove("$KEY_GROUP_NAME${group.wireName}") else putString("$KEY_GROUP_NAME${group.wireName}", clean)
    }

    /**
     * The colour seed a person picked, as ARGB. The colours shown are derived
     * from it per theme, never used raw. Null means the group's default.
     */
    fun groupSeed(group: RuleGroup): Int? =
        if (prefs.contains("$KEY_GROUP_SEED${group.wireName}")) prefs.getInt("$KEY_GROUP_SEED${group.wireName}", 0) else null

    fun setGroupSeed(group: RuleGroup, argb: Int?) = prefs.edit {
        if (argb == null) remove("$KEY_GROUP_SEED${group.wireName}") else putInt("$KEY_GROUP_SEED${group.wireName}", argb)
    }

    /**
     * Whether the device settings an older, single-vault config file carried
     * have been taken in. They are read from such a file once; after that the
     * app's own copy is the authority and no vault file carries them.
     */
    var deviceSettingsImported: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_IMPORTED, false)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_IMPORTED, value) }

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


    /**
     * Takes in the device settings an older config file carried, once. The
     * layout half of the same block goes to the vault it describes.
     */
    fun applyDevice(block: SettingsBlock) {
        serverPort = block.serverPort
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
        const val KEY_PROFILES_MIGRATED = "vault_profiles_migrated"
        const val KEY_DEVICE_IMPORTED = "device_settings_imported"
        const val KEY_GROUP_NAME = "group_name."
        const val KEY_GROUP_SEED = "group_seed."
        const val MAX_GROUP_NAME = 32
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
