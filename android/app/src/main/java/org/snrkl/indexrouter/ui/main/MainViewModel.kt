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
    val vaultDescription: String? = null,
    val baseDir: String = "",
    val blockedFolders: Int = 0,
    val hasSecret: Boolean = false,
    val ruleCount: Int = 0,
    val lastSyncedAt: Long = 0L,
    val usingFallbackConfig: Boolean = false,
    val canLaunchFromBackground: Boolean = true,
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
            val store = container.vaultStore()

            // Drawn from the last probe rather than probing now: each requirement
            // writes and deletes a file, which measured at ~450ms and queued
            // behind any recordings being processed. A fresh probe runs below and
            // updates the state only if the answer moved.
            val blocked = timing.stage("requirements") {
                container.vaultAccess.peek(
                    VaultFolders.core(app, settings),
                    VaultFolders.forRules(app, settings, rules),
                )?.count { !it.satisfied }
            }

            _state.value = MainUiState(
                receiverRunning = running,
                serverPort = settings.serverPort,
                vaultDescription = store?.takeIf { it.isReady }?.describe(),
                baseDir = settings.baseDir,
                blockedFolders = blocked ?: 0,
                hasSecret = container.secrets.hasSecret(),
                ruleCount = rules.size,
                lastSyncedAt = settings.lastSyncedAt,
                usingFallbackConfig = settings.usingFallbackConfig,
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
            val core = VaultFolders.core(app, settings)
            val ruleFolders = VaultFolders.forRules(app, settings, rules)
            val before = container.vaultAccess.peek(core, ruleFolders)?.count { !it.satisfied }
            val after = container.vaultAccess.refresh(core, ruleFolders).count { !it.satisfied }
            if (before != after) _state.update { it.copy(blockedFolders = after) }
        }
    }

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

    fun rebuildMoc(rebuildMaps: Boolean, refreshSamples: Boolean, retention: Int?) {
        retention?.let { container.settings.mocBackupRetention = it }
        viewModelScope.launch {
            val result: MocGenerator.Result =
                container.moc.run(rebuildMaps = rebuildMaps, refreshSamples = refreshSamples)
            _messages.value = result.failure
                ?.let { app.getString(org.snrkl.indexrouter.R.string.moc_failed, it) }
                ?: app.getString(
                    org.snrkl.indexrouter.R.string.moc_ran,
                    result.written.size,
                    result.prunedBackups,
                )
        }
    }

    val mocRetention: Int get() = container.settings.mocBackupRetention

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
