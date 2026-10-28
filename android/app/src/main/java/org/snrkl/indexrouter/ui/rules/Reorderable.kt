package org.snrkl.indexrouter.ui.rules

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a [LazyListState], driven from a handle rather than from
 * the whole row.
 *
 * A handle, not a long-press on the card, because the card is now covered in
 * controls: a long-press anywhere on it would fight the dropdowns, the switch
 * and the swipe-to-delete gesture already living there.
 *
 * Positions come from [LazyListState.layoutInfo] rather than from an assumed
 * row height. The cards genuinely differ in height — an expanded one is roughly
 * three times a collapsed one — so arithmetic on a fixed size would drop a card
 * in the wrong place exactly when the list was most interesting.
 */
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    /** Which items may be dragged; the fixed cards at the end may not. */
    private val isDraggable: (LazyListItemInfo) -> Boolean,
    /** Called as the dragged item passes another, to reflect it immediately. */
    private val onMove: (from: Int, to: Int) -> Unit,
    /** Called once on release, with the order settled. */
    private val onSettled: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    private var draggingIndex: Int? = null
    private var itemTop = 0f
    private var itemHeight = 0
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** Pixels to shift the dragged card by, or 0 for every other card. */
    fun offsetFor(key: Any?): Float = if (key == draggingKey) dragOffset else 0f

    fun onDragStart(key: Any) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (!isDraggable(info)) return
        draggingKey = key
        draggingIndex = info.index
        itemTop = info.offset.toFloat()
        itemHeight = info.size
        dragOffset = 0f
    }

    fun onDrag(delta: Float) {
        val from = draggingIndex ?: return
        dragOffset += delta

        // The centre of the card as dragged, in the list's own coordinates.
        val centre = itemTop + dragOffset + itemHeight / 2f
        val target = listState.layoutInfo.visibleItemsInfo
            .filter { isDraggable(it) && it.index != from }
            .firstOrNull { centre.toInt() in it.offset..(it.offset + it.size) }
            ?: return

        onMove(from, target.index)
        // The list has re-laid out around the move, so the drag continues from
        // the new home rather than snapping back to where the finger started.
        draggingIndex = target.index
        itemTop = target.offset.toFloat()
        dragOffset = centre - itemHeight / 2f - target.offset
    }

    fun onDragEnd() {
        draggingKey = null
        draggingIndex = null
        dragOffset = 0f
        // Renumbering happens once, on release, rather than on every crossing:
        // a drag past four cards should be one write, not four.
        scope.launch { onSettled() }
    }
}

@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    scope: CoroutineScope,
    isDraggable: (LazyListItemInfo) -> Boolean,
    onMove: (Int, Int) -> Unit,
    onSettled: () -> Unit,
): ReorderState = remember(listState) {
    ReorderState(listState, scope, isDraggable, onMove, onSettled)
}

/** Put this on the handle, not the card. */
internal fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier =
    this.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(key) },
            onDrag = { _, amount -> state.onDrag(amount.y) },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
