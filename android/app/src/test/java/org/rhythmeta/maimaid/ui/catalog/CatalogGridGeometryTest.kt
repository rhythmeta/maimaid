package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGridGeometryTest {
    @Test
    fun settledZoomUsesOnlyThreeOrFiveColumns() {
        for (zoom in listOf(0f, 1f, 1.2f, 1.49f, 1.5f, 1.8f, 2f, 4f)) {
            val grid = CatalogGridGeometry(390f, 2f, 200, zoom)
            assertTrue(grid.columns == 3 || grid.columns == 5)
        }
        assertEquals(5, catalogGridColumns(1f))
        assertEquals(3, catalogGridColumns(2f))
    }

    @Test
    fun cellSizeInterpolatesContinuouslyBetweenSettledLayouts() {
        val five = CatalogGridGeometry(390f, 2f, 200, 1f)
        val middle = CatalogGridGeometry(390f, 2f, 200, 1.5f)
        val three = CatalogGridGeometry(390f, 2f, 200, 2f)
        assertEquals((five.cellSize + three.cellSize) / 2f, middle.cellSize, 0.0001f)
        assertEquals(390f, five.cell(4).x + five.cellSize, 0.0001f)
        assertEquals(390f, three.cell(2).x + three.cellSize, 0.0001f)
    }

    @Test
    fun pinchKeepsTheCenterCoverAnchored() {
        val state = CatalogPhotoGridState(100)
        val ids = (0 until 1_000).map(Int::toString)
        state.prepareLayout(390f, 800f, 2f, 100f, 96f, ids)
        val anchor = state.anchorAt(Offset(195f, 400f), centerOnCover = true)!!
        state.transform(1.4f, anchor)
        val cell = state.geometry!!.cell(anchor.index)
        assertEquals(anchor.point.y, cell.y + state.topPadding - state.scrollOffset + cell.size / 2f, 0.01f)
    }
}
