package org.snrkl.indexrouter.ui.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.prefs.StorageMode
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.common.CardLabel
import org.snrkl.indexrouter.ui.common.CardValueRow
import org.snrkl.indexrouter.ui.common.CommonConflictDialog
import org.snrkl.indexrouter.ui.common.GestureSelector
import org.snrkl.indexrouter.ui.common.OnResume
import org.snrkl.indexrouter.ui.common.OutlinedCard
import org.snrkl.indexrouter.ui.common.Pill
import org.snrkl.indexrouter.ui.settings.SettingsUiState
import org.snrkl.indexrouter.ui.settings.SettingsViewModel
import org.snrkl.indexrouter.ui.theme.GroupColor
import org.snrkl.indexrouter.ui.theme.GroupPalette
import org.snrkl.indexrouter.ui.theme.Tonal
import org.snrkl.indexrouter.ui.theme.rememberGroupColors

/**
 * The groups themselves: what each is called, its colour, and for each
 * gesture the vault it files into.
 *
 * The common group comes first because it belongs to neither gesture. The
 * gesture below is chosen with the same selector the settings screen uses,
 * and everything under it — name, colour, vault, access — is that gesture's.
 * The gesture-to-vault mapping lives only in the app, never in a vault file,
 * so this is the one place it is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TriggersScreen(
    viewModel: SettingsViewModel,
    initialGroup: RuleGroup?,
    onBack: () -> Unit,
    onChooseVault: () -> Unit,
    onChooseBaseFolder: () -> Unit,
    onOpenFileAccess: () -> Unit,
    hasAllFilesAccess: () -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val nested by viewModel.nestedGrant.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val groupColors = rememberGroupColors(state.groupSeeds)
    val gestureColor = groupColors[state.group]

    var renaming by remember { mutableStateOf<RuleGroup?>(null) }
    var recolouring by remember { mutableStateOf<RuleGroup?>(null) }

    LaunchedEffect(initialGroup) { initialGroup?.let(viewModel::selectGroup) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    // Access is granted outside the app, so coming back re-reads it.
    OnResume { viewModel.refresh(hasAllFilesAccess()) }

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
                title = { Text(stringResource(R.string.triggers_title)) },
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
            Text(
                text = stringResource(R.string.triggers_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            IdentityCard(
                name = state.groupNames[RuleGroup.COMMON] ?: RuleGroup.COMMON.defaultName,
                subtitle = stringResource(R.string.group_common_sub),
                seed = state.groupSeeds[RuleGroup.COMMON] ?: GroupPalette.defaultSeed(RuleGroup.COMMON),
                color = groupColors[RuleGroup.COMMON],
                onRename = { renaming = RuleGroup.COMMON },
                onRecolour = { recolouring = RuleGroup.COMMON },
            )

            GestureSelector(
                selected = state.group,
                names = state.groupNames,
                colors = groupColors,
                onSelect = viewModel::selectGroup,
            )

            IdentityCard(
                name = state.groupNames[state.group] ?: state.group.defaultName,
                subtitle = stringResource(R.string.triggers_gesture_sub, state.group.wireName),
                seed = state.groupSeeds[state.group] ?: GroupPalette.defaultSeed(state.group),
                color = gestureColor,
                onRename = { renaming = state.group },
                onRecolour = { recolouring = state.group },
            )

            // The vault is granted, not chosen, so it takes the outlined card
            // the rules screen uses for the things that are not choices. Its
            // border, and the two cards after it, wear the gesture's colour:
            // everything down to the end of Tasks belongs to that gesture.
            OutlinedCard(border = gestureColor?.accent ?: MaterialTheme.colorScheme.outline) {
                Text(
                    text = stringResource(R.string.settings_group_vault),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (!state.vaultAssigned) {
                    Text(
                        text = stringResource(R.string.settings_vault_unassigned),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (!state.vaultAssigned && state.carriedOverPath != null) {
                    FilledTonalButton(
                        onClick = viewModel::useCarriedOver,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.settings_use_carried_over,
                                state.carriedOverPath.orEmpty().substringAfterLast('/'),
                                state.groupNames[state.group] ?: state.group.defaultName,
                            ),
                        )
                    }
                }
                // The pill holds the vault's own name; a full path does not fit
                // on one line and was being cut to its least useful part.
                CardValueRow(
                    label = stringResource(R.string.settings_vault_root),
                    value = when (state.storageMode) {
                        StorageMode.FILES -> state.vaultRoot?.trimEnd('/')?.substringAfterLast('/')
                            ?: stringResource(R.string.vault_root_unset)
                        StorageMode.SAF -> stringResource(R.string.settings_vault_saf)
                    },
                    onClick = onChooseVault,
                    note = state.vaultRoot.takeIf { state.storageMode == StorageMode.FILES },
                )
                CardValueRow(
                    label = stringResource(R.string.settings_base_folder),
                    value = state.baseDir.ifBlank { stringResource(R.string.settings_base_is_root) },
                    onClick = onChooseBaseFolder,
                )
                // Storage Scopes grants access without "all files", so the
                // permission alone says nothing; a vault whose folders all
                // proved writable has access, whatever granted it.
                val accessProven = state.requirements.isNotEmpty() && state.requirements.all { it.satisfied }
                val accessMissing = state.storageMode == StorageMode.FILES && !state.hasAllFilesAccess && !accessProven
                CardValueRow(
                    label = stringResource(R.string.settings_file_access),
                    value = if (accessMissing) {
                        stringResource(R.string.settings_file_access_missing)
                    } else {
                        stringResource(R.string.settings_file_access_granted)
                    },
                    onClick = onOpenFileAccess,
                    note = if (accessMissing) {
                        stringResource(R.string.vault_needs_all_files)
                    } else {
                        null
                    },
                )
                FolderChecklist(state)
                if (state.vaultAssigned) {
                    TextButton(onClick = viewModel::unassignVault) {
                        Text(stringResource(R.string.action_unassign_vault))
                    }
                }
            }

        }
    }

    renaming?.let { group ->
        RenameDialog(
            current = state.groupNames[group] ?: group.defaultName,
            default = group.defaultName,
            onDismiss = { renaming = null },
            onSave = {
                viewModel.setGroupName(group, it)
                renaming = null
            },
        )
    }

    recolouring?.let { group ->
        ColourDialog(
            name = state.groupNames[group] ?: group.defaultName,
            current = state.groupSeeds[group] ?: GroupPalette.defaultSeed(group),
            default = GroupPalette.defaultSeed(group),
            onDismiss = { recolouring = null },
            onPick = {
                viewModel.setGroupSeed(group, it)
                recolouring = null
            },
        )
    }

    nested?.let { grant ->
        AlertDialog(
            onDismissRequest = { viewModel.nestedGrantDismissed() },
            title = { Text(stringResource(R.string.grant_nested_title)) },
            text = { Text(stringResource(R.string.grant_nested_body, grant.folderName, grant.baseDir)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.nestedGrantDismissed()
                    onChooseVault()
                }) { Text(stringResource(R.string.grant_choose_again)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.adoptGrantedFolderAsBase(grant.folderName) }) {
                    Text(stringResource(R.string.grant_adjust_paths))
                }
            },
        )
    }
}

/**
 * A group's name and colour, on a card in that colour. The name is always
 * written out, so the colour is never the only way to tell groups apart.
 */
@Composable
private fun IdentityCard(
    name: String,
    subtitle: String,
    seed: Int,
    color: GroupColor?,
    onRename: () -> Unit,
    onRecolour: () -> Unit,
) {
    val container = color?.container ?: MaterialTheme.colorScheme.secondaryContainer
    val onContainer = color?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .padding(start = 6.dp)
                .width(6.dp)
                .height(48.dp)
                .background(color?.accent ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = onContainer)
            CardValueRow(label = stringResource(R.string.trigger_name), value = name, onClick = onRename)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardLabel(stringResource(R.string.trigger_colour))
                Box(modifier = Modifier.weight(1f))
                Swatch(color?.accent ?: Color(seed), selected = false, size = 28, onClick = onRecolour)
                Pill(
                    text = GroupPalette.toHex(seed),
                    onClick = onRecolour,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: Int = 40, description: String? = null, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .padding(if (selected) 5.dp else 0.dp)
            .background(color, CircleShape)
            .clickable(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

@Composable
private fun RenameDialog(current: String, default: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trigger_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.trigger_rename_body, default), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it.take(24) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * Swatches, or any hex, with a preview of what will actually be drawn.
 *
 * A seed is never drawn as it is: it is rebuilt for each theme at a fixed
 * lightness, so the preview shows that result in both themes — what the rules
 * screen will show — rather than the raw colour picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourDialog(
    name: String,
    current: Int,
    default: Int,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    var seed by remember { mutableStateOf(current) }
    var hex by remember { mutableStateOf(GroupPalette.toHex(current)) }
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb()
    val error = scheme.error.toArgb()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trigger_colour_title, name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GroupPalette.SWATCHES.forEach { swatch ->
                        val dark = scheme.surface.luminanceIsDark()
                        Swatch(
                            color = Color(Tonal.tones(swatch, primary, dark, error).accent),
                            selected = swatch == seed,
                            description = GroupPalette.toHex(swatch),
                        ) {
                            seed = swatch
                            hex = GroupPalette.toHex(swatch)
                        }
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { typed ->
                        hex = typed.take(7)
                        GroupPalette.parseHex(typed)?.let { seed = it }
                    },
                    label = { Text(stringResource(R.string.trigger_colour_hex)) },
                    isError = GroupPalette.parseHex(hex) == null,
                    supportingText = if (GroupPalette.parseHex(hex) == null) {
                        { Text(stringResource(R.string.trigger_colour_hex_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                )
                Text(stringResource(R.string.trigger_colour_preview), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewTile(seed, primary, error, dark = false, label = name, modifier = Modifier.weight(1f))
                    PreviewTile(seed, primary, error, dark = true, label = name, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(if (seed == default) null else seed) },
                enabled = GroupPalette.parseHex(hex) != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.trigger_colour_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

private fun Color.luminanceIsDark(): Boolean = Tonal.luminance(toArgb()) < 0.18

/** A miniature rule card, as the derived colours will draw it in one theme. */
@Composable
private fun PreviewTile(seed: Int, primary: Int, error: Int, dark: Boolean, label: String, modifier: Modifier) {
    val t = Tonal.tones(seed, primary, dark, error)
    val surface = if (dark) Color(0xFF1B1B1F) else Color(0xFFFBFBFF)
    Column(
        modifier = modifier
            .background(surface, RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(t.container), RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .width(4.dp)
                    .height(32.dp)
                    .background(Color(t.accent), RoundedCornerShape(2.dp)),
            )
            Text(
                text = label,
                color = Color(t.onContainer),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(8.dp),
            )
        }
        Text(
            text = stringResource(if (dark) R.string.trigger_preview_dark else R.string.trigger_preview_light),
            color = if (dark) Color(0xFFE3E2E6) else Color(0xFF1B1B1F),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Every folder the configuration needs, with its state.
 *
 * Under Storage Scopes this is not one permission but several, and a missing
 * one is invisible until a note goes nowhere — so it is listed here rather than
 * summarised as a count.
 */
@Composable
private fun FolderChecklist(state: SettingsUiState) {
    if (state.requirements.isEmpty()) return
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.vault_folders_heading),
            style = MaterialTheme.typography.labelLarge,
        )
        state.requirements.forEach { requirement ->
            Text(
                text = stringResource(
                    if (requirement.satisfied) R.string.vault_folder_ok else R.string.vault_folder_blocked,
                    requirement.folderPath,
                    requirement.reason,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (requirement.satisfied) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

