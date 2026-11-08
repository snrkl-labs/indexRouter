package org.snrkl.indexrouter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.RuleGroup

/** A group's colours in the current theme. */
internal data class GroupColor(val container: Color, val onContainer: Color, val accent: Color)

/** Each group's seed: what the user picked, or the group's default. */
internal fun SettingsStore.groupSeeds(): Map<RuleGroup, Int> =
    RuleGroup.entries.associateWith { groupSeed(it) ?: GroupPalette.defaultSeed(it) }

/**
 * The colours for every group, derived from their seeds against the theme in
 * force — dynamic or fallback, light or dark — so they sit inside the palette
 * rather than on top of it. See [Tonal] for the derivation and its guarantees.
 */
@Composable
internal fun rememberGroupColors(seeds: Map<RuleGroup, Int>): Map<RuleGroup, GroupColor> {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val primary = scheme.primary.toArgb()
    val error = scheme.error.toArgb()
    return remember(seeds, dark, primary, error) {
        seeds.mapValues { (_, seed) ->
            val t = Tonal.tones(seed, primary, dark, error)
            GroupColor(Color(t.container), Color(t.onContainer), Color(t.accent))
        }
    }
}
