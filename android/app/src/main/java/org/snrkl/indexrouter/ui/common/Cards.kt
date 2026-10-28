package org.snrkl.indexrouter.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The card vocabulary both configuration screens are built from.
 *
 * Shared rather than duplicated because the two screens are meant to look like
 * one app: a label is bold with a colon, a value sits in a white pill, and a
 * group of them sits on a tinted card. Keeping private copies per screen is how
 * they drift into looking almost the same.
 */

/** A titled group of settings, filled, in the rules-card tint. */
@Composable
internal fun SettingCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/**
 * White with a dark border, for the things that are not choices.
 *
 * On the rules screen that is the default route and the recordings folder; on
 * settings it is the vault, which is granted rather than configured.
 */
@Composable
internal fun OutlinedCard(content: @Composable () -> Unit) {
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
internal fun CardLabel(text: String, enabled: Boolean = true) {
    Text(
        text = "$text:",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(end = 6.dp)
            .alpha(if (enabled) 1f else 0.5f),
    )
}

/** The white rounded control every value on a card sits in. */
@Composable
internal fun Pill(
    text: String,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    modifier: Modifier = Modifier,
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
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (dropdown) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
    }
}

/** A pill that opens a menu, with an optional action pinned below the options. */
@Composable
internal fun PillDropdown(
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    enabled: Boolean = true,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Pill(text = value, onClick = { open = true }, enabled = enabled, dropdown = true)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
            if (extraLabel != null && onExtra != null) {
                if (options.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(extraLabel) },
                    onClick = {
                        open = false
                        onExtra()
                    },
                )
            }
        }
    }
}

/** Label on the left, value pill on the right, the whole row tappable. */
@Composable
internal fun CardValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    note: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardLabel(label)
            Box(modifier = Modifier.weight(1f))
            Pill(text = value, onClick = onClick)
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Label on the left, switch on the right, the whole row tappable. */
@Composable
internal fun CardSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
