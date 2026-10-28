package org.snrkl.indexrouter.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.snrkl.indexrouter.R

/**
 * A field label with the **(i)** that opens its real explanation.
 *
 * The explanation lives in a dialog rather than in hint text because the fields
 * are too narrow to show a sentence, and a hint disappears the moment the field
 * holds a value — which is exactly when someone is most likely to want it.
 */
@Composable
internal fun LabelWithInfo(
    label: String,
    infoTitle: String,
    infoBody: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showing by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.4f),
        )
        IconButton(onClick = { showing = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = stringResource(R.string.info_content_description, label),
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showing) {
        InfoDialog(title = infoTitle, body = infoBody, onDismiss = { showing = false })
    }
}

@Composable
internal fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * Keywords as chips rather than a comma-separated string.
 *
 * The underlying storage is still a list; this only changes how it is entered
 * and read. Typing a comma commits the chip, as does the keyboard's Done
 * action — the comma is kept as a separator because that is what the field
 * accepted before, and muscle memory is worth more than consistency here. Text
 * left uncommitted when the editor is saved is taken as a final keyword, so a
 * word typed but not "entered" is not silently dropped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordChips(
    keywords: List<String>,
    onChange: (List<String>) -> Unit,
    pending: String,
    onPendingChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?,
    modifier: Modifier = Modifier,
) {
    fun commit(raw: String) {
        val added = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (added.isEmpty()) return
        onChange((keywords + added).distinct())
        onPendingChange("")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keywords.forEach { keyword ->
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { onChange(keywords - keyword) },
                        label = { Text(keyword) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.keyword_remove, keyword),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = pending,
            onValueChange = { typed ->
                // A comma is the commit gesture, so it never lands in the field.
                if (typed.endsWith(',')) commit(typed) else onPendingChange(typed)
            },
            enabled = enabled,
            isError = isError,
            singleLine = true,
            label = { Text(stringResource(R.string.rule_keyword_hint)) },
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(pending) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}



