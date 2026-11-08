package org.snrkl.indexrouter.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.Timing
import org.snrkl.indexrouter.action.ActivityLauncher
import org.snrkl.indexrouter.data.db.LogKeeper
import org.snrkl.indexrouter.moc.MocGenerator
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.service.RouterForegroundService
import org.snrkl.indexrouter.ui.common.VaultFolders

/**
 * What the status screen shows.
 *
 * The app is not opened in normal use — a voice note goes from ring to vault
 * without anyone touching the phone — so this screen exists to answer "is it
 * actually working?" and to reach the thing that fixes it when it is not.
 */
internal data class MainUiState(
    val receiverRunning: Boolean = false,
    val serverPort: Int = 0,
    val vaults: List<VaultStatus> = emptyList(),
    val hasSecret: Boolean = false,
    val ruleCount: Int = 0,
    val canLaunchFromBackground: Boolean = true,
)

/** One gesture's vault, as the status card shows it. */
internal data class VaultStatus(
    val group: RuleGroup,
    val name: String,
    val assigned: Boolean,
    val description: String? = null,
    val baseDir: String = "",
    val blockedFolders: Int = 0,
    val lastSyncedAt: Long = 0L,
    val usingFallbackConfig: Boolean = false,
    val commonStale: Boolean = false,
)

internal class MainViewModel(private val app: Application) : ViewModel() {

    private val container get() = (app as IndexRouterApp).container

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun messageShown() {
        _messages.value = null
    }

    /** Reads the vault file, as the spec's "on app open" moment. A webhook never does. */
    fun initialise() {
        viewModelScope.launch {
            container.config.ensureInitialised()
            // The other moment the log matters: opening the app is when it is
            // read, and nothing should be sitting there past its keep-for time.
            runCatching { LogKeeper(container.database.log()) { container.settings.logRetentionHours }.prune() }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val timing = Timing.Run("render")
            val settings = container.settings
            val running = timing.stage("serviceState") { RouterForegroundService.isRunning(app) }
            val rules = timing.stage("rules") { container.config.rules() }

            // Drawn from the last probe rather than probing now: each requirement
            // writes and deletes a file, which measured at ~450ms. A fresh probe
            // runs below and updates the state only if the answer moved.
            val vaults = timing.stage("requirements") {
                settings.vaultProfiles.map { profile ->
                    val store = container.vaultStore(profile.group)
                    val blocked = container.vaultAccess(profile.group).peek(
                        VaultFolders.core(app, profile),
                        VaultFolders.forRules(app, profile, rules),
                    )?.count { !it.satisfied }
                    VaultStatus(
                        group = profile.group,
                        name = settings.groupName(profile.group),
                        assigned = profile.isAssigned,
                        description = store?.takeIf { profile.isAssigned && it.isReady }?.describe(),
                        baseDir = profile.baseDir,
                        blockedFolders = blocked ?: 0,
                        lastSyncedAt = profile.lastSyncedAt,
                        usingFallbackConfig = profile.usingFallbackConfig,
                        commonStale = profile.commonStale,
                    )
                }
            }

            _state.value = MainUiState(
                receiverRunning = running,
                serverPort = settings.serverPort,
                vaults = vaults,
                hasSecret = container.secrets.hasSecret(),
                ruleCount = rules.size,
                canLaunchFromBackground = ActivityLauncher.canLaunchFromBackground(app),
            )
            timing.finish()
            reprobeVaultAccess()
        }
    }

    /** Re-probes off the render path, so a revoked scope still surfaces. */
    private fun reprobeVaultAccess() {
        viewModelScope.launch {
            val settings = container.settings
            val rules = container.config.rules()
            settings.vaultProfiles.filter { it.isAssigned }.forEach { profile ->
                val core = VaultFolders.core(app, profile)
                val ruleFolders = VaultFolders.forRules(app, profile, rules)
                val cache = container.vaultAccess(profile.group)
                val before = cache.peek(core, ruleFolders)?.count { !it.satisfied }
                val after = cache.refresh(core, ruleFolders).count { !it.satisfied }
                if (before != after) {
                    _state.update { state ->
                        state.copy(
                            vaults = state.vaults.map {
                                if (it.group == profile.group) it.copy(blockedFolders = after) else it
                            },
                        )
                    }
                }
            }
        }
    }

    /** The user's answer when a vault's common rules differ from the app's. */
    fun resolveCommonConflict(useVault: Boolean) {
        viewModelScope.launch {
            container.config.resolveCommonConflict(useVault)
            refresh()
        }
    }

    val commonConflict get() = container.config.commonConflict

    fun groupName(group: RuleGroup): String = container.settings.groupName(group)

    fun toggleReceiver() {
        val settings = container.settings
        if (_state.value.receiverRunning) {
            settings.receiverEnabled = false
            RouterForegroundService.stop(app)
        } else {
            settings.receiverEnabled = true
            RouterForegroundService.start(app)
        }
        // The service takes a moment to come up or go down; re-read rather than
        // assuming the toggle succeeded.
        viewModelScope.launch { refresh() }
    }

    /**
     * Self-heal: the receiver may have been killed while the app was closed, and
     * the app being open is the one moment it can be put back. An explicit Stop
     * is respected.
     */
    fun restartReceiverIfExpected() {
        if (container.settings.receiverEnabled && !RouterForegroundService.isRunning(app)) {
            RouterForegroundService.start(app)
        }
    }

    /** Rebuilds the maps in every vault that has one assigned, each in its own vault. */
    fun rebuildMoc(rebuildMaps: Boolean, refreshSamples: Boolean, retention: Int?) {
        viewModelScope.launch {
            val profiles = container.settings.vaultProfiles.filter { it.isAssigned }
            var written = 0
            var pruned = 0
            var failure: String? = null
            profiles.forEach { profile ->
                retention?.let { profile.mocBackupRetention = it }
                val result: MocGenerator.Result = container.moc.run(
                    group = profile.group,
                    rebuildMaps = rebuildMaps,
                    refreshSamples = refreshSamples,
                )
                written += result.written.size
                pruned += result.prunedBackups
                result.failure?.let { failure = "${container.settings.groupName(profile.group)}: $it" }
            }
            if (retention != null) profiles.forEach { container.config.writeThroughToVault(it.group) }
            _messages.value = failure
                ?.let { app.getString(org.snrkl.indexrouter.R.string.moc_failed, it) }
                ?: app.getString(org.snrkl.indexrouter.R.string.moc_ran, written, pruned)
        }
    }

    val mocRetention: Int
        get() = container.settings.vaultProfiles.firstOrNull { it.isAssigned }?.mocBackupRetention
            ?: org.snrkl.indexrouter.data.config.LayoutBlock().mocBackupRetention

    /**
     * Both values the Pebble app needs. The secret is generated on first read
     * rather than as a separate step that can be forgotten — opening this is the
     * moment it is needed.
     */
    fun pebbleSetup(): Pair<String, String> {
        val port = container.settings.serverPort
        val url = "http://127.0.0.1:$port/webhook"
        return url to (container.secrets.read() ?: container.secrets.generate())
    }

    fun regenerateSecret() {
        container.secrets.generate()
        refresh()
    }
}
