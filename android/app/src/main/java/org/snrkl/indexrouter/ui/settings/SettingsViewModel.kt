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
import org.snrkl.indexrouter.ui.common.VaultFolders
import org.snrkl.indexrouter.vault.GrantCheck
import org.snrkl.indexrouter.vault.VaultAccess
import org.snrkl.indexrouter.voice.VoiceImeStatus

internal data class SettingsUiState(
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
            _state.value = SettingsUiState(
                vaultRoot = settings.vaultRootPath,
                storageMode = settings.storageMode,
                hasAllFilesAccess = hasAllFilesAccess,
                baseDir = settings.baseDir,
                taskDir = settings.taskDirName,
                fallbackDir = settings.fallbackDirName,
                audioDir = settings.audioDirName,
                taskTags = settings.taskTags.joinToString(", "),
                taskCreatedDate = settings.taskCreatedDate,
                dateFiling = settings.dateFiling,
                serverPort = settings.serverPort,
                mocRetention = settings.mocBackupRetention,
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
                    if (probeFolders) {
                        container.vaultAccess.refresh(
                            VaultFolders.core(app, settings),
                            VaultFolders.forRules(app, settings, rules),
                        )
                    } else {
                        // Whatever the last probe found. Not stale in any way
                        // that matters: nothing that skips the probe can change
                        // which folders are reachable.
                        container.vaultAccess.peek(
                            VaultFolders.core(app, settings),
                            VaultFolders.forRules(app, settings, rules),
                        ) ?: _state.value.requirements
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
        mirrorToVault()
    }

    fun setNotifyFallback(enabled: Boolean) {
        settings.notifyFallback = enabled
        _state.update { it.copy(notifyFallback = enabled) }
        mirrorToVault()
    }

    fun setDebugStream(enabled: Boolean) {
        settings.debugStream = enabled
        _state.update { it.copy(debugStream = enabled) }
        mirrorToVault()
    }

    fun setLogRetentionHours(hours: Int) {
        settings.logRetentionHours = hours
        _state.update { it.copy(logRetentionHours = settings.logRetentionHours) }
        mirrorToVault()
    }

    fun setDetailedContentLogging(enabled: Boolean) {
        settings.detailedContentLogging = enabled
        _state.update { it.copy(detailedContentLogging = enabled) }
        mirrorToVault()
    }

    fun setCalendarMode(mode: CalendarMode) {
        settings.calendarMode = mode
        _state.update { it.copy(calendarMode = mode) }
        mirrorToVault()
    }

    fun setCalendarSharePackage(pkg: String) {
        settings.calendarSharePackage = pkg
        _state.update {
            it.copy(
                calendarSharePackage = settings.calendarSharePackage,
                calendarShareInstalled = isShareTargetInstalled(settings.calendarSharePackage),
            )
        }
        mirrorToVault()
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
        mirrorToVault()
    }

    fun setVoiceBufferSize(size: Int) {
        settings.voiceBufferSize = size
        _state.update { it.copy(voiceBufferSize = settings.voiceBufferSize) }
        mirrorToVault()
    }

    // ---- folders and behaviour ---------------------------------------------

    fun setTaskTags(raw: String) {
        settings.taskTags = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        _state.update { it.copy(taskTags = settings.taskTags.joinToString(", ")) }
        mirrorToVault()
    }

    fun setTaskCreatedDate(enabled: Boolean) {
        settings.taskCreatedDate = enabled
        _state.update { it.copy(taskCreatedDate = enabled) }
        mirrorToVault()
    }

    fun setDateFiling(depth: DateFiling.Depth) {
        settings.dateFiling = depth
        _state.update { it.copy(dateFiling = depth) }
        mirrorToVault()
    }

    fun setServerPort(port: Int) {
        settings.serverPort = port
        _state.update { it.copy(serverPort = settings.serverPort) }
        mirrorToVault()
        _messages.value = app.getString(R.string.settings_port_restart)
    }

    fun setMocRetention(count: Int) {
        settings.mocBackupRetention = count
        _state.update { it.copy(mocRetention = settings.mocBackupRetention) }
        mirrorToVault()
    }

    fun setReservedFolder(which: ReservedFolder, name: String) {
        val clean = name.trim().trim('/').substringAfterLast('/')
        if (clean.isBlank()) return
        when (which) {
            ReservedFolder.TASK -> settings.taskDirName = clean
            ReservedFolder.FALLBACK -> settings.fallbackDirName = clean
            ReservedFolder.AUDIO -> settings.audioDirName = clean
        }
        // This one genuinely changes which folders have to be reachable, so it
        // is the case that does pay for a re-probe.
        container.vaultAccess.invalidate()
        viewModelScope.launch {
            container.config.writeThroughToVault()
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
        viewModelScope.launch {
            val timing = Timing.Run("settingsMirror")
            timing.stage("writeThrough") { container.config.writeThroughToVault() }
            timing.finish()
        }
    }

    // ---- vault grant --------------------------------------------------------

    fun applyVaultGrant(path: String?, treeUriString: String?, onNeedsBaseFolder: () -> Unit) {
        viewModelScope.launch {
            settings.storageMode = if (path != null) StorageMode.FILES else StorageMode.SAF
            if (path != null) settings.vaultRootPath = path
            treeUriString?.let { settings.vaultTreeUri = android.net.Uri.parse(it) }
            container.vaultAccess.invalidate()

            // Checked BEFORE anything is written: a grant one folder too deep
            // does not fail, it quietly creates folders in the wrong place.
            if (!checkGrantLevel()) return@launch

            if (!settings.baseDirConfirmed) {
                onNeedsBaseFolder()
                return@launch
            }
            reload()
        }
    }

    fun setBaseDir(relative: String) {
        settings.baseDir = relative
        settings.baseDirConfirmed = true
        viewModelScope.launch {
            container.config.writeThroughToVault()
            _messages.value = app.getString(R.string.base_folder_set, relative)
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    /**
     * The granted folder becomes the base: tasks, inbox and audio nest directly
     * inside it. One setting now does what used to need each path rewritten.
     */
    fun adoptGrantedFolderAsBase(folderName: String) {
        settings.baseDir = ""
        viewModelScope.launch {
            container.config.writeThroughToVault()
            _messages.value = app.getString(R.string.grant_paths_adjusted, folderName)
            _nestedGrant.value = null
            refresh(_state.value.hasAllFilesAccess)
        }
    }

    private suspend fun checkGrantLevel(): Boolean {
        val store = container.vaultStore() ?: return false
        val diagnosis = withContext(Dispatchers.IO) {
            GrantCheck.diagnose(
                grantedFolderName = store.rootName(),
                configuredPaths = listOf(settings.baseDir),
            )
        }
        return when (diagnosis) {
            is GrantCheck.Result.Ok -> true
            is GrantCheck.Result.NestedInsideItself -> {
                _nestedGrant.value = NestedGrant(diagnosis.folderName, settings.baseDir)
                false
            }
        }
    }

    // ---- config -------------------------------------------------------------

    fun reload() {
        viewModelScope.launch {
            _messages.value = when (val result = container.config.reloadFromVault()) {
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
            _messages.value = when (val result = container.config.writeThroughToVault()) {
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
