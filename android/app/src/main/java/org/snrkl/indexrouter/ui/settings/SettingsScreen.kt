package org.snrkl.indexrouter.ui.settings

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.common.CommonConflictDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.action.DateFiling
import org.snrkl.indexrouter.data.db.LogRetention
import org.snrkl.indexrouter.data.prefs.StorageMode
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.ui.common.CardSwitchRow
import org.snrkl.indexrouter.ui.common.CardValueRow
import org.snrkl.indexrouter.ui.common.OnResume
import org.snrkl.indexrouter.ui.common.OutlinedCard
import org.snrkl.indexrouter.ui.common.SettingCard
import org.snrkl.indexrouter.ui.common.GestureSelector
import org.snrkl.indexrouter.ui.theme.GroupColor
import org.snrkl.indexrouter.ui.theme.rememberGroupColors

/**
 * Everything that configures rather than reports.
 *
 * Until now these lived as dialogs behind ten buttons on the status screen,
 * which made the first screen a menu rather than an answer to "is it working?".
 * Grouped here by what they affect, with every value visible without opening
 * anything — a setting whose current value is only discoverable by tapping is
 * half a setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenTriggers: (RuleGroup) -> Unit,
    onOpenNotificationChannels: () -> Unit,
    onOpenInputMethodSettings: () -> Unit,
    hasAllFilesAccess: () -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<TextSetting?>(null) }
    var pickingDateFiling by remember { mutableStateOf(false) }
    var pickingCalendarMode by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }
    var turningOn by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // The two rows that matter most here send the user to Android's settings to
    // fix something — file access, and enabling the input method. Without this,
    // they come back and the screen still reports the problem they just fixed,
    // which reads as the fix not having worked.
    OnResume { viewModel.refresh(hasAllFilesAccess()) }

    val groupColors = rememberGroupColors(state.groupSeeds)
    // In one vault the vault belongs to neither gesture, so it wears neither colour.
    val gestureColor = if (state.perGesture) groupColors[state.group] else null

    val conflict by viewModel.commonConflict.collectAsStateWithLifecycle()
    conflict?.let {
        CommonConflictDialog(
            groupName = state.groupNames[it.group] ?: it.group.defaultName,
            onUseVault = { viewModel.resolveCommonConflict(useVault = true) },
            onKeepApp = { viewModel.resolveCommonConflict(useVault = false) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // First, because it decides what every vault setting below means.
            VaultModeCard(
                state = state,
                onRequestOn = { turningOn = true },
                onRequestOff = { turningOff = true },
                onOpenVaults = { onOpenTriggers(state.group) },
            )

            // With a vault per gesture, the three cards below it — vault,
            // folders, tasks — describe whichever one is selected here.
            if (state.perGesture) {
                GestureSelector(
                    selected = state.group,
                    names = state.groupNames,
                    colors = groupColors,
                    onSelect = viewModel::selectGroup,
                )
            }

            // The vault itself — which folder, base folder, access — is set on
            // the Triggers screen, with the gesture's name and colour. Shown
            // here so the cards below are never read without knowing which
            // vault they describe.
            OutlinedCard(border = gestureColor?.accent ?: MaterialTheme.colorScheme.outline) {
                Text(
                    text = stringResource(R.string.settings_group_vault),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                CardValueRow(
                    label = stringResource(R.string.settings_vault_root),
                    value = if (state.vaultAssigned) {
                        state.vaultRoot?.trimEnd('/')?.substringAfterLast('/')
                            ?: stringResource(R.string.settings_vault_saf)
                    } else {
                        stringResource(R.string.vault_root_unset)
                    },
                    onClick = { onOpenTriggers(state.group) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_base_folder),
                    value = state.baseDir.ifBlank { stringResource(R.string.settings_base_is_root) },
                    onClick = { onOpenTriggers(state.group) },
                )
                TextButton(onClick = { onOpenTriggers(state.group) }) {
                    Text(stringResource(R.string.settings_vault_in_triggers))
                }
            }

            SettingCard(
                title = stringResource(R.string.settings_group_folders),
                container = gestureColor?.container ?: MaterialTheme.colorScheme.secondaryContainer,
                onContainer = gestureColor?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                CardValueRow(
                    label = stringResource(R.string.settings_task_dir),
                    value = state.taskDir,
                    onClick = { editing = TextSetting.Reserved(ReservedFolder.TASK, state.taskDir) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_fallback_dir),
                    value = state.fallbackDir,
                    onClick = { editing = TextSetting.Reserved(ReservedFolder.FALLBACK, state.fallbackDir) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_audio_dir),
                    value = state.audioDir,
                    onClick = { editing = TextSetting.Reserved(ReservedFolder.AUDIO, state.audioDir) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_date_filing),
                    value = stringResource(dateFilingLabel(state.dateFiling)),
                    onClick = { pickingDateFiling = true },
                )
            }

            SettingCard(
                title = stringResource(R.string.settings_group_tasks),
                container = gestureColor?.container ?: MaterialTheme.colorScheme.secondaryContainer,
                onContainer = gestureColor?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                CardValueRow(
                    label = stringResource(R.string.settings_task_tags),
                    value = state.taskTags,
                    onClick = { editing = TextSetting.TaskTags(state.taskTags) },
                )
                CardSwitchRow(
                    label = stringResource(R.string.settings_task_created_date),
                    checked = state.taskCreatedDate,
                    onCheckedChange = viewModel::setTaskCreatedDate,
                    note = stringResource(R.string.settings_task_created_date_sub),
                )
            }

            SettingCard(stringResource(R.string.settings_group_notifications)) {
                Text(
                    text = stringResource(R.string.notify_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ActionType.entries.forEach { action ->
                    CardSwitchRow(
                        label = stringResource(R.string.notify_row_route, action.displayName),
                        checked = state.routeNotify[action] ?: false,
                        onCheckedChange = { viewModel.setRouteNotify(action, it) },
                    )
                }
                CardSwitchRow(
                    label = stringResource(R.string.notify_row_fallback),
                    checked = state.notifyFallback,
                    onCheckedChange = viewModel::setNotifyFallback,
                )
                CardSwitchRow(
                    label = stringResource(R.string.notify_row_debug),
                    checked = state.debugStream,
                    onCheckedChange = viewModel::setDebugStream,
                    note = stringResource(R.string.settings_debug_sub),
                )
                CardValueRow(
                    label = stringResource(R.string.notify_open_channels),
                    value = stringResource(R.string.settings_sounds_open),
                    onClick = onOpenNotificationChannels,
                    note = stringResource(R.string.settings_sounds_sub),
                )
            }

            SettingCard(stringResource(R.string.settings_group_privacy)) {
                Text(
                    text = stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                CardValueRow(
                    label = stringResource(R.string.settings_log_retention),
                    value = LogRetention.describe(state.logRetentionHours),
                    onClick = {
                        editing = TextSetting.LogRetention(state.logRetentionHours.toString())
                    },
                )
                CardSwitchRow(
                    label = stringResource(R.string.settings_detailed_content),
                    checked = state.detailedContentLogging,
                    onCheckedChange = viewModel::setDetailedContentLogging,
                    note = stringResource(R.string.settings_detailed_content_sub),
                )
            }

            SettingCard(stringResource(R.string.settings_group_calendar)) {
                Text(
                    text = stringResource(R.string.settings_calendar_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                CardValueRow(
                    label = stringResource(R.string.settings_calendar_mode),
                    value = stringResource(calendarModeLabel(state.calendarMode)),
                    onClick = { pickingCalendarMode = true },
                )
                if (state.calendarMode == CalendarMode.SHARE_TO) {
                    CardValueRow(
                        label = stringResource(R.string.settings_calendar_package),
                        value = state.calendarSharePackage,
                        onClick = {
                            editing = TextSetting.CalendarPackage(state.calendarSharePackage)
                        },
                        note = if (state.calendarShareInstalled) {
                            stringResource(R.string.settings_calendar_installed)
                        } else {
                            stringResource(R.string.settings_calendar_missing)
                        },
                    )
                }
            }

            SettingCard(stringResource(R.string.settings_group_voice)) {
                Text(
                    text = stringResource(R.string.settings_voice_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                CardValueRow(
                    label = stringResource(R.string.settings_voice_enable),
                    value = stringResource(R.string.settings_sounds_open),
                    onClick = onOpenInputMethodSettings,
                    note = stringResource(R.string.settings_voice_enable_sub),
                )
                state.voiceStatus?.let { status ->
                    val text = when {
                        !status.enabled -> stringResource(R.string.voice_status_not_enabled)
                        !status.reachable -> stringResource(R.string.voice_status_not_reachable)
                        !status.isFirst -> stringResource(
                            R.string.voice_status_not_first,
                            status.competitors.firstOrNull().orEmpty(),
                        )
                        else -> stringResource(R.string.voice_status_ready)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.reachable && status.isFirst) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                CardValueRow(
                    label = stringResource(R.string.settings_voice_cutoff),
                    value = if (state.voiceCutoffMinutes <= 0) {
                        stringResource(R.string.settings_voice_cutoff_none)
                    } else {
                        stringResource(R.string.settings_voice_cutoff_value, state.voiceCutoffMinutes)
                    },
                    onClick = { editing = TextSetting.VoiceCutoff(state.voiceCutoffMinutes.toString()) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_voice_buffer),
                    value = state.voiceBufferSize.toString(),
                    onClick = { editing = TextSetting.VoiceBuffer(state.voiceBufferSize.toString()) },
                )
            }

            SettingCard(stringResource(R.string.settings_group_advanced)) {
                CardValueRow(
                    label = stringResource(R.string.settings_port),
                    value = state.serverPort.toString(),
                    onClick = { editing = TextSetting.Port(state.serverPort.toString()) },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_moc_retention),
                    value = state.mocRetention.toString(),
                    onClick = { editing = TextSetting.Retention(state.mocRetention.toString()) },
                )
            }

            SettingCard(stringResource(R.string.settings_group_config)) {
                CardValueRow(
                    label = stringResource(R.string.action_reload),
                    value = stringResource(R.string.settings_action_run),
                    onClick = viewModel::reload,
                    note = stringResource(R.string.settings_reload_sub),
                )
                CardValueRow(
                    label = stringResource(R.string.action_export),
                    value = stringResource(R.string.settings_action_run),
                    onClick = viewModel::export,
                    note = stringResource(R.string.settings_export_sub),
                )
                CardValueRow(
                    label = stringResource(R.string.action_reset_defaults),
                    value = stringResource(R.string.settings_action_run),
                    onClick = { confirmingReset = true },
                    note = stringResource(R.string.settings_reset_sub),
                )
            }

            Box(modifier = Modifier.padding(bottom = 32.dp))
        }
    }

    if (turningOn) {
        TurnOnDialog(
            state = state,
            onDismiss = { turningOn = false },
            onConfirm = { keptBy, copy, chooseNow ->
                turningOn = false
                viewModel.switchToPerGesture(keptBy, copy) { needing ->
                    if (chooseNow) onOpenTriggers(needing)
                }
            },
        )
    }
    if (turningOff) {
        TurnOffDialog(
            state = state,
            onDismiss = { turningOff = false },
            onConfirm = { keep, bring ->
                turningOff = false
                viewModel.switchToOneVault(keep, bring)
            },
        )
    }

    editing?.let { setting ->
        TextSettingDialog(
            setting = setting,
            onDismiss = { editing = null },
            onSave = { value ->
                when (setting) {
                    is TextSetting.Reserved -> viewModel.setReservedFolder(setting.which, value)
                    is TextSetting.TaskTags -> viewModel.setTaskTags(value)
                    is TextSetting.Port -> value.toIntOrNull()?.let(viewModel::setServerPort)
                    is TextSetting.Retention -> value.toIntOrNull()?.let(viewModel::setMocRetention)
                    is TextSetting.VoiceCutoff -> value.toIntOrNull()?.let(viewModel::setVoiceCutoffMinutes)
                    is TextSetting.VoiceBuffer -> value.toIntOrNull()?.let(viewModel::setVoiceBufferSize)
                    is TextSetting.CalendarPackage -> viewModel.setCalendarSharePackage(value)
                    is TextSetting.LogRetention -> value.toIntOrNull()?.let(viewModel::setLogRetentionHours)
                }
                editing = null
            },
        )
    }

    if (pickingCalendarMode) {
        AlertDialog(
            onDismissRequest = { pickingCalendarMode = false },
            title = { Text(stringResource(R.string.settings_calendar_mode)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_calendar_mode_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    CalendarMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = mode == state.calendarMode,
                                    onClick = {
                                        viewModel.setCalendarMode(mode)
                                        pickingCalendarMode = false
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == state.calendarMode,
                                onClick = {
                                    viewModel.setCalendarMode(mode)
                                    pickingCalendarMode = false
                                },
                            )
                            Text(stringResource(calendarModeLabel(mode)))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingCalendarMode = false }) {
                    Text(stringResource(R.string.rule_cancel))
                }
            },
        )
    }

    if (pickingDateFiling) {
        DateFilingDialog(
            current = state.dateFiling,
            onPick = {
                viewModel.setDateFiling(it)
                pickingDateFiling = false
            },
            onDismiss = { pickingDateFiling = false },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    viewModel.resetToDefaults()
                }) { Text(stringResource(R.string.action_reset_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

}

/** The settings that are just a value to type. */
internal sealed interface TextSetting {
    val initial: String

    data class Reserved(val which: ReservedFolder, override val initial: String) : TextSetting
    data class TaskTags(override val initial: String) : TextSetting
    data class Port(override val initial: String) : TextSetting
    data class Retention(override val initial: String) : TextSetting
    data class VoiceCutoff(override val initial: String) : TextSetting
    data class VoiceBuffer(override val initial: String) : TextSetting
    data class CalendarPackage(override val initial: String) : TextSetting
    data class LogRetention(override val initial: String) : TextSetting
}

@Composable
private fun TextSettingDialog(
    setting: TextSetting,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(setting.initial) }
    val numeric = setting is TextSetting.Port || setting is TextSetting.Retention ||
        setting is TextSetting.VoiceCutoff || setting is TextSetting.VoiceBuffer ||
        setting is TextSetting.LogRetention

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleFor(setting))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(bodyFor(setting)), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = if (numeric) it.filter(Char::isDigit) else it },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.rule_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rule_cancel)) }
        },
    )
}

@Composable
private fun DateFilingDialog(
    current: DateFiling.Depth,
    onPick: (DateFiling.Depth) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_date_filing)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_date_filing_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DateFiling.Depth.entries.forEach { depth ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = depth == current, onClick = { onPick(depth) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = depth == current, onClick = { onPick(depth) })
                        Text(stringResource(dateFilingLabel(depth)))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rule_cancel)) }
        },
    )
}

private fun calendarModeLabel(mode: CalendarMode) = when (mode) {
    CalendarMode.INSERT -> R.string.settings_calendar_mode_insert
    CalendarMode.SHARE_TO -> R.string.settings_calendar_mode_share
}

private fun dateFilingLabel(depth: DateFiling.Depth) = when (depth) {
    DateFiling.Depth.NONE -> R.string.settings_date_none
    DateFiling.Depth.MONTH -> R.string.settings_date_month
    DateFiling.Depth.DAY -> R.string.settings_date_day
}

private fun titleFor(setting: TextSetting) = when (setting) {
    is TextSetting.Reserved -> when (setting.which) {
        ReservedFolder.TASK -> R.string.settings_task_dir
        ReservedFolder.FALLBACK -> R.string.settings_fallback_dir
        ReservedFolder.AUDIO -> R.string.settings_audio_dir
    }
    is TextSetting.TaskTags -> R.string.settings_task_tags
    is TextSetting.Port -> R.string.settings_port
    is TextSetting.Retention -> R.string.settings_moc_retention
    is TextSetting.VoiceCutoff -> R.string.settings_voice_cutoff
    is TextSetting.VoiceBuffer -> R.string.settings_voice_buffer
    is TextSetting.CalendarPackage -> R.string.settings_calendar_package
    is TextSetting.LogRetention -> R.string.settings_log_retention
}

private fun bodyFor(setting: TextSetting) = when (setting) {
    is TextSetting.Reserved -> R.string.settings_reserved_body
    is TextSetting.TaskTags -> R.string.settings_task_tags_body
    is TextSetting.Port -> R.string.settings_port_body
    is TextSetting.Retention -> R.string.settings_retention_body
    is TextSetting.VoiceCutoff -> R.string.settings_voice_cutoff_body
    is TextSetting.VoiceBuffer -> R.string.settings_voice_buffer_body
    is TextSetting.CalendarPackage -> R.string.settings_calendar_package_body
    is TextSetting.LogRetention -> R.string.settings_log_retention_body
}
