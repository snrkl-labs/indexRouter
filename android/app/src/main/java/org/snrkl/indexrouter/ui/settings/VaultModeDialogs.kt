package org.snrkl.indexrouter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.ui.common.CardSwitchRow
import org.snrkl.indexrouter.ui.common.SettingCard

/**
 * The first card in Settings: one vault, or a vault per gesture.
 *
 * The switch never flips by itself. Turning it either way opens the guided
 * flow for that direction, and only confirming the flow changes the mode.
 */
@Composable
internal fun VaultModeCard(
    state: SettingsUiState,
    onRequestOn: () -> Unit,
    onRequestOff: () -> Unit,
    onOpenVaults: () -> Unit,
) {
    SettingCard(stringResource(R.string.vault_mode_card)) {
        CardSwitchRow(
            label = stringResource(R.string.vault_mode_switch),
            checked = state.perGesture,
            onCheckedChange = { wanted -> if (wanted) onRequestOn() else onRequestOff() },
            note = stringResource(
                if (state.perGesture) R.string.vault_mode_on_sub else R.string.vault_mode_off_sub,
            ),
        )
        TextButton(onClick = onOpenVaults) { Text(stringResource(R.string.vault_mode_open_vaults)) }
    }
}

/**
 * One vault → a vault per gesture: which gesture keeps the vault, where the
 * rules go, then confirm. The other gesture's vault is chosen afterwards, on
 * the Vaults & gestures screen, or later.
 */
@Composable
internal fun TurnOnDialog(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onConfirm: (keptBy: RuleGroup, copyEitherToBoth: Boolean, chooseNow: Boolean) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var keptBy by remember { mutableStateOf(state.home) }
    var copyToBoth by remember { mutableStateOf(false) }
    val other = keptBy.other!!
    fun name(g: RuleGroup) = state.groupNames[g] ?: g.defaultName
    val vaultName = state.vaultNames[state.home] ?: stringResource(R.string.vault_root_unset)

    val inVault = state.rules.filter { it.group == state.home }
    val common = state.rules.filter { it.group == RuleGroup.COMMON }
    val toOther = inVault.filter { it.gesture == other }
    val either = inVault.filter { it.gesture == null }
    val toKept = inVault.filter { it.gesture == keptBy }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_mode_on_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.vault_mode_on_keep, vaultName))
                        RuleGroup.TRIGGERS.forEach { g ->
                            Choice(name(g), selected = keptBy == g) { keptBy = g }
                        }
                        Text(
                            stringResource(R.string.vault_mode_on_keep_note, name(keptBy.other!!)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    1 -> {
                        Text(stringResource(R.string.vault_mode_on_rules), fontWeight = FontWeight.Bold)
                        PreviewLine(
                            pluralStringResource(R.plurals.vault_mode_preview_common, common.size, common.size),
                            common,
                        )
                        if (toOther.isNotEmpty()) {
                            PreviewLine(
                                pluralStringResource(R.plurals.vault_mode_preview_other, toOther.size, toOther.size, name(other)),
                                toOther,
                            )
                        }
                        if (toKept.isNotEmpty()) {
                            PreviewLine(
                                pluralStringResource(R.plurals.vault_mode_preview_kept, toKept.size, toKept.size, name(keptBy), vaultName),
                                toKept,
                            )
                        }
                        if (either.isNotEmpty()) {
                            PreviewLine(
                                pluralStringResource(R.plurals.vault_mode_preview_either, either.size, either.size),
                                either,
                            )
                            Choice(stringResource(R.string.vault_mode_either_stay, name(keptBy)), selected = !copyToBoth) {
                                copyToBoth = false
                            }
                            Choice(stringResource(R.string.vault_mode_either_copy), selected = copyToBoth) {
                                copyToBoth = true
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.vault_mode_on_summary, vaultName, name(keptBy), name(other)))
                        Text(
                            stringResource(R.string.vault_mode_on_later_note, name(other), vaultName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onConfirm(keptBy, copyToBoth, true) }) {
                            Text(stringResource(R.string.vault_mode_on_choose_now, name(other)))
                        }
                        TextButton(onClick = { onConfirm(keptBy, copyToBoth, false) }) {
                            Text(stringResource(R.string.vault_mode_on_choose_later))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step < 2) {
                TextButton(onClick = { step++ }) { Text(stringResource(R.string.action_next)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 0) onDismiss() else step-- }) {
                Text(stringResource(if (step == 0) android.R.string.cancel else R.string.action_previous))
            }
        },
    )
}

/**
 * A vault per gesture → one vault: which vault to keep, and what to do with
 * the other's rules. Nothing in the other vault is touched either way.
 */
@Composable
internal fun TurnOffDialog(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onConfirm: (keep: RuleGroup, bringAcross: Boolean) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var keep by remember {
        mutableStateOf(RuleGroup.TRIGGERS.firstOrNull { state.vaultNames[it] != null } ?: RuleGroup.SINGLE_CLICK_HOLD)
    }
    var bring by remember { mutableStateOf(false) }
    val other = keep.other!!
    fun name(g: RuleGroup) = state.groupNames[g] ?: g.defaultName
    val unset = stringResource(R.string.vault_root_unset)
    fun vault(g: RuleGroup) = state.vaultNames[g] ?: unset
    val theirs = state.rules.filter { it.group == other }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_mode_off_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.vault_mode_off_keep))
                        RuleGroup.TRIGGERS.forEach { g ->
                            Choice(stringResource(R.string.vault_mode_off_keep_option, vault(g), name(g)), selected = keep == g) {
                                keep = g
                            }
                        }
                    }
                    else -> {
                        Text(
                            pluralStringResource(R.plurals.vault_mode_off_theirs, theirs.size, vault(other), theirs.size),
                            fontWeight = FontWeight.Bold,
                        )
                        if (theirs.isNotEmpty()) PreviewLine(null, theirs)
                        Choice(stringResource(R.string.vault_mode_off_leave), selected = !bring) { bring = false }
                        Choice(stringResource(R.string.vault_mode_off_bring, name(other)), selected = bring) { bring = true }
                        Text(
                            stringResource(R.string.vault_mode_off_note, vault(other)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (step == 0) {
                TextButton(onClick = { step = 1 }) { Text(stringResource(R.string.action_next)) }
            } else {
                TextButton(onClick = { onConfirm(keep, bring) }) { Text(stringResource(R.string.vault_mode_off_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 0) onDismiss() else step = 0 }) {
                Text(stringResource(if (step == 0) android.R.string.cancel else R.string.action_previous))
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One line of the preview, with the rules it covers named by their keywords. */
@Composable
private fun PreviewLine(heading: String?, rules: List<RoutingRule>) {
    Column {
        if (heading != null) Text(heading, style = MaterialTheme.typography.bodyMedium)
        if (rules.isNotEmpty()) {
            Text(
                text = rules.joinToString(", ") { r ->
                    r.keywords.firstOrNull()?.let { "“$it”" } ?: "—"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
