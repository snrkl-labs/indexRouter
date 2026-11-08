package org.snrkl.indexrouter.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.theme.GroupColor
import org.snrkl.indexrouter.ui.theme.rememberGroupColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.ui.common.CommonConflictDialog
import org.snrkl.indexrouter.ui.common.OnResume
import java.text.DateFormat
import java.util.Date

/**
 * Status and the handful of actions that are not settings.
 *
 * The status card answers "is it actually working?" in one read: receiver,
 * vault, base folder, blocked folders, secret, rule count, last sync, and
 * whether a timer can fire without a notification tap. Everything that
 * configures rather than reports has moved to the settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    viewModel: MainViewModel,
    onOpenRules: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onFixBackgroundLaunch: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val groupColors = rememberGroupColors(remember(state.vaults) { viewModel.groupSeeds() })

    var showMoc by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // Everything on the status card can be changed from outside this app —
    // "Display over other apps", a Storage Scope, the receiver being killed — so
    // coming back to the screen has to re-read it rather than show what was true
    // when the screen was first opened.
    OnResume { viewModel.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // After an upgrade, or a reinstall, neither gesture has a vault.
            // Said first, because until it is fixed nothing is filed.
            if (state.vaults.any { !it.assigned }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.first_run_assign_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.first_run_assign_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_triggers)) }
                    }
                }
            }

            StatusCard(state, groupColors, onSync = viewModel::syncNow)

            Button(
                onClick = { viewModel.toggleReceiver() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.receiverRunning) R.string.action_stop_receiver
                        else R.string.action_start_receiver,
                    ),
                )
            }

            FilledTonalButton(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_pebble_setup))
            }

            // Only shown when it is the thing standing between a spoken timer and
            // a timer actually starting.
            if (!state.canLaunchFromBackground) {
                FilledTonalButton(onClick = onFixBackgroundLaunch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_overlay_settings))
                }
            }

            Button(onClick = onOpenRules, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_rules))
            }
            Button(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_log))
            }
            Button(onClick = { showMoc = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_rebuild_moc))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_settings))
            }
        }
    }

    val conflict by viewModel.commonConflict.collectAsStateWithLifecycle()
    conflict?.let {
        CommonConflictDialog(
            groupName = viewModel.groupName(it.group),
            onUseVault = { viewModel.resolveCommonConflict(useVault = true) },
            onKeepApp = { viewModel.resolveCommonConflict(useVault = false) },
        )
    }

    if (showMoc) {
        MocRebuildDialog(
            initialRetention = viewModel.mocRetention,
            onDismiss = { showMoc = false },
            onRun = { maps, samples, retention ->
                showMoc = false
                viewModel.rebuildMoc(maps, samples, retention)
            },
        )
    }

    if (showSetup) {
        val (url, secret) = remember { viewModel.pebbleSetup() }
        PebbleSetupDialog(
            url = url,
            secret = secret,
            onCopy = onCopy,
            onRegenerate = {
                showSetup = false
                viewModel.regenerateSecret()
            },
            onDismiss = {
                showSetup = false
                viewModel.refresh()
            },
        )
    }
}

@Composable
private fun StatusCard(
    state: MainUiState,
    groupColors: Map<RuleGroup, GroupColor>,
    onSync: (RuleGroup) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusLine(
                text = if (state.receiverRunning) {
                    stringResource(R.string.status_receiver_running, state.serverPort)
                } else {
                    stringResource(R.string.status_receiver_stopped)
                },
                ok = state.receiverRunning,
            )
            StatusLine(
                text = if (state.hasSecret) stringResource(R.string.status_secret_set)
                else stringResource(R.string.status_secret_missing),
                ok = state.hasSecret,
            )
            StatusLine(stringResource(R.string.status_rules, state.ruleCount), ok = true)
            state.vaults.forEach { vault -> VaultStatusLines(vault, groupColors[vault.group], onSync) }
            StatusLine(
                text = if (state.canLaunchFromBackground) {
                    stringResource(R.string.status_background_launch_ok)
                } else {
                    stringResource(R.string.status_background_launch_blocked)
                },
                ok = state.canLaunchFromBackground,
            )
        }
    }
}

/**
 * One gesture's vault. Each gesture is named, because "vault missing" means
 * nothing when there are two.
 */
@Composable
private fun VaultStatusLines(vault: VaultStatus, color: GroupColor?, onSync: (RuleGroup) -> Unit) {
    // The gesture's own colour, as on the rules and log screens, so "which
    // vault is this" reads the same everywhere.
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(width = 6.dp, height = 18.dp)
                    .background(color.accent, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            text = vault.name,
            style = MaterialTheme.typography.titleSmall,
        )
    }
    if (!vault.assigned) {
        StatusLine(stringResource(R.string.status_vault_unassigned), ok = false)
        return
    }
    StatusLine(
        text = vault.description
            ?.let { stringResource(R.string.status_vault_granted, it) }
            ?: stringResource(R.string.status_vault_missing),
        ok = vault.description != null,
    )
    StatusLine(stringResource(R.string.status_base_dir, vault.baseDir), ok = true)
    if (vault.blockedFolders > 0) {
        StatusLine(stringResource(R.string.status_vault_blocked, vault.blockedFolders), ok = false)
    }
    StatusLine(
        text = if (vault.lastSyncedAt == 0L) {
            stringResource(R.string.status_never_synced)
        } else {
            stringResource(
                R.string.status_last_synced,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(vault.lastSyncedAt)),
            )
        },
        ok = vault.lastSyncedAt != 0L,
    )
    if (vault.usingFallbackConfig) {
        StatusLine(stringResource(R.string.status_using_fallback), ok = false)
    }
    if (vault.commonStale) {
        StatusLine(stringResource(R.string.status_common_stale), ok = false)
    }
    vault.syncError?.let { StatusLine(stringResource(R.string.status_sync_error, it), ok = false) }
    if (vault.needsSync) {
        TextButton(onClick = { onSync(vault.group) }) {
            Text(stringResource(R.string.action_sync_now))
        }
    }
}

/**
 * One line of status, coloured only when something is wrong.
 *
 * Colouring the healthy lines too would make the card a wall of green in which
 * the one red line is no easier to find than it was in plain text.
 */
@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

/**
 * One run, both actions as ticked options.
 *
 * Retention only bites when maps are being rebuilt, so it follows that tick
 * rather than sitting there implying it governs the samples too.
 */
@Composable
private fun MocRebuildDialog(
    initialRetention: Int,
    onDismiss: () -> Unit,
    onRun: (maps: Boolean, samples: Boolean, retention: Int?) -> Unit,
) {
    var maps by remember { mutableStateOf(true) }
    var samples by remember { mutableStateOf(false) }
    var retention by remember { mutableStateOf(initialRetention.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.moc_body), style = MaterialTheme.typography.bodyMedium)
                CheckRow(stringResource(R.string.moc_opt_maps), maps) { maps = it }
                CheckRow(stringResource(R.string.moc_opt_samples), samples) { samples = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.moc_retention),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (maps) 1f else 0.4f),
                    )
                    OutlinedTextField(
                        value = retention,
                        onValueChange = { retention = it.filter(Char::isDigit) },
                        enabled = maps,
                        singleLine = true,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .width(96.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // Running with nothing ticked would report writing nothing, which
                // reads as a failure rather than as a no-op.
                enabled = maps || samples,
                onClick = { onRun(maps, samples, retention.toIntOrNull()) },
            ) { Text(stringResource(R.string.moc_rebuild)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Both values the Pebble app needs, each copyable.
 *
 * The URL is here rather than only in the status line because otherwise it is
 * hand-typed into another app's text field, and a typo there fails as a silent
 * 404 with the voice note already spoken and gone.
 */
@Composable
private fun PebbleSetupDialog(
    url: String,
    secret: String,
    onCopy: (String, String) -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRegenerate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.setup_hint), style = MaterialTheme.typography.bodyMedium)
                CopyableValue(stringResource(R.string.setup_url_label), url) {
                    onCopy(url, "url")
                }
                CopyableValue(stringResource(R.string.setup_secret_label), secret) {
                    onCopy(secret, "secret")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmingRegenerate = true }) {
                Text(stringResource(R.string.action_generate_secret))
            }
        },
    )

    if (confirmingRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmingRegenerate = false },
            title = { Text(stringResource(R.string.action_generate_secret)) },
            text = { Text(stringResource(R.string.secret_regenerate_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRegenerate = false
                    onRegenerate()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRegenerate = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CopyableValue(label: String, value: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        // Selectable as well as copyable: a value that can only be copied by
        // button cannot be partially corrected by hand.
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(onClick = onCopy, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.action_copy))
        }
    }
}
