package org.snrkl.indexrouter.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.Timing
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.action.DateFiling
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.db.LogRetention
import org.snrkl.indexrouter.data.prefs.StorageMode
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.common.VaultFolders
import org.snrkl.indexrouter.vault.GrantCheck
import org.snrkl.indexrouter.vault.VaultAccess
import org.snrkl.indexrouter.voice.VoiceImeStatus

internal data class SettingsUiState(
    /** The gesture whose vault the vault and layout cards are editing. */
    val group: RuleGroup = RuleGroup.DOUBLE_CLICK_HOLD,
    val groupNames: Map<RuleGroup, String> = emptyMap(),
    val vaultAssigned: Boolean = false,
    /** The vault this app used before there were two, if it is still waiting for a gesture. */
    val carriedOverPath: String? = null,
    val vaultRoot: String? = null,
    val storageMode: StorageMode = StorageMode.FILES,
    val hasAllFilesAccess: Boolean = false,
    val baseDir: String = "",
    val taskDir: String = "",
    val fallbackDir: String = "",
    val audioDir: String = "",
    val taskTags: String = "",
    val taskCreatedDate: Boolean = true,
    val dateFiling: DateFiling.Depth = DateFiling.Depth.DAY,
    val serverPort: Int = 0,
    val mocRetention: Int = 5,
    val routeNotify: Map<ActionType, Boolean> = emptyMap(),
    val notifyFallback: Boolean = true,
    val debugStream: Boolean = false,
    val voiceBufferSize: Int = 10,
    val voiceCutoffMinutes: Int = 10,
    val voiceStatus: VoiceImeStatus.Status? = null,
    val calendarMode: CalendarMode = CalendarMode.DEFAULT,
    val calendarSharePackage: String = "",
    /** Whether the named share target is actually installed. */
    val calendarShareInstalled: Boolean = false,
    val logRetentionHours: Int = LogRetention.DEFAULT_HOURS,
    val detailedContentLogging: Boolean = false,
    val requirements: List<VaultAccess.Requirement> = emptyList(),
)

/** A grant that would put the vault inside itself, and how to recover from it. */
internal data class NestedGrant(val folderName: String, val baseDir: String)

internal class SettingsViewModel(private val app: Application) : ViewModel() {

    private val container get() = (app as IndexRouterApp).container
    private val settings get() = container.settings

    private val _group = MutableStateFlow(
        // Start on whichever gesture still needs a vault: that is why someone
        // opens this screen after an upgrade.
        RuleGroup.TRIGGERS.firstOrNull { !container.settings.profile(it).isAssigned }
            ?: RuleGroup.DOUBLE_CLICK_HOLD,
    )

    /** The selected gesture's vault. Every vault setting on this screen means this one. */
    private val vault get() = settings.profile(_group.value)

    fun selectGroup(group: RuleGroup) {
        if (!group.isTrigger) return
        _group.value = group
        refresh(_state.value.hasAllFilesAccess)
    }

    val commonConflict get() = container.config.commonConflict

    fun resolveCommonConflict(useVault: Boolean) {
        viewModelScope.launch {
            container.config.resolveCommonConflict(useVault)
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _nestedGrant = MutableStateFlow<NestedGrant?>(null)
    val nestedGrant: StateFlow<NestedGrant?> = _nestedGrant.asStateFlow()

    fun messageShown() {
        _messages.value = null
    }

    fun nestedGrantDismissed() {
        _nestedGrant.value = null
    }

    /**
     * Re-reads the settings, and optionally re-probes vault access.
     *
     * [probeFolders] is separate because probing is the expensive half —
     * measured at 220ms, since proving a folder is writable means writing and
     * deleting a file in each one — and almost nothing that changes on this
     * screen changes the answer. Probing after a notification toggle was
     * charging 220ms for a question nobody asked.
     */
    fun refresh(hasAllFilesAccess: Boolean, probeFolders: Boolean = true) {
        viewModelScope.launch {
            val timing = Timing.Run("settingsRefresh")
            val rules = timing.stage("rules") { container.config.rules() }
            val vault = vault
            val cache = container.vaultAccess(vault.group)
            _state.value = SettingsUiState(
                group = vault.group,
                groupNames = RuleGroup.entries.associateWith { settings.groupName(it) },
                vaultAssigned = vault.isAssigned,
                carriedOverPath = settings.carriedOver?.let { it.vaultRootPath ?: it.vaultTreeUri?.toString() },
                vaultRoot = vault.vaultRootPath,
                storageMode = vault.storageMode,
                hasAllFilesAccess = hasAllFilesAccess,
                baseDir = vault.baseDir,
                taskDir = vault.taskDirName,
                fallbackDir = vault.fallbackDirName,
                audioDir = vault.audioDirName,
                taskTags = vault.taskTags.joinToString(", "),
                taskCreatedDate = vault.taskCreatedDate,
                dateFiling = vault.dateFiling,
                serverPort = settings.serverPort,
                mocRetention = vault.mocBackupRetention,
                routeNotify = ActionType.entries.associateWith { settings.routeNotify(it) },
                notifyFallback = settings.notifyFallback,
                debugStream = settings.debugStream,
                voiceBufferSize = settings.voiceBufferSize,
                voiceCutoffMinutes = settings.voiceCutoffMinutes,
                // Asked of the system rather than assumed: the mic key has
                // three separate ways to be unreachable and they all look the
                // same from outside — the key simply does nothing.
                voiceStatus = VoiceImeStatus.check(app),
                calendarMode = settings.calendarMode,
                calendarSharePackage = settings.calendarSharePackage,
                // Asked of the system: a named app that is not installed means
                // the route quietly falls back, and that is worth showing
                // rather than leaving to be discovered.
                calendarShareInstalled = isShareTargetInstalled(settings.calendarSharePackage),
                logRetentionHours = settings.logRetentionHours,
                detailedContentLogging = settings.detailedContentLogging,
                // Probed for real, every time: this is the screen someone opens
                // precisely because they suspect access is wrong, so showing the
                // cached answer would be showing them the thing they doubt.
                requirements = timing.stage("probe") {
                    if (!vault.isAssigned) {
                        emptyList()
                    } else if (probeFolders) {
                        cache.refresh(
                            VaultFolders.core(app, vault),
                            VaultFolders.forRules(app, vault, rules),
                        )
                    } else {
                        // Whatever the last probe found. Not stale in any way
                        // that matters: nothing that skips the probe can change
                        // which folders are reachable.
                        cache.peek(
                            VaultFolders.core(app, vault),
                            VaultFolders.forRules(app, vault, rules),
                        ) ?: _state.value.requirements.takeIf { _state.value.group == vault.group }.orEmpty()
                    }
                },
            )
            timing.finish()
        }
    }

    // ---- notifications -----------------------------------------------------

    fun setRouteNotify(action: ActionType, enabled: Boolean) {
        settings.setRouteNotify(action, enabled)
        _state.update { it.copy(routeNotify = it.routeNotify + (action to enabled)) }
    }

    fun setNotifyFallback(enabled: Boolean) {
        settings.notifyFallback = enabled
        _state.update { it.copy(notifyFallback = enabled) }
    }

    fun setDebugStream(enabled: Boolean) {
        settings.debugStream = enabled
        _state.update { it.copy(debugStream = enabled) }
    }

    fun setLogRetentionHours(hours: Int) {
        settings.logRetentionHours = hours
        _state.update { it.copy(logRetentionHours = settings.logRetentionHours) }
    }

    fun setDetailedContentLogging(enabled: Boolean) {
        settings.detailedContentLogging = enabled
        _state.update { it.copy(detailedContentLogging = enabled) }
    }

    fun setCalendarMode(mode: CalendarMode) {
        settings.calendarMode = mode
        _state.update { it.copy(calendarMode = mode) }
    }

    fun setCalendarSharePackage(pkg: String) {
        settings.calendarSharePackage = pkg
        _state.update {
            it.copy(
                calendarSharePackage = settings.calendarSharePackage,
                calendarShareInstalled = isShareTargetInstalled(settings.calendarSharePackage),
            )
        }
    }

    private fun isShareTargetInstalled(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
        }
        return intent.resolveActivity(app.packageManager) != null
    }

    fun setVoiceCutoffMinutes(minutes: Int) {
        settings.voiceCutoffMinutes = minutes
        _state.update { it.copy(voiceCutoffMinutes = settings.voiceCutoffMinutes) }
    }

    fun setVoiceBufferSize(size: Int) {
        settings.voiceBufferSize = size
        _state.update { it.copy(voiceBufferSize = settings.voiceBufferSize) }
    }

    // ---- folders and behaviour ---------------------------------------------

    fun setTaskTags(raw: String) {
        vault.taskTags = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        _state.update { it.copy(taskTags = vault.taskTags.joinToString(", ")) }
        mirrorToVault()
    }

    fun setTaskCreatedDate(enabled: Boolean) {
        vault.taskCreatedDate = enabled
        _state.update { it.copy(taskCreatedDate = enabled) }
        mirrorToVault()
    }

    fun setDateFiling(depth: DateFiling.Depth) {
        vault.dateFiling = depth
        _state.update { it.copy(dateFiling = depth) }
        mirrorToVault()
    }

    fun setServerPort(port: Int) {
        settings.serverPort = port
        _state.update { it.copy(serverPort = settings.serverPort) }
        _messages.value = app.getString(R.string.settings_port_restart)
    }

    fun setMocRetention(count: Int) {
        vault.mocBackupRetention = count
        _state.update { it.copy(mocRetention = vault.mocBackupRetention) }
        mirrorToVault()
    }

    fun setReservedFolder(which: ReservedFolder, name: String) {
        val clean = name.trim().trim('/').substringAfterLast('/')
        if (clean.isBlank()) return
        when (which) {
            ReservedFolder.TASK -> vault.taskDirName = clean
            ReservedFolder.FALLBACK -> vault.fallbackDirName = clean
            ReservedFolder.AUDIO -> vault.audioDirName = clean
        }
        // This one genuinely changes which folders have to be reachable, so it
        // is the case that does pay for a re-probe.
        val group = vault.group
        container.vaultAccess(group).invalidate()
        viewModelScope.launch {
            container.config.writeThroughToVault(group)
            refresh(_state.value.hasAllFilesAccess, probeFolders = true)
        }
    }

    /**
     * Mirrors the settings to the vault file, so a reinstall restores them.
     *
     * Fire-and-forget on purpose. The screen has already shown the new value —
     * it set it — so reading it back from disk afterwards only added a delay
     * before the switch appeared to move. A failure here still surfaces: the
     * status screen reports the config as unsynced.
     */
    private fun mirrorToVault() {
        val group = vault.group
        if (!vault.isAssigned) return
        viewModelScope.launch {
            val timing = Timing.Run("settingsMirror")
            timing.stage("writeThrough") { container.config.writeThroughToVault(group) }
            timing.finish()
        }
    }

    // ---- vault grant --------------------------------------------------------

    fun applyVaultGrant(path: String?, treeUriString: String?, onNeedsBaseFolder: () -> Unit) {
        val vault = vault
        viewModelScope.launch {
            vault.storageMode = if (path != null) StorageMode.FILES else StorageMode.SAF
            if (path != null) vault.vaultRootPath = path
            treeUriString?.let { vault.vaultTreeUri = android.net.Uri.parse(it) }
            container.vaultAccess(vault.group).invalidate()

            // Checked BEFORE anything is written: a grant one folder too deep
            // does not fail, it quietly creates folders in the wrong place.
            if (!checkGrantLevel(vault)) return@launch

            if (!vault.baseDirConfirmed) {
                onNeedsBaseFolder()
                return@launch
            }
            assigned(vault)
        }
    }

    fun setBaseDir(relative: String) {
        val vault = vault
        val clean = relative.trim('/')
        if (clashesWithOtherGesture(vault, clean)) {
            _messages.value = app.getString(
                R.string.base_folder_shared,
                settings.groupName(vault.group.other!!),
            )
            return
        }
        vault.baseDir = clean
        vault.baseDirConfirmed = true
        viewModelScope.launch {
            _messages.value = app.getString(R.string.base_folder_set, clean)
            assigned(vault)
        }
    }

    /**
     * A gesture's vault is complete — folder granted, base folder chosen.
     * Its own config file restores its rules if it has one; otherwise one is
     * written. Either way the gesture can now file recordings.
     */
    private suspend fun assigned(vault: org.snrkl.indexrouter.data.prefs.VaultProfile) {
        container.vaultAccess(vault.group).invalidate()
        val result = container.config.onVaultAssigned(vault.group)
        if (result !is ConfigRepository.SyncResult.Loaded) {
            _messages.value = app.getString(R.string.toast_sync_failed, syncReason(result))
        }
        refresh(_state.value.hasAllFilesAccess)
    }

    /**
     * Two gestures may share a vault, but never a base folder: each base folder
     * holds its gesture's config file, and two gestures writing one file would
     * each overwrite the other's rules.
     */
    private fun clashesWithOtherGesture(vault: org.snrkl.indexrouter.data.prefs.VaultProfile, baseDir: String): Boolean {
        val other = settings.profile(vault.group.other ?: return false)
        if (!other.isAssigned || !other.baseDirConfirmed) return false
        val sameRoot = when (vault.storageMode) {
            StorageMode.FILES -> other.storageMode == StorageMode.FILES && other.vaultRootPath == vault.vaultRootPath
            StorageMode.SAF -> other.storageMode == StorageMode.SAF && other.vaultTreeUri == vault.vaultTreeUri
        }
        return sameRoot && other.baseDir.trim('/').equals(baseDir, ignoreCase = true)
    }

    /**
     * The granted folder becomes the base: tasks, inbox and audio nest directly
     * inside it. One setting now does what used to need each path rewritten.
     */
    fun adoptGrantedFolderAsBase(folderName: String) {
        val vault = vault
        vault.baseDir = ""
        vault.baseDirConfirmed = true
        viewModelScope.launch {
            _messages.value = app.getString(R.string.grant_paths_adjusted, folderName)
            _nestedGrant.value = null
            assigned(vault)
        }
    }

    /** Gives the carried-over vault to the selected gesture. */
    fun useCarriedOver() {
        val group = vault.group
        viewModelScope.launch {
            val result = container.config.assignCarriedOver(group)
            if (result !is ConfigRepository.SyncResult.Loaded) {
                _messages.value = app.getString(R.string.toast_sync_failed, syncReason(result))
            }
            container.vaultAccess(group).invalidate()
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    /** Forgets the selected gesture's vault. Its recordings then fail visibly. */
    fun unassignVault() {
        val vault = vault
        vault.unassign()
        container.vaultAccess(vault.group).invalidate()
        refresh(_state.value.hasAllFilesAccess)
    }

    private suspend fun checkGrantLevel(vault: org.snrkl.indexrouter.data.prefs.VaultProfile): Boolean {
        val store = container.vaultStore(vault.group) ?: return false
        val diagnosis = withContext(Dispatchers.IO) {
            GrantCheck.diagnose(
                grantedFolderName = store.rootName(),
                configuredPaths = listOf(vault.baseDir),
            )
        }
        return when (diagnosis) {
            is GrantCheck.Result.Ok -> true
            is GrantCheck.Result.NestedInsideItself -> {
                _nestedGrant.value = NestedGrant(diagnosis.folderName, vault.baseDir)
                false
            }
        }
    }

    private fun syncReason(result: ConfigRepository.SyncResult): String = when (result) {
        is ConfigRepository.SyncResult.Loaded -> ""
        is ConfigRepository.SyncResult.Fallback -> result.reason
        is ConfigRepository.SyncResult.Failed -> result.reason
    }

    // ---- config -------------------------------------------------------------

    fun reload() {
        viewModelScope.launch {
            _messages.value = when (val result = container.config.reloadFromVault(vault.group)) {
                is ConfigRepository.SyncResult.Loaded ->
                    app.getString(R.string.toast_reloaded, result.ruleCount)
                is ConfigRepository.SyncResult.Fallback ->
                    app.getString(R.string.toast_sync_failed, result.reason)
                is ConfigRepository.SyncResult.Failed ->
                    app.getString(R.string.toast_sync_failed, result.reason)
            }
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    fun export() {
        viewModelScope.launch {
            _messages.value = when (val result = container.config.writeThroughToVault(vault.group)) {
                is ConfigRepository.SyncResult.Loaded -> app.getString(R.string.toast_exported)
                is ConfigRepository.SyncResult.Fallback ->
                    app.getString(R.string.toast_sync_failed, result.reason)
                is ConfigRepository.SyncResult.Failed ->
                    app.getString(R.string.toast_sync_failed, result.reason)
            }
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            container.config.resetToDefaults()
            _messages.value = app.getString(R.string.reset_done)
            refresh(_state.value.hasAllFilesAccess)
        }
    }
}

internal enum class ReservedFolder { TASK, FALLBACK, AUDIO }
