package org.snrkl.indexrouter.ui.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.ui.common.OverflowChipRow
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.Tags

/**
 * One rule, edited on the card rather than in a dialog.
 *
 * Collapsed it reads as a summary — type, priority, destination, the keywords
 * that fire it — and every one of those is also the control that changes it.
 * Expanding opens the keyword panel, where the whole set is visible and
 * editable rather than counted.
 *
 * The rarely-touched fields (match type, whether the folder appears in the
 * generated map) stay behind **More…**, along with the per-field explanations:
 * a card has less room than a dialog, not more, so putting everything inline
 * would have cost the guidance added for exactly that reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleCard(
    rule: RoutingRule,
    expanded: Boolean,
    folderOptions: List<String>,
    resolvedFolder: (String?) -> String,
    fixedFolderFor: (ActionType) -> String?,
    inheritedTags: String,
    onExpandToggle: () -> Unit,
    onChange: (RoutingRule) -> Unit,
    onDelete: () -> Unit,
    onChooseFolder: () -> Unit,
    onMore: () -> Unit,
    /** The drag handle, supplied by the list so it can own the gesture. */
    handle: @Composable () -> Unit = {},
) {
    SwipeToDelete(onDelete = onDelete) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(16.dp),
                )
                // A disabled rule dims whole, chips included: it is still in the
                // chain and still in its place in the order, it just does not
                // fire, and a half-dimmed card would read as a rendering bug.
                .alpha(if (rule.enabled) 1f else 0.45f)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                handle()
                FieldLabel(stringResource(R.string.rule_label_action))
                TypeDropdown(
                    selected = rule.action,
                    onPick = { onChange(rule.copy(action = it)) },
                    modifier = Modifier.padding(end = 12.dp),
                )
                FieldLabel(stringResource(R.string.rule_label_priority))
                PriorityField(
                    value = rule.priority,
                    onCommit = { onChange(rule.copy(priority = it)) },
                )
                Box(modifier = Modifier.weight(1f))
                val turn by animateFloatAsState(if (expanded) 0f else 90f, label = "chevron")
                IconButton(onClick = onExpandToggle) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.rule_collapse else R.string.rule_expand,
                        ),
                        modifier = Modifier.rotate(turn),
                    )
                }
            }

            if (expanded) {
                // Only where a folder means anything: a timer and a calendar
                // insert write no file, and a disabled empty control said
                // nothing while costing a line.
                if (rule.action.usesFolder || fixedFolderFor(rule.action)?.isNotEmpty() == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldLabel(stringResource(R.string.rule_label_folder))
                        val fixed = fixedFolderFor(rule.action)
                        FolderDropdown(
                            // A fixed destination is shown rather than an empty
                            // control: a task rule always writes to the same
                            // place, and that place is worth knowing without
                            // going hunting for it.
                            value = fixed ?: rule.folder?.let(resolvedFolder),
                            enabled = fixed == null && rule.action.usesFolder,
                            options = folderOptions,
                            resolvedFolder = resolvedFolder,
                            onPick = { onChange(rule.copy(folder = it)) },
                            onChoose = onChooseFolder,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                KeywordPanel(
                    keywords = rule.keywords,
                    onChange = { onChange(rule.copy(keywords = it)) },
                )
                if (rule.action.usesTags) {
                    TagsField(
                        tags = rule.tags,
                        inherited = inheritedTags,
                        onChange = { onChange(rule.copy(tags = it)) },
                    )
                }
                // Expanded only, as asked: it is a decision made once per rule,
                // not something read at a glance.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FieldLabel(stringResource(R.string.rule_label_strip))
                    Switch(
                        checked = rule.stripKeyword,
                        onCheckedChange = { onChange(rule.copy(stripKeyword = it)) },
                    )
                    Text(
                        text = stringResource(R.string.rule_strip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FieldLabel(stringResource(R.string.rule_label_enabled))
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onChange(rule.copy(enabled = it)) },
                    )
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = onMore) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                        Text(
                            text = stringResource(R.string.rule_more),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            } else {
                // Collapsed: the chips and the toggle share one line, and the
                // chips take whatever width the toggle leaves. Two lines total.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CollapsedKeywords(
                        keywords = rule.keywords,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onChange(rule.copy(enabled = it)) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Keywords at a glance: as many chips as fit on the line, then a count.
 *
 * Measured rather than a fixed number — see [OverflowChipRow]. A rule with six
 * ways of saying the same thing must not make its row taller than the card, and
 * a rule with one must not waste the space.
 */
@Composable
private fun CollapsedKeywords(keywords: List<String>, modifier: Modifier = Modifier) {
    if (keywords.isEmpty()) {
        Text(
            text = stringResource(R.string.rule_no_keywords),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        return
    }
    OverflowChipRow(
        items = keywords,
        modifier = modifier,
        chip = { KeywordChip(it) },
        overflow = { hidden -> KeywordChip(stringResource(R.string.rule_keyword_overflow, hidden)) },
    )
}

/** The whole set, editable, with somewhere to add one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordPanel(keywords: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }

    fun commit() {
        val added = pending.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (added.isNotEmpty()) onChange((keywords + added).distinct())
        pending = ""
        adding = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.rule_keywords_heading),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keywords.forEach { keyword ->
                KeywordChip(
                    label = keyword,
                    onRemove = { onChange(keywords - keyword) },
                )
            }
            if (!adding) {
                SuggestionChip(
                    onClick = { adding = true },
                    label = { Text(stringResource(R.string.rule_add_keyword)) },
                    icon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
        if (adding) {
            OutlinedTextField(
                value = pending,
                onValueChange = { typed ->
                    // A comma commits, as it did when this was one text field —
                    // muscle memory is worth more here than consistency.
                    if (typed.endsWith(',')) {
                        pending = typed.dropLast(1)
                        commit()
                        adding = true
                    } else {
                        pending = typed
                    }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.rule_keyword_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun KeywordChip(label: String, onRemove: (() -> Unit)? = null) {
    AssistChip(
        onClick = { onRemove?.invoke() },
        enabled = onRemove != null,
        label = {
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        trailingIcon = onRemove?.let {
            {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.keyword_remove, label),
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.onPrimary,
            trailingIconContentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary,
            disabledLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = null,
    )
}

/**
 * Tags, shown in the canonical hashed form whatever was typed.
 *
 * Normalised on the way out rather than as each character lands, so the hash is
 * not inserted under the cursor mid-word — what is stored is canonical, and the
 * field catches up when focus leaves.
 */
@Composable
private fun TagsField(tags: List<String>, inherited: String, onChange: (List<String>) -> Unit) {
    var text by remember(tags) { mutableStateOf(tags.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(Tags.canonical(it.split(',')))
        },
        singleLine = true,
        label = { Text(stringResource(R.string.rule_label_tags)) },
        placeholder = { Text(inherited) },
        supportingText = if (tags.isEmpty() && inherited.isNotBlank()) {
            { Text(stringResource(R.string.rule_tags_inherited)) }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = "$text:",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun TypeDropdown(
    selected: ActionType,
    onPick: (ActionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        PillBox(onClick = { open = true }) {
            Text(selected.displayName, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * Folders already under the base folder, plus a way to make a new one.
 *
 * The list is what the app can see; **Choose / create…** hands over to the
 * system picker, which is what guarantees the folder is inside the granted
 * tree. What gets stored is still a single name — the dropdown shows the
 * resolved path only so the destination is readable.
 */
@Composable
private fun FolderDropdown(
    value: String?,
    enabled: Boolean,
    options: List<String>,
    resolvedFolder: (String?) -> String,
    onPick: (String) -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        PillBox(onClick = { if (enabled) open = true }, enabled = enabled) {
            Text(
                text = value ?: stringResource(R.string.rule_folder_select),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { name ->
                DropdownMenuItem(
                    text = { Text(resolvedFolder(name)) },
                    onClick = {
                        open = false
                        onPick(name)
                    },
                )
            }
            if (options.isNotEmpty()) HorizontalDivider()
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

@Composable
private fun PriorityField(value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    PillBox(onClick = null, modifier = Modifier.width(44.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter(Char::isDigit).take(4)
                // Committed as typed rather than on focus loss: a card has no
                // Save, so a value left uncommitted would simply be lost.
                text.toIntOrNull()?.let(onCommit)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The white rounded control the mock-up uses for every value on a card. */
@Composable
private fun PillBox(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

/**
 * Swipe a card sideways to reveal Delete.
 *
 * Hand-rolled rather than using the Material swipe-to-dismiss container,
 * because dismissal is the wrong model here: the card must **stay put** with
 * the action revealed until it is tapped or the swipe is undone. A container
 * that removes the item on a completed swipe would delete a rule on a stray
 * gesture, and a rule is not cheap to retype.
 */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val revealPx = with(LocalDensity.current) { REVEAL_WIDTH.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // The action sits on the left and the card moves right, as drawn.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (offset > 0f) REVEAL_HEIGHT else 0.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDelete, modifier = Modifier.padding(start = 8.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.rule_delete),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // Snaps open or shut: half-revealed is neither a
                            // readable card nor a reachable button.
                            offset = if (offset > revealPx / 2) revealPx else 0f
                        },
                    ) { _, delta ->
                        offset = (offset + delta).coerceIn(0f, revealPx)
                    }
                },
        ) {
            content()
        }
    }
}

private val REVEAL_WIDTH = 140.dp
private val REVEAL_HEIGHT = 120.dp
