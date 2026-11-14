package org.snrkl.indexrouter.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.routing.chainFor
import org.snrkl.indexrouter.routing.VaultMode
import org.snrkl.indexrouter.ui.common.GestureSelector
import org.snrkl.indexrouter.ui.theme.GroupColor

/**
 * What a recording from one gesture is actually checked against, in order.
 *
 * The list on the rules screen is grouped by ownership, and a common rule's
 * place among a gesture's rules is decided by priority, not by which group it
 * sits in. This shows the merged chain, numbered, from the same function
 * routing uses, so what is shown is what runs.
 */
@Composable
internal fun RunOrderDialog(
    rules: List<RoutingRule>,
    names: Map<RuleGroup, String>,
    colors: Map<RuleGroup, GroupColor>,
    mode: VaultMode,
    onDismiss: () -> Unit,
) {
    var gesture by remember { mutableStateOf(RuleGroup.SINGLE_CLICK_HOLD) }
    val chain = chainFor(rules, gesture, mode).filter { it.enabled && it.keywords.any(String::isNotBlank) }
    val gestureName = names[gesture] ?: gesture.defaultName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_order_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GestureSelector(
                    selected = gesture,
                    names = names,
                    colors = colors,
                    onSelect = { gesture = it },
                    label = stringResource(R.string.run_order_for),
                )
                Text(
                    if (mode.perGesture) stringResource(R.string.run_order_body, gestureName) else stringResource(R.string.run_order_body_one, gestureName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                chain.forEachIndexed { index, rule ->
                    ChainRow(
                        number = "${index + 1}.",
                        title = rule.keywords.filter(String::isNotBlank).joinToString(", ") { "“$it”" },
                        detail = stringResource(
                            R.string.run_order_detail,
                            rule.action.displayName,
                            whoseLabel(rule, mode, names),
                            rule.priority,
                        ),
                        // In one vault the group is not something to show:
                        // a rule is coloured by its gesture, or not at all.
                        color = if (mode.perGesture) colors[rule.group] else rule.gesture?.let { colors[it] },
                    )
                }
                if (chain.isEmpty()) {
                    Text(stringResource(R.string.run_order_empty), style = MaterialTheme.typography.bodySmall)
                }
                ChainRow(
                    number = "→",
                    title = if (mode.perGesture) {
                        stringResource(R.string.run_order_default, gestureName)
                    } else {
                        stringResource(R.string.run_order_default_one)
                    },
                    detail = stringResource(R.string.run_order_default_sub),
                    color = if (mode.perGesture) colors[gesture] else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Which group a rule is in, as the run order names it, with its gesture if it has one. */
@Composable
private fun whoseLabel(rule: RoutingRule, mode: VaultMode, names: Map<RuleGroup, String>): String {
    val gesture = rule.gesture?.let { names[it] ?: it.defaultName }
    return when {
        !mode.perGesture -> gesture ?: stringResource(R.string.gesture_any)
        gesture != null -> stringResource(R.string.run_order_group_gesture, names[rule.group] ?: rule.group.defaultName, gesture)
        else -> names[rule.group] ?: rule.group.defaultName
    }
}

@Composable
private fun ChainRow(number: String, title: String, detail: String, color: GroupColor?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color?.container ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(width = 4.dp, height = 36.dp)
                .background(color?.accent ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Text(
            text = number,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .width(28.dp),
        )
        Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = color?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = color?.onContainer ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
