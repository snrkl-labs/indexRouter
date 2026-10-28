package org.snrkl.indexrouter.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.MatchType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.ui.common.LabelWithInfo
import org.snrkl.indexrouter.ui.common.OnResume

/**
 * The routing table, as the whole chain in the order it runs: keyword rules by
 * priority, then the catch-all for anything that missed, then recordings — the
 * one thing that happens to every recording whichever route fired.
 *
 * Rules are cards edited in place. There is no Save: every change writes
 * through to the vault as it is made, which is the same contract the editor
 * dialog had, just without a round trip through a form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RulesScreen(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onChooseFolderFor: (onPicked: (String) -> Unit) -> Unit,
) {
    val rules by viewModel.displayedRules.collectAsStateWithLifecycle()
    val folders by viewModel.folderOptions.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val folderWarning by viewModel.folderWarning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var moreFor by remember { mutableStateOf<RoutingRule?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Only the rule cards reorder. The default route and the recordings folder
    // are not rules and have no place in the order, so they are not draggable
    // and nothing can be dropped past them.
    val reorder = rememberReorderState(
        listState = listState,
        scope = scope,
        isDraggable = { it.key is Long },
        onMove = viewModel::moveRule,
        onSettled = viewModel::commitOrder,
    )

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // Folders can be created in the vault from outside the app, and the
    // dropdown is only useful if it knows about them.
    OnResume { viewModel.refreshFolders() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rules, key = { it.id }) { rule ->
                val dragging = reorder.draggingKey == rule.id
                Box(
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset(0, reorder.offsetFor(rule.id).toInt()) },
                ) {
                RuleCard(
                    rule = rule,
                    expanded = expandedId == rule.id,
                    folderOptions = folders,
                    resolvedFolder = viewModel::resolvedFolder,
                    fixedFolderFor = viewModel::fixedFolderFor,
                    inheritedTags = viewModel.taskTags.joinToString(", "),
                    onExpandToggle = {
                        expandedId = if (expandedId == rule.id) null else rule.id
                    },
                    onChange = viewModel::saveInline,
                    onDelete = { viewModel.delete(rule.id) },
                    onChooseFolder = {
                        onChooseFolderFor { segment ->
                            viewModel.saveInline(rule.copy(folder = segment))
                        }
                    },
                    onMore = { moreFor = rule },
                    handle = {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.rule_reorder_handle),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(24.dp)
                                .reorderHandle(reorder, rule.id),
                        )
                    },
                )
                }
            }

            item {
                // Distinct on purpose: these two are not rules. The default is
                // what happens when nothing matched, and recordings happen
                // whatever matched, so neither has a keyword, a priority or a
                // place in the order.
                FixedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoldLabel(stringResource(R.string.rule_label_action))
                        StaticPill(stringResource(R.string.rule_default_title))
                        Box(modifier = Modifier.weight(1f))
                        BoldLabel(stringResource(R.string.rule_label_priority))
                        StaticPill(stringResource(R.string.rule_default_title))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        BoldLabel(stringResource(R.string.rule_label_folder))
                        ReservedFolderDropdown(
                            current = viewModel.fallbackDirName,
                            options = folders,
                            resolved = viewModel::resolvedFolder,
                            onPick = viewModel::saveDefaultRoute,
                            onChoose = { onChooseFolderFor(viewModel::saveDefaultRoute) },
                        )
                    }
                }
            }

            item {
                FixedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoldLabel(stringResource(R.string.rule_recordings_label))
                        ReservedFolderDropdown(
                            current = viewModel.audioDirName,
                            options = folders,
                            resolved = viewModel::resolvedFolder,
                            onPick = viewModel::saveAudioFolder,
                            onChoose = { onChooseFolderFor(viewModel::saveAudioFolder) },
                        )
                    }
                    Text(
                        text = stringResource(R.string.rule_always_subtitle, viewModel.audioPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Room for the button not to sit on top of the last card.
            item { Box(modifier = Modifier.size(72.dp)) }
        }

        // Centred, as drawn, rather than in the usual corner.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            FloatingActionButton(
                onClick = { viewModel.addRule { expandedId = it } },
                shape = FloatingActionButtonDefaults.largeShape,
            ) {
                Icon(Icons.Filled.Add, stringResource(R.string.rules_add))
            }
        }
    }

    moreFor?.let { rule ->
        MoreOptionsDialog(
            rule = rules.firstOrNull { it.id == rule.id } ?: rule,
            onChange = viewModel::saveInline,
            onDismiss = { moreFor = null },
        )
    }

    folderWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { viewModel.folderWarningShown() },
            title = { Text(stringResource(R.string.rule_folder_blocked_title)) },
            text = { Text(stringResource(R.string.rule_folder_blocked_body, warning.folder)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.folderWarningShown()
                    onOpenPermissions()
                }) { Text(stringResource(R.string.rule_open_permission)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.folderWarningShown() }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * The fields nobody touches twice, and the explanations.
 *
 * Kept off the card deliberately: a card has less room than a dialog, not
 * more, so inlining everything would have cost the per-field guidance that was
 * added precisely because it did not fit anywhere smaller.
 */
@Composable
private fun MoreOptionsDialog(
    rule: RoutingRule,
    onChange: (RoutingRule) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rule_more_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_match_type),
                    infoTitle = stringResource(R.string.info_match_title),
                    infoBody = stringResource(R.string.info_match_body),
                )
                MatchType.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(rule.copy(matchType = option)) }
                            .padding(vertical = 6.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = rule.matchType == option,
                            onClick = { onChange(rule.copy(matchType = option)) },
                        )
                        Text(stringResource(matchTypeLabel(option)))
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    LabelWithInfo(
                        label = stringResource(R.string.rule_label_include_moc),
                        infoTitle = stringResource(R.string.info_moc_title),
                        infoBody = stringResource(R.string.info_moc_body),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rule.includeInMoc,
                        onCheckedChange = { onChange(rule.copy(includeInMoc = it)) },
                    )
                }

                Text(
                    text = stringResource(R.string.rule_more_explanations),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_keyword),
                    infoTitle = stringResource(R.string.info_keyword_title),
                    infoBody = stringResource(R.string.info_keyword_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_action),
                    infoTitle = stringResource(R.string.info_action_title),
                    infoBody = stringResource(R.string.info_action_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_folder),
                    infoTitle = stringResource(R.string.info_folder_title),
                    infoBody = stringResource(R.string.info_folder_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_tags),
                    infoTitle = stringResource(R.string.info_tags_title),
                    infoBody = stringResource(R.string.info_tags_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_priority),
                    infoTitle = stringResource(R.string.info_priority_title),
                    infoBody = stringResource(R.string.info_priority_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_strip),
                    infoTitle = stringResource(R.string.info_strip_title),
                    infoBody = stringResource(R.string.info_strip_body),
                )
                LabelWithInfo(
                    label = stringResource(R.string.rule_label_enabled),
                    infoTitle = stringResource(R.string.info_enabled_title),
                    infoBody = stringResource(R.string.info_enabled_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private fun matchTypeLabel(type: MatchType) = when (type) {
    MatchType.CONTAINS_WORD -> R.string.match_contains_word
    MatchType.STARTS_WITH -> R.string.match_starts_with
    MatchType.CONTAINS_SUBSTRING -> R.string.match_contains_substring
}

/** White with a dark border, against the rules' filled cards. */
@Composable
private fun FixedCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        content()
    }
}


@Composable
private fun BoldLabel(text: String) {
    Text(
        text = "$text:",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 6.dp),
    )
}

/** A value that is what it is — shown in the same pill, not editable. */
@Composable
private fun StaticPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ReservedFolderDropdown(
    current: String,
    options: List<String>,
    resolved: (String?) -> String,
    onPick: (String) -> Unit,
    onChoose: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(resolved(current), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { name ->
                DropdownMenuItem(
                    text = { Text(resolved(name)) },
                    onClick = {
                        open = false
                        onPick(name)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_folder_choose_create)) },
                onClick = {
                    open = false
                    onChoose()
                },
            )
        }
    }
}
