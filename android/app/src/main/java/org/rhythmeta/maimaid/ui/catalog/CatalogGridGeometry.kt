package org.rhythmeta.maimaid.ui.catalog

import kotlin.math.ceil
import kotlin.math.floor

internal data class PhotoGridCell(val x: Float, val y: Float, val size: Float)
internal data class PhotoGridSlot(val row: Int, val column: Int, val index: Int, val filler: Boolean)

/** The square lattice scales continuously; only its cover assignments change at density boundaries. */
internal class CatalogGridGeometry(val width: Float, val gap: Float, val count: Int, zoom: Float) {
    private val zoom = zoom.coerceIn(1f, 2f)
    val columns = catalogGridColumns(this.zoom)
    val centerColumn = columns / 2
    val cellSize = interpolateGrid(sizeFor(5), sizeFor(3), this.zoom - 1f)
    val step = cellSize + gap
    val maxCellSize = sizeFor(3)
    private val originX = (width - cellSize) / 2f - centerColumn * step
    val rows = (count + columns - 1) / columns
    val contentHeight: Float get() = if (count == 0) 0f else rows * step - gap

    fun cell(index: Int): PhotoGridCell = slotCell(index / columns, index % columns)
    fun slotCell(row: Int, column: Int) = PhotoGridCell(originX + column * step, row * step, cellSize)

    fun zoomForSize(size: Float): Float =
        1f + ((size - sizeFor(5)) / (sizeFor(3) - sizeFor(5))).coerceIn(0f, 1f)

    fun visibleIndices(top: Float, bottom: Float): IntRange {
        val rows = visibleRows(top, bottom)
        if (rows.isEmpty()) return IntRange.EMPTY
        return rows.first * columns until ((rows.last + 1) * columns).coerceAtMost(count)
    }

    /** Repeat exposed sides and optionally leading rows, never rows below the final item. */
    fun visibleSlots(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fillEdges: Boolean,
        fillLeadingRows: Boolean = false,
    ): List<PhotoGridSlot> {
        if (count == 0 || right <= left || bottom <= top) return emptyList()
        val firstColumn = floor((left - originX - cellSize) / step).toInt() + 1
        val lastColumn = ceil((right - originX) / step).toInt() - 1
        val visibleRows = if (fillLeadingRows) {
            val first = floor((top - cellSize) / step).toInt() + 1
            val last = (ceil(bottom / step).toInt() - 1).coerceAtMost(rows - 1)
            first..last
        } else {
            visibleRows(top, bottom)
        }
        return buildList {
            for (row in visibleRows) {
                val sourceRow = row.coerceIn(0, rows - 1)
                val rowCount = minOf(columns, count - sourceRow * columns)
                for (column in firstColumn..lastColumn) {
                    val filler = row != sourceRow || column !in 0 until rowCount
                    if (!filler || fillEdges) {
                        add(
                            PhotoGridSlot(
                                row = row,
                                column = column,
                                index = sourceRow * columns + Math.floorMod(column, rowCount),
                                filler = filler,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun visibleRows(top: Float, bottom: Float): IntRange {
        if (count == 0 || bottom <= top || bottom <= 0f || top >= contentHeight) return IntRange.EMPTY
        val first = (floor((top - cellSize) / step).toInt() + 1).coerceAtLeast(0)
        val last = (ceil(bottom / step).toInt() - 1).coerceAtMost(rows - 1)
        return first..last
    }

    private fun sizeFor(columns: Int) = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1f)
}

internal fun interpolateGrid(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
internal fun settledZoom(zoom: Float) = floor(zoom.coerceIn(1f, 2f) + 0.5f)

internal fun catalogGridColumns(zoom: Float): Int = if (settledZoom(zoom) == 1f) 5 else 3
