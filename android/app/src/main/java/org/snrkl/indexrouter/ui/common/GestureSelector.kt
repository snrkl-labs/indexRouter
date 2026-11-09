package org.snrkl.indexrouter.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.theme.GroupColor

/** Which gesture the cards below it describe, each segment in that gesture's colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GestureSelector(
    selected: RuleGroup,
    names: Map<RuleGroup, String>,
    colors: Map<RuleGroup, GroupColor>,
    onSelect: (RuleGroup) -> Unit,
    label: String = stringResource(R.string.settings_vault_for),
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RuleGroup.TRIGGERS.forEachIndexed { index, group ->
                SegmentedButton(
                    selected = group == selected,
                    onClick = { onSelect(group) },
                    shape = SegmentedButtonDefaults.itemShape(index, RuleGroup.TRIGGERS.size),
                    colors = colors[group]?.let { c ->
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = c.container,
                            activeContentColor = c.onContainer,
                            inactiveContentColor = c.onContainer,
                        )
                    } ?: SegmentedButtonDefaults.colors(),
                    // No checkmark: it pushed a long gesture name off its
                    // segment. The selected one is filled and bold instead.
                    icon = {},
                ) {
                    Text(
                        text = names[group] ?: group.defaultName,
                        maxLines = 1,
                        fontWeight = if (group == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
