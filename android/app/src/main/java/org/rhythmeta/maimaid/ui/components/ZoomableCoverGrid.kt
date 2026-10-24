package org.rhythmeta.maimaid.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.ui.catalog.CatalogGridMotion
import org.rhythmeta.maimaid.ui.catalog.CatalogPhotoGridState
import org.rhythmeta.maimaid.ui.catalog.PhotoGridAnchor
import org.rhythmeta.maimaid.ui.catalog.catalogGridColumns
import org.rhythmeta.maimaid.ui.catalog.settledZoom

private data object CoverGridHeaderKey

/** Shared pinch/placement behavior for the catalog, score query, and collections grids. */
@Composable
internal fun <T> ZoomableCoverGrid(
    modifier: Modifier,
    contentTopPadding: Dp,
    items: List<T>,
    state: CatalogPhotoGridState,
    itemKey: (T) -> String,
    contentBottomPadding: Dp = 96.dp,
    header: (@Composable () -> Unit)? = null,
    itemContent: @Composable (item: T, columns: Int, imageSize: Int, filler: Boolean) -> Unit,
) {
    val itemKeys = remember(items, itemKey) { items.map(itemKey) }
    val scope = rememberCoroutineScope()
    val motion = remember(state, scope) { CatalogGridMotion(state, scope) }
    val scrollState = rememberScrollableState(state::scrollBy)
    val layoutState = remember { SubcomposeLayoutState(SubcomposeSlotReusePolicy(10)) }
    val zoomIn = stringResource(R.string.grid_zoom_in)
    val zoomOut = stringResource(R.string.grid_zoom_out)
    fun centerAnchor() = state.geometry?.let { state.anchorAt(Offset(it.width / 2f, state.viewportHeight / 2f)) }
    DisposableEffect(motion) { onDispose { motion.interrupt() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        motion.settle(centerAnchor())
    }
    SubcomposeLayout(
        state = layoutState,
        modifier = modifier.fillMaxSize().clipToBounds()
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange({ state.scrollOffset }, { state.maxScroll })
                val count = catalogGridColumns(state.zoom)
                collectionInfo = CollectionInfo((itemKeys.size + count - 1) / count, count)
                scrollBy { _, y -> state.scrollBy(-y); true }
                scrollToIndex { index -> state.requestScrollToItem(index); true }
                customActions = buildList {
                    if (state.zoom < 2f) add(CustomAccessibilityAction(zoomIn) {
                        motion.settle(centerAnchor(), (settledZoom(state.zoom) + 1f).coerceAtMost(2f)); true
                    })
                    if (state.zoom > 1f) add(CustomAccessibilityAction(zoomOut) {
                        motion.settle(centerAnchor(), (settledZoom(state.zoom) - 1f).coerceAtLeast(1f)); true
                    })
                }
            }
            .pointerInput(state, motion) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    motion.interrupt()
                    var pinching = false
                    var anchor: PhotoGridAnchor? = null
                    var focus = down.position
                    var transformPointers = emptySet<PointerId>()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointers = event.changes.filter { it.pressed }.mapTo(mutableSetOf()) { it.id }
                        if (pointers.size >= 2) {
                            val centroid = event.calculateCentroid()
                            if (centroid != Offset.Unspecified) focus = centroid
                            if (!pinching) {
                                pinching = true
                                anchor = state.anchorAt(focus, centerOnCover = true)
                                state.transforming = true
                                scope.launch { scrollState.stopScroll(MutatePriority.PreventUserInput) }
                            }
                            val geometry = state.geometry
                            val pivot = anchor
                            if (geometry != null && pivot != null) {
                                val nextZoom = geometry.zoomForSize(
                                    geometry.cellSize * if (pointers == transformPointers) event.calculateZoom() else 1f,
                                )
                                state.transform(nextZoom, pivot)
                            }
                        }
                        transformPointers = pointers
                        if (pinching) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    motion.settle(anchor ?: state.anchorAt(focus, centerOnCover = true))
                }
            }
            .scrollable(scrollState, Orientation.Vertical, enabled = !state.transforming),
    ) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val headerPlaceable = header?.let {
            subcompose(CoverGridHeaderKey) { Box { it() } }.single()
                .measure(Constraints.fixedWidth(width))
        }
        val grid = state.prepareLayout(
            width = width.toFloat(),
            height = height.toFloat(),
            gap = 2.dp.toPx(),
            top = (contentTopPadding + 2.dp).toPx() + (headerPlaceable?.height ?: 0),
            bottom = contentBottomPadding.toPx(),
            ids = itemKeys,
            hasHeader = headerPlaceable != null,
        )
        val top = state.scrollOffset - state.topPadding
        val fillEdges = state.transforming || state.zoom != settledZoom(state.zoom) || state.outsideScrollBounds
        val firstTileY = if (headerPlaceable != null) (top - grid.step).coerceAtLeast(0f) else top - grid.step
        val slots = grid.visibleSlots(
            -grid.step,
            firstTileY,
            width + grid.step,
            top + height + grid.step,
            fillEdges,
            fillLeadingRows = state.outsideScrollBounds,
        )
        val baseSize = grid.maxCellSize.roundToInt().coerceAtLeast(1)
        val tiles = slots.map { slot ->
            val item = items[slot.index]
            val placeable = subcompose(state.slotKey(slot)) {
                itemContent(item, grid.columns, baseSize, slot.filler)
            }.single().measure(Constraints.fixed(baseSize, baseSize))
            slot to placeable
        }
        layout(width, height) {
            tiles.forEach { (slot, placeable) ->
                val cell = grid.slotCell(slot.row, slot.column)
                val x = cell.x
                val y = cell.y + state.topPadding - state.scrollOffset
                val integerX = x.roundToInt()
                val integerY = y.roundToInt()
                placeable.placeWithLayer(integerX, integerY) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = cell.size / baseSize
                    scaleY = scaleX
                    translationX = x - integerX
                    translationY = y - integerY
                }
            }
            headerPlaceable?.place(0, (contentTopPadding.toPx() - state.scrollOffset).roundToInt())
        }
    }
}
