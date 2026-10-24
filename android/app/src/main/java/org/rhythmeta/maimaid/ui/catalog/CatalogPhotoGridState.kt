package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.geometry.Offset
import kotlin.math.floor
import kotlin.math.roundToInt

class CatalogPhotoGridState internal constructor(index: Int = 0, offset: Int = 0, zoom: Float = 1f) {
    private var hasInitialColumns = false
    internal var zoom by mutableFloatStateOf(zoom.coerceIn(1f, 2f))
    internal var transforming by mutableStateOf(false)
    internal var scrollOffset by mutableFloatStateOf(0f)
    private var requestedPosition by mutableStateOf<Pair<Int, Int>?>(index to offset)
    internal var geometry: CatalogGridGeometry? = null
        private set
    internal var viewportHeight = 0f
        private set
    internal var topPadding = 0f
        private set
    private var bottomPadding = 0f
    private var hasHeader = false
    private var songIds = emptyList<String>()
    private var rowKeyOffset = 0

    internal fun slotKey(slot: PhotoGridSlot): Pair<Int, Int> =
        (slot.row + rowKeyOffset) to (slot.column - (geometry?.centerColumn ?: 0))

    val firstVisibleItemIndex: Int
        get() = requestedPosition?.first ?: geometry?.visibleIndices(scrollOffset, scrollOffset + viewportHeight)
            ?.firstOrNull() ?: 0
    val firstVisibleItemScrollOffset: Int
        get() = requestedPosition?.second ?: geometry?.let {
            (scrollOffset - it.cell(firstVisibleItemIndex).y).roundToInt().coerceAtLeast(0)
        } ?: 0
    internal val maxScroll: Float get() = maxScrollFor(geometry)
    internal val outsideScrollBounds: Boolean get() = scrollOffset !in 0f..maxScroll

    private fun maxScrollFor(grid: CatalogGridGeometry?) =
        ((grid?.contentHeight ?: 0f) + topPadding + bottomPadding - viewportHeight).coerceAtLeast(0f)

    internal fun anchoredScroll(zoom: Float, anchor: PhotoGridAnchor): Float {
        val old = geometry ?: return scrollOffset
        val cell = CatalogGridGeometry(old.width, old.gap, old.count, zoom).cell(anchor.index)
        return topPadding + cell.y + anchor.fractionY * cell.size - anchor.point.y
    }

    internal fun settlingCorrection(zoom: Float, anchor: PhotoGridAnchor): Float {
        val old = geometry ?: return 0f
        val desired = anchoredScroll(zoom, anchor)
        val grid = CatalogGridGeometry(old.width, old.gap, old.count, zoom)
        return desired.coerceIn(0f, maxScrollFor(grid)) - desired
    }

    fun requestScrollToItem(index: Int, scrollOffset: Int = 0) {
        requestedPosition = index.coerceAtLeast(0) to scrollOffset
    }

    internal fun restoreColumns(columns: Int) {
        if (!hasInitialColumns && !transforming) {
            requestedPosition = firstVisibleItemIndex to firstVisibleItemScrollOffset
            zoom = if (columns == 3) 2f else 1f
        }
        hasInitialColumns = true
    }

    internal fun prepareLayout(
        width: Float,
        height: Float,
        gap: Float,
        top: Float,
        bottom: Float,
        ids: List<String>,
        hasHeader: Boolean = false,
    ): CatalogGridGeometry {
        val layoutChanged = geometry?.width != width || songIds != ids || viewportHeight != height ||
            topPadding != top || bottomPadding != bottom
        val mustClamp = layoutChanged || requestedPosition != null
        if (requestedPosition == null && layoutChanged) {
            val oldIndex = firstVisibleItemIndex
            val newIndex = songIds.getOrNull(oldIndex)?.let(ids::indexOf)?.takeIf { it >= 0 } ?: 0
            requestedPosition = newIndex to firstVisibleItemScrollOffset
        }
        geometry = CatalogGridGeometry(width, gap, ids.size, zoom)
        viewportHeight = height
        topPadding = top
        bottomPadding = bottom
        this.hasHeader = hasHeader
        songIds = ids
        requestedPosition?.let { (index, offset) ->
            scrollOffset = geometry!!.cell(index.coerceIn(0, (ids.size - 1).coerceAtLeast(0))).y + offset
            requestedPosition = null
        }
        if (mustClamp) scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        return geometry!!
    }

    internal fun scrollBy(delta: Float): Float {
        val previous = scrollOffset
        scrollOffset = (previous - delta).coerceIn(0f, maxScroll)
        return previous - scrollOffset
    }

    internal fun anchorAt(point: Offset, centerOnCover: Boolean = false): PhotoGridAnchor? {
        val grid = geometry ?: return null
        if (grid.count == 0) return null
        if (hasHeader && scrollOffset == 0f) {
            return PhotoGridAnchor(grid.centerColumn.coerceAtMost(grid.count - 1), 0f, Offset(grid.width / 2f, topPadding))
        }
        val row = floor((point.y - topPadding + scrollOffset + grid.gap / 2f) / grid.step)
            .toInt().coerceIn(0, grid.rows - 1)
        val index = (row * grid.columns + grid.centerColumn).coerceAtMost(grid.count - 1)
        val cell = grid.cell(index)
        val y = if (centerOnCover) topPadding + cell.y + cell.size / 2f - scrollOffset else point.y
        return PhotoGridAnchor(
            index,
            if (centerOnCover) 0.5f else (y - topPadding - cell.y + scrollOffset) / cell.size,
            Offset(grid.width / 2f, y),
        )
    }

    internal fun transform(value: Float, anchor: PhotoGridAnchor, scrollCorrection: Float = 0f) {
        val old = geometry ?: return
        hasInitialColumns = true
        zoom = value.coerceIn(1f, 2f)
        val grid = CatalogGridGeometry(old.width, old.gap, old.count, zoom)
        rowKeyOffset += anchor.index / old.columns - anchor.index / grid.columns
        geometry = grid
        val cell = grid.cell(anchor.index)
        scrollOffset = topPadding + cell.y + anchor.fractionY * cell.size - anchor.point.y + scrollCorrection
    }

    companion object {
        internal val Saver = listSaver<CatalogPhotoGridState, Number>(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset, it.zoom) },
            restore = {
                CatalogPhotoGridState(it[0].toInt(), it[1].toInt(), it[2].toFloat()).apply {
                    hasInitialColumns = true
                }
            },
        )
    }
}

internal data class PhotoGridAnchor(val index: Int, val fractionY: Float, val point: Offset)
