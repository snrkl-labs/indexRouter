package org.snrkl.indexrouter.ui.rules

import androidx.compose.foundation.background
import org.snrkl.indexrouter.ui.theme.GroupColor
import org.snrkl.indexrouter.ui.theme.GroupPalette
import org.snrkl.indexrouter.ui.theme.rememberGroupColors
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.res.pluralStringResource
import org.snrkl.indexrouter.routing.RuleGroup
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
import androidx.compose.foundation.lazy.LazyListScope
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
    onChooseFolderFor: (group: RuleGroup, onPicked: (String) -> Unit) -> Unit,
) {
    val rules by viewModel.displayedRules.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val folderOptions by viewModel.folderOptions.collectAsStateWithLifecycle()
    val headers by viewModel.headers.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    val groupColors = rememberGroupColors(
        RuleGroup.entries.associateWith { headers[it]?.seed ?: GroupPalette.defaultSeed(it) },
    )
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val folderWarning by viewModel.folderWarning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showRunOrder by remember { mutableStateOf(false) }
    if (showRunOrder) {
        RunOrderDialog(
            rules = rules,
            names = RuleGroup.entries.associateWith { headers[it]?.name ?: it.defaultName },
            colors = groupColors,
            mode = mode,
            onDismiss = { showRunOrder = false },
        )
    }
    var moreFor by remember { mutableStateOf<RoutingRule?>(null) }
    val gestureNames = RuleGroup.TRIGGERS.associateWith { headers[it]?.name ?: it.defaultName }

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
        canDrop = viewModel::sameGroup,
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
                actions = {
                    TextButton(onClick = { showRunOrder = true }) {
                        Text(stringResource(R.string.action_run_order))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // One rule card, wherever it sits. [vault] is the group whose vault
        // its folders resolve in.
        @Composable
        fun RuleItem(rule: RoutingRule, vault: RuleGroup, color: GroupColor?, gestures: Map<RuleGroup, String>?) {
            val dragging = reorder.draggingKey == rule.id
            Box(
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(0, reorder.offsetFor(rule.id).toInt()) },
            ) {
                RuleCard(
                    rule = rule,
                    expanded = expandedId == rule.id,
                    folderOptions = folderOptions[vault].orEmpty(),
                    resolvedFolder = { viewModel.resolvedFolder(vault, it) },
                    fixedFolderFor = { viewModel.fixedFolderFor(vault, it) },
                    inheritedTags = if (vault.isTrigger) {
                        viewModel.profile(vault).taskTags.joinToString(", ")
                    } else {
                        ""
                    },
                    onExpandToggle = {
                        expandedId = if (expandedId == rule.id) null else rule.id
                    },
                    onChange = viewModel::saveInline,
                    onDelete = { viewModel.delete(rule.id) },
                    onChooseFolder = {
                        onChooseFolderFor(vault) { segment ->
                            viewModel.saveInline(rule.copy(folder = segment))
                        }
                    },
                    onMore = { moreFor = rule },
                    groupColor = color,
                    // In one vault any type can follow any other: the type
                    // decides the group. With a vault per gesture, a rule
                    // stays the kind its group holds.
                    allowedTypes = if (mode.perGesture) ({ rule.group.allows(it) }) else ({ true }),
                    gestureNames = gestures,
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

        // The default route and the recordings folder of one vault.
        fun LazyListScope.fixedCards(group: RuleGroup) {
            val profile = viewModel.profile(group)
            val folders = folderOptions[group].orEmpty()
            item(key = "default-${group.wireName}") {
                // Distinct on purpose: these two are not rules. The default
                // is what happens when nothing matched, and recordings
                // happen whatever matched, so neither has a keyword, a
                // priority or a place in the order.
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
                            current = profile.fallbackDirName,
                            options = folders,
                            resolved = { viewModel.resolvedFolder(group, it) },
                            onPick = { viewModel.saveDefaultRoute(group, it) },
                            onChoose = { onChooseFolderFor(group) { viewModel.saveDefaultRoute(group, it) } },
                        )
                    }
                }
            }

            item(key = "audio-${group.wireName}") {
                FixedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoldLabel(stringResource(R.string.rule_recordings_label))
                        ReservedFolderDropdown(
                            current = profile.audioDirName,
                            options = folders,
                            resolved = { viewModel.resolvedFolder(group, it) },
                            onPick = { viewModel.saveAudioFolder(group, it) },
                            onChoose = { onChooseFolderFor(group) { viewModel.saveAudioFolder(group, it) } },
                        )
                    }
                    Text(
                        text = stringResource(R.string.rule_always_subtitle, profile.audioPath()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!mode.perGesture) {
                // One vault: one list, in the order it runs, with no groups.
                // A rule limited to a gesture wears that gesture's colour.
                val home = mode.home
                val inUse = rules.filter { mode.inUse(it) }
                item(key = "one-vault-header") {
                    OneVaultHeader(
                        vault = headers[home]?.vault,
                        onAdd = { viewModel.addRule(home) { expandedId = it } },
                    )
                }
                items(inUse, key = { it.id }) { rule ->
                    RuleItem(
                        rule = rule,
                        vault = home,
                        color = rule.gesture?.let { groupColors[it] },
                        gestures = gestureNames,
                    )
                }
                fixedCards(home)
                item { Box(modifier = Modifier.size(24.dp)) }
                return@LazyColumn
            }

            // A vault per gesture: the common group, then each gesture's own
            // rules, its default route and its recordings.
            RuleGroup.entries.forEach { group ->
                val isCollapsed = group.wireName in collapsed
                val inGroup = rules.filter { it.group == group }
                // The carried-over vault appears only while it has rules waiting.
                if (group == RuleGroup.CARRIED_OVER && inGroup.isEmpty()) return@forEach
                item(key = "header-${group.wireName}") {
                    GroupHeaderRow(
                        name = headers[group]?.name ?: group.defaultName,
                        vault = headers[group]?.vault,
                        group = group,
                        color = groupColors[group],
                        count = inGroup.size,
                        collapsed = isCollapsed,
                        onToggle = {
                            collapsed = if (isCollapsed) collapsed - group.wireName else collapsed + group.wireName
                        },
                        onAdd = {
                            collapsed = collapsed - group.wireName
                            viewModel.addRule(group) { expandedId = it }
                        },
                    )
                }
                if (isCollapsed) return@forEach

                items(inGroup, key = { it.id }) { rule ->
                    RuleItem(
                        rule = rule,
                        vault = group,
                        color = groupColors[group],
                        // Only the common rules choose a gesture; a vault's
                        // own rules answer the gesture the vault is for.
                        gestures = if (group == RuleGroup.COMMON) gestureNames else null,
                    )
                }

                if (group.isTrigger) fixedCards(group)
            }

            item { Box(modifier = Modifier.size(24.dp)) }
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

/** The one-vault list's heading: where everything files, and the one add action. */
@Composable
private fun OneVaultHeader(vault: String?, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.rules_one_vault_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (vault != null) {
                    stringResource(R.string.rules_one_vault_sub, vault)
                } else {
                    stringResource(R.string.group_no_vault_sub)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (vault == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, stringResource(R.string.rules_add))
        }
    }
}

/**
 * A group's header: its name, what it writes to, and its own add action.
 *
 * Each group adds its own rules so a new rule can only ever be a type that
 * group allows — a note rule cannot be created among the common ones, where it
 * would have no vault to write to.
 */
@Composable
private fun GroupHeaderRow(
    name: String,
    vault: String?,
    group: RuleGroup,
    color: GroupColor?,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (collapsed) R.string.group_expand else R.string.group_collapse),
        )
        // The group's colour, beside its name — the name is always there, so
        // the colour is never the only way to tell the groups apart.
        if (color != null) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(width = 6.dp, height = 36.dp)
                    .background(color.accent, RoundedCornerShape(3.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    group == RuleGroup.CARRIED_OVER -> stringResource(R.string.group_carried_over_sub)
                    !group.isTrigger -> stringResource(R.string.group_common_sub)
                    vault != null -> stringResource(R.string.group_vault_sub, vault)
                    else -> stringResource(R.string.group_no_vault_sub)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (group.hasVault && vault == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (collapsed) {
                Text(
                    text = pluralStringResource(R.plurals.group_rule_count, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (group != RuleGroup.CARRIED_OVER) FilledTonalIconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, stringResource(R.string.group_add_rule, name))
        }
    }
}
