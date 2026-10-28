package org.snrkl.indexrouter.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One line of chips, showing as many as actually fit and counting the rest.
 *
 * Measured rather than guessed. A fixed "show three" both wastes the line on a
 * rule with one keyword and wraps on a rule with three long ones, and chip
 * widths vary with the words in them, so the only honest answer to "how many
 * fit" is to measure them against the width available.
 *
 * The overflow chip is measured too, for the count it will actually show: a
 * `+1` is narrower than a `+12`, and reserving a guessed width would either
 * clip or leave a gap.
 */
@Composable
internal fun <T> OverflowChipRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    chip: @Composable (T) -> Unit,
    overflow: @Composable (hidden: Int) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val available = constraints.maxWidth
        val gap = spacing.roundToPx()

        val chipMeasurables = subcompose("chips") {
            items.forEach { chip(it) }
        }
        val loose = Constraints(maxWidth = available, maxHeight = constraints.maxHeight)
        val chipPlaceables = chipMeasurables.map { it.measure(loose) }

        // Widest case first: everything fits and no counter is needed.
        val allWidth = chipPlaceables.sumOf { it.width } + gap * (chipPlaceables.size - 1).coerceAtLeast(0)

        var shown = chipPlaceables.size
        var overflowPlaceable: androidx.compose.ui.layout.Placeable? = null

        if (allWidth > available) {
            // Walk down until the visible chips plus the counter for what is
            // left both fit. Measured per candidate count, because the counter's
            // own width moves with the number in it.
            shown = chipPlaceables.size
            while (shown > 0) {
                shown--
                val hidden = chipPlaceables.size - shown
                val counter = subcompose("overflow$hidden") { overflow(hidden) }
                    .firstOrNull()?.measure(loose) ?: break
                val used = chipPlaceables.take(shown).sumOf { it.width } +
                    gap * shown + // one gap before each visible chip's successor, plus one before the counter
                    counter.width
                if (used <= available) {
                    overflowPlaceable = counter
                    break
                }
            }
            if (overflowPlaceable == null && shown == 0) {
                // Not even the counter alone fits; show it anyway rather than
                // silently rendering nothing, since "there are keywords you
                // cannot see" is the one thing this row must not hide.
                overflowPlaceable = subcompose("overflowAll") { overflow(chipPlaceables.size) }
                    .firstOrNull()?.measure(loose)
            }
        }

        val visible = chipPlaceables.take(shown)
        val height = (visible + listOfNotNull(overflowPlaceable)).maxOfOrNull { it.height } ?: 0

        layout(available, height) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
            overflowPlaceable?.placeRelative(x, (height - overflowPlaceable.height) / 2)
        }
    }
}
