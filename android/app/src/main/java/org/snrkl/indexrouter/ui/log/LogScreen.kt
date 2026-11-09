package org.snrkl.indexrouter.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.theme.GroupColor
import org.snrkl.indexrouter.ui.theme.groupSeeds
import org.snrkl.indexrouter.ui.theme.rememberGroupColors
import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.action.Rerouter
import org.snrkl.indexrouter.data.db.LogKeeper
import org.snrkl.indexrouter.data.db.RoutingLogEntity
import org.snrkl.indexrouter.routing.ActionType
import java.text.DateFormat
import java.util.Date

internal class LogViewModel(private val app: Application) : ViewModel() {

    private val container get() = (app as IndexRouterApp).container

    val entries: StateFlow<List<RoutingLogEntity>> =
        container.database.log().observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /** Each group's name and seed, and each gesture's vault folder name, for the row chips. */
    fun groupLabels(): GroupLabels {
        val settings = container.settings
        return GroupLabels(
            names = RuleGroup.entries.associateWith { settings.groupName(it) },
            seeds = settings.groupSeeds(),
            vaults = RuleGroup.entries.filter { it.hasVault }.associateWith { group ->
                settings.profile(group).let { it.vaultRootPath ?: it.vaultTreeUri?.lastPathSegment }
                    ?.trimEnd('/')?.substringAfterLast('/')?.substringAfterLast(':')
            },
        )
    }

    /**
     * Whether a dictation's words may be shown. The log is where the keyboard
     * collects a dictation from, so the text is stored either way; it is only
     * kept off the screen unless detailed content logging is on.
     */
    fun showsDictationText(): Boolean = container.settings.detailedContentLogging

    fun messageShown() {
        _messages.value = null
    }

    /** Destinations that hold a file. A timer has nothing to move. */
    suspend fun rerouteTargets(): List<Pair<String, Rerouter.Target>> {
        val folders = container.config.rules()
            .filter { it.action == ActionType.FILE }
            .mapNotNull { rule -> rule.folder?.takeIf { it.isNotBlank() } }
            .distinct()
        return buildList {
            add(app.getString(R.string.reroute_to_task) to (Rerouter.Target.Task as Rerouter.Target))
            add(app.getString(R.string.reroute_to_inbox) to (Rerouter.Target.Fallback as Rerouter.Target))
            folders.forEach { add(it to (Rerouter.Target.Folder(it) as Rerouter.Target)) }
        }
    }

    fun reroute(entry: RoutingLogEntity, target: Rerouter.Target) {
        viewModelScope.launch {
            _messages.value = when (val result = container.rerouter.reroute(entry, target)) {
                is Rerouter.Result.Moved -> app.getString(R.string.reroute_done, result.to)
                is Rerouter.Result.Failed -> app.getString(R.string.reroute_failed, result.reason)
            }
        }
    }

    fun reportNoFile() {
        _messages.value = app.getString(R.string.reroute_no_file)
    }

    private val keeper = LogKeeper(container.database.log()) { container.settings.logRetentionHours }

    /** The button. Content goes; a dated marker is left in its place. */
    fun clearLog() {
        viewModelScope.launch {
            val removed = keeper.clearNow()
            _messages.value = app.getString(R.string.log_cleared, removed)
        }
    }
}

/**
 * Reverse-chronological traceability log.
 *
 * For timer and calendar actions this is the only record that the voice note
 * ever existed — they leave nothing in the vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogScreen(viewModel: LogViewModel, onBack: () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val labels = remember(entries) { viewModel.groupLabels() }
    val showDictation = remember(entries) { viewModel.showsDictationText() }
    val groupColors = rememberGroupColors(labels.seeds)
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var rerouting by remember { mutableStateOf<RoutingLogEntity?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmingClear = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.log_clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LogRow(entry, labels, groupColors, showDictation) {
                        // Re-route is the user overruling the classifier, and only
                        // where there is a file to move.
                        when {
                            entry.isMarker -> Unit
                            entry.targetPath == null -> viewModel.reportNoFile()
                            else -> rerouting = entry
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.log_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    viewModel.clearLog()
                }) { Text(stringResource(R.string.log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.rule_cancel))
                }
            },
        )
    }

    rerouting?.let { entry ->
        RerouteDialog(
            viewModel = viewModel,
            onPick = { target ->
                viewModel.reroute(entry, target)
                rerouting = null
            },
            onDismiss = { rerouting = null },
        )
    }
}

internal data class GroupLabels(
    val names: Map<RuleGroup, String>,
    val seeds: Map<RuleGroup, Int>,
    val vaults: Map<RuleGroup, String?>,
)

@Composable
private fun LogRow(
    entry: RoutingLogEntity,
    labels: GroupLabels,
    colors: Map<RuleGroup, GroupColor>,
    showDictation: Boolean,
    onClick: () -> Unit,
) {
    // A marker is not a routed item: it is the record that the rest went.
    if (entry.isMarker) {
        Text(
            text = entry.transcript,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
        return
    }

    val stamp = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val subtitle = listOfNotNull(
        stamp.format(Date(entry.receivedAt)),
        if (entry.manuallyRouted) stringResource(R.string.log_entry_manual) else null,
        when {
            // A test event or a refusal was never classified; "no match" would
            // claim it went to the inbox.
            entry.outcome == "TEST" || entry.outcome == "REJECTED" -> stringResource(R.string.log_entry_not_routed)
            entry.matchedKeyword != null ->
                stringResource(R.string.log_entry_routed, entry.matchedKeyword!!, entry.action.orEmpty())
            else -> stringResource(R.string.log_entry_unmatched)
        },
        entry.outcome,
        entry.durationMs.takeIf { it > 0 }?.let { stringResource(R.string.log_entry_duration, it) },
        entry.targetPath,
        entry.errorMessage,
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = when {
                entry.action == ActionType.DICTATION.wireName && !showDictation ->
                    stringResource(R.string.log_dictation_withheld)
                else -> entry.transcript.ifBlank { stringResource(R.string.log_no_transcript) }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        // Which gesture sent it, in that gesture's colour, and which vault it
        // wrote to — named, because colour is never the only cue. The two are
        // separate: rows from before there were two vaults were sent by one
        // gesture and written to the vault the install had then.
        val trigger = RuleGroup.fromWire(entry.trigger)?.takeIf { it.isTrigger }
        val vaultGroup = RuleGroup.fromWire(entry.vaultGroup)?.takeIf { it.hasVault }
        val wrote = entry.targetPath != null || entry.audioPath != null
        val vault = vaultGroup?.let { labels.vaults[it] }?.takeIf { wrote }
        val color = trigger?.let { colors[it] }
        val label = when {
            trigger != null -> labels.names[trigger] ?: trigger.defaultName
            entry.trigger.isNullOrBlank() -> stringResource(R.string.log_chip_no_trigger)
            else -> entry.trigger!!
        }
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = if (vault != null) stringResource(R.string.log_chip_wrote, label, vault) else label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color?.container ?: MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                if (entry.ruleGroup == RuleGroup.COMMON.wireName) {
                    val common = colors[RuleGroup.COMMON]
                    Text(
                        text = labels.names[RuleGroup.COMMON] ?: RuleGroup.COMMON.defaultName,
                        style = MaterialTheme.typography.labelMedium,
                        color = common?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                common?.container ?: MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.outcome == "FAILED") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RerouteDialog(
    viewModel: LogViewModel,
    onPick: (Rerouter.Target) -> Unit,
    onDismiss: () -> Unit,
) {
    var targets by remember { mutableStateOf<List<Pair<String, Rerouter.Target>>>(emptyList()) }
    LaunchedEffect(Unit) { targets = viewModel.rerouteTargets() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reroute_title)) },
        text = {
            Column {
                targets.forEach { (label, target) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(target) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rule_cancel)) }
        },
    )
}
