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
    onDismiss: () -> Unit,
) {
    var gesture by remember { mutableStateOf(RuleGroup.SINGLE_CLICK_HOLD) }
    val chain = chainFor(rules, gesture).filter { it.enabled && it.keywords.any(String::isNotBlank) }
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
                    stringResource(R.string.run_order_body, gestureName),
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
                            names[rule.group] ?: rule.group.defaultName,
                            rule.priority,
                        ),
                        color = colors[rule.group],
                    )
                }
                if (chain.isEmpty()) {
                    Text(stringResource(R.string.run_order_empty), style = MaterialTheme.typography.bodySmall)
                }
                ChainRow(
                    number = "→",
                    title = stringResource(R.string.run_order_default, gestureName),
                    detail = stringResource(R.string.run_order_default_sub),
                    color = colors[gesture],
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
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
