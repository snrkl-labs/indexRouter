package org.snrkl.indexrouter.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.snrkl.indexrouter.R

/**
 * A vault's copy of the common rules disagrees with the app's.
 *
 * Never resolved silently: a hand-edit in that vault and an edit in the app
 * are equally likely to be the one meant. No dismiss-by-tapping-outside,
 * because "neither" would leave the two copies disagreeing until the next
 * write quietly settles it.
 */
@Composable
internal fun CommonConflictDialog(
    groupName: String,
    onUseVault: () -> Unit,
    onKeepApp: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.common_conflict_title)) },
        text = { Text(stringResource(R.string.common_conflict_body, groupName)) },
        confirmButton = {
            TextButton(onClick = onUseVault) { Text(stringResource(R.string.common_conflict_use_vault)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepApp) { Text(stringResource(R.string.common_conflict_keep_app)) }
        },
    )
}
